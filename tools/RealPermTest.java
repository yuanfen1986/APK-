package com.apkreader.parser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 用真实 aapt2 构建的 AndroidManifest.xml 验证 AXmlEditor.addPermissions：
 * 读入 /tmp/axmltest/real.xml（或命令行第一个参数），加权限后重新解析校验。
 */
public class RealPermTest {

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
        List<AXmlParser.Node> out = new ArrayList<>();
        for (AXmlParser.Node c : n.children) {
            if (name.equals(c.name)) out.add(c);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "/tmp/axmltest/real.xml";
        byte[] xml = Files.readAllBytes(Paths.get(path));
        System.out.println("输入: " + path + " (" + xml.length + " bytes)");

        AXmlEditor.ManifestInfo info0 = AXmlEditor.readManifest(xml);
        check(info0.packageName != null && !info0.packageName.isEmpty(),
                "readManifest package=" + info0.packageName);
        System.out.println("  applicationClass=" + info0.applicationClass
                + " launcherActivity=" + info0.launcherActivity
                + " 已有权限=" + info0.permissions);

        List<String> perms = Arrays.asList(
                "android.permission.INTERNET",
                "android.permission.READ_EXTERNAL_STORAGE");
        byte[] out = AXmlEditor.addPermissions(xml, perms);
        check(out != null, "addPermissions 返回非空字节");
        if (out == null) {
            System.out.println("  （可能已存在全部权限，先跳过）");
            System.exit(fails == 0 ? 0 : 1);
        }
        System.out.println("  输出 " + xml.length + " -> " + out.length + " bytes");

        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        check(bb.getInt(4) == out.length,
                "XML 头长度字段 == 实际长度 (" + bb.getInt(4) + " vs " + out.length + ")");

        AXmlParser.Node root = new AXmlParser(null).parseTree(out);
        check(root != null && "manifest".equals(root.name), "重新解析成功，根为 manifest");
        String pkg = attr(root, null, "package");
        check(pkg != null && pkg.equals(info0.packageName), "package 属性保留: " + pkg);
        check(childrenNamed(root, "application").size() == 1, "application 元素保留");

        List<AXmlParser.Node> ups = childrenNamed(root, "uses-permission");
        check(ups.size() >= 2, "uses-permission 数量 >= 2（实际 " + ups.size() + "）");
        boolean foundI = false, foundR = false;
        for (AXmlParser.Node n : ups) {
            String v = attr(n, AXmlEditor.NS_ANDROID, "name");
            if ("android.permission.INTERNET".equals(v)) foundI = true;
            if ("android.permission.READ_EXTERNAL_STORAGE".equals(v)) foundR = true;
        }
        check(foundI && foundR, "INTERNET=" + foundI + " READ_EXTERNAL_STORAGE=" + foundR);

        AXmlEditor.ManifestInfo info = AXmlEditor.readManifest(out);
        check(info.permissions.containsAll(perms), "readManifest 读到新权限: " + info.permissions);

        // 幂等
        check(AXmlEditor.addPermissions(out, perms) == null, "重复添加返回 null（幂等）");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
