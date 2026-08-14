package com.apkreader.parser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 按 App「权限添加」默认全选的 5 个权限，对真实 APK 端到端复现：
 * 取 AndroidManifest.xml -> addPermissions -> 重新解析，
 * dump 全部 uses-permission（含原有）的 android:name，检查是否有空值。
 */
public class FullPermsRealTest {

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
        check(apk.isFile(), "APK exists: " + apkPath);
        if (!apk.isFile()) System.exit(1);

        byte[] xml;
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("AndroidManifest.xml");
            check(e != null, "APK contains AndroidManifest.xml");
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

        byte[] out = AXmlEditor.addPermissions(xml, Arrays.asList(ALL_PERMS));
        check(out != null, "addPermissions returned non-null bytes");
        if (out == null) System.exit(fails == 0 ? 0 : 1);
        System.out.println("  output " + out.length + " bytes (was " + xml.length + ")");

        AXmlParser.Node root = new AXmlParser(null).parseTree(out);
        check(root != null && "manifest".equals(root.name), "re-parse OK, root=manifest");
        List<AXmlParser.Node> ups = childrenNamed(root, "uses-permission");
        System.out.println("  total uses-permission: " + ups.size());
        boolean anyEmpty = false;
        for (AXmlParser.Node n : ups) {
            String v = attr(n, AXmlEditor.NS_ANDROID, "name");
            boolean empty = v == null || v.isEmpty();
            if (empty) anyEmpty = true;
            System.out.println("    uses-permission android:name=\"" + v + "\" "
                    + (empty ? "  <-- EMPTY" : ""));
        }
        check(!anyEmpty, "no empty uses-permission names");

        int expected = 1 /* existing DYNAMIC_RECEIVER */ + ALL_PERMS.length;
        check(ups.size() == expected, "uses-permission count = " + expected + " (got " + ups.size() + ")");

        AXmlEditor.ManifestInfo info = AXmlEditor.readManifest(out);
        check(info.permissions.containsAll(Arrays.asList(ALL_PERMS)),
                "readManifest sees all 5 new perms: " + info.permissions.size() + " total");

        // idempotency
        check(AXmlEditor.addPermissions(out, Arrays.asList(ALL_PERMS)) == null, "idempotent: 2nd add -> null");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
