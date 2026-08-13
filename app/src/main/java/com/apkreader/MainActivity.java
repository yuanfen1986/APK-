package com.apkreader;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.apkreader.parser.AXmlParser;
import com.apkreader.parser.ArscParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 主界面：选择 APK -> 后台解码 AndroidManifest.xml 与 resources.arsc -> 跳转结果页。
 * 解码逻辑（AXmlParser / ArscParser）为纯 Java，与界面解耦。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PICK_APK = 1001;
    private static final long MAX_ENTRY_SIZE = 80L * 1024 * 1024; // 单文件上限 80MB，防内存溢出

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button btnPick;
    private TextView tvStatus;
    private ProgressDialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        btnPick = findViewById(R.id.btnPick);
        tvStatus = findViewById(R.id.tvStatus);
        btnPick.setOnClickListener(v -> pickApk());
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/zip", "application/octet-stream"});
        startActivityForResult(i, REQ_PICK_APK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK || resultCode != Activity.RESULT_OK) return;
        if (data == null || data.getData() == null) return;
        Uri uri = data.getData();
        startParse(uri, queryDisplayName(uri));
    }

    private String queryDisplayName(Uri uri) {
        String name = "app";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return (name == null || name.isEmpty()) ? "app" : name;
    }

    private void startParse(Uri uri, String apkName) {
        btnPick.setEnabled(false);
        tvStatus.setText("正在解码 " + apkName + " ...");
        dialog = new ProgressDialog(this);
        dialog.setMessage("正在解码 " + apkName + " ...");
        dialog.setCancelable(false);
        dialog.show();

        new Thread(() -> {
            // 按用户在设置页选择的语言/分辨率/版本过滤 arsc 输出
            ArscParser.ConfigFilter filter = SettingsActivity.loadFilter(this);
            File tmp = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("无法读取所选文件");

                // 复制到缓存文件再用 ZipFile 打开：ZipFile 走 zip 中央目录，能读到
                // LSPatch 壳等"后面追加原始 APK"的打包方式；ZipInputStream 顺序读
                // local header 会在这种文件上失步，漏掉 manifest / resources.arsc
                tmp = new File(getCacheDir(), "pick" + System.nanoTime() + ".apk");
                copyToFile(in, tmp);

                byte[] manifest = null;
                byte[] arsc = null;
                try (ZipFile zf = new ZipFile(tmp)) {
                    Enumeration<? extends ZipEntry> entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String n = entry.getName();
                        // 只认 APK 第一层（zip 根目录）的文件：条目名含路径分隔符
                        // （/ 或 \）说明在子目录里，一律忽略；存在重复条目时取第一个，不覆盖
                        if (n.indexOf('/') < 0 && n.indexOf('\\') < 0) {
                            if (manifest == null && "AndroidManifest.xml".equals(n)) manifest = readEntry(entry, zf.getInputStream(entry));
                            else if (arsc == null && "resources.arsc".equals(n)) arsc = readEntry(entry, zf.getInputStream(entry));
                        }
                    }
                }
                if (manifest == null) throw new Exception("APK 中未找到 AndroidManifest.xml");
                // 提取完毕，立即删掉临时 APK，避免残留缓存
                tmp.delete();
                tmp = null;

                // 先解 arsc 拿到资源名表，供 manifest 里的 @0x7f... 引用还原成 @string/xxx
                String arscText = "（APK 中未找到 resources.arsc）";
                java.util.Map<Integer, String> resNames = null;
                if (arsc != null) {
                    try {
                        ArscParser.Result r = new ArscParser().parseResult(arsc, filter);
                        arscText = r.text;
                        resNames = r.resNames;
                    } catch (Exception e) {
                        arscText = "resources.arsc 解析失败：" + e.getMessage();
                    }
                }

                final String manifestText;
                try {
                    manifestText = new AXmlParser(resNames).parse(manifest);
                } catch (Exception e) {
                    throw new Exception("AndroidManifest.xml 解析失败：" + e.getMessage());
                }

                final String arscOut = arscText;
                // 结果文本可能很大，写文件留在后台线程，避免主线程序列化大字符串卡顿
                File dir = new File(getCacheDir(), "parse");
                dir.mkdirs();
                File mf = new File(dir, "AndroidManifest.xml.txt");
                File af = new File(dir, "resources.arsc.txt");
                writeFile(mf, manifestText);
                writeFile(af, arscOut);
                handler.post(() -> launchResult(apkName, mf.getAbsolutePath(), af.getAbsolutePath()));
            } catch (Exception e) {
                String msg = e.getMessage();
                if (tmp != null) {
                    tmp.delete();
                    tmp = null;
                }
                handler.post(() -> {
                    dismissDialog();
                    btnPick.setEnabled(true);
                    tvStatus.setText("MIUI 风格 · 支持二进制 XML / 资源表解码");
                    Toast.makeText(this, "解析失败：" + msg, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** 把输入流完整复制到文件。 */
    private void copyToFile(InputStream in, File f) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            byte[] tmp = new byte[64 * 1024];
            int n;
            while ((n = in.read(tmp)) > 0) fos.write(tmp, 0, n);
        }
    }

    /** 读取 zip 内的单个条目到内存。 */
    private byte[] readEntry(ZipEntry entry, InputStream in) throws Exception {
        // 按条目声明大小预分配，避免大文件反复扩容拷贝；但声明大小不可信，
        // 必须按 MAX_ENTRY_SIZE 封顶，否则超大声明会在读数据前就 OOM
        long size = entry != null ? entry.getSize() : -1;
        int initial = (size > 0 && size < Integer.MAX_VALUE)
                ? (int) Math.min(size, MAX_ENTRY_SIZE) : 64 * 1024;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(initial, 1024));
        byte[] tmp = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(tmp)) > 0) {
            total += n;
            if (total > MAX_ENTRY_SIZE) throw new Exception("条目过大（超过 80MB），已停止");
            bos.write(tmp, 0, n);
        }
        return bos.toByteArray();
    }

    private void launchResult(String apkName, String manifestPath, String arscPath) {
        dismissDialog();
        btnPick.setEnabled(true);
        tvStatus.setText("MIUI 风格 · 支持二进制 XML / 资源表解码");
        Intent i = new Intent(this, ResultActivity.class);
        i.putExtra("apk_name", apkName);
        i.putExtra("manifest_path", manifestPath);
        i.putExtra("arsc_path", arscPath);
        startActivity(i);
    }

    private void writeFile(File f, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    private void dismissDialog() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }
}
