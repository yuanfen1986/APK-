import com.apkreader.fixer.PermInjector;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 单次注入驱动：把 5 个权限注入目标 APK，输出到指定路径。
 */
public class InjectOne {
    public static void main(String[] args) throws Exception {
        String apkPath = args.length > 0 ? args[0]
                : "C:/Users/LENOVO/Desktop/照片读取/MIUIXCalculator.apk";
        String outPath = args.length > 1 ? args[1]
                : "C:/Users/LENOVO/Desktop/xml读取/tools/dev/miuix_injected.apk";
        File ks = new File("C:/Users/LENOVO/Desktop/xml读取/app/release.jks");
        List<String> perms = new ArrayList<>(Arrays.asList(
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
                "android.permission.INTERNET",
                "android.permission.WRITE_EXTERNAL_STORAGE"));
        File out = new File(outPath);
        File workDir = new File(out.getParentFile(), "inject_work_" + System.currentTimeMillis());
        PermInjector.Progress p = m -> System.out.println("[progress] " + m);
        PermInjector.inject(new File(apkPath), out, workDir, perms, ks,
                "apkreader", "ApkReader@2026".toCharArray(), "ApkReader@2026".toCharArray(),
                true, p);
        System.out.println("OUT: " + out.getAbsolutePath() + " size=" + out.length());
    }
}
