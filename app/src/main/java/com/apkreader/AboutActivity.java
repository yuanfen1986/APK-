package com.apkreader;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 关于页：展示应用信息、作者与开源项目地址。
 * 点击项目地址调用系统浏览器打开 GitHub 仓库。
 */
public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        String version = "1.0";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        ((TextView) findViewById(R.id.tvVersion)).setText("版本 " + version);

        findViewById(R.id.rowRepo).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(getString(R.string.about_repo_url))));
            } catch (Exception e) {
                // 系统无可用浏览器时静默失败，不打扰用户
            }
        });
    }
}
