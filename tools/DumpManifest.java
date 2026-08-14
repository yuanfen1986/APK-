import com.apkreader.parser.AXmlEditor;
import com.apkreader.parser.AXmlParser;

import java.io.File;
import java.nio.file.Files;

public class DumpManifest {
    public static void main(String[] args) throws Exception {
        byte[] xml = Files.readAllBytes(new File(args[0]).toPath());
        AXmlEditor.ManifestInfo info = AXmlEditor.readManifest(xml);
        System.out.println("packageName   = " + info.packageName);
        System.out.println("application   = " + info.applicationClass);
        System.out.println("launcher      = " + info.launcherActivity);
        System.out.println("permissions   = " + info.permissions);
        String text = new AXmlParser(null).parse(xml);
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.contains("uses-permission") || t.contains("application")
                    || t.contains("package=") || t.contains("sharedUserId")
                    || t.contains("uses-sdk")) {
                System.out.println("  " + line.trim());
            }
        }
    }
}
