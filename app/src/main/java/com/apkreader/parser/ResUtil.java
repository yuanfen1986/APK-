package com.apkreader.parser;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 二进制资源解析公共工具：
 * - 字符串池（ResStringPool）解码，兼容 UTF-8 / UTF-16 两种存储
 * - Res_value 类型值格式化（字符串/引用/颜色/尺寸/布尔等）
 * - ResTable_config 资源配置描述解码
 */
public final class ResUtil {

    public static final byte TYPE_NULL = 0x00;
    public static final byte TYPE_REFERENCE = 0x01;
    public static final byte TYPE_ATTRIBUTE = 0x02;
    public static final byte TYPE_STRING = 0x03;
    public static final byte TYPE_FLOAT = 0x04;
    public static final byte TYPE_DIMENSION = 0x05;
    public static final byte TYPE_FRACTION = 0x06;
    public static final byte TYPE_DYNAMIC_REFERENCE = 0x07;
    public static final byte TYPE_DYNAMIC_ATTRIBUTE = 0x08;
    public static final byte TYPE_INT_DEC = 0x10;
    public static final byte TYPE_INT_HEX = 0x11;
    public static final byte TYPE_INT_BOOLEAN = 0x12;
    public static final byte TYPE_INT_COLOR_ARGB8 = 0x1c;
    public static final byte TYPE_INT_COLOR_RGB8 = 0x1d;
    public static final byte TYPE_INT_COLOR_ARGB4 = 0x1e;
    public static final byte TYPE_INT_COLOR_RGB4 = 0x1f;

    private static final int UTF8_FLAG = 0x00000100;
    private static final float[] RADIX_MULTS = {0.00390625f, 3.051758e-5f, 1.192093e-7f, 4.656613e-10f};
    private static final String[] DIM_UNITS = {"px", "dp", "sp", "pt", "in", "mm"};

    private ResUtil() {
    }

    public static int u16(ByteBuffer b) {
        return b.getShort() & 0xFFFF;
    }

    public static int u8(ByteBuffer b) {
        return b.get() & 0xFF;
    }

    /**
     * 读取字符串池。调用前 buf 必须已定位在块头（type/headerSize/size 8 字节）之后，
     * 即 stringCount 处。成功后把位置跳到本块末尾（chunkStart + chunkSize）。
     */
    public static String[] readStringPool(ByteBuffer buf, int chunkStart, int chunkSize) {
        int save = buf.position();
        try {
            int stringCount = buf.getInt();
            int styleCount = buf.getInt();
            int flags = buf.getInt();
            int stringsStart = buf.getInt();
            int stylesStart = buf.getInt();
            boolean utf8 = (flags & UTF8_FLAG) != 0;
            int[] offsets = new int[stringCount];
            for (int i = 0; i < stringCount; i++) offsets[i] = buf.getInt();

            String[] out = new String[stringCount];
            for (int i = 0; i < stringCount; i++) {
                if (offsets[i] == 0xFFFFFFFF || stringsStart == 0) {
                    out[i] = "";
                    continue;
                }
                out[i] = readString(buf, chunkStart + stringsStart + offsets[i], utf8);
            }
            buf.position(Math.min(chunkStart + chunkSize, buf.limit()));
            return out;
        } catch (Exception e) {
            buf.position(save);
            return new String[0];
        }
    }

    /** 从绝对偏移读取一条字符串（不改变调用后的 buf 位置）。 */
    public static String readString(ByteBuffer buf, int abs, boolean utf8) {
        if (abs < 0 || abs >= buf.limit()) return "";
        int save = buf.position();
        try {
            buf.position(abs);
            if (utf8) {
                decodeUtf8Len(buf); // 字符数（UTF-16 长度），忽略
                int byteLen = decodeUtf8Len(buf);
                byteLen = Math.min(byteLen, buf.remaining());
                byte[] b = new byte[byteLen];
                buf.get(b);
                return new String(b, StandardCharsets.UTF_8);
            }
            int len = u16(buf);
            if ((len & 0x8000) != 0) { // 扩展长度
                int hi = u16(buf);
                len = (len & 0x7FFF) | (hi << 15);
            }
            len = Math.min(len, buf.remaining() / 2);
            byte[] b = new byte[len * 2];
            buf.get(b);
            return new String(b, StandardCharsets.UTF_16LE);
        } catch (Exception e) {
            return "";
        } finally {
            buf.position(save);
        }
    }

