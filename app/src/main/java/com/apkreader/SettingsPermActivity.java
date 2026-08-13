package com.apkreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Switch;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 权限添加设置页：勾选要写入目标 APK 的权限（默认全选）、是否重新签名打包、
 * 修改结果的输出路径。与其它设置页共用 SharedPreferences("settings")，
 * 输出路径未单独配置时跟随资源解析的路径。
 */
public class SettingsPermActivity extends AppCompatActivity {

    private static final String PREFS = "settings";
    private static final String KEY_PERM_LIST = "perm_list";
    private static final String KEY_PERM_SIGN = "perm_sign";
    private static final String KEY_PERM_OUTPUT_PATH = "perm_output_path";

    /** 全部可选权限（顺序即界面勾选顺序），MANAGE_EXTERNAL_STORAGE 走系统设置页单独授权。 */
    static final String[] ALL_PERMS = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.INTERNET",
            "android.permission.WRITE_EXTERNAL_STORAGE",
    };

    private final CheckBox[] permChecks = new CheckBox[ALL_PERMS.length];
    private Switch permSignSwitch;
    private EditText permPathInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_perm_settings);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        int[] ids = {R.id.cbReadExternal, R.id.cbReadMediaImages, R.id.cbManageExternal,
                R.id.cbInternet, R.id.cbWriteExternal};
        for (int i = 0; i < ids.length; i++) permChecks[i] = findViewById(ids[i]);
        permSignSwitch = findViewById(R.id.permSignSwitch);
        permPathInput = findViewById(R.id.permPathInput);

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        // 已勾选集合（逗号分隔），未配置过时默认全选
        List<String> selected = loadPermList(this);
        for (int i = 0; i < ALL_PERMS.length; i++) {
            permChecks[i].setChecked(selected.contains(ALL_PERMS[i]));
        }
        permSignSwitch.setChecked(p.getBoolean(KEY_PERM_SIGN, true));
        permPathInput.setText(p.getString(KEY_PERM_OUTPUT_PATH, SettingsActivity.loadParseOutputPath(this)));

        Button btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ALL_PERMS.length; i++) {
                if (permChecks[i].isChecked()) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(ALL_PERMS[i]);
                }
            }
            SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            e.putString(KEY_PERM_LIST, sb.toString());
            e.putBoolean(KEY_PERM_SIGN, permSignSwitch.isChecked());
            e.putString(KEY_PERM_OUTPUT_PATH,
                    SettingsActivity.sanitizePath(permPathInput.getText().toString(),
                            SettingsActivity.DEFAULT_OUTPUT_PATH));
            e.apply();
            finish();
        });
    }

    /** 读取要添加的权限列表；未配置过或为空时默认全选。 */
    public static List<String> loadPermList(Context ctx) {
        String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PERM_LIST, null);
        if (s == null || s.trim().isEmpty()) return new ArrayList<>(Arrays.asList(ALL_PERMS));
        List<String> out = new ArrayList<>();
        for (String t : s.split(",")) {
            t = t.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** 修改后是否重新签名打包，默认 true。 */
    public static boolean loadPermSign(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PERM_SIGN, true);
    }

    /** 权限添加结果的保存目录：未单独配置时跟随资源解析的输出路径。 */
    public static String loadPermOutputPath(Context ctx) {
        String stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PERM_OUTPUT_PATH, null);
        if (stored == null) return SettingsActivity.loadParseOutputPath(ctx);
        return SettingsActivity.absolutePath(SettingsActivity.sanitizePath(stored, SettingsActivity.DEFAULT_OUTPUT_PATH));
    }
}
