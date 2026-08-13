package com.apkreader;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.apkreader.fixer.DexFixer;
import com.apkreader.fixer.PermInjector;
import com.apkreader.parser.AXmlParser;
import com.apkreader.parser.ArscParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 主界面：底部三 tab（资源解析 / 360 正则修复 / 权限添加）。
 * Tab1 选择 APK -> 后台解码 AndroidManifest.xml 与 resources.arsc -> 跳转结果页；
 * Tab2 选择 APK -> 后台反汇编清理 360 加固代码 -> 重打包签名 -> 自动保存到设置的输出路径；
 * Tab3 选择 APK -> 后台向 AndroidManifest.xml 追加权限并注入运行时申请代码 -> 重打包签名 -> 自动保存。
 * 解码逻辑（AXmlParser / ArscParser）与修复逻辑（DexFixer / PermInjector）均为纯 Java，与界面解耦。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PICK_APK = 1001;
    private static final int REQ_PICK_FIX = 1002;
    private static final int REQ_PICK_PERM = 1003;
    private static final int REQ_PERM_STORAGE = 2001;
    private static final int REQ_MANAGE_STORAGE = 2002;
    private static final long MAX_ENTRY_SIZE = 80L * 1024 * 1024; // 单文件上限 80MB，防内存溢出

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button btnPick;
    private TextView tvStatus;
    private Button btnFixPick;
    private TextView tvFixStatus;
    private Button btnPermPick;
    private TextView tvPermStatus;
    private ProgressDialog dialog;

    /** 当前激活的 tab：0=资源解析 / 1=360 正则修复 / 2=权限添加；FAB 据此决定打开哪个设置页。 */
    private int tab = 0;
    // 360 修复前因缺少存储权限被挂起的选择，授权后恢复
    private Uri pendingFixUri;
    private String pendingFixName;
    // 权限添加前因缺少存储权限被挂起的选择，授权后恢复
    private Uri pendingPermUri;
    private String pendingPermName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        btnPick = findViewById(R.id.btnPick);
        tvStatus = findViewById(R.id.tvStatus);
        btnFixPick = findViewById(R.id.btnFixPick);
        tvFixStatus = findViewById(R.id.tvFixStatus);
        btnPermPick = findViewById(R.id.btnPermPick);
        tvPermStatus = findViewById(R.id.tvPermStatus);
        btnPick.setOnClickListener(v -> pickApk());
        btnFixPick.setOnClickListener(v -> pickFixApk());
        btnPermPick.setOnClickListener(v -> pickPermApk());
        findViewById(R.id.btnAbout).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
        findViewById(R.id.btnSettingsFAB).setOnClickListener(v -> {
            Class<?> target = tab == 0 ? SettingsActivity.class
                    : tab == 1 ? SettingsFixActivity.class : SettingsPermActivity.class;
            startActivity(new Intent(this, target));
        });
        findViewById(R.id.tabParse).setOnClickListener(v -> switchTab(0));
        findViewById(R.id.tabFix).setOnClickListener(v -> switchTab(1));
        findViewById(R.id.tabPerm).setOnClickListener(v -> switchTab(2));

        // 启动即检查存储权限：解析/修复结果要写入任意目录，缺权限先申请，保证输出路径可用
        if (!PermissionHelper.hasStoragePermission(this)) {
            PermissionHelper.requestStoragePermission(this, REQ_PERM_STORAGE, REQ_MANAGE_STORAGE);
        }
    }

    /** 切换底部 tab：页面显隐 + 选中高亮。 */
    private void switchTab(int tab) {
        this.tab = tab;
        findViewById(R.id.pageParse).setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.pageFix).setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.pagePerm).setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.tabParse).setBackgroundResource(tab == 0 ? R.drawable.tab_active : R.drawable.tab_inactive);
        findViewById(R.id.tabFix).setBackgroundResource(tab == 1 ? R.drawable.tab_active : R.drawable.tab_inactive);
        findViewById(R.id.tabPerm).setBackgroundResource(tab == 2 ? R.drawable.tab_active : R.drawable.tab_inactive);
        setTabText(R.id.tvTabParse, tab == 0);
        setTabText(R.id.tvTabFix, tab == 1);
        setTabText(R.id.tvTabPerm, tab == 2);
    }

    private void setTabText(int id, boolean active) {
        TextView tv = findViewById(id);
        tv.setTextColor(getColor(active ? R.color.text_main : R.color.text_sub));
        tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/zip", "application/octet-stream"});
        startActivityForResult(i, REQ_PICK_APK);
    }

    private void pickFixApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/zip", "application/octet-stream"});
        startActivityForResult(i, REQ_PICK_FIX);
    }

    private void pickPermApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/zip", "application/octet-stream"});
        startActivityForResult(i, REQ_PICK_PERM);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 「所有文件访问」设置页返回：data 可能为 null，必须在下面早退判断之前处理
        if (requestCode == REQ_MANAGE_STORAGE) {
            boolean granted = PermissionHelper.hasStoragePermission(this);
            if (pendingFixUri != null) {
                if (granted) resumePendingFix();
                else Toast.makeText(this, "未授予存储权限，无法保存修复结果", Toast.LENGTH_LONG).show();
            }
            if (pendingPermUri != null) {
                if (granted) resumePendingPerm();
                else Toast.makeText(this, "未授予存储权限，无法保存修改结果", Toast.LENGTH_LONG).show();
            }
            pendingFixUri = null;
            pendingFixName = null;
            pendingPermUri = null;
            pendingPermName = null;
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_APK) {
            startParse(uri, queryDisplayName(uri));
        } else if (requestCode == REQ_PICK_FIX) {
            startFix(uri, queryDisplayName(uri));
        } else if (requestCode == REQ_PICK_PERM) {
            startPerm(uri, queryDisplayName(uri));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_STORAGE) {
            // 启动时也会走这里，只有流程被挂起时才弹失败提示，避免启动误报
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingFixUri != null) {
                if (granted) resumePendingFix();
                else Toast.makeText(this, "需要存储权限才能保存修复结果", Toast.LENGTH_LONG).show();
            }
            if (pendingPermUri != null) {
                if (granted) resumePendingPerm();
                else Toast.makeText(this, "需要存储权限才能保存修改结果", Toast.LENGTH_LONG).show();
            }
            pendingFixUri = null;
            pendingFixName = null;
            pendingPermUri = null;
            pendingPermName = null;
        }
    }

    /** 存储权限授权后，恢复被挂起的 360 修复流程。 */
    private void resumePendingFix() {
        if (pendingFixUri == null) return;
        Uri uri = pendingFixUri;
        String name = pendingFixName;
        pendingFixUri = null;
        pendingFixName = null;
        startFix(uri, name);
    }

    /** 存储权限授权后，恢复被挂起的权限添加流程。 */
    private void resumePendingPerm() {
        if (pendingPermUri == null) return;
        Uri uri = pendingPermUri;
        String name = pendingPermName;
        pendingPermUri = null;
        pendingPermName = null;
        startPerm(uri, name);
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

    // ---------- Tab1：资源解析 ----------

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

    // ---------- Tab2：360 正则修复 ----------

    private void startFix(Uri uri, String apkName) {
        // 修复结果要写任意目录，先确认存储权限；没有则挂起流程，授权后自动继续
        if (!PermissionHelper.hasStoragePermission(this)) {
            pendingFixUri = uri;
            pendingFixName = apkName;
            PermissionHelper.requestStoragePermission(this, REQ_PERM_STORAGE, REQ_MANAGE_STORAGE);
            return;
        }
        btnFixPick.setEnabled(false);
        tvFixStatus.setText("正在处理 " + apkName + " ...");
        final boolean sign = SettingsActivity.loadFixSign(this);
        dialog = new ProgressDialog(this);
        dialog.setMessage("准备中...");
        dialog.setCancelable(false);
        dialog.show();

        new Thread(() -> {
            File work = new File(getCacheDir(), "fixwork");
            File out = new File(getCacheDir(), "fixed" + System.nanoTime() + ".apk");
            File tmp = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("无法读取所选文件");
                tmp = new File(getCacheDir(), "pickfix" + System.nanoTime() + ".apk");
                copyToFile(in, tmp);

                // 内置签名密钥复制到缓存（密钥随应用分发，见 .gitignore 注释）
                File ks = new File(getCacheDir(), "release.jks");
                try (InputStream kin = getAssets().open("release.jks");
                     FileOutputStream kfos = new FileOutputStream(ks)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = kin.read(buf)) > 0) kfos.write(buf, 0, n);
                }

                DexFixer.fix(tmp, out, work, ks, "apkreader",
                        "ApkReader@2026".toCharArray(), "ApkReader@2026".toCharArray(),
                        sign,
                        msg -> handler.post(() -> {
                            if (dialog != null) dialog.setMessage(msg);
                        }));

                tmp.delete();
                ks.delete();
                handler.post(() -> {
                    dismissDialog();
                    tvFixStatus.setText("修复完成，正在保存...");
                    saveFixedApkAuto(apkName, out, sign);
                });
            } catch (Exception e) {
                deleteRecursive(work);
                if (tmp != null) tmp.delete();
                final Exception fe = e;
                handler.post(() -> {
                    dismissDialog();
                    btnFixPick.setEnabled(true);
                    tvFixStatus.setText("本地处理 · 修复后自动保存");
                    // 完整错误（含 smali/baksmali 详细输出与堆栈）写入与资源解析同目录的日志文件，
                    // 再打开结果页展示，方便查看具体失败原因；toast 只显示开头一段
                    File dir = new File(getCacheDir(), "parse");
                    dir.mkdirs();
                    File lf = new File(dir, "fix_error.log");
                    try {
                        writeFile(lf, buildFixError(fe));
                    } catch (Exception ignored) {
                    }
                    Toast.makeText(this, "修复失败：" + truncate(fe.getMessage(), 200), Toast.LENGTH_LONG).show();
                    Intent i = new Intent(this, ResultActivity.class);
                    i.putExtra("apk_name", apkName + " - 修复失败");
                    i.putExtra("manifest_path", lf.getAbsolutePath());
                    i.putExtra("arsc_path", "");
                    i.putExtra("mode", "log");
                    startActivity(i);
                });
            }
        }).start();
    }

    /** 修复完成后按设置的输出路径直接写文件（调用前需已授予存储权限）。 */
    private void saveFixedApkAuto(String apkName, File out, boolean sign) {
        String base = apkName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        final String fileName = base + (sign ? "-fixed.apk" : "-unsigned.apk");
        final String folder = SettingsActivity.loadFixOutputPath(this);
        new Thread(() -> {
            final String savedTo;
            try {
                File dir = new File(folder);
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录 " + folder);
                File target = new File(dir, fileName);
                copyToFile(new FileInputStream(out), target);
                savedTo = target.getAbsolutePath();
            } catch (Exception e) {
                handler.post(() -> {
                    btnFixPick.setEnabled(true);
                    tvFixStatus.setText("本地处理 · 修复后自动保存");
                    Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
                return;
            }
            out.delete();
            handler.post(() -> {
                btnFixPick.setEnabled(true);
                tvFixStatus.setText("本地处理 · 修复后自动保存");
                Toast.makeText(this, "修复成功，已保存到 " + savedTo, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    // ---------- Tab3：权限添加 ----------

    private void startPerm(Uri uri, String apkName) {
        // 修改结果要写任意目录，先确认存储权限；没有则挂起流程，授权后自动继续
        if (!PermissionHelper.hasStoragePermission(this)) {
            pendingPermUri = uri;
            pendingPermName = apkName;
            PermissionHelper.requestStoragePermission(this, REQ_PERM_STORAGE, REQ_MANAGE_STORAGE);
            return;
        }
        btnPermPick.setEnabled(false);
        tvPermStatus.setText("正在处理 " + apkName + " ...");
        final boolean sign = SettingsPermActivity.loadPermSign(this);
        final List<String> perms = SettingsPermActivity.loadPermList(this);
        dialog = new ProgressDialog(this);
        dialog.setMessage("准备中...");
        dialog.setCancelable(false);
        dialog.show();

        new Thread(() -> {
            File work = new File(getCacheDir(), "permwork");
            File out = new File(getCacheDir(), "perm" + System.nanoTime() + ".apk");
            File tmp = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("无法读取所选文件");
                tmp = new File(getCacheDir(), "pickperm" + System.nanoTime() + ".apk");
                copyToFile(in, tmp);

                // 内置签名密钥复制到缓存（与 360 修复共用 release.jks）
                File ks = new File(getCacheDir(), "release.jks");
                try (InputStream kin = getAssets().open("release.jks");
                     FileOutputStream kfos = new FileOutputStream(ks)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = kin.read(buf)) > 0) kfos.write(buf, 0, n);
                }

                PermInjector.inject(tmp, out, work, perms, ks, "apkreader",
                        "ApkReader@2026".toCharArray(), "ApkReader@2026".toCharArray(),
                        sign,
                        msg -> handler.post(() -> {
                            if (dialog != null) dialog.setMessage(msg);
                        }));

                tmp.delete();
                ks.delete();
                handler.post(() -> {
                    dismissDialog();
                    tvPermStatus.setText("权限添加完成，正在保存...");
                    savePermApkAuto(apkName, out, sign);
                });
            } catch (Exception e) {
                deleteRecursive(work);
                if (tmp != null) tmp.delete();
                final Exception fe = e;
                handler.post(() -> {
                    dismissDialog();
                    btnPermPick.setEnabled(true);
                    tvPermStatus.setText("本地处理 · 修改后自动保存");
                    // 完整错误写入日志文件再打开结果页展示，toast 只显示开头一段
                    File dir = new File(getCacheDir(), "parse");
                    dir.mkdirs();
                    File lf = new File(dir, "perm_error.log");
                    try {
                        writeFile(lf, buildFixError(fe));
                    } catch (Exception ignored) {
                    }
                    Toast.makeText(this, "权限添加失败：" + truncate(fe.getMessage(), 200), Toast.LENGTH_LONG).show();
                    Intent i = new Intent(this, ResultActivity.class);
                    i.putExtra("apk_name", apkName + " - 权限添加失败");
                    i.putExtra("manifest_path", lf.getAbsolutePath());
                    i.putExtra("arsc_path", "");
                    i.putExtra("mode", "log");
                    startActivity(i);
                });
            }
        }).start();
    }

    /** 权限添加完成后按设置的输出路径直接写文件（调用前需已授予存储权限）。 */
    private void savePermApkAuto(String apkName, File out, boolean sign) {
        String base = apkName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        final String fileName = base + (sign ? "-perm.apk" : "-unsigned.apk");
        final String folder = SettingsPermActivity.loadPermOutputPath(this);
        new Thread(() -> {
            final String savedTo;
            try {
                File dir = new File(folder);
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录 " + folder);
                File target = new File(dir, fileName);
                copyToFile(new FileInputStream(out), target);
                savedTo = target.getAbsolutePath();
            } catch (Exception e) {
                handler.post(() -> {
                    btnPermPick.setEnabled(true);
                    tvPermStatus.setText("本地处理 · 修改后自动保存");
                    Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
                return;
            }
            out.delete();
            handler.post(() -> {
                btnPermPick.setEnabled(true);
                tvPermStatus.setText("本地处理 · 修改后自动保存");
                Toast.makeText(this, "权限添加成功，已保存到 " + savedTo, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    // ---------- 通用 ----------

    /** 把输入流完整复制到文件。 */
    private void copyToFile(InputStream in, File f) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            byte[] tmp = new byte[256 * 1024];
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
        byte[] tmp = new byte[64 * 1024];
        int n;
        long total = 0;
        while ((n = in.read(tmp)) > 0) {
            total += n;
            if (total > MAX_ENTRY_SIZE) throw new Exception("条目过大（超过 80MB），已停止");
            bos.write(tmp, 0, n);
        }
        return bos.toByteArray();
    }

    private void writeFile(File f, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    /** 异常完整文本：类型 + 消息 + 堆栈，写入日志文件供查看。 */
    private String buildFixError(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private void dismissDialog() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
