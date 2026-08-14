import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction35c;
import org.jf.dexlib2.iface.reference.MethodReference;

import java.io.File;

/** 扫描 dex 中所有方法体里是否调用可疑 API（签名校验/进程退出/包管理器敏感项）。 */
public class ScanApp {
    public static void main(String[] args) throws Exception {
        String[] suspicious = {
                "checkSignatures", "getSignatures", "getPackageInfo", "getInstallerPackageName",
                "System;->exit", "Runtime;->exit", "killProcess", "getSignature",
                "System;->load", "loadLibrary", "PackageManager;->getPackageInfo",
                "crc32", "Checksum", "MessageDigest", "getFileSha1", "md5", "sha1",
                "isRooted", "SELinux", "checkFileIntegrity",
        };
        for (String apkPath : args) {
            System.out.println("=== " + apkPath);
            File apk = new File(apkPath);
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                    File tmp = File.createTempFile("scan", ".dex");
                    java.nio.file.Files.copy(zf.getInputStream(zf.getEntry(n)), tmp.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    byte[] magic = java.nio.file.Files.readAllBytes(tmp.toPath());
                    Opcodes op;
                    try {
                        op = Opcodes.forDexVersion(Integer.parseInt(
                                new String(magic, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).trim()));
                    } catch (Exception e) { op = Opcodes.getDefault(); }
                    DexBackedDexFile df = DexFileFactory.loadDexFile(tmp, op);
                    for (ClassDef c : df.getClasses()) {
                        for (Method m : c.getMethods()) {
                            if (m.getImplementation() == null) continue;
                            for (Instruction ins : m.getImplementation().getInstructions()) {
                                if (!(ins instanceof Instruction35c)) continue;
                                Instruction35c i35 = (Instruction35c) ins;
                                if (i35.getReference() instanceof MethodReference) {
                                    String ref = ((MethodReference) i35.getReference()).getDefiningClass()
                                            + "->" + i35.getReference().toString();
                                    for (String s : suspicious) {
                                        if (ref.contains(s)) {
                                            System.out.println("  " + c.getType() + "." + m.getName()
                                                    + ": " + ref);
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    tmp.delete();
                }
            }
        }
    }
}
