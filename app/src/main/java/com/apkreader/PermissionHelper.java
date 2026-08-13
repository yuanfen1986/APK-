package com.apkreader;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 任意目录写入所需的存储权限工具。
 * API 23-29：运行时申请 WRITE_EXTERNAL_STORAGE（29 依赖 manifest 的 requestLegacyExternalStorage）；
 * API 30+：分区存储强制生效，需 MANAGE_EXTERNAL_STORAGE（所有文件访问），跳系统设置页由用户开启。
 */
public final class PermissionHelper {

    private PermissionHelper() {
    }

    /** 当前是否具备写任意目录的存储权限。 */
    public static boolean hasStoragePermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** 发起存储权限申请：30+ 跳系统「所有文件访问」设置页，23-29 弹运行时授权框。 */
    public static void requestStoragePermission(Activity a, int runtimeReqCode, int manageReqCode) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + a.getPackageName())), manageReqCode);
            } catch (Exception e) {
                try {
                    a.startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            manageReqCode);
                } catch (Exception e2) {
                    Toast.makeText(a, "请在系统设置中允许「所有文件访问」", Toast.LENGTH_LONG).show();
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, runtimeReqCode);
        }
    }
}
