package com.apkreader.parser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析 APK 中的 resources.arsc 资源表，输出资源ID映射表：
 * 每个资源一行 "资源ID  类型/名称 = 值"（如 0x7f020000 string/app_name = "xx"），
 * 复杂条目附带其属性映射项；string-af 这类带配置后缀的类型不展示。
 *
 * 资源表结构：
 *   ResTable 头(0x0002) -> 全局字符串池(0x0001) -> 资源包(0x0200)...
 *   包内：类型字符串池 / 键字符串池 / typeSpec(0x0202) / type(0x0201)
 */
public class ArscParser {

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_TABLE = 0x0002;
    private static final int CHUNK_PACKAGE = 0x0200;
    private static final int CHUNK_TYPE = 0x0201;
    private static final int CHUNK_TYPE_SPEC = 0x0202;

    private static final int TYPE_STRING = 0x03;
    private static final int FLAG_COMPLEX = 0x0001;

    /** 解析结果：文本 + 资源 id 到 type/name 的映射（供 manifest 引用还原）。 */
    public static class Result {
        public String text;
        public Map<Integer, String> resNames;
    }

    public String parse(byte[] data) throws Exception {
        return parseResult(data, null).text;
    }

    public Result parseResult(byte[] data) throws Exception {
        return parseResult(data, null);
    }

