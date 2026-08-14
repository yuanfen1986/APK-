import com.apkreader.parser.AXmlEditor;
import com.apkreader.fixer.DexFixer;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 检查设备上已安装 APK：是否含 PermCheck 注入、manifest 是否含 5 权限。
 */
public class CheckInstalled {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            File apk = new File(path);
            System.out.println("=== " + path + " (" + apk.length() + " bytes)");
            List<String> dexes = new ArrayList<>();
            try (ZipFile zf = new ZipFile(apk)) {
                var en = zf.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (n.startsWith("classes") && n.endsWith(".dex")) dexes.add(n);
                }
                System.out.println("  dex: " + dexes);
                boolean helper = false;
                for (String d : dexes) {
                    try (InputStream in = zf.getInputStream(zf.getEntry(d))) {
                        File tmp = File.createTempFile("chk", System.nanoTime() + ".dex");
                        java.nio.file.Files.copy(in, tmp.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        try {
                            byte[] mag = java.nio.file.Files.readAllBytes(tmp.toPath());
                            String vs = new String(mag, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                            Opcodes op = Opcodes.forDexVersion(Integer.parseInt(vs.trim()));
                            DexBackedDexFile df = DexFileFactory.loadDexFile(tmp, op);
                            for (org.jf.dexlib2.iface.ClassDef c : df.getClasses()) {
                                if (c.getType().startsWith("Lcom/apktool/perminject/PermCheck")) {
                                    helper = true;
                                    System.out.println("  PERMCHECK FOUND in " + d + ": " + c.getType());
                                }
                            }
                        } finally {
                            tmp.delete();
                        }
                    }
                }
                if (!helper) System.out.println("  无 PermCheck 类 —— 未注入");
                InputStream x = zf.getInputStream(zf.getEntry("AndroidManifest.xml"));
                byte[] xml = x.readAllBytes();
                AXmlEditor.ManifestInfo info = AXmlEditor.readManifest(xml);
                System.out.println("  pkg=" + info.packageName + " app=" + info.applicationClass + " launcher=" + info.launcherActivity);
                System.out.println("  permissions=" + info.permissions);
            }
        }
    }
}
