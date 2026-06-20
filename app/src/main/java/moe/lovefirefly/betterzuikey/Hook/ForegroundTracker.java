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
    private volatile String mForegroundPkg = null;

    private ConfigResolver mResolver;

    public ForegroundTracker(ConfigResolver resolver) {
        this.mResolver = resolver;
    }

    /**
     * Install the focus-change hook. Tries multiple class/method candidates for
     * compatibility across different Android/ZUI versions.
     *
     * Android 15+: DisplayContent.setFocusedApp(ActivityRecord)
     * Android 14-: ActivityTaskManagerService.setResumedActivityUncheckLocked / setFocusTask
     */
    public void install(ClassLoader classLoader) {
        // ---- Android 15+ (SDK 35+): DisplayContent.setFocusedApp(ActivityRecord) ----
        try {
            Class<?> displayContentClass = Class.forName(
                "com.android.server.wm.DisplayContent", false, classLoader);
            Class<?> activityRecordClass = Class.forName(
                "com.android.server.wm.ActivityRecord", false, classLoader);

            XposedHelpers.findAndHookMethod(displayContentClass, "setFocusedApp",
                    activityRecordClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object r = param.args[0];
                                if (r == null) return;
                                String pkg = (String) r.getClass()
                                    .getMethod("getPackageName").invoke(r);
                                if (pkg != null && !pkg.equals(mForegroundPkg)) {
                                    LogHelper.log(VerboseLevel.INFO,
                                        "Foreground changed: ", mForegroundPkg, " → ", pkg);
                                    mForegroundPkg = pkg;
                                    if (mResolver != null) mResolver.setForegroundPackage(pkg);
                                }
                            } catch (Exception e) {
                                LogHelper.log(VerboseLevel.DEBUG,
                                    "ForegroundTracker hook error: ", e.getMessage());
                            }
                        }
                    });
            LogHelper.log(VerboseLevel.INFO,
                "ForegroundActivity hook installed: DisplayContent.setFocusedApp");
            return;
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.WARNING,
                "ForegroundTracker: DisplayContent.setFocusedApp not available: ",
                t.getMessage());
        }

        // ---- Android 14- fallback: hookAllMethods on legacy candidates ----
        String[][] legacyCandidates = {
            {"com.android.server.wm.ActivityTaskManagerService", "setResumedActivityUncheckLocked"},
            {"com.android.server.am.ActivityManagerService",     "setResumedActivityUncheckLocked"},
            {"com.android.server.wm.ActivityTaskManagerService", "activityResumed"},
            {"com.android.server.wm.ActivityTaskManagerService", "setFocusTask"},
        };

        for (String[] c : legacyCandidates) {
            try {
                final String className = c[0], methodName = c[1];
                Class<?> cls = Class.forName(className, false, classLoader);
                de.robv.android.xposed.XposedBridge.hookAllMethods(cls, methodName,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    String pkg = null;
                                    for (int i = 0; i < param.args.length && pkg == null; i++) {
                                        Object arg = param.args[i];
                                        if (arg == null) continue;
                                        try {
                                            pkg = (String) arg.getClass()
                                                .getMethod("getPackageName").invoke(arg);
                                        } catch (NoSuchMethodException e) { }
                                    }
                                    if (pkg != null && !pkg.equals(mForegroundPkg)) {
                                        LogHelper.log(VerboseLevel.INFO,
                                            "Foreground changed: ", mForegroundPkg, " → ", pkg);
                                        mForegroundPkg = pkg;
                                        if (mResolver != null) mResolver.setForegroundPackage(pkg);
                                    }
                                } catch (Exception e) {
                                    LogHelper.log(VerboseLevel.DEBUG,
                                        "ForegroundTracker hook error: ", e.getMessage());
                                }
                            }
                        });
                LogHelper.log(VerboseLevel.INFO, "ForegroundActivity hook installed: ",
                    className, ".", methodName, " (hookAllMethods)");
                return;
            } catch (Throwable t) {
                LogHelper.log(VerboseLevel.DEBUG, "ForegroundTracker candidate failed: ",
                    c[0], ".", c[1], " — ", t.getMessage());
            }
        }

        // All failed → templates unavailable
        LogHelper.log(VerboseLevel.WARNING,
            "ForegroundActivity hook unavailable, templates disabled");
    }

    /** Get the cached foreground package name. */
    public String getForegroundPackage() {
        return mForegroundPkg;
    }

    /** Update the resolver reference after hot-reload. */
    public void setResolver(ConfigResolver resolver) {
        this.mResolver = resolver;
        if (mForegroundPkg != null) resolver.setForegroundPackage(mForegroundPkg);
    }

}
