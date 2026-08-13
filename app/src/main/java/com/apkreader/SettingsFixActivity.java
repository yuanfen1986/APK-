package com.apkreader;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 360 正则修复设置页：修复后是否重新签名打包，以及修复结果的输出路径。
 * 与资源解析设置（{@link SettingsActivity}）共用 SharedPreferences("settings")，
 * 输出路径未单独配置时跟随资源解析的路径，保存后可各自独立配置。
 */
public class SettingsFixActivity extends AppCompatActivity {

    private static final String PREFS = "settings";
    private static final String KEY_FIX_SIGN = "fix_sign";
    private static final String KEY_FIX_OUTPUT_PATH = "fix_output_path";

    private Switch fixSignSwitch;
    private EditText fixPathInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fix_settings);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        fixSignSwitch = findViewById(R.id.fixSignSwitch);
        fixPathInput = findViewById(R.id.fixPathInput);

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        fixSignSwitch.setChecked(p.getBoolean(KEY_FIX_SIGN, true));
        // 未单独配置过时，默认显示资源解析的输出路径，保证两处默认一致
        fixPathInput.setText(p.getString(KEY_FIX_OUTPUT_PATH, SettingsActivity.loadParseOutputPath(this)));

        Button btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {
            SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            e.putBoolean(KEY_FIX_SIGN, fixSignSwitch.isChecked());
            e.putString(KEY_FIX_OUTPUT_PATH,
                    SettingsActivity.sanitizePath(fixPathInput.getText().toString(),
                            SettingsActivity.DEFAULT_OUTPUT_PATH));
            e.apply();
            finish();
        });
    }
}
