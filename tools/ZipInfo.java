import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 打印 APK 内每个条目的压缩方式/大小/extra 十六进制。
 * 用于观察 resources.arsc 与 lib/*.so 在原始与重打包产物中的差异。
 */
public class ZipInfo {
    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> es = zf.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                String n = e.getName();
                if (!args[1].equals("*") && !n.contains(args[1])) continue;
                byte[] ex = e.getExtra();
                System.out.printf("%-45s method=%d size=%d csize=%d extraLen=%d extra=",
                        n, e.getMethod(), e.getSize(), e.getCompressedSize(), ex == null ? 0 : ex.length);
                if (ex != null) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : ex) sb.append(String.format("%02x", b));
                    System.out.print(sb);
                }
                System.out.println();
            }
        }
    }
}
