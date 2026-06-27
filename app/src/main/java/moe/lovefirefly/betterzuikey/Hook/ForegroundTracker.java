package moe.lovefirefly.betterzuikey.Hook;

import io.github.libxposed.api.XposedModule;

import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * Event-driven foreground package tracker.
 */
public class ForegroundTracker {

    private volatile String mForegroundPkg = null;
    private ConfigResolver mResolver;
    private XposedModule mModule;

    public ForegroundTracker(ConfigResolver resolver) {
        this.mResolver = resolver;
    }

    public void install(XposedModule module, ClassLoader classLoader) {
        mModule = module;

        // ---- Android 15+ (SDK 35+): DisplayContent.setFocusedApp(ActivityRecord) ----
        try {
            Class<?> displayContentClass = Class.forName(
                "com.android.server.wm.DisplayContent", false, classLoader);
            Class<?> activityRecordClass = Class.forName(
                "com.android.server.wm.ActivityRecord", false, classLoader);

            HookCompat.hookMethod(mModule, displayContentClass, "setFocusedApp",
                    new HookCompat.HookCallback() {
                        @Override
                        protected void afterHookedMethod(HookCompat.HookParam param) {
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
                    },
                    activityRecordClass);
            LogHelper.log(VerboseLevel.INFO,
                "ForegroundActivity hook installed: DisplayContent.setFocusedApp");
            return;
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.WARNING,
                "ForegroundTracker: DisplayContent.setFocusedApp not available: ",
                t.getMessage());
        }

        // ---- Android 14- fallback ----
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
                HookCompat.hookAllMethods(mModule, cls, methodName,
                        new HookCompat.HookCallback() {
                            @Override
                            protected void afterHookedMethod(HookCompat.HookParam param) {
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

        LogHelper.log(VerboseLevel.WARNING,
            "ForegroundActivity hook unavailable, templates disabled");
    }

    public String getForegroundPackage() {
        return mForegroundPkg;
    }

    public void setResolver(ConfigResolver resolver) {
        this.mResolver = resolver;
        if (mForegroundPkg != null) resolver.setForegroundPackage(mForegroundPkg);
    }
}
