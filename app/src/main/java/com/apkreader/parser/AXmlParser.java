package com.apkreader.parser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析 APK 中的二进制 AndroidManifest.xml（AXML / Binary XML），
 * 输出缩进良好、可读的 XML 文本。
 *
 * 二进制 XML 由若干 chunk 组成：
 *   XML 头(0x0003) -> 字符串池(0x0001) -> 资源映射表(0x0180)
 *   -> 命名空间/元素/文本节点...
 */
public class AXmlParser {

    private static final int CHUNK_XML = 0x0003;
    private static final int CHUNK_START_ELEMENT = 0x0102;
    private static final int CHUNK_END_ELEMENT = 0x0103;
    private static final int CHUNK_CDATA = 0x0104;

    private ByteBuffer buf;
    private String[] strings;
    private final Map<Integer, String> resNames;
    /** 缩进字符串缓存（每层深度一份），避免每行重复拼接 4 空格。解析器每次 parse 新建，缓存随实例生命周期。 */
    private String[] indentCache = new String[16];

    /** @param resNames 可选的资源 id -> "type/name" 映射，用于把 @0x7f030001 还原成 @string/app_name */
    public AXmlParser(Map<Integer, String> resNames) {
        this.resNames = resNames;
    }

    public String parse(byte[] xml) throws Exception {
        Node root = parseTree(xml);
        // 收集所有用到的命名空间 URI，生成前缀
        Set<String> usedUris = new LinkedHashSet<>();
        collectNs(root, usedUris);
        Map<String, String> nsPrefix = new LinkedHashMap<>();
        int auto = 1;
        for (String uri : usedUris) {
            String p = nsPrefixOf(uri);
            if (p == null) p = "ns" + (auto++);
            nsPrefix.put(uri, p);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        render(root, 0, sb, nsPrefix);
        return sb.toString();
    }

    /**
     * 解析二进制 XML 并返回根节点树，不生成文本。
     * 编辑场景（AXmlEditor）需要结构而非文本：先调用本方法拿到树，
     * 再对原始字节做定点修改。调用后 strings 字段保留字符串池内容供读取。
     */
    public Node parseTree(byte[] xml) throws Exception {
        if (xml == null || xml.length < 8) throw new Exception("XML 数据为空");
        buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN);
        int type = ResUtil.u16(buf);
        ResUtil.u16(buf); // headerSize
        int size = buf.getInt();
        if (type != CHUNK_XML) {
            throw new Exception("不是有效的二进制 XML 文件 (chunk=0x" + Integer.toHexString(type) + ")");
        }
        if (size > xml.length) size = xml.length;

        strings = new String[0];
        Node root = null;
        // 二进制 XML 是扁平 chunk 序列：START ELEMENT 的 size 只覆盖自身，
        // 子元素作为后续独立 chunk 出现，因此用栈来构建树。
        Deque<Node> stack = new ArrayDeque<>();
        while (buf.position() < size) {
            int start = buf.position();
            int t = ResUtil.u16(buf);
            int hs = ResUtil.u16(buf);
            int sz = buf.getInt();
            if (sz < 8) break;
            switch (t) {
                case 0x0001: // 字符串池
                    strings = ResUtil.readStringPool(buf, start, sz);
                    break;
                case CHUNK_START_ELEMENT: {
                    Node n = readElementHeader();
                    if (stack.isEmpty()) root = n;
                    else stack.peek().children.add(n);
                    stack.push(n);
                    break;
                }
                case CHUNK_END_ELEMENT:
                    if (!stack.isEmpty()) stack.pop();
                    buf.position(start + sz);
                    break;
                case CHUNK_CDATA: {
                    buf.getInt(); // lineNumber
                    buf.getInt(); // comment
                    int data = buf.getInt();
                    ResUtil.u16(buf); // value size
                    ResUtil.u8(buf);  // res0
                    ResUtil.u8(buf);  // dataType
                    buf.getInt();     // data
                    if (!stack.isEmpty()) stack.peek().text = str(data);
                    buf.position(start + sz);
                    break;
                }
                default:
                    buf.position(start + sz);
            }
        }
        if (root == null) throw new Exception("未找到 XML 根节点");
        return root;
    }

