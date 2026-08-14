import java.util.Arrays;
import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipInfo2 {
    public static void main(String[] args) throws Exception {
        System.out.println("args=" + Arrays.toString(args));
        File apk = new File(args[0]);
        System.out.println("file exists=" + apk.exists() + " len=" + apk.length());
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> es = zf.entries();
            int c = 0;
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                String n = e.getName();
                if (c < 3 || n.equals("resources.arsc")) {
                    System.out.println(n + " method=" + e.getMethod() + " size=" + e.getSize()
                            + " csize=" + e.getCompressedSize() + " extraLen=" + (e.getExtra() == null ? 0 : e.getExtra().length));
                }
                if (n.equals("resources.arsc")) {
                    byte[] ex = e.getExtra();
                    if (ex != null) {
                        StringBuilder sb = new StringBuilder();
                        for (byte b : ex) sb.append(String.format("%02x", b));
                        System.out.println("  resources.arsc extra=" + sb);
                    }
                }
                c++;
            }
            System.out.println("total=" + c);
        }
    }
}
