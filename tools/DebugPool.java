package com.apkreader.parser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/** 调试：dump addPermissions 输出的字符串池，核对新字符串偏移。 */
public class DebugPool {

    static void dump(String label, byte[] xml) {
        ByteBuffer bb = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN);
        int type = ResUtil.u16(bb);
        bb.position(4);
        int xmlSize = bb.getInt();
        int poolStart = 8;
        bb.position(poolStart + 4);
        int poolSize = bb.getInt();
        bb.position(poolStart + 2);
        int poolHs = ResUtil.u16(bb);
        bb.getInt(); // size
        int stringCount = bb.getInt();
        int styleCount = bb.getInt();
        int flags = bb.getInt();
        int stringsStart = bb.getInt();
        int stylesStart = bb.getInt();
        System.out.println("-- " + label + " -- type=0x" + Integer.toHexString(type)
                + " xmlSize=" + xmlSize + " poolSize=" + poolSize
                + " stringCount=" + stringCount + " styleCount=" + styleCount
                + " flags=0x" + Integer.toHexString(flags)
                + " stringsStart=" + stringsStart + " stylesStart=" + stylesStart);
        int[] offs = new int[stringCount];
        bb.position(poolStart + poolHs);
        for (int i = 0; i < stringCount; i++) offs[i] = bb.getInt();
        boolean utf8 = (flags & 0x100) != 0;
        for (int i = 0; i < stringCount; i++) {
            String s = (offs[i] == 0xFFFFFFFF || stringsStart == 0)
                    ? "" : ResUtil.readString(bb, poolStart + stringsStart + offs[i], utf8);
            System.out.printf("  [%2d] off=%-6d abs=%d -> %s%n", i, offs[i],
                    poolStart + stringsStart + offs[i], printable(s));
        }
    }

    static String printable(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c >= 0x20 && c < 0x7F ? c : '.');
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        // 合成
        byte[] synth = PermAddTest.buildXml();
        List<String> perms = Arrays.asList(
                "android.permission.INTERNET",
                "android.permission.READ_EXTERNAL_STORAGE");
        dump("synth 原始", synth);
        byte[] out1 = AXmlEditor.addPermissions(synth, perms);
        System.out.println("synth 输出长度 " + (out1 == null ? "null" : out1.length));
        if (out1 != null) dump("synth 输出", out1);

        // 真实
        byte[] real = Files.readAllBytes(Paths.get(args[0]));
        dump("real 原始", real);
        byte[] out2 = AXmlEditor.addPermissions(real, perms);
        System.out.println("real 输出长度 " + (out2 == null ? "null" : out2.length));
        if (out2 != null) {
            dump("real 输出", out2);
            Files.write(Paths.get("C:/Users/LENOVO/AppData/Local/Temp/axmltest/real_out.xml"), out2);
            System.out.println("已写出 real_out.xml");
        }
    }
}