    /** 读取一个 START ELEMENT chunk 头与属性（不含子节点）。 */
    private Node readElementHeader() throws Exception {
        buf.getInt(); // lineNumber
        buf.getInt(); // comment
        int ns = buf.getInt();
        int name = buf.getInt();
        ResUtil.u16(buf); // attributeStart
        ResUtil.u16(buf); // attributeSize
        int attrCount = ResUtil.u16(buf);
        ResUtil.u16(buf); // idIndex
        ResUtil.u16(buf); // classIndex
        ResUtil.u16(buf); // styleIndex

        Node node = new Node();
        node.ns = str(ns);
        node.name = str(name);
        for (int i = 0; i < attrCount; i++) {
            node.attrs.add(readAttr());
        }
        return node;
    }

    private Attr readAttr() {
        Attr a = new Attr();
        int ns = buf.getInt();
        int name = buf.getInt();
        int raw = buf.getInt();
        ResUtil.u16(buf); // value size
        ResUtil.u8(buf);  // res0
        byte dt = buf.get();
        long data = buf.getInt() & 0xFFFFFFFFL;
        a.ns = str(ns);
        a.name = str(name);
        a.type = dt;
        a.data = data;
        a.value = ResUtil.formatValue(dt, data, str(raw), strings, resNames);
        return a;
    }

    private String str(int idx) {
        if (idx < 0 || idx >= strings.length) return null;
        return strings[idx];
    }

    private void collectNs(Node n, Set<String> out) {
        if (n.ns != null) out.add(n.ns);
        for (Attr a : n.attrs) {
            if (a.ns != null) out.add(a.ns);
        }
        for (Node c : n.children) collectNs(c, out);
    }

    private void render(Node n, int depth, StringBuilder sb, Map<String, String> nsPrefix) {
        indent(sb, depth);
        sb.append('<');
        String ePrefix = prefix(n.ns, nsPrefix);
        if (ePrefix != null) sb.append(ePrefix).append(':');
        sb.append(n.name);
        if (depth == 0) {
            for (Map.Entry<String, String> e : nsPrefix.entrySet()) {
                sb.append('\n').append(indentStr(depth + 1));
                sb.append("xmlns:").append(e.getValue()).append("=\"").append(e.getKey()).append('"');
            }
        }
        for (Attr a : n.attrs) {
            sb.append('\n').append(indentStr(depth + 1));
            String p = prefix(a.ns, nsPrefix);
            if (p != null) sb.append(p).append(':');
            sb.append(a.name).append("=\"").append(ResUtil.escapeXml(a.value)).append('"');
        }
        if (n.children.isEmpty() && n.text == null) {
            sb.append("/>\n");
            return;
        }
        sb.append(">\n");
        if (n.text != null) {
            indent(sb, depth + 1);
            sb.append(ResUtil.escapeXml(n.text)).append('\n');
        }
        for (Node c : n.children) render(c, depth + 1, sb, nsPrefix);
        indent(sb, depth);
        sb.append("</");
        if (ePrefix != null) sb.append(ePrefix).append(':');
        sb.append(n.name).append(">\n");
    }

    private static String prefix(String uri, Map<String, String> nsPrefix) {
        if (uri == null) return null;
        return nsPrefix.get(uri);
    }

    private static String nsPrefixOf(String uri) {
        switch (uri) {
            case "http://schemas.android.com/apk/res/android": return "android";
            case "http://schemas.android.com/apk/res-auto": return "app";
            case "http://schemas.android.com/tools": return "tools";
            case "http://schemas.android.com/apk/res": return "aapt";
            default: return null;
        }
    }

    private void indent(StringBuilder sb, int depth) {
        sb.append(indentStr(depth));
    }

    private String indentStr(int depth) {
        if (depth >= indentCache.length) {
            int n = indentCache.length;
            while (n <= depth) n <<= 1;
            String[] grown = new String[n];
            System.arraycopy(indentCache, 0, grown, 0, indentCache.length);
            indentCache = grown;
        }
        String s = indentCache[depth];
        if (s == null) {
            s = buildIndent(depth);
            indentCache[depth] = s;
        }
        return s;
    }

    private static String buildIndent(int depth) {
        StringBuilder sb = new StringBuilder(depth * 4);
        for (int i = 0; i < depth; i++) sb.append("    ");
        return sb.toString();
    }

    static class Node {
        String ns;
        String name;
        String text;
        List<Attr> attrs = new ArrayList<>();
        List<Node> children = new ArrayList<>();
    }

    static class Attr {
        String ns;
        String name;
        byte type;
        long data;
        String value;
    }
}
