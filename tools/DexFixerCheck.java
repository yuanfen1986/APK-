import com.android.apksig.ApkVerifier;
import com.apkreader.fixer.DexFixer;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** DexFixer 验证：cleanSmali 正则清理单测 + fix() 端到端（汇编->修复->重签名->ApkVerifier 校验）。 */
public class DexFixerCheck {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        testCleanSmali();
        testEndToEnd();
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String name, boolean cond, String detail) {
        if (cond) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "  " + detail);
        }
    }

    static String cleanSmali(String text) throws Exception {
        Method m = Class.forName("com.apkreader.fixer.DexFixer")
                .getDeclaredMethod("cleanSmali", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, text);
    }

    /** 覆盖 regex1 全部 8 个分支 + regex2 空参分支 + 删空补 return-void。 */
    static final String SAMPLE =
            ".class public Lcom/sample/A;\n"
            + ".super Ljava/lang/Object;\n"
            + "\n"
            + ".method public constructor <init>()V\n"
            + "    .registers 2\n"
            + "\n"
            + "    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method static constructor <clinit>()V\n"
            + "    .registers 2\n"
            + "\n"
            + "    const v0, 0x1234\n"
            + "\n"
            + "    invoke-static {v0}, Lcom/stub/StubApp;->interface11(I)V\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method public foo()V\n"
            + "    .registers 3\n"
            + "\n"
            + "    const v0, 0x55\n"
            + "\n"
            + "    invoke-static {v0}, Lcom/stub/StubApp;->interface11(I)V\n"
            + "\n"
            + "    invoke-static/range {p0 .. p0}, Lcom/stub/StubApp;->getOrigApplicationContext(Landroid/content/Context;)Landroid/content/Context;\n"
            + "\n"
            + "    move-result-object p0\n"
            + "\n"
            + "    sput-object p0, Lcom/sample/A;->ctx:Landroid/content/Context;\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method public bar()V\n"
            + "    .registers 2\n"
            + "\n"
            + "    move-result-object v0\n"
            + "\n"
            + "    invoke-static/range {v0 .. v0}, Lcom/stub/StubApp;->getOrigApplicationContext(Landroid/content/Context;)Landroid/content/Context;\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method public baz(Landroid/app/Activity;[Ljava/lang/String;I)V\n"
            + "    .registers 6\n"
            + "\n"
            + "    invoke-static {p0, p1, p2}, Lcom/stub/StubApp;->interface24(Landroid/app/Activity;[Ljava/lang/String;I)V\n"
            + "\n"
            + "    invoke-static {p1, p2, p3}, Lcom/stub/StubApp;->interface22(I[Ljava/lang/String;[I)V\n"
            + "\n"
            + "    invoke-static {v5}, Lcom/stub/StubApp;->interface12(Ldalvik/system/DexFile;)Ljava/util/Enumeration;\n"
            + "\n"
            + "    invoke-static {}, Lcom/stub/StubApp;->interface17()V\n"
            + ".end method\n"
            + "\n"
            + ".method public abstract onAbstract()V\n"
            + ".end method\n";

    static void testCleanSmali() throws Exception {
        System.out.println("== cleanSmali unit test ==");
        String cleaned = cleanSmali(SAMPLE);
        assertClean(cleaned, "LF");

        String cleanedCrlf = cleanSmali(SAMPLE.replace("\n", "\r\n"));
        assertClean(cleanedCrlf, "CRLF");
        check("CRLF normalized == LF result", cleanedCrlf.equals(cleaned), "");
    }

    static void assertClean(String s, String tag) {
        check(tag + ": no StubApp left", !s.contains("StubApp"), "still contains StubApp");
        check(tag + ": empty <init> removed", !s.contains("<init>"), "still contains <init>");
        check(tag + ": <clinit> removed", !s.contains("<clinit>"), "still contains <clinit>");
        check(tag + ": interface11 removed", !s.contains("interface11"), "");
        check(tag + ": interface12 removed", !s.contains("interface12"), "");
        check(tag + ": interface17 removed", !s.contains("interface17"), "");
        check(tag + ": interface22 removed", !s.contains("interface22"), "");
        check(tag + ": interface24 removed", !s.contains("interface24"), "");
        check(tag + ": getOrigApplicationContext removed", !s.contains("getOrigApplicationContext"), "");
        check(tag + ": foo keeps sput-object",
                s.contains("sput-object p0, Lcom/sample/A;->ctx:Landroid/content/Context;"), "");
        check(tag + ": bar keeps .registers 2", s.contains(".registers 2"), "");
        check(tag + ": baz keeps .registers 6", s.contains(".registers 6"), "");
        int methods = count(s, ".method");
        check(tag + ": methods left = 4 (foo/bar/baz/abstract)", methods == 4, "actual=" + methods);
        int rv = count(s, "return-void");
        check(tag + ": return-void count = 3 (foo + bar fill + baz fill)", rv == 3, "actual=" + rv);
        // 回归：抽象方法不得被补 return-void（否则 smali 报 "An abstract method cannot have any instructions"）
        check(tag + ": abstract method preserved without return-void",
                s.contains(".method public abstract onAbstract()V\n.end method"), "");
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    static final String TEST_CLASS =
            ".class public Lcom/test/TestApp;\n"
            + ".super Landroid/app/Application;\n"
            + "\n"
            + ".field public static mContext:Landroid/content/Context;\n"
            + "\n"
            + ".method public constructor <init>()V\n"
            + "    .registers 2\n"
            + "\n"
            + "    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method static constructor <clinit>()V\n"
            + "    .registers 1\n"
            + "\n"
            + "    const v0, 0x1234\n"
            + "\n"
            + "    invoke-static {v0}, Lcom/stub/StubApp;->interface11(I)V\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method public onCreate()V\n"
            + "    .registers 3\n"
            + "\n"
            + "    const v0, 0x55\n"
            + "\n"
            + "    invoke-static {v0}, Lcom/stub/StubApp;->interface11(I)V\n"
            + "\n"
            + "    invoke-static/range {p0 .. p0}, Lcom/stub/StubApp;->getOrigApplicationContext(Landroid/content/Context;)Landroid/content/Context;\n"
            + "\n"
            + "    move-result-object p0\n"
            + "\n"
            + "    sput-object p0, Lcom/test/TestApp;->mContext:Landroid/content/Context;\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method public onEmpty()V\n"
            + "    .registers 1\n"
            + "\n"
            + "    invoke-static {}, Lcom/stub/StubApp;->interface17()V\n"
            + ".end method\n"
            + "\n"
            + ".method public abstract onAbstract()V\n"
            + ".end method\n";

    static void testEndToEnd() throws Exception {
        System.out.println("== fix() end-to-end test ==");
        Path work = Files.createTempDirectory("dexfixcheck");
        File smaliDir = new File(work.toFile(), "smali");
        if (!smaliDir.mkdir()) throw new RuntimeException("mkdir fail");
        Files.write(new File(smaliDir, "TestApp.smali").toPath(), TEST_CLASS.getBytes(StandardCharsets.UTF_8));

        File classesDex = new File(work.toFile(), "classes.dex");
        SmaliOptions so = new SmaliOptions();
        so.apiLevel = 21;
        so.jobs = 1;
        so.outputDexFile = classesDex.getAbsolutePath();
        if (!Smali.assemble(so, smaliDir.getAbsolutePath())) {
            throw new RuntimeException("test smali assemble failed");
        }
        byte[] dexBytes = Files.readAllBytes(classesDex.toPath());
        check("raw dex contains StubApp", new String(dexBytes, StandardCharsets.UTF_8).contains("StubApp"), "");

        File inApk = new File(work.toFile(), "input.apk");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(inApk))) {
            // 注入真实 AndroidManifest.xml（取自 app-debug.apk），否则 apksig 无法推导 minSdk
            File debugApk = new File("app/build/outputs/apk/debug/app-debug.apk");
            if (debugApk.exists()) {
                byte[] manifest;
                try (ZipFile dzf = new ZipFile(debugApk)) {
                    ZipEntry me = dzf.getEntry("AndroidManifest.xml");
                    try (InputStream in = dzf.getInputStream(me)) {
                        manifest = in.readAllBytes();
                    }
                }
                zos.putNextEntry(new ZipEntry("AndroidManifest.xml"));
                zos.write(manifest);
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("classes.dex"));
            zos.write(dexBytes);
            zos.closeEntry();
        }

        File keystore = new File("app/release.jks");
        File outApk = new File(work.toFile(), "output.apk");
        DexFixer.fix(inApk, outApk, new File(work.toFile(), "fixwork"), keystore,
                "apkreader", "ApkReader@2026".toCharArray(), "ApkReader@2026".toCharArray(),
                true,
                msg -> System.out.println("    [fix] " + msg));

        check("output APK exists", outApk.exists() && outApk.length() > 0,
                "size=" + (outApk.exists() ? outApk.length() : -1));

        try (ZipFile zf = new ZipFile(outApk)) {
            ZipEntry e = zf.getEntry("classes.dex");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = zf.getInputStream(e)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            }
            String dexText = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            check("fixed dex has no StubApp", !dexText.contains("StubApp"), "");
        }

        ApkVerifier.Result vr = new ApkVerifier.Builder(outApk).build().verify();
        check("output APK signature verifies (v1+v2)", vr.isVerified(), "errors=" + vr.getErrors());

        System.out.println("    outputAPK=" + outApk.getAbsolutePath());

        // 回归：signApk=false 时输出未签名 APK —— 内容修复照常，但不得包含 META-INF 签名条目
        File outApkNoSign = new File(work.toFile(), "output-nosign.apk");
        DexFixer.fix(inApk, outApkNoSign, new File(work.toFile(), "fixwork-nosign"), keystore,
                "apkreader", "ApkReader@2026".toCharArray(), "ApkReader@2026".toCharArray(),
                false,
                msg -> { });
        check("nosign output APK exists", outApkNoSign.exists() && outApkNoSign.length() > 0,
                "size=" + (outApkNoSign.exists() ? outApkNoSign.length() : -1));
        try (ZipFile zf = new ZipFile(outApkNoSign)) {
            ZipEntry e = zf.getEntry("classes.dex");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = zf.getInputStream(e)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            }
            String dexText = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            check("nosign dex has no StubApp", !dexText.contains("StubApp"), "");
        }
        boolean hasSigEntry = false;
        try (ZipFile zf = new ZipFile(outApkNoSign)) {
            Enumeration<? extends ZipEntry> ents = zf.entries();
            while (ents.hasMoreElements()) {
                String n = ents.nextElement().getName();
                String u = n.toUpperCase();
                if (u.startsWith("META-INF/") && (u.endsWith(".RSA") || u.endsWith(".DSA")
                        || u.endsWith(".EC") || u.endsWith(".SF") || u.endsWith("MANIFEST.MF"))) {
                    hasSigEntry = true;
                    System.out.println("    unexpected signature entry: " + n);
                }
            }
        }
        check("nosign APK has no META-INF signature entries", !hasSigEntry, "");
        System.out.println("    outputNoSignAPK=" + outApkNoSign.getAbsolutePath());
    }
}
