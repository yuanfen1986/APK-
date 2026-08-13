import com.apkreader.parser.AXmlParser;
import com.apkreader.parser.ArscParser;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 纯 javac 可运行的测试台：手工构造二进制 AndroidManifest.xml（AXML）
 * 与 resources.arsc 的字节流，喂给解析器，打印解码结果。
 */
public class TestHarness {

    /** 小端字节组装器 */
    static class Buf extends ByteArrayOutputStream {
        void u8(int v) { write(v & 0xFF); }

        void u16(int v) { write(v & 0xFF); write((v >> 8) & 0xFF); }

        void u32(long v) { write((int) (v & 0xFF)); write((int) ((v >> 8) & 0xFF)); write((int) ((v >> 16) & 0xFF)); write((int) ((v >> 24) & 0xFF)); }

        void bytes(byte[] b) { write(b, 0, b.length); }
    }

    /** 构造 UTF-8 字符串池 chunk（含 8 字节 chunk 头） */
    static byte[] utf8Pool(String[] strs) throws UnsupportedEncodingException {
        int headerSize = 28;
        int stringsStart = headerSize + 4 * strs.length;
        List<byte[]> datas = new ArrayList<>();
        int dataLen = 0;
        int[] offsets = new int[strs.length];
        for (int i = 0; i < strs.length; i++) {
            offsets[i] = dataLen;
            byte[] raw = strs[i].getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream s = new ByteArrayOutputStream();
            s.write(strs[i].length());       // UTF-16 字符数
            s.write(raw.length);             // UTF-8 字节数
            s.write(raw, 0, raw.length);
            s.write(0);                      // 结束符
            byte[] sd = s.toByteArray();
            datas.add(sd);
            dataLen += sd.length;
            while ((dataLen & 3) != 0) dataLen++; // 4 字节对齐
        }
        int size = stringsStart + dataLen;
        Buf out = new Buf();
        out.u16(0x0001);
        out.u16(headerSize);
        out.u32(size);
        out.u32(strs.length);
        out.u32(0);                          // styleCount
        out.u32(0x100);                      // UTF8 flag
        out.u32(stringsStart);
        out.u32(0);                          // stylesStart
        for (int o : offsets) out.u32(o);
        for (byte[] sd : datas) {
            out.bytes(sd);
            int pad = (4 - (sd.length & 3)) & 3;
            while (pad-- > 0) out.write(0);
        }
        return out.toByteArray();
    }

    /** START ELEMENT chunk。attrs 元素: {nsIdx, nameIdx, rawIdx, dataType, data} */
    static void startElem(Buf out, int nsIdx, int nameIdx, int[][] attrs) {
        int n = attrs.length;
        int size = 16 + 20 * n;
        out.u16(0x0102);
        out.u16(16);
        out.u32(size);
        out.u32(1);                          // lineNumber
        out.u32(0xFFFFFFFFL);                // comment
        out.u32(nsIdx < 0 ? 0xFFFFFFFFL : nsIdx);
        out.u32(nameIdx);
        out.u16(0x14);                       // attributeStart
        out.u16(0x14);                       // attributeSize
        out.u16(n);
        out.u16(0xFFFF);                     // idIndex
        out.u16(0xFFFF);                     // classIndex
        out.u16(0xFFFF);                     // styleIndex
        for (int[] a : attrs) {
            out.u32(a[0] < 0 ? 0xFFFFFFFFL : a[0]);
            out.u32(a[1]);
            out.u32(a[2] < 0 ? 0xFFFFFFFFL : a[2]);
            out.u16(8);                      // valueSize
            out.u8(0);                       // res0
            out.u8(a[3]);                    // dataType
            out.u32(a[4]);
        }
    }

    static void endElem(Buf out, int nsIdx, int nameIdx) {
        out.u16(0x0103);
        out.u16(16);
        out.u32(32);
        out.u32(2);
        out.u32(0xFFFFFFFFL);
        out.u32(nsIdx < 0 ? 0xFFFFFFFFL : nsIdx);
        out.u32(nameIdx);
    }

    /** CDATA chunk：文本指向字符串池下标 */
    static void cdata(Buf out, int dataIdx) {
        out.u16(0x0104);
        out.u16(16);
        out.u32(40);
        out.u32(3);
        out.u32(0xFFFFFFFFL);
        out.u32(dataIdx);
        out.u16(8); out.u8(0); out.u8(0x03); out.u32(dataIdx);
    }

