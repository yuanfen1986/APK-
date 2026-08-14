import com.apkreader.parser.AXmlParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipFile;

public class DumpFull {
    public static void main(String[] args) throws Exception {
        try (ZipFile zf = new ZipFile(new File(args[0]))) {
            InputStream in = zf.getInputStream(zf.getEntry("AndroidManifest.xml"));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            System.out.println(new AXmlParser(null).parse(bos.toByteArray()));
        }
    }
}
