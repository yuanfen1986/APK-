package com.apkreader.parser;

import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 诊断用：打印 manifest application 属性（含 extractNativeLibs）与 lib/*.so 条目压缩方式。 */
public class ManifestDump {
    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> es = zf.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                String n = e.getName();
                if (n.equals("AndroidManifest.xml")) {
                    byte[] xml;
                    try (InputStream in = zf.getInputStream(e)) {
                        xml = in.readAllBytes();
                    }
                    AXmlParser.Node root = new AXmlParser(null).parseTree(xml);
                    System.out.println("root=" + (root == null ? "null" : root.name));
                    if (root != null) {
                        for (AXmlParser.Node c : root.children) {
                            if (!"application".equals(c.name)) continue;
                            System.out.println("-- application attrs --");
                            for (AXmlParser.Attr a : c.attrs) {
                                System.out.println("  " + (a.ns == null ? "-" : a.ns) + " / " + a.name + " = " + a.value);
                            }
                        }
                    }
                } else if (n.startsWith("lib/") && n.endsWith(".so")) {
                    System.out.println("LIB " + n + " method=" + e.getMethod() + " size=" + e.getSize() + " csize=" + e.getCompressedSize());
                }
            }
        }
    }
}