    /**
     * 按输出设置过滤类型块。filter 为 null 时不加过滤，行为与旧版完全一致。
     * 过滤只作用于输出文本，resNames 映射表（供 manifest 引用还原）始终保留全部条目。
     */
    public Result parseResult(byte[] data, ConfigFilter filter) throws Exception {
        Result result = new Result();
        if (data == null || data.length < 12) throw new Exception("arsc 数据为空");
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int type = ResUtil.u16(buf);
        ResUtil.u16(buf); // headerSize
        int size = buf.getInt();
        if (type != CHUNK_TABLE) {
            throw new Exception("不是有效的资源表 (chunk=0x" + Integer.toHexString(type) + ")");
        }
        if (size > data.length) size = data.length;
        int packageCount = buf.getInt();

        StringBuilder out = new StringBuilder(Math.min(Math.max(data.length * 2, 4096), 16 * 1024 * 1024));
        out.append("============================================\n");
        out.append("   resources.arsc  资源ID映射表\n");
        out.append("============================================\n");
        out.append("文件大小 : ").append(data.length).append(" 字节\n");
        out.append("资源包数 : ").append(packageCount).append("\n");

        String[] globalStrings = new String[0];
        List<Pkg> pkgs = new ArrayList<>();
        while (buf.position() < size) {
            int start = buf.position();
            int t = ResUtil.u16(buf);
            int hs = ResUtil.u16(buf);
            int sz = buf.getInt();
            if (sz < 8) break;
            if (start + sz > data.length) sz = data.length - start;
            switch (t) {
                case CHUNK_STRING_POOL:
                    globalStrings = ResUtil.readStringPool(buf, start, sz);
                    break;
                case CHUNK_PACKAGE:
                    pkgs.add(readPackage(buf, start, hs, sz));
                    break;
                default:
                    buf.position(start + sz);
            }
        }

        // 所有包解析完成后，把每个条目的 keyIdx 解析成键字符串
        for (Pkg p : pkgs) {
            for (Type ty : p.types) {
                for (Entry e : ty.entries) {
                    e.key = (p.keyStrings != null && e.keyIdx >= 0 && e.keyIdx < p.keyStrings.length)
                            ? p.keyStrings[e.keyIdx] : "0x" + Integer.toHexString(e.keyIdx);
                }
            }
        }

        // 收集所有条目的资源 id -> type/name，供 @0x7f030001 引用还原
        Map<Integer, String> resNames = new LinkedHashMap<>();
        for (Pkg p : pkgs) {
            for (Type ty : p.types) {
                String typeName = typeName(p, ty);
                for (Entry e : ty.entries) {
                    int id = (int) ((p.id << 24) | (ty.id << 16) | (e.index & 0xFFFF));
                    resNames.put(id, typeName + "/" + e.key);
                }
            }
        }
        result.resNames = resNames;

        for (Pkg p : pkgs) {
            out.append("\n资源包 : ").append(p.name)
                    .append("  (id=0x").append(String.format("%02x", p.id)).append(")\n");
            // “仅显示最高版本”：先按 (类型, 语言, 密度) 分组，标出同组中 sdk 较低的块
            Set<Type> maxSkip = (filter != null && filter.versionMode == ConfigFilter.VERSION_MAX)
                    ? filter.maxVersionSkip(p.types) : null;
            for (Type ty : p.types) {
                // 只看基础类型：string-af / string-am 这类带配置后缀的类型不展示
                if (isQualifiedType(p, ty)) continue;
                if (maxSkip != null && maxSkip.contains(ty)) continue;
                if (filter != null && !filter.keep(ty)) continue;
                // 配置非默认时标注：多语言 / 多分辨率下同一 ID 会重复出现，标注便于区分
                String configTag = "default".equals(ty.config) ? "" : "[" + ty.config + "] ";
                for (Entry e : ty.entries) {
                    int resId = (int) ((p.id << 24) | (ty.id << 16) | (e.index & 0xFFFF));
                    String tn = typeName(p, ty);
                    out.append("  ").append(configTag).append("0x").append(ResUtil.hex8(resId))
                            .append("  ").append(tn).append('/').append(e.key);
                    // id 资源没有真实值（aapt 用布尔 false 占位），只列名字
                    if ("id".equals(tn)) {
                        out.append("\n");
                        continue;
                    }
                    out.append(" = ");
                    // 复杂条目以 maps != null 判定（样式类条目可能 0 项，只继承 parent）
                    if (e.maps != null) {
                        out.append("{ 复杂条目，").append(e.maps.size()).append(" 项");
                        if (e.parent != 0) {
                            out.append("，parent=").append(ResUtil.formatValue(
                                    ResUtil.TYPE_REFERENCE, e.parent, null, null, resNames));
                        }
                        out.append(" }\n");
                        for (MapItem m : e.maps) {
                            String mn = resNames.get(m.nameId);
                            // framework 资源（0x01...）不在本 APK 的映射表里，显示原始 ID 而非 "?"
                            out.append("        [0x").append(ResUtil.hex8(m.nameId)).append("] ")
                                    .append(mn != null ? mn : "@0x" + ResUtil.hex8(m.nameId)).append(" = ");
                            appendValue(out, m.value, globalStrings, resNames);
                            out.append("\n");
                        }
                    } else if (e.value != null) {
                        appendValue(out, e.value, globalStrings, resNames);
                        out.append("\n");
                    } else {
                        out.append("(无值)\n");
                    }
                }
            }
        }
        result.text = out.toString();
        return result;
    }

    private void appendValue(StringBuilder out, Value v, String[] globalStrings, Map<Integer, String> resNames) {
        String s = ResUtil.formatValue(v.type, v.data, null, globalStrings, resNames);
        if ((v.type & 0xFF) == TYPE_STRING) {
            out.append('"').append(s).append('"');
        } else {
            out.append(s);
        }
    }

    private static String typeName(Pkg p, Type ty) {
        if (p.typeStrings != null && ty.id > 0 && ty.id - 1 < p.typeStrings.length) {
            return baseTypeName(p.typeStrings[ty.id - 1]);
        }
        return "type" + ty.id;
    }

    /** 去掉类型名的配置后缀：string-af / string-am / layout-xhdpi -> string / layout */
    private static String baseTypeName(String raw) {
        int i = raw.indexOf('-');
        return i >= 0 ? raw.substring(0, i) : raw;
    }

    /** 是否带配置后缀的类型（string-af 这类），这类类型不单独展示 */
    private static boolean isQualifiedType(Pkg p, Type ty) {
        if (p.typeStrings == null || ty.id <= 0 || ty.id - 1 >= p.typeStrings.length) return false;
        return p.typeStrings[ty.id - 1].indexOf('-') >= 0;
    }

