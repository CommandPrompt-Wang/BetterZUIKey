package moe.lovefirefly.betterzuikey.Hook;

import android.os.IBinder;
import android.view.KeyEvent;

import java.io.PrintWriter;
import java.io.StringWriter;

import android.content.ContentResolver;
import android.os.Bundle;
import android.net.Uri;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.Region.FeatureHook;
import moe.lovefirefly.betterzuikey.Region.RegionHook;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import moe.lovefirefly.betterzuikey.Utils.ZuiDetector;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

public class MainHook implements IXposedHookLoadPackage {

    private static final String CLASS_KSC =
            "com.zui.server.input.keyboard.key.policy.KeyboardShortcutController";
    private static final String CLASS_KEY_GESTURE_EVENT =
            "android.hardware.input.KeyGestureEvent";

    private ClassLoader mClassLoader;
    private Object mKscInstance = null;
    private Config cfg = null;
    private ConfigResolver mResolver = null;
    private static volatile boolean sInitDone = false;
    /** ContentResolver for cross-process config reading via Binder IPC */
    private ContentResolver mConfigResolver = null;
    private String mLastConfigSync = "";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // processName may be null in parasitic mode
            if (lpparam.processName == null) return;

            // Log all incoming processes for debugging
            LogHelper.log(VerboseLevel.DEBUG, "handleLoadPackage called, process=", lpparam.processName);

            // system_server may appear as android or system in parasitic mode
            if (!lpparam.processName.equals("system_server")
                    && !lpparam.processName.equals("android")
                    && !lpparam.processName.equals("system")) {
                LogHelper.log(VerboseLevel.DEBUG, "Skipping non-system process: ", lpparam.processName);
                return;
            }

            // Check if running on ZUXOS
            if (!ZuiDetector.INSTANCE.isZuxOS()) {
                LogHelper.log(VerboseLevel.WARNING,
                    "Non-ZUXOS device detected. Module disabled. detail=", ZuiDetector.INSTANCE.getResult().getDetail());
                setError("Non-ZUXOS device, module disabled");
                return;
            }

            // Already initialized (static fields persist under same ClassLoader)
            if (sInitDone) return;
            sInitDone = true;

            cfg = Config.load();

            mClassLoader = lpparam.classLoader;

            LogHelper.log(VerboseLevel.INFO, "Module loaded! Ciallo～(∠・ω< )⌒★");
            LogHelper.log(VerboseLevel.INFO, "Config loaded from:", Config.CONFIG_PATH, " zux=", String.valueOf(cfg.zuxKeyboardFuncEnabled));

            if (!cfg.isSystemDetected()) {
                LogHelper.log(VerboseLevel.INFO, "First run: detecting system capabilities...");
                cfg.detectFromSystem(mClassLoader);
                cfg.save();
            } else {
                // 每次都重新读取 Settings.System 开关状态
                LogHelper.log(VerboseLevel.INFO, "Refreshing system switch states...");
                cfg.readSystemSwitchesPublic();
                cfg.save();
            }

            mResolver = new ConfigResolver(cfg);

            // ContentProvider-based IPC: bypasses file permission / SELinux issues.
            // system_server gets a ContentResolver and calls ConfigSyncProvider.call("getSync").
            initConfigResolver();

            LogHelper.log(VerboseLevel.INFO, "Installing RegionHook...");
            RegionHook.install(mClassLoader, cfg);
            LogHelper.log(VerboseLevel.INFO, "Installing FeatureHook...");
            FeatureHook.install(mClassLoader, cfg);

            LogHelper.log(VerboseLevel.INFO, "Installing foreground tracker...");
            hookForegroundActivityChange();

            LogHelper.log(VerboseLevel.INFO, "Installing constructor hook...");
            hookConstructor();
            LogHelper.log(VerboseLevel.INFO, "Installing L0 hook...");
            hookL0_BeforeQueueing();
            LogHelper.log(VerboseLevel.INFO, "Installing L1 hook...");
            hookL1_BeforeDispatching();
            LogHelper.log(VerboseLevel.INFO, "Installing L4 hook...");
            hookL4_HandleKeyGestureEvent();
            LogHelper.log(VerboseLevel.INFO, "Installing L3 hook (PhoneWindowManager)...");
            hookL3_PhoneWindowManager();

