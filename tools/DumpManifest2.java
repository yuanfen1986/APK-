import com.apkreader.parser.AXmlEditor;
import com.apkreader.parser.AXmlParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipFile;

public class DumpManifest2 {
    public static void main(String[] args) throws Exception {
        try (ZipFile zf = new ZipFile(new File(args[0]))) {
            InputStream in = zf.getInputStream(zf.getEntry("AndroidManifest.xml"));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            byte[] xml = bos.toByteArray();
            System.out.println("manifest " + xml.length + " bytes, magic="
                    + Integer.toHexString((xml[0]&0xFF)<<8 | (xml[1]&0xFF)));
            AXmlEditor.ManifestInfo info = AXmlEditor.readManifest(xml);
            System.out.println("packageName   = " + info.packageName);
            System.out.println("application   = " + info.applicationClass);
            System.out.println("launcher      = " + info.launcherActivity);
            System.out.println("permissions   = " + info.permissions);
            String text = new AXmlParser(null).parse(xml);
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.contains("uses-permission") || t.contains("<application")
                        || t.contains("package=") || t.contains("sharedUserId")
                        || t.contains("uses-sdk") || t.contains("installLocation")
                        || t.contains("debuggable")) {
                    System.out.println("  " + line.trim());
                }
            }
        }
    }
}
