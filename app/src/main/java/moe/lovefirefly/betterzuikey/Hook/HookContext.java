package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * Shared mutable state and convenience methods for all interceptor classes.
 * Passed to L0/L1/L3/L4 interceptors on construction.
 */
public class HookContext {

    // ---- Mutable state (updated by checkConfigChanged / constructor hook) ----

    public Config cfg;
    public ConfigResolver resolver;
    public Object kscInstance;
    public Object policyInstance;

    // ---- Immutable collaborators ----

    public final FnKeyManager fnKeyManager;
    public final ForegroundTracker foregroundTracker;
    public final ConfigIPCManager configIPC;

    public HookContext(Config cfg, ConfigResolver resolver,
                       FnKeyManager fnKeyManager, ForegroundTracker foregroundTracker,
                       ConfigIPCManager configIPC) {
        this.cfg = cfg;
        this.resolver = resolver;
        this.fnKeyManager = fnKeyManager;
        this.foregroundTracker = foregroundTracker;
        this.configIPC = configIPC;
    }

    // ----------------------------------------------------------------
    //  ConfigResolver wrappers (template overrides global)
    // ----------------------------------------------------------------

    public Config.SwitchState r(String key, Config.SwitchState global) {
        return resolver.effectiveSwitchState(global, key);
    }

    public Config.OverrideMode ra(String key, Config.OverrideMode global) {
        return resolver.effectiveAction(global, key);
    }

    // ----------------------------------------------------------------
    //  Config hot-reload
    // ----------------------------------------------------------------

    /** Check for config changes via ContentProvider IPC. Called from every hook entry. */
    public void checkConfigChanged() {
        Config newCfg = configIPC.checkChanged();
        if (newCfg == null) return;
        newCfg.injected = cfg.injected;
        newCfg.injectError = cfg.injectError;
        cfg = newCfg;
        MainHook.globalEnabled = cfg.zuxKeyboardFuncEnabled;
        LogHelper.currentLevel = cfg.verboseLevel;
        resolver = new ConfigResolver(cfg);
        // Resync: ForegroundTracker holds old resolver ref + new resolver needs current foreground pkg
        if (foregroundTracker != null) {
            foregroundTracker.setResolver(resolver);
            String pkg = foregroundTracker.getForegroundPackage();
            if (pkg != null) resolver.setForegroundPackage(pkg);
        }
        LogHelper.log(VerboseLevel.INFO, "Config hot-reloaded, templates=",
            String.valueOf(cfg.templates != null ? cfg.templates.size() : 0));
    }

    // ----------------------------------------------------------------
    //  Action dispatch helpers (used by L0/L1/L3/L4)
    // ----------------------------------------------------------------

    /**
     * L0/L1 override dispatch with BLOCK UP cleanup.
     * @param blockKeyCode the keyCode to consume UP for in BLOCK mode
     * @return true means caller should return immediately
     */
    public boolean applyInterceptAction(Config.OverrideMode mode,
                                         XC_MethodHook.MethodHookParam param,
                                         String logLabel, int blockKeyCode) {
        if (mode == Config.OverrideMode.BLOCK && blockKeyCode != 0) {
            fnKeyManager.setPendingBlockedWinComboUp(blockKeyCode);
        }
        return applyInterceptAction(mode, param, logLabel);
    }

    /** L0/L1 override dispatch (no BLOCK UP cleanup). */
    public boolean applyInterceptAction(Config.OverrideMode mode,
                                         XC_MethodHook.MethodHookParam param,
                                         String logLabel) {
        switch (mode) {
            case BLOCK:
                LogHelper.log(VerboseLevel.INFO, logLabel, "→ BLOCK");
                param.setResult(true);
                return true;
            case OFF:
                LogHelper.log(VerboseLevel.INFO, logLabel, "→ OFF (pass through, let ZUI run)");
                return false;
            case AOSP:
                LogHelper.log(VerboseLevel.INFO, logLabel, "→ AOSP (delegate to system, let ZUI run)");
                return false;
            case FOLLOW_SYSTEM:
                LogHelper.log(VerboseLevel.INFO, logLabel, "→ FOLLOW_SYSTEM (let ZUI decide)");
                return false;
            case ZUI:
            default:
                LogHelper.log(VerboseLevel.INFO, logLabel, "→ ZUI (intercept)");
                param.setResult(true);
                return true;
        }
    }

    /**
     * L4 override dispatch: decide whether to block ZUI L4 callback.
     * @return true if ZUI handler should be blocked
     */
    public boolean applyL4BlockAction(Config.OverrideMode mode, String logLabel) {
        switch (mode) {
            case BLOCK:
            case OFF:
            case AOSP:
                LogHelper.log(VerboseLevel.INFO, logLabel, "→ blocked (mode=", mode.name(), ")");
                return true;
            case ZUI:
            case FOLLOW_SYSTEM:
                LogHelper.log(VerboseLevel.DEBUG, logLabel, "→ pass-through (let ZUI handle)");
                return false;
            default:
                return false;
        }
    }

    // ----------------------------------------------------------------
    //  Keyboard detect mode
    // ----------------------------------------------------------------

    /** Check if the keyboard detection Activity is in the foreground. */
    public boolean isDetectMode() {
        if (kscInstance == null) return false;
        try {
            android.content.Context ctx = (android.content.Context)
                    XposedHelpers.getObjectField(kscInstance, "mContext");
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks =
                    am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                android.content.ComponentName top = tasks.get(0).topActivity;
                if (top == null) return false;
                return "moe.lovefirefly.betterzuikey".equals(top.getPackageName())
                        && top.getClassName().endsWith(".KeyboardDetectActivity");
            }
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "isDetectMode error:", t.getMessage());
        }
        return false;
    }
}
