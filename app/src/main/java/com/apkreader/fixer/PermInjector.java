package com.apkreader.fixer;

import com.android.apksig.ApkSigner;
import com.android.apksig.apk.MinSdkVersionException;
import com.apkreader.parser.AXmlEditor;

import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 权限注入：先向二进制 AndroidManifest.xml 追加缺失的 uses-permission（AXmlEditor），
 * 再把一组 smali 指令注入 Application 类与 Launcher Activity 的 onCreate 开头，
 * 调用辅助类 PermCheck 检查运行时权限：已授予则跳过，未授予则弹系统授权框。
 * 最后用 release.jks 重新签名（signApk=false 时输出未签名 APK）。
 *
 * 辅助类 PermCheck 用 baksmali 文本形式生成，与目标方法写进同一 dex；其它 dex 跨 dex
 * 引用该类在 ART 上合法。MANAGE_EXTERNAL_STORAGE 不走 requestPermissions，而是
 * 跳转系统「所有文件访问」设置页。
 */
public final class PermInjector {

    /** 进度回调，msg 为当前阶段描述。 */
    public interface Progress {
        void onStep(String msg);
    }

    /** 请求码（requestPermissions 的第二个参数），任意非 0 值。 */
    private static final int REQ_CODE = 0x6d;

    private PermInjector() {
    }