    private static int decodeUtf8Len(ByteBuffer buf) {
        int b = u8(buf);
        if ((b & 0x80) != 0) {
            int b2 = u8(buf);
            return ((b & 0x7F) << 8) | b2;
        }
        return b;
    }

    /**
     * 格式化一个 Res_value。raw 为源 XML 中直接写下的原始字符串（优先返回），
     * strPool 用于解析 TYPE_STRING，resNames 用于把资源 id 转成 type/name 可读形式。
     */
    public static String formatValue(byte type, long data, String raw,
                                     String[] strPool, Map<Integer, String> resNames) {
        if (raw != null) return raw;
        int t = type & 0xFF;
        switch (t) {
            case TYPE_NULL:
                return "null";
            case TYPE_REFERENCE:
            case TYPE_DYNAMIC_REFERENCE: {
                String n = resNames != null ? resNames.get((int) data) : null;
                return n != null ? "@" + n : "@0x" + hex8((int) data);
            }
            case TYPE_ATTRIBUTE:
            case TYPE_DYNAMIC_ATTRIBUTE: {
                String n = resNames != null ? resNames.get((int) data) : null;
                return n != null ? "?" + n : "?0x" + hex8((int) data);
            }
            case TYPE_STRING: {
                if (strPool == null) return "";
                int idx = (int) data;
                if (idx < 0 || idx >= strPool.length) return "";
                return strPool[idx];
            }
            case TYPE_FLOAT:
                return Float.toString(Float.intBitsToFloat((int) data));
            case TYPE_DIMENSION:
                return complexToDimension((int) data);
            case TYPE_FRACTION:
                return complexToFraction((int) data);
            case TYPE_INT_DEC:
                return Integer.toString((int) data);
            case TYPE_INT_HEX:
                return "0x" + Integer.toHexString((int) data);
            case TYPE_INT_BOOLEAN:
                return data != 0 ? "true" : "false";
            case TYPE_INT_COLOR_ARGB8:
            case TYPE_INT_COLOR_ARGB4:
                return "#" + hex8((int) data);
            case TYPE_INT_COLOR_RGB8:
            case TYPE_INT_COLOR_RGB4:
                return "#" + hex6((int) data & 0xFFFFFF);
            default:
                return "0x" + Integer.toHexString((int) data);
        }
    }

    /** 解码 dimension 复杂值，如 48dp、1.5sp。 */
    public static String complexToDimension(int complex) {
        int unit = (complex >> 8) & 0x0F;
        if (unit < 0 || unit >= DIM_UNITS.length) return "0x" + Integer.toHexString(complex);
        float value = (float) (complex & 0xFFFFFF00) * RADIX_MULTS[(complex >> 4) & 3];
        return trimFloat(value) + DIM_UNITS[unit];
    }

    /** 解码 fraction 复杂值，如 50%、25%p。 */
    public static String complexToFraction(int complex) {
        int unit = (complex >> 8) & 0x0F;
        float value = (float) (complex & 0xFFFFFF00) * RADIX_MULTS[(complex >> 4) & 3];
        return trimFloat(value) + (unit == 1 ? "%p" : "%");
    }