            cfg.injected = true;
            cfg.injectError = "";
            LogHelper.log(VerboseLevel.INFO, "All hooks installed successfully!");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Hook installation failed:", t.getMessage());
            setError("Injection error: " + t.toString() + "\n\n" + stackTraceToString(t));
        }
    }

    /**
     * Initialize ContentResolver for cross-process config reading via Binder IPC.
     * Uses ActivityThread.getSystemContext() pattern (same as readSystemSwitches).
     */
    private void initConfigResolver() {
        try {
            Object at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null);
            android.content.Context sysCtx = (android.content.Context)
                at.getClass().getMethod("getSystemContext").invoke(at);
            mConfigResolver = sysCtx.getContentResolver();
            // Read initial config via Binder IPC
            String sync = pullConfigSync();
            mLastConfigSync = sync;
            LogHelper.log(VerboseLevel.INFO, "ContentResolver IPC initialized, config_sync len=",
                String.valueOf(sync.length()));
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.ERROR, "initConfigResolver failed:", e.getMessage());
        }
    }

    /**
     * Pull config JSON via ContentProvider.call() — Binder IPC, no file permissions needed.
     * @return config JSON string, or "" on failure
     */
    private String pullConfigSync() {
        try {
            if (mConfigResolver == null) return "";
            Bundle result = mConfigResolver.call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.METHOD_GET_SYNC,
                null, null);
            if (result == null) return "";
            return result.getString(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.KEY_CONFIG_JSON, "");
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.WARNING, "pullConfigSync failed:", e.getMessage());
            return "";
        }
    }

    /**
     * Check for config changes via ContentProvider.call(). Called from L0 hook.
     * Uses Binder IPC — immune to file permission / SELinux issues.
     */
    private void checkConfigChanged() {
        try {
            String sync = pullConfigSync();
            if (sync.isEmpty() || sync.equals(mLastConfigSync)) return;
            mLastConfigSync = sync;
            Config newCfg = Config.fromJson(sync);
            if (newCfg == null) return;
            newCfg.injected = cfg.injected;
            newCfg.injectError = cfg.injectError;
            cfg = newCfg;
            if (mResolver != null) mResolver = new ConfigResolver(cfg);
            LogHelper.log(VerboseLevel.INFO, "Config hot-reloaded via ContentProvider, len=",
                String.valueOf(sync.length()));
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.WARNING, "checkConfigChanged failed:", e.getMessage());
        }
    }

    private void setError(String msg) {
        try {
            if (cfg == null) cfg = Config.load();
            cfg.injected = false;
            cfg.injectError = msg;
        } catch (Exception ignored) { }
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    // ----------------------------------------------------------------
    //  Action dispatch helpers
    // ----------------------------------------------------------------

    /**
     * L0/L1 override dispatch: decide whether to intercept the event.
     * @return true means caller should return immediately.
     *
     * NOTE: L1 has NO control over AOSP-level shortcuts (Win+D type=1, Win+M type=201,
     * Win+N type=8, Win+↑↓ type=53/52, Ctrl+/ type=12). Their KeyGestureEvent is dispatched
     * independently by InputGestureManager and handled by PhoneWindowManager (L3).
     * Use hookL3_PhoneWindowManager() to intercept those.
     */
    private boolean applyInterceptAction(Config.OverrideMode mode, XC_MethodHook.MethodHookParam param,
                                          String logLabel) {
        switch (mode) {
            case BLOCK:
                LogHelper.log(VerboseLevel.INFO, logLabel, "→ BLOCK");
                param.setResult(true);
                return true;
            case OFF:
                // OFF = pass through: let original ZUI method run normally.
                // Do NOT call param.setResult() — the original method needs to
                // dispatch KeyGestureEvent to PhoneWindowManager for L3 AOSP shortcuts.
                LogHelper.log(VerboseLevel.INFO, logLabel, "→ OFF (pass through, let ZUI run)");
                return false;
            case AOSP:
                LogHelper.log(VerboseLevel.INFO, logLabel, "→ AOSP (delegate to system, let ZUI run)");
                return false;
            case FOLLOW_SYSTEM:
                // FOLLOW_SYSTEM: let original ZUI method run with its own internal switch logic.
                // Do NOT call param.setResult() — same reason as OFF.
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
    private boolean applyL4BlockAction(Config.OverrideMode mode, String logLabel) {
        switch (mode) {
            case BLOCK:
            case ZUI:
                LogHelper.log(VerboseLevel.INFO, logLabel, "→ blocked");
                return true;
            case OFF:
            case AOSP:
                LogHelper.log(VerboseLevel.DEBUG, logLabel, "→ pass-through / AOSP");
                return false;
            default:
                return false;
        }
    }

    /**
     * OFF re-injection helper: block ZUI + L3, inject key to foreground app
     * preserving original modifier state (Ctrl/Shift/Alt/Meta).
     * @return true if the caller should return immediately (OFF handled)
     */
    private boolean handleOffInject(String shortcutKey, Config.OverrideMode ov,
                                     XC_MethodHook.MethodHookParam param,
                                     int keyCode, int metaState) {
        if (ov != Config.OverrideMode.OFF) return false;
        LogHelper.log(VerboseLevel.INFO, "L1: ", shortcutKey,
                " → OFF (block system, inject ", keyCodeToString(keyCode), " to app)");
        param.setResult(true);
        mOffInjectKeys.add(keyCode);
        injectKeyDown(keyCode, metaState);
        return true;
    }

    /** OFF re-injection UP: consume physical UP, inject clean UP */
    private void handleOffInjectUp(int keyCode) {
        injectKeyUp(keyCode);
        LogHelper.log(VerboseLevel.DEBUG, "L1: OFF inject UP keyCode=",
                String.valueOf(keyCode));
    }

    // ----------------------------------------------------------------
    //   Event-driven foreground detection (hook ActivityTaskManager focus change)
    // ----------------------------------------------------------------

    /** Current foreground package name (updated asynchronously by event-driven hook) */
    private String mForegroundPkg = null;

    /** No-op in event-driven mode, retained for call-site compatibility */
    private void refreshForegroundPackage() {
        // mForegroundPkg is updated by hookForegroundActivityChange event callback
    }

    /**
     * Hook ActivityTaskManagerService focus change method.
     * Only updates cache on app switch, no polling needed.
     */
    private void hookForegroundActivityChange() {
        // Multi-version compatible class name + method name
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

        // 全部失败 → 静默降级，模板功能不可用
        LogHelper.log(VerboseLevel.DEBUG, "ForegroundActivity hook unavailable, templates disabled");
    }

// -- ConfigResolver wrappers (template overrides global) --

    private Config.SwitchState r(String key, Config.SwitchState global) {
        return mResolver.effectiveSwitchState(global, key);
    }

    private Config.OverrideMode ra(String key, Config.OverrideMode global) {
        return mResolver.effectiveAction(global, key);
    }

    private void hookConstructor() {
        try {
            XposedHelpers.findAndHookConstructor(
                    CLASS_KSC,
                    mClassLoader,
                    android.content.Context.class,
                    android.os.Handler.class,
                    "com.zui.server.input.keyboard.key.policy.KeyboardZuiKeyInputPolicy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            mKscInstance = param.thisObject;
                            LogHelper.log(VerboseLevel.INFO, "KeyboardShortcutController instance captured!");
                        }
                    }
            );
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook constructor:", t.getMessage());
        }
    }

    private void hookL0_BeforeQueueing() {
        try {
            XposedHelpers.findAndHookMethod(
                    CLASS_KSC,
                    mClassLoader,
                    "interceptSystemKeysAndShortcutsBeforeQueueing",
                    KeyEvent.class,
                    int.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Global enable check
                            if (cfg == null || !cfg.zuxKeyboardFuncEnabled) return;
                            refreshForegroundPackage();
                            checkConfigChanged(); // XSharedPreferences: check for App config changes

                            KeyEvent event = (KeyEvent) param.args[0];
                            int keyCode = event.getKeyCode();
                            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                            int repeatCount = event.getRepeatCount();

                            // Keyboard detect mode: intercept all keys, prevent ZUI function key side-effects
                            if (down && repeatCount == 0) {
                                // Write SystemProperties (includes VID:PID for initial detection)
                                try {
                                    android.view.InputDevice dev = event.getDevice();
                                    int vid = dev != null ? dev.getVendorId() : 0;
                                    int pid = dev != null ? dev.getProductId() : 0;
                                    String devName = dev != null ? dev.getName() : "";
                                    Class<?> sp = Class.forName("android.os.SystemProperties");
                                    sp.getMethod("set", String.class, String.class)
                                        .invoke(null, "debug.bzuikey.last_key",
                                            keyCode + ":" + event.getScanCode());
                                    sp.getMethod("set", String.class, String.class)
                                        .invoke(null, "debug.bzuikey.dev_info",
                                            vid + ":" + pid + ":" + devName);
                                } catch (Throwable ignored) { }
                            }
                            if (isDetectMode()) {
                                param.setResult(true);
                                return;
                            }

                            // Win+Tab — Recents
                            if (keyCode == KeyEvent.KEYCODE_TAB && down
                                    && event.isMetaPressed() && repeatCount == 0) {
                                if (!r("winTab", cfg.switchWinTab).isEnabled()) return;
                                if (applyInterceptAction(ra("winTab", cfg.overrideWinTab), param, "L0: Win+Tab")) return;
                            }
                            // Alt+Tab — Recents switch
                            if (keyCode == KeyEvent.KEYCODE_TAB && down
                                    && event.isAltPressed() && repeatCount == 0) {
                                if (!r("altTab", cfg.switchAltTab).isEnabled()) return;
                                if (applyInterceptAction(ra("altTab", cfg.overrideAltTab), param, "L0: Alt+Tab")) return;
                            }
                            // Win+L — Lock screen
                            if (keyCode == KeyEvent.KEYCODE_L && down
                                    && event.isMetaPressed() && repeatCount == 0) {
                                if (!r("winL", cfg.switchWinL).isEnabled()) return;
                                if (applyInterceptAction(ra("winL", cfg.overrideWinL), param, "L0: Win+L")) return;
                            }
                            // Win+P — PC mode
                            if (keyCode == KeyEvent.KEYCODE_P && down
                                    && event.isMetaPressed() && repeatCount == 0) {
                                if (!r("winP", cfg.switchWinP).isEnabled()) return;
                                if (applyInterceptAction(ra("winP", cfg.overrideWinP), param, "L0: Win+P")) return;
                            }
                            // Win+Back — Send ESC
                            if (keyCode == KeyEvent.KEYCODE_BACK && down
                                    && event.isMetaPressed() && repeatCount == 0) {
                                if (!r("winBack", cfg.switchWinBack).isEnabled()) return;
                                if (applyInterceptAction(ra("winBack", cfg.overrideWinBack), param, "L0: Win+Back")) return;
                            }
                            // Ctrl+Space — Conditional pass-through
                            if (keyCode == KeyEvent.KEYCODE_SPACE && down
                                    && event.isCtrlPressed() && repeatCount == 0) {
                                if (!r("ctrlSpace", cfg.switchCtrlSpace).isEnabled()) return;
                                if (applyInterceptAction(ra("ctrlSpace", cfg.overrideCtrlSpace), param, "L0: Ctrl+Space")) return;
                            }
                            // Ctrl+Enter — QQ foreground conditional intercept
                            if (keyCode == KeyEvent.KEYCODE_ENTER && down
                                    && event.isCtrlPressed() && repeatCount == 0) {
                                if (!r("ctrlEnter", cfg.switchCtrlEnter).isEnabled()) return;
                                if (applyInterceptAction(ra("ctrlEnter", cfg.overrideCtrlEnter), param, "L0: Ctrl+Enter")) return;
                            }
                            // Win+` -> FnLock toggle
                            if (keyCode == KeyEvent.KEYCODE_GRAVE
                                    && event.isMetaPressed() && repeatCount == 0) {
                                if (!cfg.fnLockEnabled) return;
                                param.setResult(true);
                                if (down) {
                                    mConsumeNextMetaUp = true;
                                    cfg.fnKeyEnabled = !cfg.fnKeyEnabled;
                                    cfg.save();
                                    LogHelper.log(VerboseLevel.INFO, "L0: FnLock toggled -> ",
                                            cfg.fnKeyEnabled ? "ON" : "OFF");
                                    showToast("Fn " + (cfg.fnKeyEnabled ? "ON" : "OFF"));
                                }
                                return;
                            }
                            // Consume Meta UP after Win+` to prevent Start menu
                            if (mConsumeNextMetaUp
                                    && (keyCode == KeyEvent.KEYCODE_META_LEFT
                                        || keyCode == KeyEvent.KEYCODE_META_RIGHT)
                                    && !down) {
                                param.setResult(true);
                                mConsumeNextMetaUp = false;
                                LogHelper.log(VerboseLevel.INFO, "L0: Meta UP consumed after Win+`");
                                return;
                            }

                            // -- Virtual Fn key --
                            // FnLock=ON:  single press→F1-F12, Win→original
                            // FnLock=OFF: single press→original, Win→F1-F12
                            if (down && repeatCount == 0) {
                                // Skip clean DOWN injected by restore path (avoid re-mapping loop)
                                if (mRestoreDownPending.contains(keyCode)) {
                                    return;
                                }
                                // DEBUG: Log all key events (keyCode + scanCode)
                                LogHelper.log(VerboseLevel.INFO, "L0: ALL KEYS keyCode=", String.valueOf(keyCode),
                                        " scanCode=", String.valueOf(event.getScanCode()),
                                        " fnEnabled=", String.valueOf(cfg.fnKeyEnabled));
                                boolean winOnly = event.isMetaPressed() && !event.isCtrlPressed();
                                // XOR: FnLock ON + Win reverses; FnLock OFF + Win activates
                                boolean fnActive = cfg.fnKeyEnabled ^ winOnly;
                                int fKey = getFnTarget(event);
                                if (cfg.fnKeyEnabled && winOnly) {
                                    LogHelper.log(VerboseLevel.INFO, "L0: FnLock restore",
                                        " key=", keyCodeToString(keyCode),
                                        " mapped=", fKey != 0 ? "F" + (fKey - 130) : "none",
                                        " winOnly=true fnActive=", String.valueOf(fnActive),
                                        fKey != 0 ? " -> intercept+inject original" : " -> pass");
                                }
                                if (!fnActive && fKey != 0) {
                                    LogHelper.log(VerboseLevel.INFO, "L0: restore original",
                                        " key=", keyCodeToString(keyCode),
                                        " normally=", "F" + (fKey - 130),
                                        " fnActive=false -> intercept+inject clean DOWN");
                                    param.setResult(true);          // block physical DOWN
                                    mRestoreDownPending.add(keyCode); // mark: clean DOWN injected, wait for UP
                                    injectKeyDown(keyCode);          // inject clean DOWN now
                                    return;
                                }
                                if (fnActive) {
                                    if (fKey != 0) {
                                        String fLabel = "F" + (fKey - 130);
                                        LogHelper.log(VerboseLevel.INFO, "L0: Fn",
                                                cfg.fnKeyEnabled ? "(F" + (winOnly ? "->orig)" : ")")
                                                    : "(Win->F" + ")",
                                                " + ", keyCodeToString(keyCode),
                                                " -> ", fLabel);
                                        if (cfg.fnToastEnabled) {
                                            showToast(fLabel);
                                        }
                                        param.setResult(true);
                                        mFnDownKeyMap.put(keyCode, fKey); // cache F-key for UP
                                        injectKeyDown(fKey);              // inject F-key DOWN only
                                        mFnDownKeys.add(keyCode);         // track physical UP
                                        return;
                                    }
                                }
                            }
                            // Handle UP for restore keys: block physical UP, inject clean UP now
                            if (!down && mRestoreDownPending.remove(keyCode)) {
                                param.setResult(true);   // block physical UP
                                injectKeyUp(keyCode);    // inject clean UP (follows actual release timing)
                                return;
                            }
                            // Intercept UP events for Fn-mapped keys: inject F-key UP now
                            if (!down && mFnDownKeys.remove(keyCode)) {
                                param.setResult(true);
                                Integer fKey = mFnDownKeyMap.remove(keyCode);
                                if (fKey != null) {
                                    injectKeyUp(fKey); // inject F-key UP (follows actual release)
                                }
                                return;
                            }
                            // 520 — Keyboard restore (disable physical keyboard)
                            if (keyCode == 520 && down && repeatCount == 0) {
                                if (!r("keyKeyboardRestore", cfg.switchKeyKeyboardRestore).isEnabled()) return;
                                if (applyInterceptAction(ra("keyKeyboardRestore", cfg.overrideKeyboardRestore), param, "L0: key 520")) return;
                            }
                            // 521 — Keyboard flip (enable physical keyboard + show IME)
                            if (keyCode == 521 && down && repeatCount == 0) {
                                if (!r("keyKeyboardReverse", cfg.switchKeyKeyboardReverse).isEnabled()) return;
                                if (applyInterceptAction(ra("keyKeyboardReverse", cfg.overrideKeyboardReverse), param, "L0: key 521")) return;
                            }
                            // Win+Alt+3 — Bounce keys (AOSP native)
                            if (keyCode == KeyEvent.KEYCODE_3 && down
                                    && event.isMetaPressed() && event.isAltPressed() && repeatCount == 0) {
                                LogHelper.log(VerboseLevel.INFO, "L0: Win+Alt+3 reach",
                                        " switch=", String.valueOf(cfg.switchAospBounceKeys),
                                        " override=", String.valueOf(cfg.overrideAospBounceKeys),
                                        " meta=", String.valueOf(event.isMetaPressed()),
                                        " alt=", String.valueOf(event.isAltPressed()));
                                if (!r("aospBounceKeys", cfg.switchAospBounceKeys).isEnabled()) return;
                                if (applyInterceptAction(ra("aospBounceKeys", cfg.overrideAospBounceKeys), param, "L0: Win+Alt+3")) return;
                            }
                            // Win+Alt+4 — Mouse keys (AOSP native)
                            if (keyCode == KeyEvent.KEYCODE_4 && down
                                    && event.isMetaPressed() && event.isAltPressed() && repeatCount == 0) {
                                LogHelper.log(VerboseLevel.INFO, "L0: Win+Alt+4 reach",
                                        " switch=", String.valueOf(cfg.switchAospMouseKeys),
                                        " override=", String.valueOf(cfg.overrideAospMouseKeys));
                                if (!r("aospMouseKeys", cfg.switchAospMouseKeys).isEnabled()) return;
                                if (applyInterceptAction(ra("aospMouseKeys", cfg.overrideAospMouseKeys), param, "L0: Win+Alt+4")) return;
                            }
                            // Win+Alt+5 — Sticky keys (AOSP native)
                            if (keyCode == KeyEvent.KEYCODE_5 && down
                                    && event.isMetaPressed() && event.isAltPressed() && repeatCount == 0) {
                                LogHelper.log(VerboseLevel.INFO, "L0: Win+Alt+5 reach",
                                        " switch=", String.valueOf(cfg.switchAospStickyKeys),
                                        " override=", String.valueOf(cfg.overrideAospStickyKeys));
                                if (!r("aospStickyKeys", cfg.switchAospStickyKeys).isEnabled()) return;
                                if (applyInterceptAction(ra("aospStickyKeys", cfg.overrideAospStickyKeys), param, "L0: Win+Alt+5")) return;
                            }
                            // Win+Alt+6 — Slow keys (AOSP native)
                            if (keyCode == KeyEvent.KEYCODE_6 && down
                                    && event.isMetaPressed() && event.isAltPressed() && repeatCount == 0) {
                                LogHelper.log(VerboseLevel.INFO, "L0: Win+Alt+6 reach",
                                        " switch=", String.valueOf(cfg.switchAospSlowKeys),
                                        " override=", String.valueOf(cfg.overrideAospSlowKeys));
                                if (!r("aospSlowKeys", cfg.switchAospSlowKeys).isEnabled()) return;
                                if (applyInterceptAction(ra("aospSlowKeys", cfg.overrideAospSlowKeys), param, "L0: Win+Alt+6")) return;
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook L0:", t.getMessage());
        }
    }

    private void hookL1_BeforeDispatching() {
        try {
            XposedHelpers.findAndHookMethod(
                    CLASS_KSC,
                    mClassLoader,
                    "interceptSystemKeysAndShortcutsBeforeDispatching",
                    KeyEvent.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Global enable check
                            if (cfg == null || !cfg.zuxKeyboardFuncEnabled) return;
                            refreshForegroundPackage();

                            // Detect mode: intercept all keys
                            if (isDetectMode()) {
                                param.setResult(true);
                                return;
                            }

                            KeyEvent event = (KeyEvent) param.args[0];
                            int keyCode = event.getKeyCode();
                            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                            int repeatCount = event.getRepeatCount();
                            boolean firstDown = down && repeatCount == 0;

                            // OFF re-injection UP: consume physical UP, inject clean UP
                            if (!down && mOffInjectKeys.remove(keyCode)) {
                                param.setResult(true);
                                handleOffInjectUp(keyCode);
                                return;
                            }

                            // Ctrl+Shift+T — Toggle touchpad
                            if (keyCode == KeyEvent.KEYCODE_T && firstDown
                                    && event.isCtrlPressed() && event.isShiftPressed()) {
                                if (!r("ctrlShiftT", cfg.switchCtrlShiftT).isEnabled()) return;
                                Config.OverrideMode ov = ra("ctrlShiftT", cfg.overrideCtrlShiftT);
                                if (handleOffInject("Ctrl+Shift+T", ov, param, keyCode, event.getMetaState())) return;
                                if (applyInterceptAction(ov, param, "L1: Ctrl+Shift+T")) return;
                            }

                            // Win+S — Global search
                            if (keyCode == KeyEvent.KEYCODE_S && firstDown
                                    && event.isMetaPressed()) {
                                if (!r("winS", cfg.switchWinS).isEnabled()) return;
                                if (applyInterceptAction(ra("winS", cfg.overrideWinS), param, "L1: Win+S")) return;
                            }

                            // Win+D — Back to desktop (L3 AOSP PhoneWindowManager type=1 → goHome())
                            if (keyCode == KeyEvent.KEYCODE_D && firstDown
                                    && event.isMetaPressed()) {
                                LogHelper.log(VerboseLevel.INFO, "L1: Win+D detected",
                                        " switch=", r("winD", cfg.switchWinD).name(),
                                        " override=", ra("winD", cfg.overrideWinD).name());
                                if (!r("winD", cfg.switchWinD).isEnabled()) {
                                    LogHelper.log(VerboseLevel.INFO, "L1: Win+D → switch disabled, block");
                                    param.setResult(true);
                                    return;
                                }
                                Config.OverrideMode ov = ra("winD", cfg.overrideWinD);
                                if (handleOffInject("Win+D", ov, param, keyCode, event.getMetaState())) return;
                                if (applyInterceptAction(ov, param, "L1: Win+D")) return;
                            }

                            // Win+I — Open Settings
                            if (keyCode == KeyEvent.KEYCODE_I && firstDown
                                    && event.isMetaPressed()) {
                                if (!r("winI", cfg.switchWinI).isEnabled()) return;
                                Config.OverrideMode ov = ra("winI", cfg.overrideWinI);
                                if (handleOffInject("winI", ov, param, keyCode, event.getMetaState())) return;
                                if (applyInterceptAction(ov, param, "L1: Win+I")) return;
                            }

                            // Win+N — Notification panel
                            if (keyCode == KeyEvent.KEYCODE_N && firstDown
                                    && event.isMetaPressed()) {
                                if (!r("winN", cfg.switchWinN).isEnabled()) return;
                                Config.OverrideMode ov = ra("winN", cfg.overrideWinN);
                                if (handleOffInject("winN", ov, param, keyCode, event.getMetaState())) return;
                                if (applyInterceptAction(ov, param, "L1: Win+N")) return;
                            }

                            // Ctrl+Shift — Switch IME
                            if (firstDown && event.isCtrlPressed() && event.isShiftPressed()
                                    && keyCode != KeyEvent.KEYCODE_T) {
                                if (!r("ctrlShift", cfg.switchCtrlShift).isEnabled()) return;
                                if (!cfg.rowInputMethodSwitch) return;
                                if (applyInterceptAction(ra("ctrlShift", cfg.overrideCtrlShift), param, "L1: Ctrl+Shift")) return;
                            }

                            // Alt+Shift — Switch language
                            if (firstDown && event.isAltPressed() && event.isShiftPressed()) {
                                if (!r("altShift", cfg.switchAltShift).isEnabled()) return;
                                if (!cfg.rowLanguageSwitch) return;
                                if (applyInterceptAction(ra("altShift", cfg.overrideAltShift), param, "L1: Alt+Shift")) return;
                            }

                            // Ctrl long-press — Shortcut menu (only intercept DOWN, pass UP through)
                            if (firstDown
                                    && (keyCode == KeyEvent.KEYCODE_CTRL_LEFT
                                    || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT)) {
                                if (!r("ctrlLongPress", cfg.switchCtrlLongPress).isEnabled()) return;
                                if (applyInterceptAction(ra("ctrlLongPress", cfg.overrideCtrlLongPress), param, "L1: Ctrl long-press")) return;
                            }

                            // Ctrl+/ — Shortcut menu
                            if (keyCode == KeyEvent.KEYCODE_SLASH && firstDown
                                    && event.isCtrlPressed()) {
                                if (!r("ctrlSlash", cfg.switchCtrlSlash).isEnabled()) return;
                                Config.OverrideMode ov = ra("ctrlSlash", cfg.overrideCtrlSlash);
                                if (handleOffInject("Ctrl+/", ov, param, keyCode, event.getMetaState())) return;
                                if (applyInterceptAction(ov, param, "L1: Ctrl+/")) return;
                            }

                            // 510 Settings key bug fix
                            if (keyCode == 510 && firstDown) {
                                if (!r("keySettings", cfg.switchKeySettings).isEnabled()) return;
                                if (applyInterceptAction(ra("keySettings", cfg.overrideSettings), param, "L1: key 510")) return;
                            }

                            // Print Screen (120) — Region screenshot (short) / Full screenshot (long press >=2s)
                            if (keyCode == KeyEvent.KEYCODE_SYSRQ && firstDown) {
                                if (!r("printScreenShort", cfg.switchPrintScreenShort).isEnabled()
                                        && !r("printScreenLong", cfg.switchPrintScreenLong).isEnabled()) return;
                                if (applyInterceptAction(ra("printScreenShort", cfg.overridePrintScreenShort), param, "L1: PrintScreen")) return;
                            }

                            // Caps Lock (115) — Show toast + pass through
                            if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK && firstDown) {
                                if (!r("capsLock", cfg.switchCapsLock).isEnabled()) return;
                                if (applyInterceptAction(ra("capsLock", cfg.overrideCapsLock), param, "L1: CapsLock")) return;
                            }

                            // Alt_RIGHT (58) — KR language switch
                            if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT && firstDown
                                    && !event.isShiftPressed() && !event.isCtrlPressed()
                                    && !event.isMetaPressed()) {
                                if (!r("altRightKR", cfg.switchAltRightKR).isEnabled()) return;
                                if (!cfg.krAltRightSwitch) return;
                                if (applyInterceptAction(ra("altRightKR", cfg.overrideAltRightKR), param, "L1: Alt_RIGHT KR")) return;
                            }

                            // Meta single press — Start menu
                            if ((keyCode == KeyEvent.KEYCODE_META_LEFT
                                    || keyCode == KeyEvent.KEYCODE_META_RIGHT)
                                    && down && repeatCount == 0) {
                                if (!r("metaSingle", cfg.switchMetaSingle).isEnabled()) return;
                                // 根据 MetaAction 配置分发（短按行为由 ZUI 长按计时器决定，
                                // 此处仅控制是否放行到 AOSP type=21 开始菜单）
                                Config.MetaAction action = cfg.metaShortPressAction;
                                if (action == Config.MetaAction.NONE) {
                                    LogHelper.log(VerboseLevel.DEBUG, "L1: Meta DOWN → NONE (block)");
                                    param.setResult(true);
                                    return;
                                }
                                if (action == Config.MetaAction.START_MENU) {
                                    LogHelper.log(VerboseLevel.DEBUG, "L1: Meta DOWN → START_MENU (AOSP type=21)");
                                    // 不拦截，事件自然流向 AOSP
                                    return;
                                }
                                // DEFAULT / SWITCH_LANGUAGE / VOICE_ASSIST / HOLD_SWITCH_LANGUAGE
                                // → 由 ZUI scanCode+isRow 逻辑决定，不干预
                                LogHelper.log(VerboseLevel.DEBUG, "L1: Meta DOWN (action=",
                                        action.name(), ")");
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook L1:", t.getMessage());
        }
    }

    private void hookL4_HandleKeyGestureEvent() {
        try {
            Class<?> kgClass = XposedHelpers.findClass(CLASS_KEY_GESTURE_EVENT, mClassLoader);

            XposedHelpers.findAndHookMethod(
                    CLASS_KSC,
                    mClassLoader,
                    "handleKeyGestureEvent",
                    kgClass,
                    IBinder.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 全局开关检查
                            if (cfg == null || !cfg.zuxKeyboardFuncEnabled) return;
                            refreshForegroundPackage();

                            Object kgEvent = param.args[0];
                            try {
                                int type = (int) kgEvent.getClass()
                                        .getMethod("getKeyGestureType").invoke(kgEvent);
                                LogHelper.log(VerboseLevel.DEBUG, "L4: type=", String.valueOf(type));

                                boolean blocked = false;
                                switch (type) {
                                    case 300: // Win+S — Global search
                                        if (!r("winS", cfg.switchWinS).isEnabled()) break;
                                        if (applyL4BlockAction(ra("winS", cfg.overrideWinS), "L4: Win+S (300)")) blocked = true;
                                        break;
                                    case 302: // Win+A — Hide/show taskbar
                                        if (!r("winA", cfg.switchWinA).isEnabled()) break;
                                        if (applyL4BlockAction(ra("winA", cfg.overrideWinA), "L4: Win+A (302)")) blocked = true;
                                        break;
                                    case 305: // Win+W — Force close foreground app
                                        if (!r("winW", cfg.switchWinW).isEnabled()) break;
                                        if (applyL4BlockAction(ra("winW", cfg.overrideWinW), "L4: Win+W (305)")) blocked = true;
                                        break;
                                    case 306: // Win+Back — Send ESC
                                        if (!r("winBack", cfg.switchWinBack).isEnabled()) break;
                                        if (applyL4BlockAction(ra("winBack", cfg.overrideWinBack), "L4: Win+Back (306)")) blocked = true;
                                        break;
                                    case 307: // Win+E — Open file manager
                                        if (!r("winE", cfg.switchWinE).isEnabled()) break;
                                        if (applyL4BlockAction(ra("winE", cfg.overrideWinE), "L4: Win+E (307)")) blocked = true;
                                        break;
                                    case 308: // Ctrl+Shift+T — Toggle touchpad
                                        if (!r("ctrlShiftT", cfg.switchCtrlShiftT).isEnabled()) break;
                                        if (applyL4BlockAction(ra("ctrlShiftT", cfg.overrideCtrlShiftT), "L4: Ctrl+Shift+T (308)")) blocked = true;
                                        break;
                                    case 309: // Win+1~8 — Dock bar
                                        if (!r("winNumber", cfg.switchWinNumber).isEnabled()) break;
                                        if (applyL4BlockAction(ra("winNumber", cfg.overrideWinNumber), "L4: Win+Num (309)")) blocked = true;
                                        break;
                                    case 310: // Alt+Shift — Switch language
                                        if (!r("altShift", cfg.switchAltShift).isEnabled()) break;
                                        if (applyL4BlockAction(ra("altShift", cfg.overrideAltShift), "L4: Alt+Shift (310)")) blocked = true;
                                        break;
                                    case 311: // Ctrl+Shift — Switch IME
                                        if (!r("ctrlShift", cfg.switchCtrlShift).isEnabled()) break;
                                        if (applyL4BlockAction(ra("ctrlShift", cfg.overrideCtrlShift), "L4: Ctrl+Shift (311)")) blocked = true;
                                        break;
                                    case 312: // Win+Left/Right — Split screen
                                        if (!r("winLeft", cfg.switchWinLeft).isEnabled() && !r("winRight", cfg.switchWinRight).isEnabled()) break;
                                        if (applyL4BlockAction(ra("winLeft", cfg.overrideWinLeft), "L4: Win+Arrow (312)")) blocked = true;
                                        break;
                                    case 313: // 505 Super Connect key
                                        if (!r("keySuperConnect", cfg.switchKeySuperConnect).isEnabled()) break;
                                        if (applyL4BlockAction(ra("keySuperConnect", cfg.overrideSuperConnect), "L4: SuperConnect (313)")) blocked = true;
                                        break;
                                }

                                if (blocked) {
                                    param.setResult(null);
                                }
                            } catch (Exception e) {
                                LogHelper.log(VerboseLevel.ERROR, "L4 reflection error:", e.getMessage());
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook L4:", t.getMessage());
        }
    }

    // ----------------------------------------------------------------
    //  L3: PhoneWindowManager.handleKeyGestureEvent (AOSP shortcuts)
    // ----------------------------------------------------------------

    /**
     * Hook PhoneWindowManager.handleKeyGestureEvent to intercept AOSP-level shortcuts
     * that are NOT controllable from L1 (KeyboardShortcutController).
     *
     * These shortcuts have their KeyGestureEvent dispatched independently by
     * InputGestureManager, bypassing ZUI's KeyboardShortcutController entirely.
     *
     * Gesture type mapping (from ZUXOS_Shortcut_Implement.md):
     *   type 1   → Win+D  → goHome()
     *   type 8   → Win+N / 514 → 通知面板
     *   type 12  → Ctrl+/ → toggleKeyboardShortcutsMenu
     *   type 52  → Win+↓  → moveFocusedTaskToDesktop()
     *   type 53  → Win+↑  → moveFocusedTaskToFullscreen()
     *   type 201 → Win+M  → ovMinimizeFreeformGroup()
     */
    private void hookL3_PhoneWindowManager() {
        // Multi-version compatible: try ZuiPhoneWindowManager first, fall back to base
        String[] classCandidates = {
            "com.android.server.policy.ZuiPhoneWindowManager",
            "com.android.server.policy.PhoneWindowManager",
        };

        for (String className : classCandidates) {
            try {
                Class<?> kgClass = XposedHelpers.findClass(CLASS_KEY_GESTURE_EVENT, mClassLoader);
                if (kgClass == null) {
                    kgClass = Class.forName("android.hardware.input.KeyGestureEvent");
                }

                XposedHelpers.findAndHookMethod(
                        className,
                        mClassLoader,
                        "handleKeyGestureEvent",
                        kgClass,
                        IBinder.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (cfg == null || !cfg.zuxKeyboardFuncEnabled) return;
                                refreshForegroundPackage();

                                Object kgEvent = param.args[0];
                                try {
                                    int type = (int) kgEvent.getClass()
                                            .getMethod("getKeyGestureType").invoke(kgEvent);

                                    Config.OverrideMode action = null;
                                    String label = null;

                                    switch (type) {
                                        case 1: // Win+D → goHome()
                                            if (!r("winD", cfg.switchWinD).isEnabled()) break;
                                            action = ra("winD", cfg.overrideWinD);
                                            label = "L3: Win+D (type=1)";
                                            break;
                                        case 7: // Win+I → launchSettings (AOSP default registered)
                                            if (!r("winI", cfg.switchWinI).isEnabled()) break;
                                            action = ra("winI", cfg.overrideWinI);
                                            label = "L3: Win+I (type=7)";
                                            break;
                                        case 8: // Win+N / 514 → 通知面板
                                            if (!r("winN", cfg.switchWinN).isEnabled()) break;
                                            action = ra("winN", cfg.overrideWinN);
                                            label = "L3: Win+N (type=8)";
                                            break;
                                        case 12: // Ctrl+/ → toggleKeyboardShortcutsMenu
                                            if (!r("ctrlSlash", cfg.switchCtrlSlash).isEnabled()) break;
                                            action = ra("ctrlSlash", cfg.overrideCtrlSlash);
                                            label = "L3: Ctrl+/ (type=12)";
                                            break;
                                        case 52: // Win+↓ → moveFocusedTaskToDesktop()
                                            if (!r("winDown", cfg.switchWinDown).isEnabled()) break;
                                            action = ra("winDown", cfg.overrideWinDown);
                                            label = "L3: Win+↓ (type=52)";
                                            break;
                                        case 53: // Win+↑ → moveFocusedTaskToFullscreen()
                                            if (!r("winUp", cfg.switchWinUp).isEnabled()) break;
                                            action = ra("winUp", cfg.overrideWinUp);
                                            label = "L3: Win+↑ (type=53)";
                                            break;
                                        case 201: // Win+M → ovMinimizeFreeformGroup()
                                            if (!r("winM", cfg.switchWinM).isEnabled()) break;
                                            action = ra("winM", cfg.overrideWinM);
                                            label = "L3: Win+M (type=201)";
                                            break;
                                    }

                                    if (action == null) return;

                                    switch (action) {
                                        case BLOCK:
                                            // BLOCK: 消费事件，系统和应用都收不到
                                            LogHelper.log(VerboseLevel.INFO, label,
                                                    "→ BLOCK (block gesture)");
                                            param.setResult(null);
                                            break;
                                        case OFF:
                                            // OFF: 阻止系统处理 (goHome等), 放行原始按键给前台App
                                            LogHelper.log(VerboseLevel.INFO, label,
                                                    "→ OFF (block system, pass raw keys to app)");
                                            param.setResult(null);
                                            break;
                                        case ZUI:
                                        case AOSP:
                                        case FOLLOW_SYSTEM:
                                        default:
                                            // ZUI/AOSP/FOLLOW_SYSTEM = 放行给系统
                                            LogHelper.log(VerboseLevel.DEBUG, label,
                                                    "→", action.name(), "(pass through)");
                                            break;
                                    }
                                } catch (Exception e) {
                                    LogHelper.log(VerboseLevel.ERROR,
                                            "L3 reflection error:", e.getMessage());
                                }
                            }
                        }
                );
                LogHelper.log(VerboseLevel.INFO, "L3 hook installed: ", className);
                return; // Success, don't try fallback
            } catch (Throwable t) {
                LogHelper.log(VerboseLevel.DEBUG, "L3: ", className, " not available (",
                        t.getMessage(), ")");
            }
        }
        LogHelper.log(VerboseLevel.WARNING,
                "L3 hook: PhoneWindowManager not found, AOSP shortcuts cannot be blocked");
    }

    // ----------------------------------------------------------------
    //  Virtual Fn key helpers
    // ----------------------------------------------------------------

    /** keyCode -> F-keyCode mapping cache (use keyCode because ZUI overwrites scanCode) */
    private java.util.Map<Integer, Integer> mFnKeyCodeMap = null;
    private String mFnMapProfileKey = null;

    /** keyCodes whose DOWN was intercepted for OFF pass-through re-injection */
    private final java.util.Set<Integer> mOffInjectKeys = new java.util.HashSet<>();

    /** keyCodes whose DOWN was intercepted by Fn mapping, used to intercept matching UP */
    private final java.util.Set<Integer> mFnDownKeys = new java.util.HashSet<>();

    /** physicalKeyCode -> fKeyCode mapping for Fn-mapped keys, used to inject UP */
    private final java.util.Map<Integer, Integer> mFnDownKeyMap = new java.util.HashMap<>();

    /** keyCodes whose clean DOWN was injected for restore; physical UP triggers clean UP */
    private final java.util.Set<Integer> mRestoreDownPending = new java.util.HashSet<>();

    /** Consume next Meta UP after Win+` to prevent Start menu */
    private boolean mConsumeNextMetaUp = false;



    private int getFnTarget(KeyEvent event) {
        String currentKey = cfg != null ? cfg.fnProfileKey : "";
        if (mFnKeyCodeMap == null || !java.util.Objects.equals(currentKey, mFnMapProfileKey)) {
            mFnKeyCodeMap = buildFnKeyCodeMapping(currentKey);
            mFnMapProfileKey = currentKey;
        }
        Integer fKey = mFnKeyCodeMap.get(event.getKeyCode());
        return fKey != null ? fKey : 0;
    }

    /**
     * Build keyCode->F-key(131-142) mapping.
     * Uses keyCode because ZUI internally overwrites scanCode for custom keys (501-515).
     *
     * Keyboard top row layout (Lenovo Keyboard Pack For Yoga, 17ef:6271):
     *   ESC  Mute  Vol-  Vol+  MicMute  Bright-  Bright+  Screenshot  Maximize  Split  Star  Delete
     *   F1   F2    F3    F4    F5       F6       F7       F8          F9        F10    F11   F12
     *
     * ZUI keycode reference (KeyboardShortcutController.java):
     *   501=KEYCODE_LENOVO_VOLUME_MUTE, 502=KEYCODE_TP_MUTE, 503=KEYCODE_MIC_MUTE
     *   504=KEYCODE_SPLIT_SCREEN, 505=KEYCODE_SUPER_CONNECT, 507=KEYCODE_APP1
     *   508=KEYCODE_APP2, 509=KEYCODE_SEARCH, 510=KEYCODE_SETTINGS, 511=KEYCODE_FN_LOCK
     *   512=KEYCODE_BACK_LIGHT, 514=KEYCODE_TP_UP_MOVE, 515=KEYCODE_SCREEN_LOCK
     */
    private java.util.Map<Integer, Integer> buildFnKeyCodeMapping(String profileKey) {
        // Auto-detect: match connected keyboard VID:PID
        if (profileKey == null || profileKey.isEmpty()) {
            profileKey = detectKeyboardProfile();
            if (profileKey == null) {
                LogHelper.log(VerboseLevel.DEBUG, "Fn auto-detect: no matching keyboard");
                return java.util.Collections.emptyMap(); // treated as disabled
            }
            LogHelper.log(VerboseLevel.INFO, "Fn auto-detected profile: ", profileKey);
        }

        java.util.Map<Integer, Integer> map = new java.util.HashMap<>();
        // Built-in default mapping (17ef:6271 Lenovo Yoga Keyboard)
        if ("17ef:6271".equals(profileKey)) {
            map.put(KeyEvent.KEYCODE_ESCAPE,      131);
            map.put(501,                          132);
            map.put(KeyEvent.KEYCODE_VOLUME_DOWN, 133);
            map.put(KeyEvent.KEYCODE_VOLUME_UP,   134);
            map.put(KeyEvent.KEYCODE_MUTE,        135);
            map.put(220,                          136);
            map.put(221,                          137);
            map.put(KeyEvent.KEYCODE_SYSRQ,       138);
            map.put(500,                          139);
            map.put(504,                          140);
            map.put(507,                          141);
            map.put(KeyEvent.KEYCODE_FORWARD_DEL, 142);
            return map;
        }

        // 从自定义配置加载
        if (cfg != null && cfg.fnCustomProfiles != null) {
            try {
                Object raw = cfg.fnCustomProfiles.get(profileKey);
                if (raw == null) {
                    LogHelper.log(VerboseLevel.DEBUG, "Fn profile not found: ", profileKey);
                    return map;
                }
                com.google.gson.Gson gson = new com.google.gson.Gson();
                moe.lovefirefly.betterzuikey.KeyboardProfiles.Profile profile =
                    gson.fromJson(gson.toJsonTree(raw),
                        moe.lovefirefly.betterzuikey.KeyboardProfiles.Profile.class);

                if (profile.getKeys() == null || profile.getKeys().isEmpty()) {
                    LogHelper.log(VerboseLevel.WARNING, "Fn profile has no keys: ", profileKey);
                    return map;
                }

                int loaded = 0;
                for (int i = 1; i <= 12; i++) {
                    String fk = "F" + i;
                    moe.lovefirefly.betterzuikey.KeyboardProfiles.KeyEntry entry =
                        profile.getKeys().get(fk);
                    if (entry == null || !entry.isValid()) continue;
                    if (entry.getKeyCode() > 0) {
                        map.put(entry.getKeyCode(), 130 + i);
                        loaded++;
                    }
                }
                LogHelper.log(VerboseLevel.INFO, "Fn profile loaded: ",
                    profile.getFriendlyName(), " (", String.valueOf(loaded), "/12)");
            } catch (Exception e) {
                LogHelper.log(VerboseLevel.WARNING, "Fn profile load failed: ",
                    profileKey, " ", e.getMessage());
            }
        }
        return map;
    }

    /** 检测当前连接的键盘，匹配可用 profile。返回 key 或 null */
    private String detectKeyboardProfile() {
        if (mKscInstance == null) return null;
        try {
            android.content.Context ctx = (android.content.Context)
                XposedHelpers.getObjectField(mKscInstance, "mContext");
            android.hardware.input.InputManager im =
                (android.hardware.input.InputManager)
                    ctx.getSystemService(android.content.Context.INPUT_SERVICE);
            if (im == null) return null;
            for (int id : im.getInputDeviceIds()) {
                android.view.InputDevice dev = im.getInputDevice(id);
                if (dev == null) continue;
                String key = String.format("%04x:%04x", dev.getVendorId(), dev.getProductId());
                if ("17ef:6271".equals(key)) return key;
                if (cfg != null && cfg.fnCustomProfiles != null
                        && cfg.fnCustomProfiles.containsKey(key)) {
                    return key;
                }
            }
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "detectKeyboardProfile error:", t.getMessage());
        }
        return null;
    }

    private String keyCodeToString(int code) {
        try {
            return KeyEvent.keyCodeToString(code);
        } catch (Exception e) {
            return String.valueOf(code);
        }
    }

    /** 检查键盘检测 Activity 是否在前台 */
    private boolean isDetectMode() {
        if (mKscInstance == null) return false;
        try {
            android.content.Context ctx = (android.content.Context)
                XposedHelpers.getObjectField(mKscInstance, "mContext");
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

    /**
     * Inject a key event (DOWN + UP) via mPolicy.injectKeyEvent(int).
     */
    private void injectKey(int keyCode) {
        if (keyCode <= 0 || mKscInstance == null) return;
        try {
            // KSC.mPolicy is KeyboardZuiKeyInputPolicy, which has injectKeyEvent(int keyCode)
            Object policy = XposedHelpers.getObjectField(mKscInstance, "mPolicy");
            XposedHelpers.callMethod(policy, "injectKeyEvent", keyCode);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Fn injectKey failed (keyCode=", String.valueOf(keyCode), "):", t.getMessage());
        }
    }

    /** Inject a clean key DOWN event (metaState=0, no modifiers). */
    private void injectKeyDown(int keyCode) {
        injectKeyDown(keyCode, 0);
    }

    /** Inject key DOWN event with specified metaState (preserves Ctrl/Shift/Alt/Meta). */
    private void injectKeyDown(int keyCode, int metaState) {
        if (keyCode <= 0) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            android.view.KeyEvent ev = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_DOWN, keyCode,
                    0, metaState, 0, 0, 0);
            Object im = XposedHelpers.callStaticMethod(
                    android.hardware.input.InputManager.class, "getInstance");
            XposedHelpers.callMethod(im, "injectInputEvent", (android.view.InputEvent) ev, 0);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "injectKeyDown failed (keyCode=",
                    String.valueOf(keyCode), "):", t.getMessage());
        }
    }

    /** Inject a clean key UP event (metaState=0, no modifiers). */
    private void injectKeyUp(int keyCode) {
        if (keyCode <= 0) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            android.view.KeyEvent ev = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_UP, keyCode,
                    0, 0, 0, 0, 0);
            Object im = XposedHelpers.callStaticMethod(
                    android.hardware.input.InputManager.class, "getInstance");
            XposedHelpers.callMethod(im, "injectInputEvent", (android.view.InputEvent) ev, 0);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "injectKeyUp failed (keyCode=",
                    String.valueOf(keyCode), "):", t.getMessage());
        }
    }

    private static android.widget.Toast sFnToast = null;

    private static void showToast(String msg) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    if (sFnToast != null) {
                        sFnToast.cancel();
                    }
                    Object at = Class.forName("android.app.ActivityThread")
                        .getMethod("currentActivityThread").invoke(null);
                    android.content.Context ctx = (android.content.Context)
                        at.getClass().getMethod("getSystemContext").invoke(at);
                    sFnToast = android.widget.Toast.makeText(ctx, msg,
                        android.widget.Toast.LENGTH_SHORT);
                    sFnToast.show();
                } catch (Exception ignored) { }
            });
        } catch (Throwable ignored) { }
    }
}