package android.content;

/**
 * 运行时桩：仅覆盖 android.jar 中会抛 Stub! 的 Context 构造函数，
 * 让 MockCtx 能在桌面 JVM 上实例化。必须放在 android.jar 之前。
 */
public class Context {
    public Context() {
    }
}