    private static String trimFloat(float f) {
        if (f == (long) f) return Long.toString((long) f);
        return Float.toString(f);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 手动拼 8 位十六进制（String.format 开销大，热路径一律用这个）。 */
    static String hex8(int v) {
        char[] c = new char[8];
        for (int i = 7; i >= 0; i--) {
            c[i] = HEX[v & 0xF];
            v >>>= 4;
        }
        return new String(c);
    }

    static String hex6(int v) {
        char[] c = new char[6];
        for (int i = 5; i >= 0; i--) {
            c[i] = HEX[v & 0xF];
            v >>>= 4;
        }
        return new String(c);
    }

    static String hex2(int v) {
        char[] c = new char[2];
        c[0] = HEX[(v >> 4) & 0xF];
        c[1] = HEX[v & 0xF];
        return new String(c);
    }

    /** XML 文本转义：无特殊字符时直接返回原串，避免为每个属性分配新字符串。 */
    public static String escapeXml(String s) {
        if (s == null) return "";
        int first = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') {
                first = i;
                break;
            }
        }
        if (first < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int start = 0;
        for (int i = first; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep;
            switch (c) {
                case '&': rep = "&amp;"; break;
                case '<': rep = "&lt;"; break;
                case '>': rep = "&gt;"; break;
                case '"': rep = "&quot;"; break;
                case '\'': rep = "&apos;"; break;
                default: continue;
            }
            sb.append(s, start, i);
            sb.append(rep);
            start = i + 1;
        }
        if (start < s.length()) sb.append(s, start, s.length());
        return sb.toString();
    }

    /**
     * 读取 ResTable_config 并生成可读配置描述，如 "zh-rCN-land-hdpi-v21"。
     * 调用前 buf 定位在 config 起始处；结束后恢复原位置。
     */
    public static String configToString(ByteBuffer buf, int configSize) {
        return parseConfig(buf, configSize).display();
    }

    /** 解析后的资源配置：字段供 ArscParser 过滤使用，display() 生成展示字符串。 */
    public static class Config {
        public String lang = "";     // 基础语言码，如 "zh"
        public String langFull = ""; // 国家码（不带 -r），如 "CN"
        public String density = "";  // 密度标签（无前导 '-'），如 "xxhdpi"、"480dpi"；nodpi 为 ""
        public int sw;               // 最小宽度 dp
        public int sdk;              // sdkVersion
        private int mcc, mnc, orientation, screenWidthPx, screenHeightPx, w, h;
        private int screenLayout, uiMode;

        public String display() {
            StringBuilder sb = new StringBuilder();
            if (!lang.isEmpty()) {
                sb.append(lang);
                if (!langFull.isEmpty()) sb.append("-r").append(langFull);
            }
            if (mcc != 0) sb.append("-mcc").append(mcc);
            if (mnc != 0) sb.append("-mnc").append(mnc);
            if (orientation == 1) sb.append("-port");
            else if (orientation == 2) sb.append("-land");
            // uiMode 类型（如 watch）+ 昼夜（顺序与 aapt 一致：方向之后、密度之前）
            int uiType = uiMode & 0x0F;
            switch (uiType) {
                case 2: sb.append("-desk"); break;
                case 3: sb.append("-car"); break;
                case 4: sb.append("-television"); break;
                case 5: sb.append("-appliance"); break;
                case 6: sb.append("-watch"); break;
                case 7: sb.append("-vrheadset"); break;
                default: break;
            }
            int night = uiMode & 0x30;
            if (night == 0x10) sb.append("-night");
            else if (night == 0x20) sb.append("-notnight");
            if (!density.isEmpty()) sb.append("-").append(density);
            if (screenWidthPx != 0 && screenHeightPx != 0) {
                sb.append("-").append(screenWidthPx).append("x").append(screenHeightPx);
            }
            if (sdk != 0) sb.append("-v").append(sdk);
            // screenLayout：尺寸 → 长屏 → 布局方向
            int slSize = screenLayout & 0x0F;
            if (slSize == 1) sb.append("-small");
            else if (slSize == 3) sb.append("-large");
            else if (slSize == 4) sb.append("-xlarge");
            int slLong = screenLayout & 0x30;
            if (slLong == 0x10) sb.append("-long");
            else if (slLong == 0x20) sb.append("-notlong");
            int slDir = screenLayout & 0xC0;
            if (slDir == 0x40) sb.append("-ldltr");
            else if (slDir == 0x80) sb.append("-ldrtl");
            if (sw != 0) sb.append("-sw").append(sw).append("dp");
            if (w != 0) sb.append("-w").append(w).append("dp");
            if (h != 0) sb.append("-h").append(h).append("dp");
            String s = sb.toString();
            if (s.startsWith("-")) s = s.substring(1);
            return s.isEmpty() ? "default" : s;
        }
    }