    private Pkg readPackage(ByteBuffer buf, int start, int headerSize, int size) {
        Pkg p = new Pkg();
        p.id = buf.getInt();
        byte[] nm = new byte[256];
        buf.get(nm);
        p.name = decodeUtf16(nm);
        p.typeStringsOff = buf.getInt();
        buf.getInt(); // lastPublicType
        p.keyStringsOff = buf.getInt();
        buf.getInt(); // lastPublicKey

        // 内部块从 start + headerSize 开始。Android 12+ 的 ResTable_package 多了 4 字节
        // typeIdOffset 字段，固定 284 字节的头会错位，必须按 chunk 声明的 headerSize 定位。
        int end = start + size;
        buf.position(start + headerSize);
        while (buf.position() < end) {
            int cs = buf.position();
            int t = ResUtil.u16(buf);
            int hs = ResUtil.u16(buf);
            int sz = buf.getInt();
            if (sz < 8) break;
            // 块大小超出包尾时截断而不是 break，避免一个坏块丢掉后面所有类型
            if (cs + sz > end) sz = end - cs;
            if (sz < 8) break;
            switch (t) {
                case CHUNK_STRING_POOL: {
                    String[] pool = ResUtil.readStringPool(buf, cs, sz);
                    int off = cs - start;
                    if (off == p.typeStringsOff) p.typeStrings = pool;
                    else if (off == p.keyStringsOff) p.keyStrings = pool;
                    else p.otherPools.add(pool);
                    break;
                }
                case CHUNK_TYPE_SPEC:
                    buf.get(); // id
                    buf.get(); // res0
                    buf.getShort(); // res1
                    int entryCount = buf.getInt();
                    buf.position(buf.position() + entryCount * 4); // flags
                    break;
                case CHUNK_TYPE:
                    p.types.add(readType(buf, cs, hs, sz));
                    buf.position(cs + sz); // readType 内部逐条恢复位置，这里强制跳到块尾
                    break;
                default:
                    buf.position(cs + sz);
            }
        }
        return p;
    }

    private Type readType(ByteBuffer buf, int start, int headerSize, int size) {
        Type ty = new Type();
        ty.id = ResUtil.u8(buf);
        ResUtil.u8(buf); // res0
        buf.getShort(); // res1
        int entryCount = buf.getInt();
        int entriesStart = buf.getInt();
        int configSize = headerSize - 20;
        ResUtil.Config c = ResUtil.parseConfig(buf, configSize);
        ty.config = c.display();
        ty.lang = c.lang;
        ty.density = c.density;
        ty.sw = c.sw;
        ty.sdk = c.sdk;

        buf.position(start + headerSize);
        long[] offsets = new long[entryCount];
        for (int i = 0; i < entryCount; i++) offsets[i] = buf.getInt() & 0xFFFFFFFFL;

        int entriesBase = start + entriesStart;
        for (int i = 0; i < entryCount; i++) {
            if (offsets[i] == 0xFFFFFFFFL) continue;
            int pos = entriesBase + (int) offsets[i];
            if (pos < 0 || pos + 8 > buf.limit()) continue;
            int save = buf.position();
            try {
                buf.position(pos);
                Entry e = new Entry();
                e.index = i;
                int eSize = ResUtil.u16(buf);
                int eFlags = ResUtil.u16(buf);
                e.keyIdx = buf.getInt();
                if ((eFlags & FLAG_COMPLEX) != 0) {
                    long parent = buf.getInt() & 0xFFFFFFFFL;
                    int count = buf.getInt();
                    e.parent = parent;
                    e.maps = new ArrayList<>();
                    for (int k = 0; k < count && buf.position() + 12 <= buf.limit(); k++) {
                        MapItem m = new MapItem();
                        m.nameId = buf.getInt();
                        m.value = readValue(buf);
                        e.maps.add(m);
                    }
                } else {
                    e.value = readValue(buf);
                }
                ty.entries.add(e);
            } catch (Exception ignored) {
            } finally {
                buf.position(save);
            }
        }
        return ty;
    }

