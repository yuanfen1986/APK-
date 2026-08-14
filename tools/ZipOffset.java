import java.io.RandomAccessFile;

/**
 * 遍历 zip 本地头，打印每个条目的数据起始偏移。
 * 用于确认 resources.arsc 数据偏移是否为 4 的倍数。
 */
public class ZipOffset {
    public static void main(String[] args) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(args[0], "r")) {
            long off = 0;
            long len = raf.length();
            int count = 0;
            while (off + 4 <= len) {
                raf.seek(off);
                int sig = raf.readInt();
                if (sig != 0x504b0304) {
                    System.out.println("end at offset " + off + " sig=" + String.format("%08x", sig));
                    break;
                }
                raf.seek(off + 18);
                long compSize = raf.readInt() & 0xFFFFFFFFL;
                raf.seek(off + 26);
                int nameLen = raf.readUnsignedShort();
                int extraLen = raf.readUnsignedShort();
                byte[] name = new byte[nameLen];
                raf.seek(off + 30);
                raf.readFully(name);
                String n = new String(name);
                long dataOff = off + 30 + nameLen + extraLen;
                if (count < 3 || n.contains("resources.arsc") || n.contains(".so")) {
                    System.out.printf("%-45s dataOff=%d  mod4=%d  compSize=%d%n",
                            n, dataOff, dataOff % 4, compSize);
                }
                off = dataOff + compSize;
                count++;
            }
        }
    }
}
