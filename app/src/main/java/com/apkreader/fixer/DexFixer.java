package com.apkreader.fixer;

import com.android.apksig.ApkSigner;
import com.android.apksig.apk.MinSdkVersionException;

import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 360 加固正则修复：提取 APK 的 classes*.dex -> baksmali 反汇编 -> 按两组正则
 * （顺序固定，先 regex1 后 regex2）删除 StubApp 加固调用 -> smali 重新汇编 ->
 * 重打包 APK -> 按调用方设置用 release.jks 重新签名（signApk=false 时跳过，输出未签名 APK）。
 *
 * 中间产物 smali 文本做两件事兜底：\r\n 归一化为 \n（多行正则才能正确锚定行尾），
 * 以及被删空的方法体补 return-void（空方法体无法汇编）。
 */
public class DexFixer {

    /** 进度回调，msg 为当前阶段描述。 */
    public interface Progress {
        void onStep(String msg);
    }

    // ---- 用户提供的两组正则，顺序固定：先 regex1 后 regex2，不能变 ----

    private static final Pattern RX1 = Pattern.compile(
            "(?m)"
            + "(?:^\\.method\\s+public\\s+constructor\\s+<init>\\(\\)V\\s*\\n\\s*\\.registers\\s+2\\s*\\n\\s*invoke-direct\\s+\\{p0\\},\\s*Ljava/lang/Object;-><init>\\(\\)V\\s*\\n\\s*return-void\\s*\\n\\.end\\s+method$)"
            + "|(?:^\\.method\\s+static\\s+constructor\\s+<clinit>\\(\\)V\\s*\\n\\s*\\.registers\\s+2\\s*\\n\\s*const\\s+v0,\\s*(0x[0-9a-fA-F]+)\\s*\\n\\s*invoke-static\\s+\\{v0\\},\\s*Lcom/stub/StubApp;->interface11\\(I\\)V\\s*\\n\\s*return-void\\s*\\n\\.end\\s+method$)"
            + "|(?:^\\s*const\\s+v0,\\s*(0x[0-9a-fA-F]+)\\s*\\n\\s*invoke-static\\s+\\{v0\\},\\s*Lcom/stub/StubApp;->interface11\\(I\\)V\\s*$)"
            + "|(?:^\\s*invoke-static/range\\s+\\{([pv]\\d+)\\s+\\.\\.\\s+\\3\\},\\s*Lcom/stub/StubApp;->getOrigApplicationContext\\(Landroid/content/Context;\\)Landroid/content/Context;\\s*(?:\\n\\s*move-result-object\\s+\\3)?$)"
            + "|(?:^\\s*move-result-object\\s+([pv]\\d+)\\s*\\n\\s*invoke-static/range\\s+\\{\\4\\s+\\.\\.\\s+\\4\\},\\s*Lcom/stub/StubApp;->getOrigApplicationContext\\(Landroid/content/Context;\\)Landroid/content/Context;\\s*$)"
            + "|(?:^\\s*invoke-static\\s+\\{p0,\\s*p1,\\s*p2\\},\\s*Lcom/stub/StubApp;->interface24\\(Landroid/app/Activity;\\[Ljava/lang/String;I\\)V\\s*$)"
            + "|(?:^\\s*invoke-static\\s+\\{p1,\\s*p2,\\s*p3\\},\\s*Lcom/stub/StubApp;->interface22\\(I\\[Ljava/lang/String;\\[I\\)V\\s*$)"
            + "|(?:^\\s*invoke-static\\s+\\{(v5|[pv]\\d+)\\},\\s*Lcom/stub/StubApp;->interface12\\(Ldalvik/system/DexFile;\\)Ljava/util/Enumeration;\\s*$)");

