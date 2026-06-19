package moe.lovefirefly.betterzuikey.Hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * Event-driven foreground package tracker.
 * Hooks ActivityTaskManagerService focus change to update the cached package name
 * without polling. Used by ConfigResolver for per-app template matching.
 */
public class ForegroundTracker {

    /** Current foreground package name (updated asynchronously by event-driven hook) */
    private String mForegroundPkg = null;

    private final ConfigResolver mResolver;

    public ForegroundTracker(ConfigResolver resolver) {
        this.mResolver = resolver;
    }

    /**
     * Install the focus-change hook. Tries multiple class/method candidates for
     * compatibility across different Android/ZUI versions.
     */
    public void install(ClassLoader classLoader) {
        String[][] candidates = {
                {"com.android.server.wm.ActivityTaskManagerService", "setResumedActivityUncheckLocked"},
                {"com.android.server.am.ActivityManagerService",     "setResumedActivityUncheckLocked"},
                {"com.android.server.wm.ActivityTaskManagerService", "activityResumed"},
        };

        for (String[] c : candidates) {
            try {
                final String className = c[0], methodName = c[1];
                Class<?> cls = Class.forName(className);
                XposedHelpers.findAndHookMethod(cls, methodName,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    // Try to extract ActivityRecord -> packageName from params
                                    Object record = param.args.length > 0 ? param.args[0] : null;
                                    if (record == null && param.args.length > 1)
                                        record = param.args[1];
                                    if (record == null) return;
                                    String pkg = (String) record.getClass()
                                            .getMethod("getPackageName").invoke(record);
                                    if (pkg != null && !pkg.equals(mForegroundPkg)) {
                                        mForegroundPkg = pkg;
                                        if (mResolver != null) mResolver.setForegroundPackage(pkg);
                                    }
                                } catch (Exception ignored) {}
                            }
                        });
                LogHelper.log(VerboseLevel.INFO, "ForegroundActivity hook installed: ",
                        className, ".", methodName);
                return;
            } catch (Throwable t) {
                // Try next candidate
            }
        }

        // All failed → silent degradation, templates unavailable
        LogHelper.log(VerboseLevel.DEBUG, "ForegroundActivity hook unavailable, templates disabled");
    }

    /** Get the cached foreground package name. */
    public String getForegroundPackage() {
        return mForegroundPkg;
    }

    /**
     * No-op in event-driven mode, retained for call-site compatibility.
     * mForegroundPkg is updated asynchronously by the focus-change hook.
     */
    public void refresh() {
        // event-driven — no polling needed
    }
}
