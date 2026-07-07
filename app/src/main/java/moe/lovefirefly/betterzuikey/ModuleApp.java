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
    }
}
