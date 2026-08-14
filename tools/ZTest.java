import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZTest {
    public static void main(String[] a) throws Exception {
        System.out.println("start");
        ZipFile zf = new ZipFile(new File(a[0]));
        System.out.println("opened, size=" + zf.size());
        Enumeration<? extends ZipEntry> es = zf.entries();
        int c = 0;
        while (es.hasMoreElements()) {
            ZipEntry e = es.nextElement();
            System.out.println(c++ + " " + e.getName() + " method=" + e.getMethod()
                    + " size=" + e.getSize() + " csize=" + e.getCompressedSize());
            if (c > 8) break;
        }
        zf.close();
    }
}
