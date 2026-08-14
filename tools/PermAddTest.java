package com.apkreader.parser;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AXmlEditor.addPermissions 的定点字节手术回归测试（纯 javac 可运行）：
 * 手工构造二进制 manifest，添加权限后重新解析校验。
 */
public class PermAddTest {

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

    // ---- 以下为二进制 XML 字节组装（与 TestHarness 相同手法）----

    static class Buf extends ByteArrayOutputStream {
        void u8(int v) { write(v & 0xFF); }

        void u16(int v) { write(v & 0xFF); write((v >> 8) & 0xFF); }

        void u32(long v) {
            write((int) (v & 0xFF));
            write((int) ((v >> 8) & 0xFF));
            write((int) ((v >> 16) & 0xFF));
            write((int) ((v >> 24) & 0xFF));
        }

        void bytes(byte[] b) { write(b, 0, b.length); }
    }

    static byte[] utf8Pool(String[] strs) {
        int headerSize = 28;
        int stringsStart = headerSize + 4 * strs.length;
        List<byte[]> datas = new ArrayList<>();
        int dataLen = 0;
        int[] offsets = new int[strs.length];
        for (int i = 0; i < strs.length; i++) {
            offsets[i] = dataLen;
            byte[] raw = strs[i].getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream s = new ByteArrayOutputStream();
            s.write(strs[i].length());
            s.write(raw.length);
            s.write(raw, 0, raw.length);
            s.write(0);
            byte[] sd = s.toByteArray();
            datas.add(sd);
            dataLen += sd.length;
            while ((dataLen & 3) != 0) dataLen++;
        }
        int size = stringsStart + dataLen;
        Buf out = new Buf();
        out.u16(0x0001);
        out.u16(headerSize);
        out.u32(size);
        out.u32(strs.length);
        out.u32(0);
        out.u32(0x100);
        out.u32(stringsStart);
        out.u32(0);
        for (int o : offsets) out.u32(o);
        for (byte[] sd : datas) {
            out.bytes(sd);
            int pad = (4 - (sd.length & 3)) & 3;
            while (pad-- > 0) out.write(0);
        }
        return out.toByteArray();
    }

    /** UTF-16 字符串池（flags=0x0）：长度前缀为小端 u16，数据 UTF-16LE，与真实 aapt2 manifest 一致。 */
    /** 带空槽的池：offset 数组第 gapIdx 项写 0xFFFFFFFF（数据仍按序存放但不被引用），
     *  模拟真实 aapt2 manifest 中被删除/闲置的字符串槽位。 */
    static byte[] utf8PoolGap(String[] strs, int... gapIdx) {
        int headerSize = 28;
        int stringsStart = headerSize + 4 * strs.length;
        List<byte[]> datas = new ArrayList<>();
        int dataLen = 0;
        int[] offsets = new int[strs.length];
        for (int i = 0; i < strs.length; i++) {
            offsets[i] = dataLen;
            byte[] raw = strs[i].getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream s = new ByteArrayOutputStream();
            s.write(strs[i].length());
            s.write(raw.length);
            s.write(raw, 0, raw.length);
            s.write(0);
            byte[] sd = s.toByteArray();
            datas.add(sd);
            dataLen += sd.length;
            while ((dataLen & 3) != 0) dataLen++;
        }
        for (int g : gapIdx) offsets[g] = 0xFFFFFFFF;
        int size = stringsStart + dataLen;
        Buf out = new Buf();
        out.u16(0x0001);
        out.u16(headerSize);
        out.u32(size);
        out.u32(strs.length);
        out.u32(0);
        out.u32(0x100);
        out.u32(stringsStart);
        out.u32(0);
        for (int o : offsets) out.u32(o);
        for (byte[] sd : datas) {
            out.bytes(sd);
            int pad = (4 - (sd.length & 3)) & 3;
            while (pad-- > 0) out.write(0);
        }
        return out.toByteArray();
    }