    /** 构造一个最小但完整的二进制 AndroidManifest.xml */
    static byte[] buildXml() throws UnsupportedEncodingException {
        String[] strs = {
                "http://schemas.android.com/apk/res/android", // 0  android 命名空间
                "manifest",            // 1
                "package",             // 2
                "versionCode",         // 3
                "versionName",         // 4
                "com.example.demo",    // 5
                "1.0",                 // 6
                "application",         // 7
                "label",               // 8
                "icon",                // 9
                "activity",            // 10
                "name",                // 11
                ".MainActivity",       // 12
                "hello world",         // 13
                "allowBackup",         // 14
                "supportsRtl",         // 15
        };
        byte[] pool = utf8Pool(strs);

        Buf body = new Buf();
        // manifest 属性: package(字符串 raw)、versionCode(整数)、versionName(字符串 raw)
        startElem(body, -1, 1, new int[][]{
                {-1, 2, 5, 0x03, 5},
                {-1, 3, -1, 0x10, 1},
                {-1, 4, 6, 0x03, 6},
        });
        // application: android:label=@0x7f020000, android:icon=@0x7f030000,
        //              android:allowBackup=true, android:supportsRtl=false
        // 注意：元素名不带命名空间，只有属性带 android: 前缀
        startElem(body, -1, 7, new int[][]{
                {0, 8, -1, 0x01, 0x7f020000},
                {0, 9, -1, 0x01, 0x7f030000},
                {0, 14, -1, 0x12, 1},
                {0, 15, -1, 0x12, 0},
        });
        startElem(body, -1, 10, new int[][]{
                {0, 11, 12, 0x03, 12},
        });
        cdata(body, 13);
        endElem(body, -1, 10);
        endElem(body, -1, 7);
        endElem(body, -1, 1);

        Buf out = new Buf();
        out.u16(0x0003);
        out.u16(8);
        out.u32(8 + pool.length + body.size());
        out.bytes(pool);
        out.bytes(body.toByteArray());
        return out.toByteArray();
    }

