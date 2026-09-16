package moe.lovefirefly.betterzuikey;

import android.app.Application;

/**
 * Custom Application that initialises the {@link ModuleServiceBridge} early
 * so the Xposed service Binder is captured before the UI queries module status.
 */
public class ModuleApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        android.util.Log.i("BetterZUIKey", "[APP] ModuleApp.onCreate");
        LocaleHelper.applyFromConfig(this);
        // Register early — before any Activity starts — so we never miss the Binder
        ModuleServiceBridge.init();
        // 载入 IME profiles 并同步到框架远端配置：输入法进程只能通过远端配置拿到它们。
        // seedBuiltinsIfEmpty 仍留在 IME 设置页（不在每次启动时补种内置项）。
        try {
            moe.lovefirefly.betterzuikey.ime.IMEProfileManager.loadFromSP(this);
        } catch (Throwable t) {
            android.util.Log.w("BetterZUIKey", "[APP] loadFromSP failed: " + t.getMessage());
        }
    }
}