    static byte[] utf16Pool(String[] strs) {
        int headerSize = 28;
        int stringsStart = headerSize + 4 * strs.length;
        List<byte[]> datas = new ArrayList<>();
        int dataLen = 0;
        int[] offsets = new int[strs.length];
        for (int i = 0; i < strs.length; i++) {
            offsets[i] = dataLen;
            byte[] raw = strs[i].getBytes(StandardCharsets.UTF_16LE);
            ByteArrayOutputStream s = new ByteArrayOutputStream();
            s.write(strs[i].length() & 0xFF);
            s.write((strs[i].length() >> 8) & 0xFF);
            s.write(raw, 0, raw.length);
            s.write(0);
            s.write(0); // UTF-16 结束符
            byte[] sd = s.toByteArray();
            datas.add(sd);
            dataLen += sd.length;
            while ((dataLen & 3) != 0) dataLen++;
        }
        int size = stringsStart + dataLen;
        Buf out = new Buf();
        out.u16(0x0001);
        out.u16(headerSize);
        out.u32(size);
        out.u32(strs.length);
        out.u32(0);
        out.u32(0); // UTF16 flag
        out.u32(stringsStart);
        out.u32(0);
        for (int o : offsets) out.u32(o);
        for (byte[] sd : datas) {
            out.bytes(sd);
            int pad = (4 - (sd.length & 3)) & 3;
            while (pad-- > 0) out.write(0);
        }
        return out.toByteArray();
    }

    static void startElem(Buf out, int nsIdx, int nameIdx, int[][] attrs) {
        int n = attrs.length;
        out.u16(0x0102);
        out.u16(16);
        out.u32(36 + 20 * n); // 8 头 + 16 固定 + 12 属性头 + 20n
        out.u32(1);
        out.u32(0xFFFFFFFFL);
        out.u32(nsIdx < 0 ? 0xFFFFFFFFL : nsIdx);
        out.u32(nameIdx);
        out.u16(0x14);
        out.u16(0x14);
        out.u16(n);
        out.u16(0xFFFF);
        out.u16(0xFFFF);
        out.u16(0xFFFF);
        for (int[] a : attrs) {
            out.u32(a[0] < 0 ? 0xFFFFFFFFL : a[0]);
            out.u32(a[1]);
            out.u32(a[2] < 0 ? 0xFFFFFFFFL : a[2]);
            out.u16(8);
            out.u8(0);
            out.u8(a[3]);
            out.u32(a[4]);
        }
    }

    static void endElem(Buf out, int nsIdx, int nameIdx) {
        out.u16(0x0103);
        out.u16(16);
        out.u32(24); // 8 头 + 4*4 固定
        out.u32(2);
        out.u32(0xFFFFFFFFL);
        out.u32(nsIdx < 0 ? 0xFFFFFFFFL : nsIdx);
        out.u32(nameIdx);
    }

    private static final String[] POOL_STRINGS = {
            "http://schemas.android.com/apk/res/android", // 0 android ns
            "manifest",            // 1
            "package",             // 2
            "com.example.demo",    // 3
            "application",         // 4
            "activity",            // 5
            "name",                // 6
            ".MainActivity",       // 7
            ".App",                // 8
            "intent-filter",       // 9
            "action",              // 10
            "category",            // 11
            "android.intent.action.MAIN",   // 12
            "android.intent.category.LAUNCHER", // 13
    };

    /** 最小但完整的 manifest：package + application(android:name) + 入口 activity(MAIN/LAUNCHER)。 */
    static byte[] buildXml() {
        return buildXml(utf8Pool(POOL_STRINGS));
    }

    /** 同 buildXml，但字符串池用 UTF-16 编码（与真实 aapt2 产物一致）。 */
    static byte[] buildXmlUtf16() {
        return buildXml(utf16Pool(POOL_STRINGS));
    }

    static byte[] buildXml(byte[] pool) {
        Buf body = new Buf();
        // manifest android:package（未加名，仅 package 属性）
        startElem(body, -1, 1, new int[][]{{-1, 2, 3, 0x03, 3}});
        // application android:name=.App
        startElem(body, -1, 4, new int[][]{{0, 6, 8, 0x03, 8}});
        // activity android:name=.MainActivity + MAIN/LAUNCHER intent-filter
        startElem(body, -1, 5, new int[][]{{0, 6, 7, 0x03, 7}});
        startElem(body, -1, 9, new int[][]{});
        startElem(body, -1, 10, new int[][]{{0, 6, 12, 0x03, 12}});
        endElem(body, -1, 10);
        startElem(body, -1, 11, new int[][]{{0, 6, 13, 0x03, 13}});
        endElem(body, -1, 11);
        endElem(body, -1, 9);
        endElem(body, -1, 5);
        endElem(body, -1, 4);
        endElem(body, -1, 1);

        Buf out = new Buf();
        out.u16(0x0003);
        out.u16(8);
        out.u32(8 + pool.length + body.size());
        out.bytes(pool);
        out.bytes(body.toByteArray());
        return out.toByteArray();
    }