    private static final Pattern RX2 = Pattern.compile(
            "const .*\\s*invoke-static \\{.*\\}, Lcom/stub/StubApp;->.*\\(I\\)V"
            + "|invoke-static \\{.*\\}, Lcom/stub/StubApp;->.*\\(Landroid/app/Activity;\\[Ljava/lang/String;I\\)V"
            + "|invoke-static/range \\{.*\\}, Lcom/stub/StubApp;->.*\\(Landroid/content/Context;\\)Landroid/content/Context;\\s*move-result-object (.*)"
            + "|invoke-static \\{.*\\}, Lcom/stub/StubApp;->.*\\(I\\[Ljava/lang/String;\\[I\\)V"
            + "|invoke-static \\{\\}, Lcom/stub/StubApp;->.*\\(\\)V"
            + "|invoke-static \\{.*\\}, Lcom/stub/StubApp;->.*\\(Ldalvik/system/DexFile;\\)Ljava/util/Enumeration;\\s*move-result-object (.*)");

    /** 被删空的方法体补 return-void：.method 行 + 若干仅指令式行（.registers/.locals/.annotation/#注释/空行）+ .end method。
     *  group2 用 (?!end\s+method\b) 阻止吞掉下一方法的 .end method，替换时保留 group2（.registers 等指令不可丢）。
     *  group1 用 (?![^\n]*\b(?:abstract|native)\b) 排除抽象/本地方法：它们本来就没有方法体，
     *  插 return-void 会让 smali 报 "An abstract method cannot have any instructions"（真实 APK 汇编失败的根因）。 */
    private static final Pattern EMPTY_BODY = Pattern.compile(
            "(?m)^(\\.method(?![^\\n]*\\b(?:abstract|native)\\b)[^\\n]*\\n)((?:[ \\t]*(?:\\.(?!end\\s+method\\b)[a-zA-Z][^\\n]*|#[^\\n]*)?\\n)*)(\\.end method)");

