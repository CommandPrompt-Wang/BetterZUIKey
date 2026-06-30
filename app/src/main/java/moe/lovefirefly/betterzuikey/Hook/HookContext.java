package moe.lovefirefly.betterzuikey.Hook;

import android.os.SystemClock;
import android.view.KeyEvent;

import moe.lovefirefly.betterzuikey.Hook.HookCompat;


import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import moe.lovefirefly.betterzuikey.ime.IMEDispatcher;
import moe.lovefirefly.betterzuikey.ime.IMEProfileManager;
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

    /** Lenovo keyboard firmware scanCode handled in ZUI case 117. */
    public static final int ZUI_META_SCAN_CODE = 787345;
    /** ZUI short vs long Meta threshold (ms), matches mLaunchAssistantRunnable delay. */
    public static final long ZUI_META_LONG_PRESS_MS = 2000L;

    /** Per-press Win/Meta session — reset on DOWN, cleared after UP handling. */
    public final MetaKeySession metaSession = new MetaKeySession();

    /** ScanCode of the last physical Meta DOWN (for L4 fallback). */
    public int lastMetaScanCode = 0;

    /** Set when L0 triggers Start Menu; suppress duplicate type=21. */
    public boolean metaStartMenuDispatched = false;

    /** Lazy-capture KSC from L0/L1 hook thisObject when constructor hook missed. */
    public void ensureKscFromHook(Object hookThis) {
        if (kscInstance == null && hookThis != null) {
            kscInstance = hookThis;
            MetaTrace.decision("Policy", "kscInstance captured from hook");
        }
    }

    /** ZUI policy/KSC handler — same queue as {@code mLaunchAssistantRunnable}. */
    public android.os.Handler resolvePolicyHandler() {
        if (kscInstance != null) {
            try {
                return (android.os.Handler) HookCompat.getObjectField(kscInstance, "mHandler");
            } catch (Throwable ignored) {
            }
        }
        if (policyInstance != null) {
            try {
                return (android.os.Handler) HookCompat.getObjectField(policyInstance, "mHandler");
            } catch (Throwable ignored) {
            }
        }
        return new android.os.Handler(android.os.Looper.getMainLooper());
    }

    public static final class MetaKeySession {
        public boolean active;
        public boolean upHandled;
        public long downTimeMs;
        public int scanCode;
        public int keyCode = -1;
        public int deviceId = -1;
        /** Voice or IME long-press fired during this press. */
        public boolean longFired;
        /** A top-row key was Fn-mapped during this Meta press (Win consumed as modifier). */
        public boolean fnMapped;

        /** @return true if session was started; false if overlapping DOWN was ignored */
        public boolean begin(KeyEvent event) {
            if (active && !upHandled) {
                MetaTrace.decision("Session", "skip overlapping DOWN",
                        "held kc=", String.valueOf(keyCode),
                        " new kc=", String.valueOf(event.getKeyCode()),
                        " sc=", String.valueOf(event.getScanCode()));
                return false;
            }
            active = true;
            upHandled = false;
            longFired = false;
            fnMapped = false;
            downTimeMs = System.currentTimeMillis();
            scanCode = event.getScanCode();
            keyCode = event.getKeyCode();
            deviceId = event.getDeviceId();
            return true;
        }

        public boolean isShortPress() {
            if (!active || downTimeMs == 0) return false;
            return System.currentTimeMillis() - downTimeMs < ZUI_META_LONG_PRESS_MS;
        }

        public void clear() {
            active = false;
            upHandled = false;
            longFired = false;
            fnMapped = false;
            downTimeMs = 0;
            keyCode = -1;
        }
    }

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
        fnKeyManager.setHookContext(this);
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
        // Sync FnKeyManager with new Config (was holding stale ref → master switch broken)
        if (fnKeyManager != null) {
            fnKeyManager.setConfig(cfg);
        }
        // Resync: ForegroundTracker holds old resolver ref + new resolver needs current foreground pkg
        if (foregroundTracker != null) {
            foregroundTracker.setResolver(resolver);
            String pkg = foregroundTracker.getForegroundPackage();
            if (pkg != null) resolver.setForegroundPackage(pkg);
        }
        // Notify static Config holders so they don't operate on stale references
        moe.lovefirefly.betterzuikey.Region.FeatureHook.updateConfig(cfg);
        // Reload IME profiles on config change
        IMEProfileManager.reload();
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
                                         HookCompat.HookParam param,
                                         String logLabel, int blockKeyCode) {
        if (mode == Config.OverrideMode.BLOCK && blockKeyCode != 0) {
            fnKeyManager.setPendingBlockedWinComboUp(blockKeyCode);
        }
        return applyInterceptAction(mode, param, logLabel);
    }

    /** L0/L1 override dispatch (no BLOCK UP cleanup). */
    public boolean applyInterceptAction(Config.OverrideMode mode,
                                         HookCompat.HookParam param,
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
                    HookCompat.getObjectField(kscInstance, "mContext");
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

    // ----------------------------------------------------------------
    //  IME injection guard — ThreadLocal flag to prevent re-processing
    //  injected events through the hook chain.
    // ----------------------------------------------------------------

    /** @return true if the current thread is injecting a synthetic key event */
    public boolean isInjecting() {
        return IMEDispatcher.isInjecting();
    }

    // ----------------------------------------------------------------
    //  IME state detection
    // ----------------------------------------------------------------

    /** Delegates to {@link IMEDispatcher#isAcceptingText()}. */
    public boolean isAcceptingText() {
        return IMEDispatcher.isAcceptingText();
    }

    // ----------------------------------------------------------------
    //  IME key injection (via InputManager, guarded by ThreadLocal)
    // ----------------------------------------------------------------

    /**
     * Inject a synthetic Ctrl+Space key pair to the IME.
     * This is the default remap target when Ctrl+Shift is remapped.
     * The injected events carry the {@link IMEDispatcher#INJECTING} marker
     * so all hook layers skip them.
     *
     * @return true if the events were dispatched
     */
    public boolean injectCtrlSpace() {
        return IMEDispatcher.injectKeyEvents(IMEDispatcher.createCtrlSpaceEvents());
    }

    /**
     * Try to execute an IME profile strategy for the current IME.
     * Called from L4 when a shortcut key (Ctrl+Shift/Alt+Shift) is triggered
     * while the IME is accepting text.
     *
     * If the current IME has a matching JSON profile, execute its strategy.
     * If no profile matches, do nothing — return false so the caller falls
     * through to native OverrideMode logic.
     *
     * @return true if an IME strategy was executed
     */
    /**
     * Switch to the next IME via ZUI's switchInputMethod().
     * Also resolves the current IME name for toast display.
     * @return IME display name, or null on error
     */
    public String switchInputMethod() {
        if (policyInstance == null) {
            LogHelper.log(VerboseLevel.ERROR, "IME switch: policyInstance is null");
            return null;
        }
        try {
            // Get current IME name before switching
            String before = getCurrentIMEName();
            policyInstance.getClass()
                    .getMethod("switchInputMethod")
                    .invoke(policyInstance);
            String after = getCurrentIMEName();
            // If unchanged (only 1 IME), show the same name; else show the new one
            String label = (after != null) ? after : before;
            LogHelper.log(VerboseLevel.INFO, "IME switch: ", before, " → ", after);
            return label;
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "IME switch: failed: ", t.getMessage());
            return null;
        }
    }

    private String getCurrentIMEName() {
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            android.content.Context sysCtx = (android.content.Context)
                    at.getClass().getMethod("getSystemContext").invoke(at);
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                    sysCtx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            java.util.List<android.view.inputmethod.InputMethodInfo> list =
                    imm.getEnabledInputMethodList();
            String defaultId = android.provider.Settings.Secure.getString(
                    sysCtx.getContentResolver(), "default_input_method");
            for (android.view.inputmethod.InputMethodInfo info : list) {
                if (info.getId().equals(defaultId)) {
                    return info.loadLabel(sysCtx.getPackageManager()).toString();
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public boolean triggerIMEProfile() {
        String imePkg = IMEDispatcher.getCurrentIMEPackage();
        if (IMEProfileManager.executeForIME(imePkg)) {
            LogHelper.log(VerboseLevel.INFO, "IME: profile strategy executed for '",
                    imePkg != null ? imePkg : "<null>", "'");
            return true;
        }
        return false;
    }

    /**
     * Inject a single KeyEvent through the guarded pipeline.
     */
    public boolean injectKeyEvent(KeyEvent event) {
        return IMEDispatcher.injectKeyEvent(event);
    }

    // ----------------------------------------------------------------
    //  Meta key policy (Start Menu / voice)
    // ----------------------------------------------------------------

    /** Cancel ZUI's 2s voice-assistant timer posted from case 117 Meta DOWN. */
    public void cancelZuiAssistantTimer() {
        if (kscInstance == null || policyInstance == null) return;
        try {
            Object runnable = HookCompat.getObjectField(
                    policyInstance, "mLaunchAssistantRunnable");
            Object handler = HookCompat.getObjectField(kscInstance, "mHandler");
            if (runnable != null && handler != null) {
                HookCompat.callMethod(handler, "removeCallbacks", runnable);
            }
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG,
                    "cancelZuiAssistantTimer:", t.getMessage());
        }
    }

    /** ZUI Start Menu — {@code KeyboardZuiKeyInputPolicy.triggerShowAllApps} (A15/16). */
    public void triggerShowAllApps(int displayId) {
        MetaTrace.decision("Policy", "start menu triggerShowAllApps",
                "displayId=", String.valueOf(displayId),
                " policy=", policyInstance != null ? "ok" : "NULL");
        if (policyInstance == null) {
            LogHelper.log(VerboseLevel.ERROR,
                    "triggerShowAllApps: policyInstance is null");
            return;
        }
        metaStartMenuDispatched = true;
        try {
            policyInstance.getClass()
                    .getMethod("triggerShowAllApps", boolean.class, int.class)
                    .invoke(policyInstance, true, displayId);
            MetaTrace.decision("Policy", "triggerShowAllApps ok",
                    "displayId=", String.valueOf(displayId));
        } catch (Throwable t) {
            MetaTrace.decision("Policy", "triggerShowAllApps FAIL", t.getMessage());
            LogHelper.log(VerboseLevel.ERROR,
                    "triggerShowAllApps failed:", t.getMessage());
        }
    }

    /**
     * Win long-press while accepting text and bound to WIN on the IME page.
     * Dispatches to switch-input-method or language profile depending on binding.
     */
    public void dispatchWinLongPressIme() {
        if (cfg == null || !cfg.imeMasterEnabled || !isAcceptingText()) return;
        if (cfg.imeSwitchBinding == Config.IMEBinding.WIN) {
            LogHelper.log(VerboseLevel.INFO, "Win long → switch IME");
            String imeName = switchInputMethod();
            if (cfg.imeToastEnabled && imeName != null) {
                KeyInjector.showToast(imeName);
            }
        } else if (cfg.languageSwitchBinding == Config.IMEBinding.WIN) {
            LogHelper.log(VerboseLevel.INFO, "Win long → switch language (profile)");
            boolean ok = triggerIMEProfile();
            if (cfg.imeToastEnabled) {
                KeyInjector.showToast(ok ? "Language switched" : "No IME profile matched");
            }
        }
    }

    /** Mirror ZUI {@code mLaunchAssistantRunnable} — voice assistant on Win long press. */
    public void launchVoiceAssistant() {
        int deviceId = metaSession.deviceId >= 0 ? metaSession.deviceId : 0;
        PassthroughTrace.noteMsg("Policy", "launchVoiceAssistant call dev=" + deviceId);
        MetaTrace.decision("Policy", "launchVoiceAssistant call",
                "policy=", policyInstance != null ? "ok" : "NULL",
                " dev=", String.valueOf(deviceId));
        LogHelper.log(VerboseLevel.INFO,
                "launchVoiceAssistant: dev=", String.valueOf(deviceId));
        if (policyInstance == null) {
            PassthroughTrace.noteMsg("Policy", "launchVoiceAssistant FAIL policy=null");
            LogHelper.log(VerboseLevel.ERROR, "launchVoiceAssistant: policyInstance null");
            return;
        }
        try {
            Object delegate = HookCompat.getObjectField(policyInstance, "mServiceDelegate");
            Object service = HookCompat.getObjectField(delegate, "mService");
            HookCompat.callMethod(service, "launchAssistActionExternal",
                    null, deviceId, System.currentTimeMillis(), 7);
            PassthroughTrace.noteMsg("Policy", "launchVoiceAssistant ok dev=" + deviceId);
            MetaTrace.decision("Policy", "launchVoiceAssistant ok",
                    "dev=", String.valueOf(deviceId));
            LogHelper.log(VerboseLevel.INFO,
                    "launchVoiceAssistant ok dev=", String.valueOf(deviceId));
        } catch (Throwable t) {
            PassthroughTrace.noteMsg("Policy", "launchVoiceAssistant FAIL " + t.getMessage());
            MetaTrace.decision("Policy", "launchVoiceAssistant FAIL", t.getMessage());
            LogHelper.log(VerboseLevel.ERROR,
                    "launchVoiceAssistant failed:", t.getMessage());
        }
    }
}
