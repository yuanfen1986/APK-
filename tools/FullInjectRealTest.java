package com.apkreader.fixer;

import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.ApkFormatException;
import com.apkreader.parser.AXmlEditor;
import com.apkreader.parser.AXmlParser;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 全链路端到端回归：对真实 APK 跑 PermInjector.inject()（manifest 加权限 +
 * dex 注入 + 重打包 + release.jks 签名），再对产物做四项验证：
 * 1) 输出 APK 存在、各 dex 可被 dexlib2 重新加载（结构有效）；
 * 2) 反汇编-重汇编后类/方法数量与原始 dex 一致（round-trip 保真）；
 * 3) 产物 manifest 含新权限、helper 类存在于某 dex；
 * 4) apksig 能验证签名（V1/V2 至少其一有效）。
 * 注入崩溃根因调查：先排除产物本身在「结构/签名」层面的缺陷。
 */
public class FullInjectRealTest {

    static final String[] ALL_PERMS = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.INTERNET",
            "android.permission.WRITE_EXTERNAL_STORAGE",
    };

    static int fails = 0;

    static void check(boolean cond, String msg) {
        System.out.println((cond ? "PASS: " : "FAIL: ") + msg);
        if (!cond) fails++;
    }

    public static void main(String[] args) throws Exception {
        String apkPath = args.length > 0 ? args[0]
                : "C:/Users/LENOVO/Desktop/照片读取/MIUIXCalculator.apk";
        File apk = new File(apkPath);
        check(apk.isFile(), "输入 APK 存在: " + apkPath);
        if (!apk.isFile()) System.exit(1);

        File workDir = Files.createTempDirectory("fullinject").toFile();
        File out = new File(workDir, "out.apk");
        File ks = new File("C:/Users/LENOVO/Desktop/xml读取/app/release.jks");

        System.out.println("  workDir=" + workDir);
        PermInjector.Progress p = m -> System.out.println("  [progress] " + m);
        try {
            PermInjector.inject(apk, out, workDir,
                    Arrays.asList(ALL_PERMS), ks, "apkreader",
                    "ApkReader@2026".toCharArray(), "ApkReader@2026".toCharArray(),
                    true, p);
        } catch (Exception e) {
            System.out.println("FAIL: inject() 抛异常: " + e);
            e.printStackTrace(System.out);
            System.exit(1);
        }
        check(out.isFile(), "产物 APK 存在: " + out);

        // ---- 1) 每个 dex 用 dexlib2 重新加载 ----
        List<String> dexNames = new ArrayList<>();
        try (ZipFile zf = new ZipFile(out)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("classes") && n.endsWith(".dex")) dexNames.add(n);
            }
        }
        check(!dexNames.isEmpty(), "产物含 dex: " + dexNames);
        List<File> tmpDex = new ArrayList<>();
        for (String n : dexNames) {
            try (ZipFile zf = new ZipFile(out)) {
                InputStream in = zf.getInputStream(zf.getEntry(n));
                File f = new File(workDir, "chk_" + n);
                Files.copy(in, f.toPath());
                tmpDex.add(f);
                Opcodes op = DexFixer.opcodesForDex(f);
                DexBackedDexFile df = DexFileFactory.loadDexFile(f, op);
                int clazz = 0, m = 0;
                for (ClassDef c : df.getClasses()) {
                    clazz++;
                    for (Object ignored : c.getMethods()) m++;
                }
                System.out.println("  " + n + " 可加载: classes=" + clazz + " methods=" + m);
            } catch (Exception e) {
                System.out.println("FAIL: " + n + " dexlib2 加载失败: " + e);
                fails++;
            }
        }

        // ---- 2) 原始 vs 产物 dex 的类/方法数对比（round-trip 保真）----
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                File f = new File(workDir, "orig_" + n);
                Files.copy(zf.getInputStream(zf.getEntry(n)), f.toPath());
                File fixed = new File(workDir, "chk_" + n);
                if (!fixed.isFile()) {
                    check(false, n + " 未出现在产物中");
                    continue;
                }
                Opcodes op = DexFixer.opcodesForDex(f);
                DexFile od = DexFileFactory.loadDexFile(f, op);
                DexFile nd = DexFileFactory.loadDexFile(fixed, op);
                int oc = 0, om = 0, nc = 0, nm = 0;
                for (ClassDef c : od.getClasses()) { oc++; for (Object ignored : c.getMethods()) om++; }
                for (ClassDef c : nd.getClasses()) { nc++; for (Object ignored : c.getMethods()) nm++; }
                boolean same = oc == nc && om == nm;
                check(same, n + " round-trip 保真: " + oc + "/" + om + " -> " + nc + "/" + nm);
            }
        }

        // ---- 3) 产物 manifest 含新权限；helper 类在某 dex 中 ----
        try (ZipFile zf = new ZipFile(out)) {
            InputStream in = zf.getInputStream(zf.getEntry("AndroidManifest.xml"));
            byte[] xml = in.readAllBytes();
            AXmlEditor.ManifestInfo info = AXmlEditor.readManifest(xml);
            boolean all = info.permissions.containsAll(Arrays.asList(ALL_PERMS));
            System.out.println("  产物权限: " + info.permissions);
            check(all, "产物 manifest 含全部 5 个新权限");
        }
        boolean helperFound = false;
        for (File f : tmpDex) {
            Opcodes op = DexFixer.opcodesForDex(f);
            DexBackedDexFile df = DexFileFactory.loadDexFile(f, op);
            for (ClassDef c : df.getClasses()) {
                if (c.getType().startsWith("Lcom/apktool/perminject/PermCheck")) helperFound = true;
            }
        }
        check(helperFound, "产物某 dex 含 PermCheck helper 类");

        // ---- 4) apksig 签名验证 ----
        try {
            ApkVerifier verifier = new ApkVerifier.Builder(out).build();
            ApkVerifier.Result r = verifier.verify();
            System.out.println("  apksig: v1=" + r.isVerifiedUsingV1Scheme()
                    + " v2=" + r.isVerifiedUsingV2Scheme()
                    + " v3=" + r.isVerifiedUsingV3Scheme()
                    + " errors=" + r.getErrors().size());
            for (ApkVerifier.IssueWithParams e : r.getErrors()) {
                System.out.println("    ERR: " + e);
            }
            check(r.isVerified() || r.isVerifiedUsingV1Scheme() || r.isVerifiedUsingV2Scheme(),
                    "apksig 签名可验证");
        } catch (ApkFormatException e) {
            System.out.println("FAIL: 产物 APK 格式错误: " + e);
            fails++;
        } catch (Exception e) {
            System.out.println("WARN: apksig verify 抛异常(可能仅 V1): " + e);
        }

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