    /** 构造最小 resources.arsc：全局池 + 1 个包（2 个类型） */
    static byte[] buildArsc() throws UnsupportedEncodingException {
        String[] global = {"APK解析工具", "你好世界", "第一个布局", "नमस्ते"};
        byte[] gp = utf8Pool(global);

        String[] typeStrs = {"attr", "string", "layout", "string-af"};
        String[] keyStrs = {"app_name", "hello", "activity_main"};
        byte[] tp = utf8Pool(typeStrs);
        byte[] kp = utf8Pool(keyStrs);

        Buf pkg = new Buf();
        pkg.u16(0x0200);
        pkg.u16(0x011C);                     // 固定头长度 284
        pkg.u32(0);                          // size 稍后回填
        int sizePos = pkg.size() - 4;
        pkg.u32(0x7F);                       // 包 id
        byte[] nm = new byte[256];
        byte[] raw = "com.example.demo".getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(raw, 0, nm, 0, raw.length);
        pkg.bytes(nm);
        int typeStringsOff = 0x11C;
        int keyStringsOff = typeStringsOff + tp.length;
        pkg.u32(typeStringsOff);
        pkg.u32(3);                          // lastPublicType
        pkg.u32(keyStringsOff);
        pkg.u32(2);                          // lastPublicKey

        pkg.bytes(tp);
        pkg.bytes(kp);

        // typeSpec: id=2 (string), 2 个条目
        pkg.u16(0x0202);
        pkg.u16(16);
        pkg.u32(16 + 8);
        pkg.u8(2); pkg.u8(0); pkg.u16(0);
        pkg.u32(2);
        pkg.u32(0); pkg.u32(0);

        // type: id=2，default 配置，2 条 string 条目
        pkg.u16(0x0201);
        pkg.u16(0x44);                       // headerSize = 20 + config(0x30)
        pkg.u32(0x44 + 8 + 32);              // 头 + offsets + 2 条目(16 字节 each)
        pkg.u8(2); pkg.u8(0); pkg.u16(0);
        pkg.u32(2);                          // entryCount
        pkg.u32(0x4C);                       // entriesStart = headerSize + 4*2
        pkg.u32(0x30);                       // config.size
        for (int i = 0; i < 0x30 - 4; i++) pkg.u8(0); // 其余配置字段全 0
        pkg.u32(0);                          // offset[0]
        pkg.u32(0x10);                       // offset[1]
        writeEntry(pkg, 0, 0x03, 0);         // app_name = 全局串 0
        writeEntry(pkg, 1, 0x03, 1);         // hello = 全局串 1

        // type: id=2 (string)，配置语言 hi，1 条字符串条目 —— 验证语言过滤
        pkg.u16(0x0201);
        pkg.u16(0x44);
        pkg.u32(0x44 + 4 + 16);
        pkg.u8(2); pkg.u8(0); pkg.u16(0);
        pkg.u32(1);                          // entryCount
        pkg.u32(0x48);                       // entriesStart
        pkg.u32(0x30);                       // config.size
        pkg.u16(0); pkg.u16(0);              // mcc, mnc @4-7
        pkg.u8('h'); pkg.u8('i');            // language @8-9
        pkg.u8(0); pkg.u8(0);                // country @10-11
        pkg.u8(0); pkg.u8(0);                // orientation, touchscreen @12-13
        pkg.u16(0);                          // density @14-15
        pkg.u8(0); pkg.u8(0); pkg.u8(0); pkg.u8(0); // keyboard..pad0 @16-19
        pkg.u16(0); pkg.u16(0);              // screenWidthPx, screenHeightPx @20-23
        pkg.u16(0);                          // sdkVersion @24-25
        pkg.u16(0);                          // minorVersion @26-27
        pkg.u8(0); pkg.u8(0);                // screenLayout, uiMode @28-29
        pkg.u16(0); pkg.u16(0); pkg.u16(0);  // sw/w/h dp @30-35
        for (int i = 0; i < 12; i++) pkg.u8(0); // 补足 config 至 0x30
        pkg.u32(0);                          // offset[0]
        writeEntry(pkg, 0, 0x03, 3);         // app_name = 全局串 3 "नमस्ते"

        // type: id=3 (layout)，配置 zh-rCN-port-xxhdpi-v33，1 条引用条目
        pkg.u16(0x0201);
        pkg.u16(0x44);
        pkg.u32(0x44 + 4 + 16);
        pkg.u8(3); pkg.u8(0); pkg.u16(0);
        pkg.u32(1);                          // entryCount
        pkg.u32(0x48);                       // entriesStart
        pkg.u32(0x30);                       // config.size
        pkg.u16(0); pkg.u16(0);              // mcc, mnc @4-7
        pkg.u8('z'); pkg.u8('h');            // language @8-9
        pkg.u8('C'); pkg.u8('N');            // country @10-11
        pkg.u8(1); pkg.u8(0);                // orientation=1(port), touchscreen @12-13
        pkg.u16(480);                        // density @14-15
        pkg.u8(0); pkg.u8(0); pkg.u8(0); pkg.u8(0); // keyboard..pad0 @16-19
        pkg.u16(0); pkg.u16(0);              // screenWidthPx, screenHeightPx @20-23
        pkg.u16(33);                         // sdkVersion @24-25
        pkg.u16(0);                          // minorVersion @26-27
        pkg.u8(0); pkg.u8(0);                // screenLayout, uiMode @28-29
        pkg.u16(0); pkg.u16(0); pkg.u16(0);  // sw/w/h dp @30-35
        for (int i = 0; i < 12; i++) pkg.u8(0); // 补足 config 至 0x30
        pkg.u32(0);                          // offset[0]
        writeEntry(pkg, 2, 0x01, 0x7f030000L); // activity_main = @layout/activity_main

        // type: id=3 (layout)，配置与上一条相同但 sdkVersion=21 —— 验证版本过滤
        pkg.u16(0x0201);
        pkg.u16(0x44);
        pkg.u32(0x44 + 4 + 16);
        pkg.u8(3); pkg.u8(0); pkg.u16(0);
        pkg.u32(1);                          // entryCount
        pkg.u32(0x48);                       // entriesStart
        pkg.u32(0x30);                       // config.size
        pkg.u16(0); pkg.u16(0);              // mcc, mnc @4-7
        pkg.u8('z'); pkg.u8('h');            // language @8-9
        pkg.u8('C'); pkg.u8('N');            // country @10-11
        pkg.u8(1); pkg.u8(0);                // orientation=1(port), touchscreen @12-13
        pkg.u16(480);                        // density @14-15
        pkg.u8(0); pkg.u8(0); pkg.u8(0); pkg.u8(0); // keyboard..pad0 @16-19
        pkg.u16(0); pkg.u16(0);              // screenWidthPx, screenHeightPx @20-23
        pkg.u16(21);                         // sdkVersion @24-25
        pkg.u16(0);                          // minorVersion @26-27
        pkg.u8(0); pkg.u8(0);                // screenLayout, uiMode @28-29
        pkg.u16(0); pkg.u16(0); pkg.u16(0);  // sw/w/h dp @30-35
        for (int i = 0; i < 12; i++) pkg.u8(0); // 补足 config 至 0x30
        pkg.u32(0);                          // offset[0]
        writeEntry(pkg, 2, 0x01, 0x7f030000L); // activity_main = @layout/activity_main

        // type: id=3 (layout)，配置仅 density=480 无语言无版本 —— 验证分辨率过滤
        pkg.u16(0x0201);
        pkg.u16(0x44);
        pkg.u32(0x44 + 4 + 16);
        pkg.u8(3); pkg.u8(0); pkg.u16(0);
        pkg.u32(1);                          // entryCount
        pkg.u32(0x48);                       // entriesStart
        pkg.u32(0x30);                       // config.size
        pkg.u16(0); pkg.u16(0);              // mcc, mnc @4-7
        pkg.u8(0); pkg.u8(0);                // language "" @8-9
        pkg.u8(0); pkg.u8(0);                // country "" @10-11
        pkg.u8(0); pkg.u8(0);                // orientation, touchscreen @12-13
        pkg.u16(480);                        // density @14-15
        pkg.u8(0); pkg.u8(0); pkg.u8(0); pkg.u8(0); // keyboard..pad0 @16-19
        pkg.u16(0); pkg.u16(0);              // screenWidthPx, screenHeightPx @20-23
        pkg.u16(0);                          // sdkVersion @24-25
        pkg.u16(0);                          // minorVersion @26-27
        pkg.u8(0); pkg.u8(0);                // screenLayout, uiMode @28-29
        pkg.u16(0); pkg.u16(0); pkg.u16(0);  // sw/w/h dp @30-35
        for (int i = 0; i < 12; i++) pkg.u8(0); // 补足 config 至 0x30
        pkg.u32(0);                          // offset[0]
        writeEntry(pkg, 2, 0x01, 0x7f030000L); // activity_main = @layout/activity_main

        // type: id=3 (layout)，配置仅 uiMode=watch —— 验证新限定的展示（[watch]）
        pkg.u16(0x0201);
        pkg.u16(0x44);
        pkg.u32(0x44 + 4 + 16);
        pkg.u8(3); pkg.u8(0); pkg.u16(0);
        pkg.u32(1);                          // entryCount
        pkg.u32(0x48);                       // entriesStart
        pkg.u32(0x30);                       // config.size
        pkg.u16(0); pkg.u16(0);              // mcc, mnc @4-7
        pkg.u8(0); pkg.u8(0);                // language "" @8-9
        pkg.u8(0); pkg.u8(0);                // country "" @10-11
        pkg.u8(0); pkg.u8(0);                // orientation, touchscreen @12-13
        pkg.u16(0);                          // density @14-15
        pkg.u8(0); pkg.u8(0); pkg.u8(0); pkg.u8(0); // keyboard..pad0 @16-19
        pkg.u16(0); pkg.u16(0);              // screenWidthPx, screenHeightPx @20-23
        pkg.u16(0);                          // sdkVersion @24-25
        pkg.u16(0);                          // minorVersion @26-27
        pkg.u8(0); pkg.u8(0x06);             // screenLayout=0, uiMode=watch @28-29
        pkg.u16(0); pkg.u16(0); pkg.u16(0);  // sw/w/h dp @30-35
        for (int i = 0; i < 12; i++) pkg.u8(0); // 补足 config 至 0x30
        pkg.u32(0);                          // offset[0]
        writeEntry(pkg, 2, 0x01, 0x7f030000L); // activity_main = @layout/activity_main

        // type: id=4 (string-af)，配置 [af]，1 条字符串条目 —— 应被过滤，不单独展示
        pkg.u16(0x0201);
        pkg.u16(0x44);
        pkg.u32(0x44 + 4 + 16);
        pkg.u8(4); pkg.u8(0); pkg.u16(0);
        pkg.u32(1);                          // entryCount
        pkg.u32(0x48);                       // entriesStart
        pkg.u32(0x30);                       // config.size
        pkg.u16(0); pkg.u16(0);              // mcc, mnc @4-7
        pkg.u8('a'); pkg.u8('f');            // language @8-9
        pkg.u8(0); pkg.u8(0);                // country @10-11
        pkg.u8(0); pkg.u8(0);                // orientation, touchscreen @12-13
        pkg.u16(0);                          // density @14-15
        pkg.u8(0); pkg.u8(0); pkg.u8(0); pkg.u8(0); // keyboard..pad0 @16-19
        pkg.u16(0); pkg.u16(0);              // screenWidthPx, screenHeightPx @20-23
        pkg.u16(0);                          // sdkVersion @24-25
        pkg.u16(0);                          // minorVersion @26-27
        pkg.u8(0); pkg.u8(0);                // screenLayout, uiMode @28-29
        pkg.u16(0); pkg.u16(0); pkg.u16(0);  // sw/w/h dp @30-35
        for (int i = 0; i < 12; i++) pkg.u8(0); // 补足 config 至 0x30
        pkg.u32(0);                          // offset[0]
        writeEntry(pkg, 0, 0x03, 1);         // app_name = 全局串 1 "你好世界"

        // 回填包 chunk size
        byte[] pkgBytes = pkg.toByteArray();
        int pkgSize = pkgBytes.length;
        pkgBytes[sizePos] = (byte) (pkgSize & 0xFF);
        pkgBytes[sizePos + 1] = (byte) ((pkgSize >> 8) & 0xFF);
        pkgBytes[sizePos + 2] = (byte) ((pkgSize >> 16) & 0xFF);
        pkgBytes[sizePos + 3] = (byte) ((pkgSize >> 24) & 0xFF);

        Buf out = new Buf();
        out.u16(0x0002);
        out.u16(12);
        out.u32(12 + gp.length + pkgSize);
        out.u32(1);                          // packageCount
        out.bytes(gp);
        out.bytes(pkgBytes);
        return out.toByteArray();
    }

