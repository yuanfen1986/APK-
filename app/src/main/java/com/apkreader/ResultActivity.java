package com.apkreader;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 结果页：双 Tab（AndroidManifest / resources.arsc）查看解码文本，
 * 支持保存 TXT 到下载目录与分享文本。
 */
public class ResultActivity extends AppCompatActivity {

    private TextView tvTitle;
    private TextView tvContent;
    private TextView tabManifest;
    private TextView tabArsc;
    private Button btnSave;
    private Button btnShare;
    private ScrollView scroll;

    private String manifestText;
    private String arscText;
    private String apkName;
    private boolean showingManifest = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        tvTitle = findViewById(R.id.tvTitle);
        tvContent = findViewById(R.id.tvContent);
        tabManifest = findViewById(R.id.tabManifest);
        tabArsc = findViewById(R.id.tabArsc);
        btnSave = findViewById(R.id.btnSave);
        btnShare = findViewById(R.id.btnShare);
        scroll = findViewById(R.id.scroll);

        apkName = getIntent().getStringExtra("apk_name");
        tvTitle.setText(apkName == null ? "解析结果" : apkName);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        tabManifest.setOnClickListener(v -> {
            showingManifest = true;
            refresh();
        });
        tabArsc.setOnClickListener(v -> {
            showingManifest = false;
            refresh();
        });
        btnSave.setOnClickListener(v -> saveTxt());
        btnShare.setOnClickListener(v -> shareTxt());
        btnSave.setEnabled(false);
        btnShare.setEnabled(false);

        // 大文件在后台线程读取+解码，避免主线程卡死
        tvContent.setText("正在加载...");
        new Thread(() -> {
            final String m = readFile(getIntent().getStringExtra("manifest_path"));
            final String a = readFile(getIntent().getStringExtra("arsc_path"));
            new Handler(Looper.getMainLooper()).post(() -> {
                manifestText = m;
                arscText = a;
                btnSave.setEnabled(true);
                btnShare.setEnabled(true);
                refresh();
            });
        }).start();
    }

    /** 超长文本只显示开头部分，避免 TextView 渲染超大字符串卡死；完整内容可保存/分享。 */
    private static final int MAX_DISPLAY_CHARS = 2_000_000;

    private void refresh() {
        if (manifestText == null || arscText == null) return;
        tabManifest.setBackgroundResource(showingManifest ? R.drawable.tab_active : R.drawable.tab_inactive);
        tabArsc.setBackgroundResource(showingManifest ? R.drawable.tab_inactive : R.drawable.tab_active);
        int blue = ContextCompat.getColor(this, R.color.blue);
        int sub = ContextCompat.getColor(this, R.color.text_sub);
        tabManifest.setTextColor(showingManifest ? blue : sub);
        tabArsc.setTextColor(showingManifest ? sub : blue);
        tvContent.setText(showingManifest ? displayText(manifestText) : displayText(arscText));
        scroll.scrollTo(0, 0);
    }

    private static String displayText(String full) {
        if (full == null) return "";
        if (full.length() <= MAX_DISPLAY_CHARS) return full;
        return full.substring(0, MAX_DISPLAY_CHARS)
                + "\n\n……（内容过长，仅显示前 " + MAX_DISPLAY_CHARS + " 字符，完整内容请保存或分享）";
    }

    private String readFile(String path) {
        if (path == null) return "";
        try (FileInputStream fis = new FileInputStream(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = fis.read(b)) > 0) bos.write(b, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "读取失败: " + e.getMessage();
        }
    }

    private void saveTxt() {
        String text = showingManifest ? manifestText : arscText;
        String base = showingManifest ? "AndroidManifest" : "resources_arsc";
        String fileName = base + "_" + safeName(apkName) + ".txt";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/APK解析工具");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("无法创建文件");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) os.write(text.getBytes(StandardCharsets.UTF_8));
                }
                Toast.makeText(this, "已保存到 下载/APK解析工具/" + fileName, Toast.LENGTH_LONG).show();
            } else {
                // 旧系统写入应用专属目录，无需存储权限
                File dir = new File(getExternalFilesDir(null), "exports");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                }
                Toast.makeText(this, "已保存到 " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareTxt() {
        String text = showingManifest ? manifestText : arscText;
        if (text == null) {
            Toast.makeText(this, "内容尚未加载完成", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // 大文本走 EXTRA_TEXT 会超过 Binder 事务上限崩溃，改为写缓存文件经 FileProvider 分享
            File dir = new File(getCacheDir(), "share");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "share.txt");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, (apkName == null ? "APK" : apkName)
                    + " - " + (showingManifest ? "AndroidManifest" : "resources.arsc"));
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享文本"));
        } catch (Exception e) {
            Toast.makeText(this, "分享失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String safeName(String s) {
        if (s == null || s.isEmpty()) return "app";
        String r = s.replaceAll("[^\\w\\-.]", "_");
        return r.isEmpty() ? "app" : r;
    }
}