    /**
     * 含空槽与冗余串的池：idxMap.size()（16）比 stringCount（18）小 2。修复前 ensure()
     * 以 idxMap.size() 分配新索引，首个权限名撞上池中第 16 项 "uses-sdk"、第二个撞上
     * 空槽 —— 精确复现真实 apk 上「权限名为 uses-sdk/空值」的现场。
     */
    static byte[] buildXmlGap() {
        String[] pool = {
                "http://schemas.android.com/apk/res/android", // 0 android ns
                "manifest",            // 1
                "package",             // 2
                "com.example.demo",    // 3
                "application",         // 4
                "activity",            // 5
                "name",                // 6
                ".MainActivity",       // 7
                ".App",                // 8
                "intent-filter",       // 9
                "action",              // 10
                "category",            // 11
                "android.intent.action.MAIN",   // 12
                "android.intent.category.LAUNCHER", // 13
                "uses-permission",     // 14 已存在于池中
                "dummy-removed",       // 15 空槽
                "uses-sdk",            // 16 修复前首个新索引会撞上它
                "dummy-removed2",      // 17 空槽
        };
        byte[] poolBytes = utf8PoolGap(pool, 15, 17);
        Buf body = new Buf();
        startElem(body, -1, 1, new int[][]{{-1, 2, 3, 0x03, 3}});
        startElem(body, -1, 4, new int[][]{{0, 6, 8, 0x03, 8}});
        startElem(body, -1, 5, new int[][]{{0, 6, 7, 0x03, 7}});
        startElem(body, -1, 9, new int[][]{});
        startElem(body, -1, 10, new int[][]{{0, 6, 12, 0x03, 12}});
        endElem(body, -1, 10);
        startElem(body, -1, 11, new int[][]{{0, 6, 13, 0x03, 13}});
        endElem(body, -1, 11);
        endElem(body, -1, 9);
        endElem(body, -1, 5);
        endElem(body, -1, 4);
        endElem(body, -1, 1);

        Buf out = new Buf();
        out.u16(0x0003);
        out.u16(8);
        out.u32(8 + poolBytes.length + body.size());
        out.bytes(poolBytes);
        out.bytes(body.toByteArray());
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        // 1) 基础往返 + readManifest 元信息
        byte[] xml = buildXml();
        AXmlEditor.ManifestInfo info0 = AXmlEditor.readManifest(xml);
        check("com.example.demo".equals(info0.packageName), "readManifest package");
        check(".App".equals(info0.applicationClass), "readManifest applicationClass=.App");
        check(".MainActivity".equals(info0.launcherActivity), "readManifest launcherActivity=.MainActivity");
        check(info0.permissions.isEmpty(), "readManifest 初始无权限");

        List<String> perms = Arrays.asList(
                "android.permission.INTERNET",
                "android.permission.READ_EXTERNAL_STORAGE");
        byte[] out = AXmlEditor.addPermissions(xml, perms);
        check(out != null, "addPermissions 返回非空字节");

        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        check(bb.getInt(4) == out.length,
                "XML 头长度字段 == 实际长度 (" + bb.getInt(4) + " vs " + out.length + ")");

        AXmlParser.Node root = new AXmlParser(null).parseTree(out);
        check(root != null && "manifest".equals(root.name), "重新解析成功，根为 manifest");
        check("com.example.demo".equals(attr(root, null, "package")), "package 属性保留");
        check(childrenNamed(root, "application").size() == 1, "application 元素保留");
        List<AXmlParser.Node> apps = childrenNamed(root, "application");
        List<AXmlParser.Node> acts = apps.isEmpty() ? new ArrayList<>() : childrenNamed(apps.get(0), "activity");
        check(acts.size() == 1, "application 内 activity 元素保留");

        List<AXmlParser.Node> ups = childrenNamed(root, "uses-permission");
        check(ups.size() == 2, "新增 2 个 uses-permission（实际 " + ups.size() + "）");
        if (ups.size() == 2) {
            String v0 = attr(ups.get(0), AXmlEditor.NS_ANDROID, "name");
            String v1 = attr(ups.get(1), AXmlEditor.NS_ANDROID, "name");
            check(perms.contains(v0) && perms.contains(v1) && !v0.equals(v1),
                    "uses-permission name 正确: " + v0 + ", " + v1);
        }

        // 幂等：同样权限再添加返回 null
        check(AXmlEditor.addPermissions(out, perms) == null, "重复添加返回 null（幂等）");

        // 2) 去重：先加 READ_EXTERNAL_STORAGE，再传 {READ_EXTERNAL_STORAGE, INTERNET}
        byte[] one = AXmlEditor.addPermissions(buildXml(),
                Arrays.asList("android.permission.READ_EXTERNAL_STORAGE"));
        byte[] two = AXmlEditor.addPermissions(one,
                Arrays.asList("android.permission.READ_EXTERNAL_STORAGE", "android.permission.INTERNET"));
        AXmlParser.Node r2 = new AXmlParser(null).parseTree(two);
        List<AXmlParser.Node> ups2 = childrenNamed(r2, "uses-permission");
        check(ups2.size() == 2, "去重后共 2 个 uses-permission（实际 " + ups2.size() + "）");
        if (ups2.size() == 2) {
            String v0 = attr(ups2.get(0), AXmlEditor.NS_ANDROID, "name");
            String v1 = attr(ups2.get(1), AXmlEditor.NS_ANDROID, "name");
            check(!v0.equals(v1), "权限不重复: " + v0 + ", " + v1);
        }

        AXmlEditor.ManifestInfo info = AXmlEditor.readManifest(two);
        check("com.example.demo".equals(info.packageName), "readManifest package 一致");
        check(info.permissions.size() == 2 && info.permissions.containsAll(perms),
                "readManifest 读到 2 个权限: " + info.permissions);

        // 3) UTF-16 池回归：真实 aapt2 manifest 用 UTF-16，曾因长度前缀按大端写入导致解码错位
        byte[] xml16 = buildXmlUtf16();
        byte[] out16 = AXmlEditor.addPermissions(xml16, perms);
        check(out16 != null, "UTF-16 池 addPermissions 返回非空字节");
        if (out16 != null) {
            AXmlParser.Node r3 = new AXmlParser(null).parseTree(out16);
            check(r3 != null && "manifest".equals(r3.name), "UTF-16 输出重新解析成功，根为 manifest");
            List<AXmlParser.Node> ups3 = childrenNamed(r3, "uses-permission");
            check(ups3.size() == 2, "UTF-16 池新增 2 个 uses-permission（实际 " + ups3.size() + "）");
            boolean fI = false, fR = false;
            for (AXmlParser.Node n : ups3) {
                String v = attr(n, AXmlEditor.NS_ANDROID, "name");
                if ("android.permission.INTERNET".equals(v)) fI = true;
                if ("android.permission.READ_EXTERNAL_STORAGE".equals(v)) fR = true;
            }
            check(fI && fR, "UTF-16 池权限名正确: INTERNET=" + fI + " READ=" + fR);
            check(AXmlEditor.addPermissions(out16, perms) == null, "UTF-16 池重复添加返回 null");
        }

        // 4) 空槽池回归：idxMap.size() != stringCount 时，新索引必须以 stringCount 为基
        byte[] xmlGap = buildXmlGap();
        byte[] outGap = AXmlEditor.addPermissions(xmlGap, perms);
        check(outGap != null, "空槽池 addPermissions 返回非空字节");
        if (outGap != null) {
            AXmlParser.Node r4 = new AXmlParser(null).parseTree(outGap);
            check(r4 != null && "manifest".equals(r4.name), "空槽池输出重新解析成功，根为 manifest");
            List<AXmlParser.Node> ups4 = childrenNamed(r4, "uses-permission");
            check(ups4.size() == 2, "空槽池新增 2 个 uses-permission（实际 " + ups4.size() + "）");
            if (ups4.size() == 2) {
                String v0 = attr(ups4.get(0), AXmlEditor.NS_ANDROID, "name");
                String v1 = attr(ups4.get(1), AXmlEditor.NS_ANDROID, "name");
                check(perms.contains(v0) && perms.contains(v1) && !v0.equals(v1),
                        "空槽池权限名正确（修复前 v0=uses-sdk 或空值）: " + v0 + ", " + v1);
            }
            check(AXmlEditor.addPermissions(outGap, perms) == null, "空槽池重复添加返回 null（幂等）");
        }

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
