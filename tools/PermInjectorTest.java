package com.apkreader.fixer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PermInjector.injectIntoSmaliFile 回归测试：
 * 曾因 injected 标志在每个 .method 行被重置，目标方法（onCreate）之后只要还有
 * 其他方法，末尾判断就误判「未注入」而丢弃整个编辑，运行时权限请求静默丢失。
 */
public class PermInjectorTest {

    static int fails = 0;

    static void check(boolean cond, String msg) {
        System.out.println((cond ? "PASS: " : "FAIL: ") + msg);
        if (!cond) fails++;
    }

    static final String CALL = "    invoke-static {p0}, Lcom/apktool/perminject/PermCheck;->check(Landroid/content/Context;)V";

    /** onCreate 在最前、后面还有 onResume：真实 Activity 的常见形态，曾失败。 */
    static final String ACTIVITY_WITH_TRAILING =
            ".class public Lcom/example/MainActivity;\n"
            + ".super Landroid/app/Activity;\n"
            + "\n"
            + ".method protected onCreate(Landroid/os/Bundle;)V\n"
            + "    .registers 2\n"
            + "\n"
            + "    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method protected onResume()V\n"
            + "    .registers 1\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method public onDestroy()V\n"
            + "    .registers 1\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n";

    /** onCreate 在文件末尾（原本就工作的场景，防回归）。 */
    static final String ACTIVITY_ONCREATE_LAST =
            ".class public Lcom/example/MainActivity2;\n"
            + ".super Landroid/app/Activity;\n"
            + "\n"
            + ".method public onDestroy()V\n"
            + "    .registers 1\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method protected onCreate(Landroid/os/Bundle;)V\n"
            + "    .registers 2\n"
            + "\n"
            + "    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n";

    /** 完全没有 onCreate 的类：不应注入、文件不得被改写。 */
    static final String NO_ONCREATE =
            ".class public Lcom/example/NoCreate;\n"
            + ".super Ljava/lang/Object;\n"
            + "\n"
            + ".method public foo()V\n"
            + "    .registers 1\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n";

    /** onCreate 方法体以注解块（含多行数组值）开头：注入行必须落在 .end annotation 之后，
     *  否则 smali 重汇编报 missing EQUAL（invoke-static {p0} 被当作注解元素值）。 */
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
        Path dir = Files.createTempDirectory("perminjtest");

        // 1) onCreate 在前、后有其他方法 —— 回归点
        File f1 = write(dir, "MainActivity.smali", ACTIVITY_WITH_TRAILING);
        boolean r1 = PermInjector.injectIntoSmaliFile(f1, CALL);
        check(r1, "onCreate 后有其他方法时注入返回 true（原 bug 返回 false）");
        String s1 = new String(Files.readAllBytes(f1.toPath()), StandardCharsets.UTF_8);
        int idx = s1.indexOf(CALL);
        check(idx >= 0, "注入指令已写入文件");
        int onCreateIdx = s1.indexOf(".method protected onCreate");
        int instrIdx = s1.indexOf("invoke-super", onCreateIdx);
        check(idx >= 0 && onCreateIdx >= 0 && instrIdx >= 0
                        && idx > onCreateIdx && idx < instrIdx,
                "注入指令位于 onCreate 首条指令（invoke-super）之前");
        check(count(s1, CALL) == 1, "整文件仅注入 1 条（未被误插到 onResume/onDestroy）");

        // 2) onCreate 在末尾（原已支持的形态）
        File f2 = write(dir, "MainActivity2.smali", ACTIVITY_ONCREATE_LAST);
        boolean r2 = PermInjector.injectIntoSmaliFile(f2, CALL);
        check(r2, "onCreate 在末尾时注入返回 true");
        check(new String(Files.readAllBytes(f2.toPath()), StandardCharsets.UTF_8).contains(CALL),
                "末尾形态注入指令已写入");

        // 3) 无 onCreate：返回 false 且文件原样
        File f3 = write(dir, "NoCreate.smali", NO_ONCREATE);
        boolean r3 = PermInjector.injectIntoSmaliFile(f3, CALL);
        check(!r3, "无 onCreate 返回 false");
        check(NO_ONCREATE.equals(new String(Files.readAllBytes(f3.toPath()), StandardCharsets.UTF_8)),
                "无 onCreate 时文件未被改写");

        // 4) 幂等防抖：对已注入过的文件再次调用不得重复插入
        File f4 = write(dir, "Dup.smali", ACTIVITY_WITH_TRAILING);
        PermInjector.injectIntoSmaliFile(f4, CALL);
        boolean second = PermInjector.injectIntoSmaliFile(f4, CALL);
        check(!second, "已注入过的文件再次调用返回 false");
        String s4 = new String(Files.readAllBytes(f4.toPath()), StandardCharsets.UTF_8);
        check(count(s4, CALL) == 1, "单文件重复调用只插入 1 条（实际 " + count(s4, CALL) + "）");

        // 5) 注解块在方法体开头：注入行必须跳过注解区，落在 .end annotation 与 invoke-super 之间
        File f5 = write(dir, "AnnotActivity.smali", ACTIVITY_ANNOTATION_FIRST);
        boolean r5 = PermInjector.injectIntoSmaliFile(f5, CALL);
        check(r5, "方法体开头有注解块时注入返回 true");
        String s5 = new String(Files.readAllBytes(f5.toPath()), StandardCharsets.UTF_8);
        int call5 = s5.indexOf(CALL);
        int endAnn5 = s5.indexOf(".end annotation");
        int inv5 = s5.indexOf("invoke-super", s5.indexOf(".method protected onCreate"));
        check(call5 >= 0 && endAnn5 >= 0 && inv5 >= 0
                        && call5 > endAnn5 && call5 < inv5,
                "注入行位于 .end annotation 之后、invoke-super 之前");
        check(count(s5, CALL) == 1, "注解开头形态仅注入 1 条");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    static File write(Path dir, String name, String content) throws Exception {
        File f = new File(dir.toFile(), name);
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }
}
