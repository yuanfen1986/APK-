package android.content;

/**
 * 运行时桩：仅覆盖 android.jar 中会抛 Stub! 的 Context 构造函数，
 * 让 MockCtx 能在桌面 JVM 上实例化。必须放在 android.jar 之前。
 * getSharedPreferences 须与 android.jar 同签名声明，否则编译后的
 * Settings*Activity 字节码会因方法解析不到抛 NoSuchMethodError。
 */
public abstract class Context {
    public Context() {
    }

    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
