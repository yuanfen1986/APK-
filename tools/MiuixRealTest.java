package com.apkreader.parser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 端到端回归：直接打开真实 APK，取 AndroidManifest.xml 条目，跑 addPermissions
 * 后重新解析，校验新权限名。用户报告 com.miuix.calculator 曾出现
 * android:name="uses-sdk" / "" 的坏名，此测试锁死该现场。
 */
public class MiuixRealTest {

    static int fails = 0;

    static void check(boolean cond, String msg) {
        System.out.println((cond ? "PASS: " : "FAIL: ") + msg);
        if (!cond) fails++;
    }

    static String attr(AXmlParser.Node n, String ns, String name) {
        for (AXmlParser.Attr a : n.attrs) {
            if (name.equals(a.name) && (ns == null ? a.ns == null : ns.equals(a.ns))) return a.value;
        }
        return null;
    }

    static List<AXmlParser.Node> childrenNamed(AXmlParser.Node n, String name) {
        List<AXmlParser.Node> out = new java.util.ArrayList<>();
        for (AXmlParser.Node c : n.children) {
            if (name.equals(c.name)) out.add(c);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String apkPath = args.length > 0 ? args[0]
                : "C:/Users/LENOVO/Desktop/照片读取/MIUIXCalculator.apk";
        File apk = new File(apkPath);
        check(apk.isFile(), "APK 存在: " + apkPath);
        if (!apk.isFile()) System.exit(1);

        byte[] xml;
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("AndroidManifest.xml");
            check(e != null, "APK 含 AndroidManifest.xml");
            if (e == null) System.exit(1);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = zf.getInputStream(e)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            }
            xml = bos.toByteArray();
        }
        System.out.println("  manifest " + xml.length + " bytes");

        AXmlEditor.ManifestInfo info0 = AXmlEditor.readManifest(xml);
        System.out.println("  package=" + info0.packageName
                + " app=" + info0.applicationClass
                + " launcher=" + info0.launcherActivity
                + " 已有权限=" + info0.permissions);
        check(info0.packageName != null, "读到包名");

        List<String> perms = Arrays.asList(
                "android.permission.INTERNET",
                "android.permission.READ_EXTERNAL_STORAGE");
        byte[] out = AXmlEditor.addPermissions(xml, perms);
        check(out != null, "addPermissions 返回非空字节");
        if (out == null) System.exit(fails == 0 ? 0 : 1);

        AXmlParser.Node root = new AXmlParser(null).parseTree(out);
        check(root != null && "manifest".equals(root.name), "输出重新解析成功，根为 manifest");
        List<AXmlParser.Node> ups = childrenNamed(root, "uses-permission");
        boolean foundI = false, foundR = false;
        for (AXmlParser.Node n : ups) {
            String v = attr(n, AXmlEditor.NS_ANDROID, "name");
            System.out.println("  uses-permission android:name=\"" + v + "\"");
            if ("android.permission.INTERNET".equals(v)) foundI = true;
            if ("android.permission.READ_EXTERNAL_STORAGE".equals(v)) foundR = true;
        }
        check(foundI && foundR, "INTERNET=" + foundI + " READ_EXTERNAL_STORAGE=" + foundR);
        check(AXmlEditor.addPermissions(out, perms) == null, "重复添加返回 null（幂等）");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
