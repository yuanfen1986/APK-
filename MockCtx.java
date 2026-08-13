import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.view.Display;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 桌面测试用的 Context 桩：不依赖 Android 运行时，仅提供内存版 SharedPreferences，
 * 供测试 SettingsActivity 等读取 SharedPreferences 的静态逻辑。
 * 其余抽象方法均抛 UnsupportedOperationException，便于尽早发现误用。
 */
public class MockCtx extends Context {

    public static Map<String, Object> store = new HashMap<>();

    public static void put(String k, Object v) {
        store.put(k, v);
    }

    public static void clear() {
        store.clear();
    }

    /** 反射替换目标静态字段的实例（如测试静态单例），便于 mock 后调用其静态方法。 */
    public static void inject(Class<?> target, String fieldName, Object value) throws Exception {
        Field f = target.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(null, value);
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return new SharedPreferences() {
            @Override
            public Map<String, ?> getAll() {
                return store;
            }

            @Override
            public String getString(String k, String d) {
                Object v = store.get(k);
                return v instanceof String ? (String) v : d;
            }

            @Override
            public Set<String> getStringSet(String k, Set<String> d) {
                return d;
            }

            @Override
            public int getInt(String k, int d) {
                Object v = store.get(k);
                return v instanceof Integer ? (Integer) v : d;
            }

            @Override
            public long getLong(String k, long d) {
                Object v = store.get(k);
                return v instanceof Long ? (Long) v : d;
            }

            @Override
            public float getFloat(String k, float d) {
                Object v = store.get(k);
                return v instanceof Float ? (Float) v : d;
            }

            @Override
            public boolean getBoolean(String k, boolean d) {
                Object v = store.get(k);
                return v instanceof Boolean ? (Boolean) v : d;
            }

            @Override
            public boolean contains(String k) {
                return store.containsKey(k);
            }

            @Override
            public SharedPreferences.Editor edit() {
                return new SharedPreferences.Editor() {
                    @Override
                    public SharedPreferences.Editor putString(String k, String v) {
                        store.put(k, v);
                        return this;
                    }

                    @Override
                    public SharedPreferences.Editor putStringSet(String k, Set<String> v) {
                        store.put(k, v);
                        return this;
                    }

                    @Override
                    public SharedPreferences.Editor putInt(String k, int v) {
                        store.put(k, v);
                        return this;
                    }

                    @Override
                    public SharedPreferences.Editor putLong(String k, long v) {
                        store.put(k, v);
                        return this;
                    }

                    @Override
                    public SharedPreferences.Editor putFloat(String k, float v) {
                        store.put(k, v);
                        return this;
                    }

                    @Override
                    public SharedPreferences.Editor putBoolean(String k, boolean v) {
                        store.put(k, v);
                        return this;
                    }

                    @Override
                    public SharedPreferences.Editor remove(String k) {
                        store.remove(k);
                        return this;
                    }

                    @Override
                    public SharedPreferences.Editor clear() {
                        store.clear();
                        return this;
                    }

                    @Override
                    public boolean commit() {
                        return true;
                    }

                    @Override
                    public void apply() {
                    }
                };
            }

            @Override
            public void registerOnSharedPreferenceChangeListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
            }

            @Override
            public void unregisterOnSharedPreferenceChangeListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
            }
        };
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("MockCtx: 该方法未实现");
    }

    @Override
    public AssetManager getAssets() {
        throw unsupported();
    }

    @Override
    public Resources getResources() {
        throw unsupported();
    }

    @Override
    public PackageManager getPackageManager() {
        throw unsupported();
    }

    @Override
    public ContentResolver getContentResolver() {
        throw unsupported();
    }

    @Override
    public Looper getMainLooper() {
        throw unsupported();
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public void setTheme(int resid) {
        throw unsupported();
    }

    @Override
    public Resources.Theme getTheme() {
        throw unsupported();
    }

    @Override
    public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public String getPackageName() {
        throw unsupported();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        throw unsupported();
    }

    @Override
    public String getPackageResourcePath() {
        throw unsupported();
    }

    @Override
    public String getPackageCodePath() {
        throw unsupported();
    }

    @Override
    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        throw unsupported();
    }

    @Override
    public boolean deleteSharedPreferences(String name) {
        throw unsupported();
    }

    @Override
    public FileInputStream openFileInput(String name) {
        throw unsupported();
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode) {
        throw unsupported();
    }

    @Override
    public boolean deleteFile(String name) {
        throw unsupported();
    }

    @Override
    public File getFileStreamPath(String name) {
        throw unsupported();
    }

    @Override
    public File getDataDir() {
        throw unsupported();
    }

    @Override
    public File getFilesDir() {
        throw unsupported();
    }

    @Override
    public File getNoBackupFilesDir() {
        throw unsupported();
    }

    @Override
    public File getExternalFilesDir(String type) {
        throw unsupported();
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        throw unsupported();
    }

    @Override
    public File getObbDir() {
        throw unsupported();
    }

    @Override
    public File[] getObbDirs() {
        throw unsupported();
    }

    @Override
    public File getCacheDir() {
        throw unsupported();
    }

    @Override
    public File getCodeCacheDir() {
        throw unsupported();
    }

    @Override
    public File getExternalCacheDir() {
        throw unsupported();
    }

    @Override
    public File[] getExternalCacheDirs() {
        throw unsupported();
    }

    @Override
    public File[] getExternalMediaDirs() {
        throw unsupported();
    }

    @Override
    public String[] fileList() {
        throw unsupported();
    }

    @Override
    public File getDir(String name, int mode) {
        throw unsupported();
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        throw unsupported();
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
            DatabaseErrorHandler errorHandler) {
        throw unsupported();
    }

    @Override
    public boolean moveDatabaseFrom(Context sourceContext, String name) {
        throw unsupported();
    }

    @Override
    public boolean deleteDatabase(String name) {
        throw unsupported();
    }

    @Override
    public File getDatabasePath(String name) {
        throw unsupported();
    }

    @Override
    public String[] databaseList() {
        throw unsupported();
    }

    @Override
    public Drawable getWallpaper() {
        throw unsupported();
    }

    @Override
    public Drawable peekWallpaper() {
        throw unsupported();
    }

    @Override
    public int getWallpaperDesiredMinimumWidth() {
        throw unsupported();
    }

    @Override
    public int getWallpaperDesiredMinimumHeight() {
        throw unsupported();
    }

    @Override
    public void setWallpaper(Bitmap bitmap) {
        throw unsupported();
    }

    @Override
    public void setWallpaper(InputStream data) {
        throw unsupported();
    }

    @Override
    public void clearWallpaper() {
        throw unsupported();
    }

    @Override
    public void startActivity(Intent intent) {
        throw unsupported();
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        throw unsupported();
    }

    @Override
    public void startActivities(Intent[] intents) {
        throw unsupported();
    }

    @Override
    public void startActivities(Intent[] intents, Bundle options) {
        throw unsupported();
    }

    @Override
    public void startIntentSender(android.content.IntentSender intent, Intent fillInIntent, int flagsMask,
            int flagsValues, int extraFlags) {
        throw unsupported();
    }

    @Override
    public void startIntentSender(android.content.IntentSender intent, Intent fillInIntent, int flagsMask,
            int flagsValues, int extraFlags, Bundle options) {
        throw unsupported();
    }

    @Override
    public void sendBroadcast(Intent intent) {
        throw unsupported();
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        throw unsupported();
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
        throw unsupported();
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission, android.content.BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        throw unsupported();
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        throw unsupported();
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission) {
        throw unsupported();
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission,
            android.content.BroadcastReceiver resultReceiver, Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        throw unsupported();
    }

    @Override
    public void sendStickyBroadcast(Intent intent) {
        throw unsupported();
    }

    @Override
    public void sendStickyOrderedBroadcast(Intent intent, android.content.BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        throw unsupported();
    }

    @Override
    public void removeStickyBroadcast(Intent intent) {
        throw unsupported();
    }

    @Override
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {
        throw unsupported();
    }

    @Override
    public void sendStickyOrderedBroadcastAsUser(Intent intent, UserHandle user,
            android.content.BroadcastReceiver resultReceiver, Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        throw unsupported();
    }

    @Override
    public void removeStickyBroadcastAsUser(Intent intent, UserHandle user) {
        throw unsupported();
    }

    @Override
    public Intent registerReceiver(android.content.BroadcastReceiver receiver, IntentFilter filter) {
        throw unsupported();
    }

    @Override
    public Intent registerReceiver(android.content.BroadcastReceiver receiver, IntentFilter filter, int flags) {
        throw unsupported();
    }

    @Override
    public Intent registerReceiver(android.content.BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        throw unsupported();
    }

    @Override
    public Intent registerReceiver(android.content.BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler, int flags) {
        throw unsupported();
    }

    @Override
    public void unregisterReceiver(android.content.BroadcastReceiver receiver) {
        throw unsupported();
    }

    @Override
    public ComponentName startService(Intent service) {
        throw unsupported();
    }

    @Override
    public ComponentName startForegroundService(Intent service) {
        throw unsupported();
    }

    @Override
    public boolean stopService(Intent service) {
        throw unsupported();
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        throw unsupported();
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        throw unsupported();
    }

    @Override
    public boolean startInstrumentation(ComponentName className, String profileFile, Bundle arguments) {
        throw unsupported();
    }

    @Override
    public Object getSystemService(String name) {
        throw unsupported();
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        throw unsupported();
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        throw unsupported();
    }

    @Override
    public int checkCallingPermission(String permission) {
        throw unsupported();
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        throw unsupported();
    }

    @Override
    public int checkSelfPermission(String permission) {
        throw unsupported();
    }

    @Override
    public void enforcePermission(String permission, int pid, int uid, String message) {
        throw unsupported();
    }

    @Override
    public void enforceCallingPermission(String permission, String message) {
        throw unsupported();
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        throw unsupported();
    }

    @Override
    public void grantUriPermission(String toPackage, Uri uri, int modeFlags) {
        throw unsupported();
    }

    @Override
    public void revokeUriPermission(Uri uri, int modeFlags) {
        throw unsupported();
    }

    @Override
    public void revokeUriPermission(String toPackage, Uri uri, int modeFlags) {
        throw unsupported();
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        throw unsupported();
    }

    @Override
    public int checkCallingUriPermission(Uri uri, int modeFlags) {
        throw unsupported();
    }

    @Override
    public int checkCallingOrSelfUriPermission(Uri uri, int modeFlags) {
        throw unsupported();
    }

    @Override
    public int checkUriPermission(Uri uri, String readPermission, String writePermission, int pid, int uid,
            int modeFlags) {
        throw unsupported();
    }

    @Override
    public void enforceUriPermission(Uri uri, int pid, int uid, int modeFlags, String message) {
        throw unsupported();
    }

    @Override
    public void enforceCallingUriPermission(Uri uri, int modeFlags, String message) {
        throw unsupported();
    }

    @Override
    public void enforceCallingOrSelfUriPermission(Uri uri, int modeFlags, String message) {
        throw unsupported();
    }

    @Override
    public void enforceUriPermission(Uri uri, String readPermission, String writePermission, int pid, int uid,
            int modeFlags, String message) {
        throw unsupported();
    }

    @Override
    public Context createPackageContext(String packageName, int flags) {
        throw unsupported();
    }

    @Override
    public Context createContextForSplit(String splitName) {
        throw unsupported();
    }

    @Override
    public Context createConfigurationContext(Configuration overrideConfiguration) {
        throw unsupported();
    }

    @Override
    public Context createDisplayContext(Display display) {
        throw unsupported();
    }

    @Override
    public Context createDeviceProtectedStorageContext() {
        throw unsupported();
    }

    @Override
    public boolean isDeviceProtectedStorage() {
        return false;
    }
}
