package com.apkreader.fixer;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.dexbacked.DexBackedMethod;
import org.jf.dexlib2.dexbacked.DexBackedMethodImplementation;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 逐方法结构对比：对 orig_*.dex 与 chk_*.dex 的每个方法比较
 * registerCount + 指令条数 + opcode 序列，输出不一致的方法。
 * 目的：证明 baksmali-重汇编 round-trip 在方法层面结构无损，
 * 排除 ART 校验器因结构损坏拒绝加载的嫌疑。
 */
public class DexStructCompare {

    static int fails = 0;

    static void check(boolean c, String m) {
        System.out.println((c ? "PASS: " : "FAIL: ") + m);
        if (!c) fails++;
    }

    static Map<String, String> collect(File dex) throws Exception {
        Opcodes op = DexFixer.opcodesForDex(dex);
        DexBackedDexFile df = DexFileFactory.loadDexFile(dex, op);
        Map<String, String> map = new HashMap<>();
        for (ClassDef c : df.getClasses()) {
            for (Method m : c.getMethods()) {
                DexBackedMethodImplementation impl = (DexBackedMethodImplementation) m.getImplementation();
                if (impl == null) continue; // abstract/native
                int regs = impl.getRegisterCount();
                StringBuilder sb = new StringBuilder();
                for (org.jf.dexlib2.iface.instruction.Instruction ins : impl.getInstructions()) {
                    sb.append(ins.getOpcode().name).append(';');
                }
                map.put(c.getType() + "->" + m.getName() + m.getParameterTypes(), regs + "|" + sb.length() + "|" + sb);
            }
        }
        return map;
    }

    public static void main(String[] args) throws Exception {
        String workDir = args.length > 0 ? args[0]
                : "C:/Users/LENOVO/AppData/Local/Temp/fullinject1460117883941747174";
        File dir = new File(workDir);
        File[] files = dir.listFiles();
        if (files == null) {
            System.out.println("FAIL: 目录不存在: " + workDir);
            System.exit(1);
        }
        int dexN = 0;
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith("orig_") || !n.endsWith(".dex")) continue;
            dexN++;
            String base = n.substring("orig_".length());
            File chk = new File(dir, "chk_" + base);
            if (!chk.isFile()) {
                check(false, base + " 产物缺失");
                continue;
            }
            Map<String, String> a = collect(f);
            Map<String, String> b = collect(chk);
            int same = 0, diff = 0;
            for (Map.Entry<String, String> e : a.entrySet()) {
                String s2 = b.get(e.getKey());
                if (s2 != null && s2.equals(e.getValue())) same++;
                else {
                    diff++;
                    if (diff <= 5) {
                        System.out.println("  DIFF " + e.getKey());
                        System.out.println("    orig=" + (s2 == null ? "<缺失>" : e.getValue().substring(0, Math.min(80, e.getValue().length()))));
                        System.out.println("    chk =" + (s2 == null ? "<缺失>" : s2.substring(0, Math.min(80, s2.length()))));
                    }
                }
            }
            // 产物里多出的（应为 PermCheck 注入的 +1 类 +N 方法）
            int extra = 0;
            for (String k : b.keySet()) {
                if (!a.containsKey(k)) extra++;
            }
            check(diff == 0, base + " 方法级结构一致: " + same + " 方法相同, " + diff + " 不同, 产物多出 " + extra + " (PermCheck)");
        }
        System.out.println("对比 dex 数: " + dexN);
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
