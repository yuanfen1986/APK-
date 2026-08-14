import com.apkreader.SettingsActivity;
import com.apkreader.SettingsPermActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SettingsPermActivity 保存/加载回环回归（纯 JVM，用 MockCtx 的内存 SharedPreferences）：
 * 复刻 btnSave 里「ALL_PERMS 勾选 -> 逗号串 -> putString」的拼接逻辑，
 * 校验 loadPermList/loadPermSign/loadPermOutputPath 能按保存值取回。
 *
 * 曾因 loadPermList 把「显式保存的空列表」与「从未保存」混为一谈（都返回默认全选），
 * 用户取消全部权限后保存，再进设置页勾选全回来了、操作仍把全部权限加进 APK ——
 * 即「保存设置没有作用」。本测试第 4 组锁死该现场：空选择必须保持为空。
 */
public class PermSettingsTest {

    /** 与 SettingsPermActivity.ALL_PERMS 顺序一致的镜像（该常量包私有，测试不可见）。 */
    static final String[] PERMS = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.INTERNET",
            "android.permission.WRITE_EXTERNAL_STORAGE",
    };

    static int fails = 0;

    static void check(boolean cond, String msg) {
        System.out.println((cond ? "PASS: " : "FAIL: ") + msg);
        if (!cond) fails++;
    }

    /** 复刻 SettingsPermActivity.btnSave 的勾选 -> 逗号串 拼接。 */
    static String joinSelected(boolean[] checked) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PERMS.length; i++) {
            if (checked[i]) {
                if (sb.length() > 0) sb.append(',');
                sb.append(PERMS[i]);
            }
        }
        return sb.toString();
    }

    /** 复刻 btnSave 的写入：perm_list / perm_sign / perm_output_path 一条 apply。 */
    static void save(boolean[] checked, boolean sign, String path) {
        MockCtx ctx = new MockCtx();
        ctx.getSharedPreferences("settings", 0).edit()
                .putString("perm_list", joinSelected(checked))
                .putBoolean("perm_sign", sign)
                .putString("perm_output_path", path)
                .apply();
    }

    static List<String> list(String... a) {
        return new ArrayList<>(Arrays.asList(a));
    }

    public static void main(String[] args) {
        MockCtx ctx = new MockCtx();

        // 1) 从未保存：默认全选
        MockCtx.clear();
        List<String> all = SettingsPermActivity.loadPermList(ctx);
        check(all.size() == PERMS.length && all.containsAll(Arrays.asList(PERMS)),
                "从未保存 -> 默认全选 (" + all.size() + " 项)");

        // 2) 部分勾选回环：只勾 INTERNET + WRITE_EXTERNAL_STORAGE
        MockCtx.clear();
        save(new boolean[]{false, false, false, true, true}, true, "/sdcard/APK解析工具");
        List<String> partial = SettingsPermActivity.loadPermList(ctx);
        check(partial.equals(list(PERMS[3], PERMS[4])), "部分勾选回环: " + partial);

        // 3) 全选回环
        MockCtx.clear();
        save(new boolean[]{true, true, true, true, true}, true, "/sdcard/APK解析工具");
        List<String> full = SettingsPermActivity.loadPermList(ctx);
        check(full.size() == PERMS.length, "全选回环 (" + full.size() + " 项)");

        // 4) 取消全部后保存（perm_list=""）：必须返回空列表 —— 修复点
        MockCtx.clear();
        save(new boolean[]{false, false, false, false, false}, true, "/sdcard/APK解析工具");
        List<String> none = SettingsPermActivity.loadPermList(ctx);
        check(none.isEmpty(), "取消全部后保存 -> 空列表（修复前误回全选: " + none.size() + " 项）");

        // 5) perm_sign 回环
        MockCtx.clear();
        save(new boolean[]{false, false, false, false, false}, false, "/sdcard/APK解析工具");
        check(!SettingsPermActivity.loadPermSign(ctx), "perm_sign=false 保存后读取为 false");
        MockCtx.clear();
        check(SettingsPermActivity.loadPermSign(ctx), "从未保存 perm_sign 默认 true");

        // 6) perm_output_path：未单独配置时跟随资源解析路径
        MockCtx.clear();
        MockCtx.put("parse_output_path", "/sdcard/解析目录");
        check("/sdcard/解析目录".equals(SettingsPermActivity.loadPermOutputPath(ctx)),
                "perm_output_path 未保存 -> 跟随解析路径");

        // 7) perm_output_path：单独保存后以其为准
        MockCtx.clear();
        MockCtx.put("parse_output_path", "/sdcard/解析目录");
        save(new boolean[]{false, false, false, false, false}, true, "/sdcard/权限输出");
        check("/sdcard/权限输出".equals(SettingsPermActivity.loadPermOutputPath(ctx)),
                "perm_output_path 已保存 -> 以保存值为准");

        // 8) 无路径（空串）输入按默认值落盘：sanitizePath("") -> DEFAULT_OUTPUT_PATH
        MockCtx.clear();
        MockCtx.put("parse_output_path", "/sdcard/解析目录");
        save(new boolean[]{false, false, false, false, false}, true, "");
        check(SettingsActivity.DEFAULT_OUTPUT_PATH.equals(
                        SettingsPermActivity.loadPermOutputPath(ctx)),
                "perm_output_path 存空串 -> 回退默认目录");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
