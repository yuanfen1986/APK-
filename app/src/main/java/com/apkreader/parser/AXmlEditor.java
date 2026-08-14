package com.apkreader.parser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 二进制 AndroidManifest.xml 编辑（不改写整个文件，只做定点字节手术）：
 * - {@link #readManifest}：读取包名、Application 类、Launcher Activity、已有 uses-permission
 * - {@link #addPermissions}：向字符串池追加权限相关字符串，并在根 &lt;manifest&gt;
 *   起始元素 chunk 后插入 &lt;uses-permission&gt; 的 START/END chunk，返回新字节
 * - {@link #resolveClassName}/{@link #classToSmaliPath}：类名 -> dex 内 smali 路径
 *
 * 二进制 XML 里所有交叉引用都是字符串池索引（u32），不是字节偏移，因此：
 * 在字符串池偏移数组尾部插入新偏移 + 在池末尾追加字符串数据后，其余 chunk 整体
 * 平移若干字节无需改任何字段，只需更新 XML 总长、池的 size/stringCount/stringsStart。
 */
public final class AXmlEditor {

    public static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_START_ELEMENT = 0x0102;
    private static final int CHUNK_END_ELEMENT = 0x0103;
    private static final int UTF8_FLAG = 0x00000100;

    private AXmlEditor() {
    }

    /** manifest 结构信息，供权限注入定位目标类。 */
    public static class ManifestInfo {
        public String packageName;
        public String applicationClass;
        public String launcherActivity;
        public final List<String> permissions = new ArrayList<>();
    }

    /** 读取 manifest 结构：包名 / Application 类 / Launcher Activity / 现有权限。 */
    public static ManifestInfo readManifest(byte[] xml) throws Exception {
        ManifestInfo info = new ManifestInfo();
        AXmlParser.Node root = new AXmlParser(null).parseTree(xml);
        if (root == null || !"manifest".equals(root.name)) return info;
        for (AXmlParser.Attr a : root.attrs) {
            if (a.ns == null && "package".equals(a.name)) info.packageName = a.value;
        }
        AXmlParser.Node app = null;
        for (AXmlParser.Node c : root.children) {
            if ("uses-permission".equals(c.name)) {
                String p = attr(c, NS_ANDROID, "name");
                if (p != null && !p.isEmpty()) info.permissions.add(p);
            } else if ("application".equals(c.name)) {
                app = c;
                info.applicationClass = attr(c, NS_ANDROID, "name");
            }
        }
        if (app != null) {
            for (AXmlParser.Node c : app.children) {
                if (!isLauncher(c)) continue;
                if (info.launcherActivity != null) continue;
                // activity-alias 指向目标 activity，取其 targetActivity
                info.launcherActivity = "activity-alias".equals(c.name)
                        ? attr(c, NS_ANDROID, "targetActivity")
                        : attr(c, NS_ANDROID, "name");
            }
        }
        return info;
    }

    private static String attr(AXmlParser.Node n, String ns, String name) {
        for (AXmlParser.Attr a : n.attrs) {
            if (name.equals(a.name) && (ns == null ? a.ns == null : ns.equals(a.ns))) return a.value;
        }
        return null;
    }

    /** 是否带 MAIN + LAUNCHER 的入口 activity（含 activity-alias）。 */
    private static boolean isLauncher(AXmlParser.Node node) {
        if (!"activity".equals(node.name) && !"activity-alias".equals(node.name)) return false;
        for (AXmlParser.Node c : node.children) {
            if (!"intent-filter".equals(c.name)) continue;
            boolean main = false, launcher = false;
            for (AXmlParser.Node ec : c.children) {
                String n = attr(ec, NS_ANDROID, "name");
                if ("action".equals(ec.name) && "android.intent.action.MAIN".equals(n)) main = true;
                else if ("category".equals(ec.name) && "android.intent.category.LAUNCHER".equals(n)) launcher = true;
            }
            if (main && launcher) return true;
        }
        return false;
    }

    /**
     * 类名解析：.Foo -> pkg.Foo；裸名 Foo -> pkg.Foo；全限定名原样。
     * 返回 null 表示未声明（如缺省 Application）。
     */
    public static String resolveClassName(String pkg, String name) {
        if (name == null || name.isEmpty()) return null;
        if (name.startsWith(".")) return pkg + name;
        if (name.indexOf('.') >= 0) return name;
        return pkg + "." + name;
    }

    /** 类名 -> smali 相对路径（含 .smali 后缀），如 com/a/b/MainActivity.smali。 */
    public static String classToSmaliPath(String className) {
        return className.replace('.', '/') + ".smali";
    }

    /**
     * 向 manifest 追加权限。perms 中已存在的会被跳过；无可新增时返回 null。
     * 返回新字节，可写回 APK 的 AndroidManifest.xml 条目。
     */
    public static byte[] addPermissions(byte[] xml, List<String> perms) throws Exception {
        List<String> add = new ArrayList<>();
        ManifestInfo info = readManifest(xml);
        for (String p : perms) {
            if (p != null && !p.isEmpty() && !info.permissions.contains(p)) add.add(p);
        }
        if (add.isEmpty()) return null;

        int[] c = findChunks(xml); // {poolStart, poolSize, rootStart, rootSize, xmlSize}
        int poolStart = c[0], poolSize = c[1], rootStart = c[2], rootSize = c[3], xmlSize = c[4];

        ByteBuffer bb = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(poolStart + 2);
        int poolHs = ResUtil.u16(bb);
        bb.getInt(); // 跳过 chunk size 字段（相对池起始 +4），stringCount 在 +8
        int stringCount = bb.getInt();
        int styleCount = bb.getInt();
        int flags = bb.getInt();
        int stringsStart = bb.getInt();
        int stylesStart = bb.getInt();
        boolean utf8 = (flags & UTF8_FLAG) != 0;
        int[] offsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) offsets[i] = bb.getInt();

        // 读出现有字符串 -> 池索引，新增字符串沿用索引
        Map<String, Integer> idxMap = new HashMap<>();
        for (int i = 0; i < stringCount; i++) {
            String s = (offsets[i] == 0xFFFFFFFF || stringsStart == 0)
                    ? "" : ResUtil.readString(bb, poolStart + stringsStart + offsets[i], utf8);
            if (!s.isEmpty()) idxMap.putIfAbsent(s, i);
        }
        List<String> toAdd = new ArrayList<>();
        int nameIdx = ensure(idxMap, "uses-permission", toAdd, stringCount);
        int androidNsIdx = ensure(idxMap, NS_ANDROID, toAdd, stringCount);
        int attrNameIdx = ensure(idxMap, "name", toAdd, stringCount);
        int[] permIdx = new int[add.size()];
        for (int i = 0; i < add.size(); i++) permIdx[i] = ensure(idxMap, add.get(i), toAdd, stringCount);

        // 编码新增字符串（与池同编码），offsets 相对新 stringsStart
        int n = toAdd.size();
        byte[][] enc = new byte[n][];
        int total = 0;
        for (int i = 0; i < n; i++) {
            enc[i] = encodeString(toAdd.get(i), utf8);
            total += enc[i].length;
        }
        int oldDataLen = poolSize - stringsStart;
        byte[] offBytes = new byte[4 * n];
        // 池 chunk size 必须是 4 的倍数（aapt2 / Android ResXMLTree 强制校验，
        // 否则报 "XML size ... is not on an integer boundary"），追加的字符串数据
        // 末尾按需补 0 对齐。
        int pad = (4 - ((poolSize + 4 * n + total) % 4)) % 4;
        byte[] stringData = new byte[total + pad];
        int running = 0;
        for (int i = 0; i < n; i++) {
            writeInt(offBytes, i * 4, oldDataLen + running);
            System.arraycopy(enc[i], 0, stringData, running, enc[i].length);
            running += enc[i].length;
        }

        // 每个权限一个 uses-permission 元素：START(56B, 1 属性) + END(24B)
        byte[] chunks = new byte[add.size() * 80];
        for (int i = 0; i < add.size(); i++) {
            int p = i * 80;
            putShort(chunks, p, (short) CHUNK_START_ELEMENT);
            putShort(chunks, p + 2, (short) 0x10);
            writeInt(chunks, p + 4, 0x38);
            writeInt(chunks, p + 8, 0);            // lineNumber
            writeInt(chunks, p + 12, -1);          // comment
            writeInt(chunks, p + 16, -1);          // ns（元素无命名空间）
            writeInt(chunks, p + 20, nameIdx);     // "uses-permission"
            putShort(chunks, p + 24, (short) 0x14); // attributeStart
            putShort(chunks, p + 26, (short) 0x14); // attributeSize
            putShort(chunks, p + 28, (short) 1);    // attributeCount
            putShort(chunks, p + 30, (short) 0);    // idIndex
            putShort(chunks, p + 32, (short) 0);    // classIndex
            putShort(chunks, p + 34, (short) 0);    // styleIndex
            writeInt(chunks, p + 36, androidNsIdx);   // 属性 ns = android
            writeInt(chunks, p + 40, attrNameIdx);    // 属性名 "name"
            writeInt(chunks, p + 44, permIdx[i]);     // raw 原始字符串
            putShort(chunks, p + 48, (short) 0x08); // valueSize
            putByte(chunks, p + 50, (byte) 0);      // res0
            putByte(chunks, p + 51, (byte) 0x03);   // dataType = TYPE_STRING
            writeInt(chunks, p + 52, permIdx[i]);     // data
            putShort(chunks, p + 56, (short) CHUNK_END_ELEMENT);
            putShort(chunks, p + 58, (short) 0x10);
            writeInt(chunks, p + 60, 0x18);
            writeInt(chunks, p + 64, 0);            // lineNumber
            writeInt(chunks, p + 68, -1);           // comment
            writeInt(chunks, p + 72, -1);           // ns
            writeInt(chunks, p + 76, nameIdx);      // "uses-permission"
        }

        // 三段插入：字符串偏移数组后插新偏移（在样式偏移之前，使新串索引 == stringCount + j）；
        // 池末尾追加字符串数据；根元素 chunk 后插元素 chunk
        int insertOffsets = poolStart + poolHs + stringCount * 4;
        int poolEnd = poolStart + poolSize;
        int rootEnd = rootStart + rootSize;
        byte[] out = concat(
                Arrays.copyOfRange(xml, 0, insertOffsets),
                offBytes,
                Arrays.copyOfRange(xml, insertOffsets, poolEnd),
                stringData,
                Arrays.copyOfRange(xml, poolEnd, rootEnd),
                chunks,
                Arrays.copyOfRange(xml, rootEnd, xmlSize));
        writeInt(out, 4, xmlSize + 4 * n + total + pad + chunks.length);    // XML 头总长度
        writeInt(out, poolStart + 4, poolSize + 4 * n + total + pad);       // 池 chunk size
        writeInt(out, poolStart + 8, stringCount + n);                      // stringCount
        writeInt(out, poolStart + 20, stringsStart + 4 * n);                // stringsStart
        if (styleCount > 0) writeInt(out, poolStart + 24, stylesStart + 4 * n); // stylesStart
        return out;
    }

    /** 在索引表里找字符串，没有则登记为待新增并分配新索引。
     *  新索引必须从 stringCount 起（新偏移写在偏移数组尾部），不能用 idxMap.size()：
     *  池中空槽（0xFFFFFFFF）和重复字符串不会被 idxMap 收录，两者并不相等，
     *  用 idxMap.size() 会让新索引撞上已有的池字符串。 */
    private static int ensure(Map<String, Integer> idxMap, String s, List<String> toAdd, int stringCount) {
        Integer e = idxMap.get(s);
        if (e != null) return e;
        int idx = stringCount + toAdd.size();
        idxMap.put(s, idx);
        toAdd.add(s);
        return idx;
    }

    /** 扫描 chunk 找字符串池与根起始元素。返回 {poolStart, poolSize, rootStart, rootSize, xmlSize}。 */
    private static int[] findChunks(byte[] xml) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN);
        int type = ResUtil.u16(b);
        b.position(4);
        int xmlSize = b.getInt();
        if (type != 0x0003) throw new Exception("不是有效的二进制 XML 文件");
        if (xmlSize > xml.length) xmlSize = xml.length;
        int poolStart = -1, poolSize = -1, rootStart = -1, rootSize = -1;
        while (b.position() + 8 <= xmlSize) {
            int start = b.position();
            int t = ResUtil.u16(b);
            ResUtil.u16(b); // headerSize
            int sz = b.getInt();
            if (sz < 8) break;
            if (t == CHUNK_STRING_POOL && poolStart < 0) {
                poolStart = start;
                poolSize = sz;
            } else if (t == CHUNK_START_ELEMENT && rootStart < 0) {
                rootStart = start;
                rootSize = sz;
            }
            b.position(start + sz);
        }
        if (poolStart < 0 || rootStart < 0) throw new Exception("未找到字符串池或根元素");
        return new int[]{poolStart, poolSize, rootStart, rootSize, xmlSize};
    }

    /** 编码一条池字符串（与池存储格式一致，长度前缀 + 数据 + 结尾空字符）。
     *  空字符必须写：aapt2/ResStringPool 校验每个字符串后的 null 终结符，
     *  缺了会报 "Bad string block: string #N is not null-terminated" 并把
     *  该字符串视为空值。 */
    private static byte[] encodeString(String s, boolean utf8) {
        if (utf8) {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            int len = utf8LenSize(s.length()) + utf8LenSize(data.length);
            ByteBuffer b = ByteBuffer.allocate(len + data.length + 1);
            writeUtf8Len(b, s.length());
            writeUtf8Len(b, data.length);
            b.put(data);
            b.put((byte) 0);
            return b.array();
        }
        int units = s.length();
        ByteBuffer b;
        if (units < 0x8000) {
            b = ByteBuffer.allocate(2 + units * 2 + 2).order(ByteOrder.LITTLE_ENDIAN);
            b.putShort((short) units);
        } else {
            b = ByteBuffer.allocate(4 + units * 2 + 2).order(ByteOrder.LITTLE_ENDIAN);
            b.putShort((short) (0x8000 | (units & 0x7FFF)));
            b.putShort((short) (units >> 15));
        }
        b.put(s.getBytes(StandardCharsets.UTF_16LE));
        b.putShort((short) 0);
        return b.array();
    }

    private static int utf8LenSize(int len) {
        return len < 0x80 ? 1 : 2;
    }

    private static void writeUtf8Len(ByteBuffer b, int len) {
        if (len < 0x80) b.put((byte) len);
        else {
            b.put((byte) (0x80 | (len >> 8)));
            b.put((byte) (len & 0xFF));
        }
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private static void putShort(byte[] a, int o, short v) {
        a[o] = (byte) (v & 0xFF);
        a[o + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void putByte(byte[] a, int o, byte v) {
        a[o] = v;
    }

    private static void writeInt(byte[] a, int o, int v) {
        a[o] = (byte) (v & 0xFF);
        a[o + 1] = (byte) ((v >> 8) & 0xFF);
        a[o + 2] = (byte) ((v >> 16) & 0xFF);
        a[o + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