    /** 写入一条非复杂资源条目（entry 头 + Res_value） */
    static void writeEntry(Buf b, int keyIdx, int dataType, long data) {
        b.u16(8);                            // entry size
        b.u16(0);                            // flags
        b.u32(keyIdx);
        b.u16(8);                            // value size
        b.u8(0);                             // res0
        b.u8(dataType);
        b.u32(data);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("================ 构造合成数据 ================");
        byte[] xml = buildXml();
        byte[] arsc = buildArsc();
        System.out.println("AXML 字节数 : " + xml.length);
        System.out.println("ARSC 字节数 : " + arsc.length);

        System.out.println("\n================ resources.arsc 解码 ================");
        ArscParser.Result r = new ArscParser().parseResult(arsc);
        System.out.println(r.text);
        System.out.println("--- resNames 映射（供 manifest 引用还原）---");
        for (Map.Entry<Integer, String> e : r.resNames.entrySet()) {
            System.out.println("  0x" + String.format("%08x", e.getKey()) + " -> " + e.getValue());
        }

        System.out.println("\n================ AndroidManifest.xml 解码 ================");
        String xmlText = new AXmlParser(r.resNames).parse(xml);
        System.out.println(xmlText);

        // 基本断言
        int fail = 0;
        if (!xmlText.contains("package=\"com.example.demo\"")) { System.out.println("FAIL: package 属性"); fail++; }
        if (!xmlText.contains("versionCode=\"1\"")) { System.out.println("FAIL: versionCode"); fail++; }
        if (!xmlText.contains("versionName=\"1.0\"")) { System.out.println("FAIL: versionName"); fail++; }
        if (!xmlText.contains("android:label=\"@string/app_name\"")) { System.out.println("FAIL: label 引用还原"); fail++; }
        if (!xmlText.contains("android:icon=\"@layout/activity_main\"")) { System.out.println("FAIL: icon 引用还原"); fail++; }
        if (!xmlText.contains("android:allowBackup=\"true\"")) { System.out.println("FAIL: 布尔 true"); fail++; }
        if (!xmlText.contains("android:supportsRtl=\"false\"")) { System.out.println("FAIL: 布尔 false"); fail++; }
        if (!xmlText.contains("<activity") || !xmlText.contains("hello world")) { System.out.println("FAIL: 子元素/文本"); fail++; }
        if (!xmlText.contains("xmlns:android=\"http://schemas.android.com/apk/res/android\"")) { System.out.println("FAIL: xmlns"); fail++; }
        if (!r.text.contains("0x7f020000  string/app_name = \"APK解析工具\"")) { System.out.println("FAIL: arsc 字符串条目"); fail++; }
        if (!r.text.contains("0x7f020001  string/hello = \"你好世界\"")) { System.out.println("FAIL: arsc 第二条目"); fail++; }
        if (!r.text.contains("[zh-rCN-port-xxhdpi-v33] 0x7f030000  layout/activity_main = @layout/activity_main")) { System.out.println("FAIL: arsc 引用条目"); fail++; }
        if (!r.text.contains("资源ID映射表")) { System.out.println("FAIL: 映射表标题"); fail++; }
        if (!r.text.contains("资源包 : com.example.demo  (id=0x7f)")) { System.out.println("FAIL: 资源包标题"); fail++; }

        // string-af 类型：不单独展示，但 resNames 归一化为 string/app_name
        if (!r.resNames.containsKey(0x7f040000)) { System.out.println("FAIL: string-af 条目缺失"); fail++; }
        else if (!"string/app_name".equals(r.resNames.get(0x7f040000))) { System.out.println("FAIL: string-af 未归一化: " + r.resNames.get(0x7f040000)); fail++; }
        if (r.text.contains("string-af")) { System.out.println("FAIL: 输出中仍出现 string-af 类型"); fail++; }

        // 语言过滤：自定义只保留 zh
        ArscParser.ConfigFilter fLang = new ArscParser.ConfigFilter();
        fLang.langMode = ArscParser.ConfigFilter.LANG_CUSTOM;
        fLang.langSel.add("zh");
        String langText = new ArscParser().parseResult(arsc, fLang).text;
        if (!langText.contains("[zh-rCN-port-xxhdpi-v33]")) { System.out.println("FAIL: 语言自定义 zh 应保留 zh 块"); fail++; }
        if (langText.contains("[hi]")) { System.out.println("FAIL: 语言自定义 zh 不应含 hi 块"); fail++; }

        // 分辨率过滤：仅默认（无密度限定）；密度单独的配置显示为 [xxhdpi]
        ArscParser.ConfigFilter fDen = new ArscParser.ConfigFilter();
        fDen.densityMode = ArscParser.ConfigFilter.DENSITY_DEFAULT;
        String denText = new ArscParser().parseResult(arsc, fDen).text;
        if (denText.contains("[xxhdpi]")) { System.out.println("FAIL: 分辨率仅默认不应含 xxhdpi 块"); fail++; }

        // 新限定展示：watch / ldltr / hNdp 等（修复 config 偏移后能正确读出）
        if (!r.text.contains("[watch] 0x7f030000  layout/activity_main = @layout/activity_main")) {
            System.out.println("FAIL: watch 配置应显示 [watch]"); fail++;
        }
        if (r.text.contains("[-")) { System.out.println("FAIL: 配置标注不应出现孤立前导 '-'"); fail++; }

        // 版本过滤：同组只保留最高 sdk（v33 替代 v21），且不误伤不同组（hi 语言块应保留）
        ArscParser.ConfigFilter fVer = new ArscParser.ConfigFilter();
        fVer.versionMode = ArscParser.ConfigFilter.VERSION_MAX;
        String verText = new ArscParser().parseResult(arsc, fVer).text;
        if (!verText.contains("[zh-rCN-port-xxhdpi-v33]")) { System.out.println("FAIL: 版本 max 应保留 v33 块"); fail++; }
        if (verText.contains("[zh-rCN-port-xxhdpi-v21]")) { System.out.println("FAIL: 版本 max 不应含 v21 块"); fail++; }
        if (!verText.contains("[hi]")) { System.out.println("FAIL: 版本 max 不应误伤语言组"); fail++; }

        // 过滤只作用于输出文本，resNames 映射始终完整
        ArscParser.Result rFiltered = new ArscParser().parseResult(arsc, fVer);
        if (!rFiltered.resNames.containsKey(0x7f020000)
                || !"string/app_name".equals(rFiltered.resNames.get(0x7f020000))) {
            System.out.println("FAIL: 过滤后 resNames 应保留 string/app_name"); fail++;
        }

        System.out.println("\n================ 结果 ================");
        System.out.println(fail == 0 ? "全部断言通过 ✔" : fail + " 项断言失败 ✘");
        if (fail != 0) System.exit(1);
    }
}