    /**
     * 读取 ResTable_config 并解析出结构化字段。调用前 buf 定位在 config 起始处；
     * 结束后恢复原位置；解析失败返回全空 Config。
     *
     * 字段布局（canonical，AOSP ResTable_config）：
     *   size(4)@0 mcc(2)@4 mnc(2)@6 language(2)@8 country(2)@10 orientation(1)@12
     *   touchscreen(1)@13 density(2)@14 keyboard(1)@16 navigation(1)@17 inputFlags(1)@18
     *   pad0(1)@19 screenWidth(2)@20 screenHeight(2)@22 sdkVersion(2)@24 minorVersion(2)@26
     *   screenLayout(1)@28 uiMode(1)@29 smallestScreenWidthDp(2)@30 screenWidthDp(2)@32
     *   screenHeightDp(2)@34 （Android 13+ 扩展到 64 字节，此处用不到后面的 localeScript 等）
     */
    public static Config parseConfig(ByteBuffer buf, int configSize) {
        Config c = new Config();
        int save = buf.position();
        int cfgEnd = save + configSize;
        try {
            buf.getInt(); // ResTable_config 首字段是自身的 size（u32），先跳过
            c.mcc = u16(buf);               // @4
            c.mnc = u16(buf);               // @6
            c.lang = readLang(buf);         // @8-9
            c.langFull = readLang(buf);     // @10-11
            c.orientation = u8(buf);        // @12
            u8(buf);                        // touchscreen @13
            int density = u16(buf);         // @14-15
            buf.position(buf.position() + 4); // keyboard,navigation,inputFlags,pad0 @16-19
            c.screenWidthPx = u16(buf);     // @20-21
            c.screenHeightPx = u16(buf);    // @22-23
            c.sdk = u16(buf);               // @24-25
            u16(buf);                       // minorVersion @26-27
            // @28 之后是 Android 3.0+ 才有的字段；旧版 28 字节 config 到此为止，
            // 越界字段按 0 处理，避免把紧随其后的条目偏移表误读成配置
            c.screenLayout = buf.position() + 1 <= cfgEnd ? u8(buf) : 0;   // @28
            c.uiMode = buf.position() + 1 <= cfgEnd ? u8(buf) : 0;         // @29
            c.sw = buf.position() + 2 <= cfgEnd ? u16(buf) : 0;            // @30-31
            c.w = buf.position() + 2 <= cfgEnd ? u16(buf) : 0;             // @32-33
            c.h = buf.position() + 2 <= cfgEnd ? u16(buf) : 0;             // @34-35

            switch (density) {
                case 120: c.density = "ldpi"; break;
                case 160: c.density = "mdpi"; break;
                case 240: c.density = "hdpi"; break;
                case 320: c.density = "xhdpi"; break;
                case 480: c.density = "xxhdpi"; break;
                case 640: c.density = "xxxhdpi"; break;
                default:
                    if (density != 0 && (density & 1) == 0) c.density = density + "dpi";
            }
        } catch (Exception ignored) {
        } finally {
            buf.position(save);
        }
        return c;
    }

    /** 读取 2 字节的语言/国家码，兼容 2 字母与压缩 3 字母格式。 */
    private static String readLang(ByteBuffer buf) {
        int b0 = u8(buf), b1 = u8(buf);
        if (b0 == 0 && b1 == 0) return "";
        if ((b0 & 0x80) != 0) {
            char c0 = (char) ('a' + ((b0 >> 2) & 0x1f));
            char c1 = (char) ('a' + (((b0 & 0x03) << 3) | (b1 >> 5)));
            char c2 = (char) ('a' + (b1 & 0x1f));
            return "" + c0 + c1 + c2;
        }
        StringBuilder sb = new StringBuilder();
        if (b0 != 0) sb.append((char) b0);
        if (b1 != 0) sb.append((char) b1);
        return sb.toString();
    }
}