    /** 并行度：baksmali/smali/清洗共用，多核手机 2–4 路，单核旧机自动回落 2。 */
    static final int JOBS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));

    /**
     * 完整修复流程：inputApk -> outputApk。
     * workDir 为临时工作目录（可复用，内部会清理），keystoreFile 为签名密钥库文件
     * （signApk=false 时跳过签名，直接输出未签名 APK，keystore 参数可传 null）。
     */
    public static void fix(File inputApk, File outputApk, File workDir,
                           File keystoreFile, String alias, char[] storePwd, char[] keyPwd,
                           boolean signApk, Progress progress) throws Exception {
        if (!workDir.exists() && !workDir.mkdirs()) throw new Exception("无法创建工作目录 " + workDir);
        progress.onStep("正在提取 dex 文件...");
        Map<String, File> dexMap = extractDexFiles(inputApk, workDir);
        if (dexMap.isEmpty()) throw new Exception("APK 中未找到 classes*.dex");

        Map<String, File> fixed = new LinkedHashMap<>();
        for (Map.Entry<String, File> me : dexMap.entrySet()) {
            String name = me.getKey();
            progress.onStep("正在反汇编 " + name + " ...");
            Opcodes opcodes = opcodesForDex(me.getValue());
            File smaliDir = new File(workDir, "smali_" + name);
            deleteRecursive(smaliDir);
            if (!smaliDir.mkdirs()) throw new Exception("无法创建临时目录 " + smaliDir);

            BaksmaliOptions bo = new BaksmaliOptions();
            bo.apiLevel = opcodes.api;
            bo.parameterRegisters = true; // 输出 p0/p1 参数寄存器写法，与正则匹配
            DexBackedDexFile dexFile = DexFileFactory.loadDexFile(me.getValue(), opcodes);
            Captured<Boolean> dis = runCaptured(() -> Baksmali.disassembleDexFile(dexFile, smaliDir, JOBS, bo));
            if (!dis.value) {
                throw new Exception(name + " 反汇编失败（可能是加固或非标准 dex）：" + firstError(dis.text));
            }

            progress.onStep("正在清理 " + name + " 中的 360 加固代码...");
            List<File> smaliFiles = new ArrayList<>();
            walkSmali(smaliFiles, smaliDir);
            boolean anyChanged = cleanParallel(smaliFiles);

            // 清洗没改动任何文件时无需重汇编，直接复用原始 dex，省掉一次最贵的汇编
            if (!anyChanged) {
                progress.onStep(name + " 无需修改，复用原始 dex");
                fixed.put(name, me.getValue());
                continue;
            }

            progress.onStep("正在重新汇编 " + name + " ...");
            File fixedDex = new File(workDir, "fixed_" + name);
            SmaliOptions so = new SmaliOptions();
            so.apiLevel = opcodes.api;
            so.jobs = JOBS;
            so.verboseErrors = true;
            so.outputDexFile = fixedDex.getAbsolutePath();
            Captured<Boolean> asm = runCaptured(() -> Smali.assemble(so, smaliDir.getAbsolutePath()));
            if (!asm.value) {
                throw new Exception(name + " 重新汇编失败：" + firstError(asm.text));
            }
            fixed.put(name, fixedDex);
        }

        progress.onStep("正在打包修复后的 APK...");
        File unsigned = new File(workDir, "unsigned.apk");
        repack(inputApk, unsigned, fixed);

        if (signApk) {
            progress.onStep("正在签名...");
            KeyStore ks = loadKeyStore(keystoreFile, storePwd);
            PrivateKey pk = (PrivateKey) ks.getKey(alias, keyPwd);
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
            if (pk == null || cert == null) throw new Exception("签名密钥库中未找到别名 " + alias);
            List<X509Certificate> certs = Collections.singletonList(cert);
            ApkSigner.SignerConfig signer =
                    new ApkSigner.SignerConfig.Builder(alias, pk, certs).build();
            File signed = new File(workDir, "signed.apk");
            try {
                sign(unsigned, signed, signer);
            } catch (MinSdkVersionException e) {
                // 极少数 APK 缺少/损坏 AndroidManifest.xml，apksig 无法推导 minSdk，退到 21 重签
                sign(unsigned, signed, signer, 21);
            }
            Files.copy(signed.toPath(), outputApk.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            progress.onStep("正在输出未签名 APK...");
            Files.copy(unsigned.toPath(), outputApk.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        progress.onStep("修复完成");
    }

    static void sign(File unsigned, File signed, ApkSigner.SignerConfig signer) throws Exception {
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build().sign();
    }

    static void sign(File unsigned, File signed, ApkSigner.SignerConfig signer, int minSdk) throws Exception {
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build().sign();
    }

    /** 清理单个 smali 文本：\r\n 归一化 -> regex1 -> regex2 -> 空方法体补 return-void。 */
    static String cleanSmali(String text) {
        String s = text.replace("\r\n", "\n");
        s = RX1.matcher(s).replaceAll("");
        s = RX2.matcher(s).replaceAll("");
        s = EMPTY_BODY.matcher(s).replaceAll("$1$2    return-void\n$3");
        return s;
    }

    /** 并行清洗 smali 文件（每个文件独立读写，无共享状态），返回是否有任何文件被修改。 */
    private static boolean cleanParallel(List<File> smaliFiles) throws Exception {
        if (smaliFiles.isEmpty()) return false;
        ExecutorService pool = Executors.newFixedThreadPool(JOBS);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>(smaliFiles.size());
            for (File f : smaliFiles) {
                tasks.add(() -> {
                    String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    String cleaned = cleanSmali(text);
                    if (!cleaned.equals(text)) {
                        Files.write(f.toPath(), cleaned.getBytes(StandardCharsets.UTF_8));
                        return true;
                    }
                    return false;
                });
            }
            boolean any = false;
            for (Future<Boolean> ft : pool.invokeAll(tasks)) any |= ft.get();
            return any;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 提取 APK 根目录下所有 classes*.dex 到 workDir，返回 条目名 -> 文件。 */
    static Map<String, File> extractDexFiles(File apk, File workDir) throws Exception {
        Map<String, File> map = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> es = zf.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                String n = e.getName();
                if (n.indexOf('/') < 0 && n.indexOf('\\') < 0 && isDexName(n)) {
                    if (map.containsKey(n)) continue;
                    File out = new File(workDir, n);
                    try (InputStream in = zf.getInputStream(e); FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[256 * 1024];
                        int r;
                        while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
                    }
                    map.put(n, out);
                }
            }
        }
        return map;
    }

    private static boolean isDexName(String n) {
        return n.matches("classes\\d*\\.dex");
    }

    /** 按 dex 头声明的 dex 版本选择 Opcodes，未知版本退回默认。 */
    static Opcodes opcodesForDex(File dex) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(dex, "r")) {
            byte[] magic = new byte[8];
            int n = raf.read(magic);
            if (n >= 8) {
                String ver = new String(magic, 4, 4, StandardCharsets.US_ASCII).replace("\0", "").trim();
                if (ver.matches("\\d{3}")) {
                    try {
                        return Opcodes.forDexVersion(Integer.parseInt(ver));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return Opcodes.getDefault();
    }

    static void walkSmali(List<File> out, File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) walkSmali(out, f);
            else if (f.getName().endsWith(".smali")) out.add(f);
        }
    }

    /** 重打包：复制除旧签名与待替换 dex 外的所有条目，再写入修复后的 dex。 */
    static void repack(File inputApk, File outputApk, Map<String, File> fixedDexes) throws Exception {
        repack(inputApk, outputApk, fixedDexes, Collections.<String, byte[]>emptyMap());
    }

    /**
     * 重打包（可额外替换条目字节，如权限注入后的 AndroidManifest.xml）：
     * 复制除旧签名、待替换 dex、待替换字节条目外的所有内容，最后写入新的 dex 与字节条目。
     */
    static void repack(File inputApk, File outputApk, Map<String, File> fixedDexes,
                       Map<String, byte[]> replacedBytes) throws Exception {
        try (ZipFile zf = new ZipFile(inputApk); ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputApk))) {
            Enumeration<? extends ZipEntry> es = zf.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                String n = e.getName();
                if (fixedDexes.containsKey(n)) continue;
                if (isSignatureEntry(n)) continue;
                zos.putNextEntry(new ZipEntry(n));
                byte[] rb = replacedBytes.get(n);
                if (rb != null) {
                    zos.write(rb);
                } else {
                    try (InputStream in = zf.getInputStream(e)) {
                        byte[] buf = new byte[256 * 1024];
                        int r;
                        while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                    }
                }
                zos.closeEntry();
            }
            byte[] buf = new byte[256 * 1024];
            for (Map.Entry<String, File> me : fixedDexes.entrySet()) {
                zos.putNextEntry(new ZipEntry(me.getKey()));
                try (InputStream in = new FileInputStream(me.getValue())) {
                    int r;
                    while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                }
                zos.closeEntry();
            }
        }
    }

    private static boolean isSignatureEntry(String n) {
        String up = n.toUpperCase(java.util.Locale.US);
        return up.startsWith("META-INF/") && (up.endsWith(".SF") || up.endsWith(".RSA")
                || up.endsWith(".DSA") || up.endsWith(".EC") || up.equals("META-INF/MANIFEST.MF"));
    }

    static KeyStore loadKeyStore(File f, char[] pwd) throws Exception {
        Exception last = null;
        for (String type : new String[]{"JKS", "PKCS12"}) {
            try {
                KeyStore ks = KeyStore.getInstance(type);
                try (InputStream in = new FileInputStream(f)) {
                    ks.load(in, pwd);
                }
                return ks;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new Exception("无法读取签名密钥库：" + last.getMessage());
    }

    /** baksmali/smali 把详细错误打到 System.out/err（真机上即 logcat，不便查看），
     *  这里临时接管标准流把错误文本捕获进异常消息，让用户直接在提示里看到失败原因。 */
    static <T> Captured<T> runCaptured(ThrowingSupplier<T> op) throws Exception {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bos, true, "UTF-8");
        System.setOut(ps);
        System.setErr(ps);
        try {
            return new Captured<>(op.get(), bos.toString("UTF-8"));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    /** 取 baksmali/smali 的详细错误输出（完整保留，供写入日志文件），空输出时给提示。 */
    static String firstError(String text) {
        if (text == null || text.trim().isEmpty()) return "无详细输出";
        return text.trim();
    }

    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    static final class Captured<T> {
        final T value;
        final String text;

        Captured(T value, String text) {
            this.value = value;
            this.text = text;
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
