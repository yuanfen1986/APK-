package com.apkreader.fixer;

import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 注入结果语法回归：方法体以注解块（含多行数组值）开头时，注入行必须落在
 * .end annotation 之后，否则 Smali.assemble 报 missing EQUAL
 * （invoke-static {p0} 被当作注解元素值）。本测试对注入后的 smali 真实重汇编。
 */
public class SmaliRoundTripTest {

    static int fails = 0;

    static void check(boolean c, String m) {
        System.out.println((c ? "PASS: " : "FAIL: ") + m);
        if (!c) fails++;
    }

    static final String CALL = "    invoke-static {p0}, Lcom/apktool/perminject/PermCheck;->check(Landroid/content/Context;)V";

    static final String ACTIVITY_ANNOTATION_FIRST =
            ".class public Lcom/example/AnnotActivity;\n"
            + ".super Landroid/app/Activity;\n"
            + "\n"
            + ".method protected onCreate(Landroid/os/Bundle;)V\n"
            + "    .registers 2\n"
            + "    .annotation system Ldalvik/annotation/Throws;\n"
            + "        value = {\n"
            + "            Ljava/lang/Exception;\n"
            + "        }\n"
            + "    .end annotation\n"
            + "\n"
            + "    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n";

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("roundtrip");
        Path smaliRoot = dir.resolve("smali");
        Files.createDirectories(smaliRoot.resolve("com/example"));
        File f = smaliRoot.resolve("com/example/AnnotActivity.smali").toFile();
        Files.write(f.toPath(), ACTIVITY_ANNOTATION_FIRST.getBytes(StandardCharsets.UTF_8));

        boolean r = PermInjector.injectIntoSmaliFile(f, CALL);
        check(r, "注入返回 true");

        File outDex = dir.resolve("out.dex").toFile();
        SmaliOptions so = new SmaliOptions();
        so.apiLevel = 21;
        so.jobs = 1;
        so.verboseErrors = true;
        so.outputDexFile = outDex.getAbsolutePath();
        boolean ok = Smali.assemble(so, smaliRoot.toFile().getAbsolutePath());
        check(ok, "注入后的 smali 可被 Smali.assemble 重新汇编");
        check(outDex.isFile() && outDex.length() > 0, "生成 dex 文件，size=" + outDex.length());

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