    private Value readValue(ByteBuffer buf) {
        Value v = new Value();
        ResUtil.u16(buf); // size
        ResUtil.u8(buf);  // res0
        v.type = buf.get();
        v.data = buf.getInt() & 0xFFFFFFFFL;
        return v;
    }

    private static String decodeUtf16(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length / 2);
        for (int i = 0; i + 1 < b.length; i += 2) {
            char c = (char) ((b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8));
            if (c == 0) break;
            sb.append(c);
        }
        return sb.toString();
    }

    static class Pkg {
        int id;
        String name;
        int typeStringsOff;
        int keyStringsOff;
        String[] typeStrings;
        String[] keyStrings;
        List<String[]> otherPools = new ArrayList<>();
        List<Type> types = new ArrayList<>();
    }

    static class Type {
        int id;
        String config;
        String lang;    // 基础语言码，如 "zh"；无语言限定为 ""
        String density; // 密度标签，如 "xxhdpi"；nodpi/无限定为 ""
        int sw;         // 最小宽度 dp
        int sdk;        // sdkVersion
        List<Entry> entries = new ArrayList<>();
    }

    /**
     * 输出过滤条件：按 语言 / 分辨率 / 版本 选择要展示的类型块。
     * 每个 type chunk 对应一个 ResTable_config，过滤在 chunk 级生效。
     */
    public static class ConfigFilter {
        public static final int LANG_ALL = 0, LANG_DEFAULT = 1, LANG_CUSTOM = 2;
        public static final int DENSITY_ALL = 0, DENSITY_DEFAULT = 1, DENSITY_CUSTOM = 2;
        public static final int VERSION_ALL = 0, VERSION_MAX = 1;

        public int langMode = LANG_ALL;
        public Set<String> langSel = new HashSet<>();
        public int densityMode = DENSITY_ALL;
        public Set<String> densitySel = new HashSet<>();
        public int versionMode = VERSION_ALL;

        /** 语言/分辨率过滤判断（版本过滤由 maxVersionSkip 预扫描处理）。 */
        public boolean keep(Type ty) {
            if (langMode == LANG_DEFAULT && !ty.lang.isEmpty()) return false;
            if (langMode == LANG_CUSTOM && !ty.lang.isEmpty() && !langSel.contains(ty.lang)) return false;
            if (densityMode == DENSITY_DEFAULT && (!ty.density.isEmpty() || ty.sw != 0)) return false;
            if (densityMode == DENSITY_CUSTOM) {
                boolean hasDensity = !ty.density.isEmpty() || ty.sw != 0;
                if (hasDensity) {
                    boolean hit = (ty.sw != 0 && densitySel.contains("sw" + ty.sw + "dp"))
                            || densitySel.contains(ty.density);
                    if (!hit) return false;
                }
            }
            return true;
        }

        /**
         * “仅显示最高版本”预扫描：先按 keep() 过滤，再把剩余块按 (类型, 语言, 密度, sw)
         * 分组，每组只保留 sdk 最高的块，返回其余应跳过的块（按对象引用比较）。
         */
        public Set<Type> maxVersionSkip(List<Type> types) {
            Map<String, Type> best = new HashMap<>();
            for (Type ty : types) {
                if (!keep(ty)) continue;
                String key = ty.id + "|" + ty.lang + "|" + ty.density + "|" + ty.sw;
                Type cur = best.get(key);
                if (cur == null || ty.sdk > cur.sdk) best.put(key, ty);
            }
            Set<Type> skip = new HashSet<>();
            for (Type ty : types) {
                if (!keep(ty)) continue;
                String key = ty.id + "|" + ty.lang + "|" + ty.density + "|" + ty.sw;
                if (best.get(key) != ty) skip.add(ty);
            }
            return skip;
        }
    }

    static class Entry {
        int index;
        int keyIdx;
        long parent;
        Value value;
        List<MapItem> maps;
        String key;
    }

    static class MapItem {
        int nameId;
        Value value;
    }

    static class Value {
        byte type;
        long data;
    }
}
