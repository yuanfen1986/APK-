package com.apkreader;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.nio.charset.StandardCharsets;

/**
 * 结果页：双 Tab（AndroidManifest / resources.arsc）查看解码文本，
 * 支持保存 TXT 到设置的输出目录与分享文本。
 */
public class ResultActivity extends AppCompatActivity {

    private TextView tvTitle;
    private TextView tvContent;
    private TextView tabManifest;
    private TextView tabArsc;
    private Button btnSave;
    private Button btnShare;
    private ScrollView scroll;

    private static final int REQ_PERM_STORAGE = 3001;
    private static final int REQ_MANAGE_STORAGE = 3002;

    private String manifestText;
    private String arscText;
    private String apkName;
    private boolean showingManifest = true;
    /** 用户点了保存但缺存储权限，授权后自动补一次保存。 */
    private boolean pendingSave;
    /** 保存/分享写盘在后台线程进行，完成结果经主线程 Handler 回 UI；忙标志防止重复点击。 */
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean saving;
    private boolean sharing;

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

        // 日志模式（如修复失败的错误日志）：单 Tab 展示，隐藏第二个 Tab
        if ("log".equals(getIntent().getStringExtra("mode"))) {
            tabManifest.setText("错误日志");
            tabArsc.setVisibility(View.GONE);
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        tabManifest.setOnClickListener(v -> {
            showingManifest = true;
            refresh();
        });
        tabArsc.setOnClickListener(v -> {
            showingManifest = false;
            refresh();
        });
        btnSave.setOnClickListener(v -> {
            // 保存到任意目录需存储权限；没有则先申请，授权后自动保存
            if (!PermissionHelper.hasStoragePermission(this)) {
                pendingSave = true;
                PermissionHelper.requestStoragePermission(this, REQ_PERM_STORAGE, REQ_MANAGE_STORAGE);
            } else {
                saveTxt();
            }
        });
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

    /** 直接写文件到设置的绝对路径目录（调用前需已授予存储权限）；几十 MB 文本在后台线程写盘。 */
    private void saveTxt() {
        if (saving) return;
        String text = showingManifest ? manifestText : arscText;
        if (text == null) {
            Toast.makeText(this, "内容尚未加载完成", Toast.LENGTH_SHORT).show();
            return;
        }
        String base = showingManifest ? "AndroidManifest" : "resources_arsc";
        final String fileName = base + "_" + safeName(apkName) + ".txt";
        final File dir = new File(SettingsActivity.loadParseOutputPath(this));
        final String payload = text;
        saving = true;
        btnSave.setEnabled(false);
        new Thread(() -> {
            String msg;
            try {
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录 " + dir);
                File f = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(payload.getBytes(StandardCharsets.UTF_8));
                }
                msg = "已保存到 " + f.getAbsolutePath();
            } catch (Exception e) {
                msg = "保存失败：" + e.getMessage();
            }
            final String result = msg;
            handler.post(() -> {
                saving = false;
                btnSave.setEnabled(true);
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && pendingSave) {
                pendingSave = false;
                saveTxt();
            } else {
                Toast.makeText(this, "需要存储权限才能保存 TXT", Toast.LENGTH_LONG).show();
            }
            pendingSave = false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 「所有文件访问」设置页返回：data 可能为 null，直接按权限状态判断
        if (requestCode == REQ_MANAGE_STORAGE) {
            if (pendingSave && PermissionHelper.hasStoragePermission(this)) {
                pendingSave = false;
                saveTxt();
            } else if (pendingSave) {
                Toast.makeText(this, "未授予存储权限，无法保存 TXT", Toast.LENGTH_LONG).show();
            }
            pendingSave = false;
        }
    }

    private void shareTxt() {
        if (sharing) return;
        String text = showingManifest ? manifestText : arscText;
        if (text == null) {
            Toast.makeText(this, "内容尚未加载完成", Toast.LENGTH_SHORT).show();
            return;
        }
        final String payload = text;
        final String subject = (apkName == null ? "APK" : apkName)
                + " - " + (showingManifest ? "AndroidManifest" : "resources.arsc");
        sharing = true;
        btnShare.setEnabled(false);
        new Thread(() -> {
            Uri uri = null;
            String err = null;
            try {
                // 大文本走 EXTRA_TEXT 会超过 Binder 事务上限崩溃，改为写缓存文件经 FileProvider 分享
                File dir = new File(getCacheDir(), "share");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "share.txt");
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(payload.getBytes(StandardCharsets.UTF_8));
                }
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            } catch (Exception e) {
                err = "分享失败：" + e.getMessage();
            }
            final Uri fUri = uri;
            final String fErr = err;
            handler.post(() -> {
                sharing = false;
                btnShare.setEnabled(true);
                if (fErr != null) {
                    Toast.makeText(this, fErr, Toast.LENGTH_LONG).show();
                    return;
                }
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, subject);
                i.putExtra(Intent.EXTRA_STREAM, fUri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "分享文本"));
            });
        }).start();
    }

    private String safeName(String s) {
        if (s == null || s.isEmpty()) return "app";
        String r = s.replaceAll("[^\\w\\-.]", "_");
        return r.isEmpty() ? "app" : r;
    }
}
