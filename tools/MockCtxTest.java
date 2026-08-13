import java.util.HashMap;
import java.util.Map;

/** MockCtx 冒烟测试：验证内存版 SharedPreferences 与未实现方法抛异常。 */
public class MockCtxTest {
    public static void main(String[] args) {
        MockCtx.clear();
        MockCtx ctx = new MockCtx();

        // 直接 put，再经 getSharedPreferences 读取
        MockCtx.put("parse_output_path", "/sdcard/APK解析工具");
        String p = ctx.getSharedPreferences("settings", 0).getString("parse_output_path", null);
        check(p.equals("/sdcard/APK解析工具"), "直接 put 后读取");

        // Editor 写入后读取
        ctx.getSharedPreferences("settings", 0).edit()
                .putString("lang_mode", "custom")
                .putBoolean("fix_sign", false)
                .apply();
        check(ctx.getSharedPreferences("settings", 0).getString("lang_mode", null).equals("custom"), "Editor.putString");
        check(!ctx.getSharedPreferences("settings", 0).getBoolean("fix_sign", true), "Editor.putBoolean");

        // 未实现方法应抛 UnsupportedOperationException
        try {
            ctx.getFilesDir();
            check(false, "getFilesDir 应抛异常");
        } catch (UnsupportedOperationException e) {
            check(true, "getFilesDir 抛 UnsupportedOperationException");
        }

        // 已实现的有意义方法
        check(ctx.getApplicationContext() == ctx, "getApplicationContext 返回自身");
        check(ctx.isDeviceProtectedStorage() == false, "isDeviceProtectedStorage 默认 false");
        check(!ctx.isDeviceProtectedStorage(), "isDeviceProtectedStorage false");

        System.out.println("ALL PASS");
    }

    private static void check(boolean cond, String name) {
        if (!cond) {
            System.out.println("FAIL: " + name);
            System.exit(1);
        }
        System.out.println("PASS: " + name);
    }
}
