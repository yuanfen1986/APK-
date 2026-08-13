package com.apkreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.apkreader.parser.ArscParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 输出设置页：选择解码 arsc 时保留的语言 / 分辨率 / 版本。
 * 选择结果存 SharedPreferences("settings")，解析时由
 * {@link #loadFilter(Context)} 转成 {@link ArscParser.ConfigFilter}。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS = "settings";
    private static final String KEY_LANG_MODE = "lang_mode";
    private static final String KEY_LANG_CUSTOM = "lang_custom";
    private static final String KEY_DENSITY_MODE = "density_mode";
    private static final String KEY_DENSITY_CUSTOM = "density_custom";
    private static final String KEY_VERSION_MODE = "version_mode";

    private static final String[] LANG_PRESETS = {
            "zh", "en", "ja", "ko", "hi", "my", "ar", "es", "fr", "de", "ru", "pt",
            "th", "vi", "in", "tr", "pl", "nl", "it", "sv", "fa", "uk", "ms", "tl"};
    private static final String[] DENSITY_PRESETS = {
            "ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "tvdpi", "sw600dp", "sw720dp"};

    private RadioGroup langGroup, densityGroup, versionGroup;
    private LinearLayout langCustomBox, densityCustomBox;
    private EditText langOther, densityOther;
    private final List<CheckBox> langChecks = new ArrayList<>();
    private final List<CheckBox> densityChecks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        langGroup = findViewById(R.id.langGroup);
        densityGroup = findViewById(R.id.densityGroup);
        versionGroup = findViewById(R.id.versionGroup);
        langCustomBox = findViewById(R.id.langCustomBox);
        densityCustomBox = findViewById(R.id.densityCustomBox);
        langOther = findViewById(R.id.langOther);
        densityOther = findViewById(R.id.densityOther);

        fillGrid(findViewById(R.id.langGrid), LANG_PRESETS, langChecks);
        fillGrid(findViewById(R.id.densityGrid), DENSITY_PRESETS, densityChecks);

        // 选中「自定义选择」时才展开复选框与输入框
        langGroup.setOnCheckedChangeListener((g, id) ->
                langCustomBox.setVisibility(id == R.id.radioLangCustom ? android.view.View.VISIBLE : android.view.View.GONE));
        densityGroup.setOnCheckedChangeListener((g, id) ->
                densityCustomBox.setVisibility(id == R.id.radioDensityCustom ? android.view.View.VISIBLE : android.view.View.GONE));

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        checkRadio(langGroup, R.id.radioLangAll, R.id.radioLangDefault, R.id.radioLangCustom,
                p.getString(KEY_LANG_MODE, "all"));
        checkRadio(densityGroup, R.id.radioDensityAll, R.id.radioDensityDefault, R.id.radioDensityCustom,
                p.getString(KEY_DENSITY_MODE, "all"));
        versionGroup.check("max".equals(p.getString(KEY_VERSION_MODE, "all"))
                ? R.id.radioVersionMax : R.id.radioVersionAll);
        applyCustom(p.getString(KEY_LANG_CUSTOM, ""), langChecks, langOther);
        applyCustom(p.getString(KEY_DENSITY_CUSTOM, ""), densityChecks, densityOther);

        Button btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {
            SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            e.putString(KEY_LANG_MODE, modeOf(langGroup, R.id.radioLangAll, R.id.radioLangDefault, R.id.radioLangCustom, "all"));
            e.putString(KEY_LANG_CUSTOM, collectCustom(langChecks, langOther));
            e.putString(KEY_DENSITY_MODE, modeOf(densityGroup, R.id.radioDensityAll, R.id.radioDensityDefault, R.id.radioDensityCustom, "all"));
            e.putString(KEY_DENSITY_CUSTOM, collectCustom(densityChecks, densityOther));
            e.putString(KEY_VERSION_MODE, versionGroup.getCheckedRadioButtonId() == R.id.radioVersionMax ? "max" : "all");
            e.apply();
            finish();
        });
    }

    /** 复选框网格：每行 3 个，代码填充避免手写几十个 id。 */
    private void fillGrid(LinearLayout container, String[] presets, List<CheckBox> out) {
        LinearLayout row = null;
        for (int i = 0; i < presets.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row);
            }
            CheckBox cb = new CheckBox(this);
            cb.setText(presets[i]);
            cb.setTextColor(ContextCompat.getColor(this, R.color.text_main));
            cb.setTextSize(13);
            row.addView(cb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            out.add(cb);
        }
    }

    /** 按存储的 mode 字符串勾选对应单选。 */
    private void checkRadio(RadioGroup g, int all, int def, int custom, String mode) {
        int id = all;
        if ("default".equals(mode)) id = def;
        else if ("custom".equals(mode)) id = custom;
        g.check(id);
    }

    private String modeOf(RadioGroup g, int all, int def, int custom, String fallback) {
        int id = g.getCheckedRadioButtonId();
        if (id == def) return "default";
        if (id == custom) return "custom";
        return "all";
    }

    /** 自定义值 = 勾选的预设 ∪ 自由输入解析。 */
    private static String collectCustom(List<CheckBox> checks, EditText other) {
        Set<String> set = new HashSet<>();
        for (CheckBox cb : checks) if (cb.isChecked()) set.add(cb.getText().toString().trim());
        set.addAll(parseCustom(other.getText().toString()));
        return TextUtils.join(",", set);
    }

    /** 把勾选项映射回界面：预设命中的打勾，其余填入输入框。 */
    private static void applyCustom(String stored, List<CheckBox> checks, EditText other) {
        Set<String> tokens = parseCustom(stored);
        List<String> extra = new ArrayList<>();
        for (CheckBox cb : checks) {
            if (tokens.remove(cb.getText().toString())) cb.setChecked(true);
            else cb.setChecked(false);
        }
        for (String t : tokens) extra.add(t);
        other.setText(TextUtils.join(",", extra));
    }

    /**
     * 解析逗号分隔的限定符：转小写、去空、去重；带配置后缀的取基础码
     * （zh-rCN -> zh，便于与类型块的基础语言码匹配）。
     */
    static Set<String> parseCustom(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String t : s.split(",")) {
            t = t.trim().toLowerCase();
            if (t.isEmpty()) continue;
            int dash = t.indexOf('-');
            if (dash > 0) t = t.substring(0, dash);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 读取保存的设置，构建解析过滤条件；无设置时默认全部显示。 */
    public static ArscParser.ConfigFilter loadFilter(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ArscParser.ConfigFilter f = new ArscParser.ConfigFilter();
        String langMode = p.getString(KEY_LANG_MODE, "all");
        if ("default".equals(langMode)) {
            f.langMode = ArscParser.ConfigFilter.LANG_DEFAULT;
        } else if ("custom".equals(langMode)) {
            f.langMode = ArscParser.ConfigFilter.LANG_CUSTOM;
            f.langSel.addAll(parseCustom(p.getString(KEY_LANG_CUSTOM, "")));
        }
        String denMode = p.getString(KEY_DENSITY_MODE, "all");
        if ("default".equals(denMode)) {
            f.densityMode = ArscParser.ConfigFilter.DENSITY_DEFAULT;
        } else if ("custom".equals(denMode)) {
            f.densityMode = ArscParser.ConfigFilter.DENSITY_CUSTOM;
            f.densitySel.addAll(parseCustom(p.getString(KEY_DENSITY_CUSTOM, "")));
        }
        if ("max".equals(p.getString(KEY_VERSION_MODE, "all"))) {
            f.versionMode = ArscParser.ConfigFilter.VERSION_MAX;
        }
        return f;
    }
}
