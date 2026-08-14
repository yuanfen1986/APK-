package com.apkreader.fixer;

import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * buildHelperSmali 的按 SDK 门禁回归：
 * 1) 全选 5 权限时，READ_MEDIA_IMAGES(仅 33+) / READ_EXTERNAL_STORAGE(23-32) /
 *    WRITE_EXTERNAL_STORAGE(23-28) 各带版本门禁，INTERNET 无门禁；
 * 2) MANAGE_EXTERNAL_STORAGE 整块被 API 30+ 门禁包住（修复低版本调用
 *    Environment.isExternalStorageManager 抛 NoSuchMethodError 的缺陷）；
 * 3) 重复权限去重，同一权限只检查一次；
 * 4) 生成的 smali 可被 Smali.assemble 真实重汇编。
 */
public class PermCheckSmaliTest {

    static final String HELPER = "Lcom/apktool/perminject/PermCheck;";

    /** 与 App「权限添加」默认全选一致。 */
    static final String[] ALL_PERMS = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.INTERNET",
            "android.permission.WRITE_EXTERNAL_STORAGE",
    };

    static int fails = 0;

    static void check(boolean c, String m) {
        System.out.println((c ? "PASS: " : "FAIL: ") + m);
        if (!c) fails++;
    }

    static int count(String s, String needle) {
        int n = 0, idx = 0;
        while ((idx = s.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    static List<String> list(String... a) {
        return new ArrayList<>(Arrays.asList(a));
    }

    public static void main(String[] args) throws Exception {
        // ---- 1) 全选：三类存储权限带门禁，INTERNET 不带 ----
        String s = PermInjector.buildHelperSmali(HELPER, list(ALL_PERMS));
        check(count(s, "const/16 v5, 0x21") == 1, "READ_MEDIA_* 门禁 0x21(API33) 出现 1 次");
        check(count(s, "const/16 v5, 0x20") == 1, "READ_EXTERNAL_STORAGE 门禁 0x20(API32) 出现 1 次");
        check(count(s, "const/16 v5, 0x1c") == 1, "WRITE_EXTERNAL_STORAGE 门禁 0x1c(API28) 出现 1 次");

        // READ_MEDIA_IMAGES 门禁：if-lt v0, v5（SDK < 33 跳过）
        int mi = s.indexOf("android.permission.READ_MEDIA_IMAGES");
        check(mi >= 0 && s.indexOf("if-lt v0, v5, :perm_", mi) >= 0
                        && s.indexOf("if-lt v0, v5, :perm_", mi) < s.indexOf("checkSelfPermission", mi),
                "READ_MEDIA_IMAGES 前置 if-lt 门禁（SDK<33 跳过）");
        // READ_EXTERNAL_STORAGE 门禁：if-gt v0, v5（SDK > 32 跳过）
        int re = s.indexOf("android.permission.READ_EXTERNAL_STORAGE");
        check(re >= 0 && s.indexOf("if-gt v0, v5, :perm_", re) >= 0
                        && s.indexOf("if-gt v0, v5, :perm_", re) < s.indexOf("checkSelfPermission", re),
                "READ_EXTERNAL_STORAGE 前置 if-gt 门禁（SDK>32 跳过）");
        // WRITE_EXTERNAL_STORAGE 门禁：if-gt v0, v5（SDK > 28 跳过）
        int we = s.indexOf("android.permission.WRITE_EXTERNAL_STORAGE");
        check(we >= 0 && s.indexOf("if-gt v0, v5, :perm_", we) >= 0
                        && s.indexOf("if-gt v0, v5, :perm_", we) < s.indexOf("checkSelfPermission", we),
                "WRITE_EXTERNAL_STORAGE 前置 if-gt 门禁（SDK>28 跳过）");
        // INTERNET：注释后紧跟 const-string，中间无门禁指令
        int in = s.indexOf("# android.permission.INTERNET\n");
        String afterIn = s.substring(in + "# android.permission.INTERNET\n".length());
        check(in >= 0 && afterIn.startsWith("    const-string v2, \"android.permission.INTERNET\""),
                "INTERNET 无版本门禁，直接 checkSelfPermission");

        // ---- 2) MANAGE 整块 API 30+ 门禁 ----
        int up = s.indexOf("isExternalStorageManager()Z");
        check(up > 0, "MANAGE 块含 isExternalStorageManager");
        String pre = s.substring(0, up);
        check(pre.lastIndexOf("const/16 v5, 0x1e") > pre.lastIndexOf("sget v0") + 1
                        && s.indexOf("if-lt v0, v5, :done", pre.lastIndexOf("const/16 v5, 0x1e")) < up,
                "isExternalStorageManager 前有 API30+ 门禁（0x1e + if-lt :done）");
        check(s.contains(".catch Ljava/lang/Exception; {:try_manage .. :try_manage_end} :catch_manage"),
                "MANAGE startActivity 包 try/catch（防定制 ROM ActivityNotFoundException）");
        check(s.indexOf("startActivity(Landroid/content/Intent;)V") < s.indexOf("    :catch_manage\n"),
                "startActivity 在 catch 处理器之前");

        // ---- 3) 重复权限去重 ----
        String dup = PermInjector.buildHelperSmali(HELPER,
                list("android.permission.INTERNET", "android.permission.INTERNET"));
        check(count(dup, "const-string v2, \"android.permission.INTERNET\"") == 1,
                "重复 INTERNET 去重，只检查 1 次");

        // ---- 4) 只含 MANAGE 时低版本不调用 30+ API ----
        String mg = PermInjector.buildHelperSmali(HELPER, list("android.permission.MANAGE_EXTERNAL_STORAGE"));
        check(mg.indexOf("if-lt v0, v5, :done") < mg.indexOf("isExternalStorageManager()Z"),
                "仅 MANAGE 时门禁仍在 isExternalStorageManager 之前");
        check(count(mg, "checkSelfPermission") == 0, "仅 MANAGE 时无 checkSelfPermission");

        // ---- 5) 空列表：无任何权限逻辑，可汇编 ----
        String empty = PermInjector.buildHelperSmali(HELPER, list());
        check(count(empty, "checkSelfPermission") == 0 && !empty.contains("isExternalStorageManager"),
                "空权限列表无权限逻辑");

        // ---- 6) 真实汇编回归（apiLevel 21，验证标签/指令合法）----
        Path dir = Files.createTempDirectory("permcheck");
        Path smaliRoot = dir.resolve("smali");
        Path f = smaliRoot.resolve("com/apktool/perminject/PermCheck.smali");
        Files.createDirectories(f.getParent());
        Files.write(f, s.getBytes(StandardCharsets.UTF_8));
        File outDex = dir.resolve("out.dex").toFile();
        SmaliOptions so = new SmaliOptions();
        so.apiLevel = 21;
        so.jobs = 1;
        so.verboseErrors = true;
        so.outputDexFile = outDex.getAbsolutePath();
        boolean ok = Smali.assemble(so, smaliRoot.toFile().getAbsolutePath());
        check(ok, "全选权限生成的 smali 可汇编");
        check(outDex.isFile() && outDex.length() > 0, "汇编产出 dex, size=" + outDex.length());

        Path dir2 = Files.createTempDirectory("permcheck2");
        Path smaliRoot2 = dir2.resolve("smali");
        Path f2 = smaliRoot2.resolve("com/apktool/perminject/PermCheck.smali");
        Files.createDirectories(f2.getParent());
        Files.write(f2, mg.getBytes(StandardCharsets.UTF_8));
        File outDex2 = dir2.resolve("out2.dex").toFile();
        SmaliOptions so2 = new SmaliOptions();
        so2.apiLevel = 21;
        so2.jobs = 1;
        so2.verboseErrors = true;
        so2.outputDexFile = outDex2.getAbsolutePath();
        check(Smali.assemble(so2, smaliRoot2.toFile().getAbsolutePath()) && outDex2.isFile(),
                "仅 MANAGE 的 smali 可汇编");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