    /**
     * 完整流程：inputApk -> outputApk。
     * perms 为要确保声明的权限全名列表（如 android.permission.INTERNET）；
     * 已在 manifest 声明的会跳过，权限注入始终执行（缺省 application 时只注入 Launcher）。
     */
    public static void inject(File inputApk, File outputApk, File workDir,
                              List<String> perms, File keystoreFile, String alias,
                              char[] storePwd, char[] keyPwd, boolean signApk,
                              Progress progress) throws Exception {
        if (!workDir.exists() && !workDir.mkdirs()) throw new Exception("无法创建工作目录 " + workDir);
        if (perms == null || perms.isEmpty()) throw new Exception("未选择任何权限");

        progress.onStep("正在读取 AndroidManifest.xml...");
        byte[] manifest = readEntry(inputApk, "AndroidManifest.xml");
        if (manifest == null) throw new Exception("APK 中未找到 AndroidManifest.xml");
        AXmlEditor.ManifestInfo info = AXmlEditor.readManifest(manifest);
        byte[] newManifest = AXmlEditor.addPermissions(manifest, perms);
        String appClass = AXmlEditor.resolveClassName(info.packageName, info.applicationClass);
        String launcher = AXmlEditor.resolveClassName(info.packageName, info.launcherActivity);

        progress.onStep("正在提取 dex 文件...");
        Map<String, File> dexMap = DexFixer.extractDexFiles(inputApk, workDir);
        if (dexMap.isEmpty()) throw new Exception("APK 中未找到 classes*.dex");

        // 反汇编所有 dex（需要注入的目标可能分布在不同 dex）
        Map<String, File> smaliDirs = new LinkedHashMap<>();
        for (Map.Entry<String, File> me : dexMap.entrySet()) {
            String name = me.getKey();
            progress.onStep("正在反汇编 " + name + " ...");
            Opcodes opcodes = DexFixer.opcodesForDex(me.getValue());
            File smaliDir = new File(workDir, "psmali_" + name);
            DexFixer.deleteRecursive(smaliDir);
            if (!smaliDir.mkdirs()) throw new Exception("无法创建临时目录 " + smaliDir);
            BaksmaliOptions bo = new BaksmaliOptions();
            bo.apiLevel = opcodes.api;
            bo.parameterRegisters = true; // p0/p1 参数寄存器写法，注入代码用 p0 引用 this
            DexBackedDexFile dexFile = DexFileFactory.loadDexFile(me.getValue(), opcodes);
            DexFixer.Captured<Boolean> dis = DexFixer.runCaptured(
                    () -> Baksmali.disassembleDexFile(dexFile, smaliDir, DexFixer.JOBS, bo));
            if (!dis.value) throw new Exception(name + " 反汇编失败：" + DexFixer.firstError(dis.text));
            smaliDirs.put(name, smaliDir);
        }

        // 选择不冲突的辅助类名，并定位注入目标
        String helper = findHelperName(smaliDirs.values());
        String helperCall = "    invoke-static {p0}, " + helper + "->check(Landroid/content/Context;)V";
        Set<String> injectedFiles = new LinkedHashSet<>();
        File helperDir = null;
        boolean anyInjected = false;
        for (String className : new String[]{appClass, launcher}) {
            if (className == null || className.isEmpty()) continue;
            String relPath = AXmlEditor.classToSmaliPath(className);
            File smaliFile = findSmaliFile(smaliDirs, relPath);
            if (smaliFile == null) {
                progress.onStep("警告：未找到入口类 " + className + "，跳过注入");
                continue;
            }
            if (!injectedFiles.add(smaliFile.getAbsolutePath())) continue; // 同一文件只注入一次
            if (helperDir == null) helperDir = dirOf(smaliDirs, smaliFile);
            if (injectIntoSmaliFile(smaliFile, helperCall)) anyInjected = true;
        }

        if (anyInjected) {
            File helperFile = new File(helperDir, descriptorToRelPath(helper));
            if (!helperFile.getParentFile().exists() && !helperFile.getParentFile().mkdirs()) {
                throw new Exception("无法创建目录 " + helperFile.getParentFile());
            }
            Files.write(helperFile.toPath(),
                    buildHelperSmali(helper, perms).getBytes(StandardCharsets.UTF_8));
        }

        // 重新汇编发生改动的 dex，未改动的直接复用原始 dex
        Map<String, File> fixed = new LinkedHashMap<>();
        for (Map.Entry<String, File> me : smaliDirs.entrySet()) {
            String name = me.getKey();
            File dir = me.getValue();
            if (!touched(dir, injectedFiles, helperDir)) continue;
            progress.onStep("正在重新汇编 " + name + " ...");
            Opcodes opcodes = DexFixer.opcodesForDex(dexMap.get(name));
            File fixedDex = new File(workDir, "pfixed_" + name);
            SmaliOptions so = new SmaliOptions();
            so.apiLevel = opcodes.api;
            so.jobs = DexFixer.JOBS;
            so.verboseErrors = true;
            so.outputDexFile = fixedDex.getAbsolutePath();
            DexFixer.Captured<Boolean> asm = DexFixer.runCaptured(
                    () -> Smali.assemble(so, dir.getAbsolutePath()));
            if (!asm.value) throw new Exception(name + " 重新汇编失败：" + DexFixer.firstError(asm.text));
            fixed.put(name, fixedDex);
        }

        progress.onStep("正在打包修改后的 APK...");
        File unsigned = new File(workDir, "punsigned.apk");
        Map<String, byte[]> replaced = new LinkedHashMap<>();
        if (newManifest != null) replaced.put("AndroidManifest.xml", newManifest);
        DexFixer.repack(inputApk, unsigned, fixed, replaced);

        if (signApk) {
            progress.onStep("正在签名...");
            KeyStore ks = DexFixer.loadKeyStore(keystoreFile, storePwd);
            PrivateKey pk = (PrivateKey) ks.getKey(alias, keyPwd);
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
            if (pk == null || cert == null) throw new Exception("签名密钥库中未找到别名 " + alias);
            ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                    alias, pk, Collections.singletonList(cert)).build();
            File signed = new File(workDir, "psigned.apk");
            try {
                DexFixer.sign(unsigned, signed, signer);
            } catch (MinSdkVersionException e) {
                // 极少数 APK 缺/损坏 AndroidManifest.xml，apksig 无法推导 minSdk，退到 21 重签
                DexFixer.sign(unsigned, signed, signer, 21);
            }
            Files.copy(signed.toPath(), outputApk.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            progress.onStep("正在输出未签名 APK...");
            Files.copy(unsigned.toPath(), outputApk.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        progress.onStep("权限添加完成");
    }

    /**
     * 生成辅助类 smali 文本。类描述符如 Lcom/apktool/perminject/PermCheck;。
     * 逻辑：API&lt;23 直接返回；把每个运行时权限的缺失项收集进 ArrayList；
     * 列表非空且调用者是 Activity 时 requestPermissions 申请（MANAGE_EXTERNAL_STORAGE
     * 除外，它走系统设置页）。MANAGE_EXTERNAL_STORAGE 单独处理：系统未授予时
     * 启动「所有文件访问」设置页。
     */
    static String buildHelperSmali(String helper, List<String> perms) {
        List<String> runtime = new ArrayList<>();
        boolean manage = false;
        for (String p : perms) {
            if ("android.permission.MANAGE_EXTERNAL_STORAGE".equals(p)) manage = true;
            else runtime.add(p);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(".class public ").append(helper).append('\n');
        sb.append(".super Ljava/lang/Object;\n\n");
        sb.append(".method public static check(Landroid/content/Context;)V\n");
        sb.append("    .registers 12\n\n");
        // 寄存器分配（p0=v11）：v0 sdk / v1 缺失列表 / v2 权限串 / v3 检查结果 / v4 临时
        // v5 计数 / v6 String[] / v7 未用 / v8 未用 / v9 临时 / v10 未用
        sb.append("    # API < 23 无运行时权限，直接返回\n");
        sb.append("    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I\n");
        sb.append("    const/16 v5, 0x17\n");
        sb.append("    if-lt v0, v5, :done\n\n");
        sb.append("    new-instance v1, Ljava/util/ArrayList;\n");
        sb.append("    invoke-direct {v1}, Ljava/util/ArrayList;-><init>()V\n\n");
        for (int i = 0; i < runtime.size(); i++) {
            sb.append("    # ").append(runtime.get(i)).append('\n');
            sb.append("    const-string v2, \"").append(runtime.get(i)).append("\"\n");
            sb.append("    invoke-virtual {p0, v2}, Landroid/content/Context;->checkSelfPermission(Ljava/lang/String;)I\n");
            sb.append("    move-result v3\n");
            sb.append("    if-eqz v3, :perm_").append(i).append("_ok\n");
            sb.append("    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z\n");
            sb.append("    :perm_").append(i).append("_ok\n\n");
        }
        sb.append("    # 缺失列表为空则跳过申请\n");
        sb.append("    invoke-virtual {v1}, Ljava/util/ArrayList;->isEmpty()Z\n");
        sb.append("    move-result v4\n");
        sb.append("    if-nez v4, :request_done\n\n");
        sb.append("    # 只有 Activity 上下文才能直接弹授权框\n");
        sb.append("    instance-of v4, p0, Landroid/app/Activity;\n");
        sb.append("    if-eqz v4, :request_done\n\n");
        sb.append("    check-cast v4, Landroid/app/Activity;\n");
        sb.append("    # 构建 String[] 权限数组并申请\n");
        sb.append("    invoke-virtual {v1}, Ljava/util/ArrayList;->size()I\n");
        sb.append("    move-result v5\n");
        sb.append("    new-array v6, v5, [Ljava/lang/String;\n");
        sb.append("    const/4 v5, 0x0\n");
        sb.append("    :arr_loop\n");
        sb.append("    invoke-virtual {v1}, Ljava/util/ArrayList;->size()I\n");
        sb.append("    move-result v9\n");
        sb.append("    if-ge v5, v9, :arr_done\n");
        sb.append("    invoke-virtual {v1, v5}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;\n");
        sb.append("    move-result-object v9\n");
        sb.append("    check-cast v9, Ljava/lang/String;\n");
        sb.append("    aput-object v9, v6, v5\n");
        sb.append("    add-int/lit8 v5, v5, 0x1\n");
        sb.append("    goto :arr_loop\n");
        sb.append("    :arr_done\n");
        sb.append("    const/16 v9, ").append(hex(REQ_CODE)).append("\n");
        sb.append("    invoke-virtual {v4, v6, v9}, Landroid/app/Activity;->requestPermissions([Ljava/lang/String;I)V\n");
        sb.append("    :request_done\n\n");
        if (manage) {
            sb.append("    # MANAGE_EXTERNAL_STORAGE：未授予时跳转系统「所有文件访问」设置页\n");
            sb.append("    invoke-static {}, Landroid/os/Environment;->isExternalStorageManager()Z\n");
            sb.append("    move-result v4\n");
            sb.append("    if-nez v4, :done\n");
            sb.append("    const-string v2, \"android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION\"\n");
            sb.append("    new-instance v3, Landroid/content/Intent;\n");
            sb.append("    invoke-direct {v3, v2}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V\n");
            sb.append("    const-string v2, \"package:\"\n");
            sb.append("    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;\n");
            sb.append("    move-result-object v4\n");
            sb.append("    invoke-virtual {v2, v4}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;\n");
            sb.append("    move-result-object v4\n");
            sb.append("    invoke-static {v4}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;\n");
            sb.append("    move-result-object v4\n");
            sb.append("    invoke-virtual {v3, v4}, Landroid/content/Intent;->setData(Landroid/net/Uri;)Landroid/net/Uri;\n");
            sb.append("    const/high16 v4, 0x10000000\n");
            sb.append("    invoke-virtual {v3, v4}, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;\n");
            sb.append("    invoke-virtual {p0, v3}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V\n");
        }
        sb.append("    :done\n");
        sb.append("    return-void\n");
        sb.append(".end method\n");
        return sb.toString();
    }

    private static String hex(int v) {
        return "0x" + Integer.toHexString(v);
    }

    /** 从所有 smali 目录取已占用类描述符，返回第一个空闲的 PermCheck 候选。 */
    static String findHelperName(Collection<File> smaliDirs) {
        String base = "Lcom/apktool/perminject/PermCheck;";
        Set<String> taken = new HashSet<>();
        for (File dir : smaliDirs) {
            List<File> files = new ArrayList<>();
            DexFixer.walkSmali(files, dir);
            for (File f : files) {
                try (BufferedReader br = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                    String first = br.readLine();
                    if (first == null || !first.startsWith(".class")) continue;
                    for (String tok : first.trim().split("\\s+")) {
                        if (tok.startsWith("L") && tok.endsWith(";")) taken.add(tok);
                    }
                } catch (IOException ignored) {
                }
            }
        }
        String candidate = base;
        int n = 2;
        while (taken.contains(candidate)) {
            candidate = base.substring(0, base.length() - 1) + n++ + ";";
        }
        return candidate;
    }

    /** 把 inject 指令插到目标类 smali 文件里所有 onCreate 方法的第一条指令之前。 */
    static boolean injectIntoSmaliFile(File f, String helperCall) throws IOException {
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        if (lines.contains(helperCall)) return false; // 已注入过，防跨流程重复插指令
        List<String> out = new ArrayList<>(lines.size() + 4);
        boolean inTarget = false;
        boolean injectedThis = false;
        boolean injectedAny = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith(".method")) {
                inTarget = isTargetMethod(t);
                injectedThis = false;
            }
            if (inTarget && !injectedThis && isInstruction(t)) {
                out.add(helperCall);
                injectedThis = true;
                injectedAny = true;
            }
            out.add(line);
            if (t.startsWith(".end method")) inTarget = false;
        }
        if (!injectedAny) return false;
        Files.write(f.toPath(), out, StandardCharsets.UTF_8);
        return true;
    }

    /** 是否是要注入的 onCreate（非 abstract/native/static 的实例方法）。 */
    private static boolean isTargetMethod(String t) {
        if (t.contains(" abstract") || t.contains(" native") || t.contains(" static")) return false;
        int lp = t.indexOf('(');
        if (lp < 0) return false;
        String head = t.substring(0, lp).trim();
        String name = head.substring(head.lastIndexOf(' ') + 1);
        return "onCreate".equals(name);
    }

    /** 第一条可插入指令：跳过空行、注释、标签、指令（.locals/.param/.annotation 等）。 */
    private static boolean isInstruction(String t) {
        if (t.isEmpty()) return false;
        char c = t.charAt(0);
        return c != '#' && c != ':' && c != '.';
    }

    /** 在多个 smali 目录中定位类文件，返回 null 表示该 dex 中没有这个类。 */
    private static File findSmaliFile(Map<String, File> smaliDirs, String relPath) {
        for (File dir : smaliDirs.values()) {
            File f = new File(dir, relPath);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static File dirOf(Map<String, File> smaliDirs, File smaliFile) {
        String abs = smaliFile.getAbsolutePath();
        for (File dir : smaliDirs.values()) {
            if (abs.startsWith(dir.getAbsolutePath() + File.separator)) return dir;
        }
        return null;
    }

    /** 该 smali 目录是否有注入目标或辅助类，决定是否重汇编。 */
    private static boolean touched(File dir, Set<String> injectedFiles, File helperDir) {
        String prefix = dir.getAbsolutePath() + File.separator;
        if (helperDir != null && helperDir.getAbsolutePath().equals(dir.getAbsolutePath())) return true;
        for (String abs : injectedFiles) {
            if (abs.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 类描述符 -> smali 相对路径，如 Lcom/a/B; -> com/a/B.smali。 */
    private static String descriptorToRelPath(String descriptor) {
        String s = descriptor;
        if (s.startsWith("L")) s = s.substring(1);
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        return s + ".smali";
    }

    static byte[] readEntry(File apk, String name) throws Exception {
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                return bos.toByteArray();
            }
        }
    }
}
