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

    /** Meta UP must not open Start Menu (Win was used as modifier). Cleared on next Meta DOWN. */
    public boolean metaSuppressStartMenu = false;

    /** UP cleanup after 507/508 was blocked on DOWN. */
    public int appKeyPendingBlockUp = 0;

    /** Per-press 507/508 session for short vs long (CUSTOM). */
    public final AppKeySession appKeySession = new AppKeySession();

    public static final class AppKeySession {
        public boolean active;
        public int keyCode;
        public long downTimeMs;
        /** Long-press timer fired (editor opened). */
        public boolean longFired;
    }

    private android.os.Handler appKeyLongHandler;
    private Runnable appKeyLongRunnable;
    private android.os.Handler fallbackMainHandler;

    private android.os.Handler getFallbackMainHandler() {
        if (fallbackMainHandler == null) {
            fallbackMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return fallbackMainHandler;
    }

    private void cancelAppKeyLongTimer() {
        if (appKeyLongHandler != null && appKeyLongRunnable != null) {
            appKeyLongHandler.removeCallbacks(appKeyLongRunnable);
        }
        appKeyLongRunnable = null;
    }

    private void armAppKeyLongTimer(int keyCode, String appKey, String label) {
        cancelAppKeyLongTimer();
        android.os.Handler handler = resolvePolicyHandler();
        appKeyLongHandler = handler;
        appKeyLongRunnable = () -> {
            if (!appKeySession.active || appKeySession.keyCode != keyCode) {
                LogHelper.log(VerboseLevel.INFO, label,
                        " long timer stale (session inactive or kc mismatch)");
                return;
            }
            appKeySession.longFired = true;
            long held = SystemClock.uptimeMillis() - appKeySession.downTimeMs;
            LogHelper.log(VerboseLevel.INFO, label,
                    " long timer fired held=", String.valueOf(held), "ms → open editor appKey=", appKey);
            configIPC.openAppKeyCommandEditor(appKey);
        };
        handler.postDelayed(appKeyLongRunnable, ZUI_META_LONG_PRESS_MS);
        LogHelper.log(VerboseLevel.INFO, label,
                " arm long timer ", String.valueOf(ZUI_META_LONG_PRESS_MS),
                "ms appKey=", appKey, " handler=", handler.getLooper().getThread().getName());
    }

    /** Lazy-capture KSC from L0/L1 hook thisObject when constructor hook missed. */
    public void ensureKscFromHook(Object hookThis) {
        if (kscInstance == null && hookThis != null) {
            kscInstance = hookThis;
            MetaTrace.decision("Policy", "kscInstance captured from hook");
        }
    }

    /** ZUI policy/KSC handler — same queue as {@code mLaunchAssistantRunnable}. */
    public android.os.Handler resolvePolicyHandler() {
        android.os.Handler handler = null;
        if (kscInstance != null) {
            try {
                handler = (android.os.Handler) HookCompat.getObjectField(kscInstance, "mHandler");
            } catch (Throwable ignored) {
            }
        }
        if (handler == null && policyInstance != null) {
            try {
                handler = (android.os.Handler) HookCompat.getObjectField(policyInstance, "mHandler");
            } catch (Throwable ignored) {
            }
        }
        if (handler == null) {
            handler = getFallbackMainHandler();
            LogHelper.log(VerboseLevel.DEBUG, "AppKey: resolvePolicyHandler → main looper fallback");
        }
        return handler;
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
        /** Another key was pressed with Win held (Win+D etc.). */
        public boolean winComboUsed;

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
            winComboUsed = false;
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
            winComboUsed = false;
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
    //  ZUI smart keys (507/508)
    // ----------------------------------------------------------------

    /**
     * Always capture 507/508 at L0.
     * FOLLOW_SYSTEM → pass to ZUI (incl. long press → system editor);
     * BLOCK → consume; CUSTOM → short=run script, long=open module editor.
     *
     * @return true if caller should return immediately
     */
    public boolean handleZuiAppKey(int keyCode, boolean down, int repeatCount,
                                   HookCompat.HookParam param) {
        if (keyCode != 507 && keyCode != 508) return false;

        String key = keyCode == 507 ? "keyApp1" : "keyApp2";
        Config.SwitchState sw = keyCode == 507 ? cfg.switchKeyApp1 : cfg.switchKeyApp2;
        Config.AppKeyMode mode = keyCode == 507 ? cfg.app1Mode : cfg.app2Mode;
        String label = "AppKey:" + (keyCode == 507 ? "507" : "508");

        if (!down) {
            LogHelper.log(VerboseLevel.INFO, label, " UP",
                    " pending=", String.valueOf(appKeyPendingBlockUp),
                    " session=", appKeySession.active ? "active" : "idle",
                    " sessKc=", String.valueOf(appKeySession.keyCode),
                    " longFired=", String.valueOf(appKeySession.longFired),
                    " mode=", mode.name());
            if (appKeyPendingBlockUp == keyCode) {
                appKeyPendingBlockUp = 0;
                cancelAppKeyLongTimer();
                if (appKeySession.active && appKeySession.keyCode == keyCode) {
                    appKeySession.active = false;
                    Config.AppKeyMode upMode = keyCode == 507 ? cfg.app1Mode : cfg.app2Mode;
                    long held = SystemClock.uptimeMillis() - appKeySession.downTimeMs;
                    if (upMode == Config.AppKeyMode.CUSTOM) {
                        if (appKeySession.longFired) {
                            LogHelper.log(VerboseLevel.INFO, label,
                                    " → CUSTOM UP after long (skip short) held=", String.valueOf(held), "ms");
                        } else {
                            String command = keyCode == 507 ? cfg.app1Command : cfg.app2Command;
                            boolean commandRoot = keyCode == 507 ? cfg.app1CommandRoot : cfg.app2CommandRoot;
                            boolean commandSingleton = keyCode == 507 ? cfg.app1CommandSingleton : cfg.app2CommandSingleton;
                            int commandTimeoutMin = keyCode == 507 ? cfg.app1CommandTimeoutMin : cfg.app2CommandTimeoutMin;
                            if (command != null && !command.trim().isEmpty()) {
                                configIPC.runAppKeyCommand(command, commandRoot, commandSingleton, commandTimeoutMin);
                                LogHelper.log(VerboseLevel.INFO, label, " → CUSTOM short held=", String.valueOf(held),
                                        "ms RUN_COMMAND root=", String.valueOf(commandRoot),
                                        " len=", String.valueOf(command.length()));
                            } else {
                                LogHelper.log(VerboseLevel.INFO, label,
                                        " → CUSTOM short held=", String.valueOf(held), "ms (empty script)");
                            }
                        }
                    }
                    appKeySession.longFired = false;
                } else {
                    LogHelper.log(VerboseLevel.WARNING, label,
                            " UP pending matched but session missing/inactive");
                }
                LogHelper.log(VerboseLevel.DEBUG, label, " UP → consumed (pending BLOCK)");
                param.setResult(true);
                return true;
            }
            if (appKeyPendingBlockUp != 0) {
                LogHelper.log(VerboseLevel.DEBUG, label,
                        " UP ignored (pending=", String.valueOf(appKeyPendingBlockUp), ")");
            }
            return false;
        }

        LogHelper.log(VerboseLevel.INFO, label, " DOWN",
                " mode=", mode.name(),
                " switch=", sw.name(),
                " repeat=", String.valueOf(repeatCount));

        if (!r(key, sw).isEnabled()) {
            LogHelper.log(VerboseLevel.INFO, label, " → BLOCK (switch OFF)");
            param.setResult(true);
            appKeyPendingBlockUp = keyCode;
            return true;
        }

        if (repeatCount > 0) {
            if (appKeyPendingBlockUp == keyCode) {
                param.setResult(true);
                return true;
            }
            return false;
        }

        switch (mode) {
            case FOLLOW_SYSTEM:
                LogHelper.log(VerboseLevel.DEBUG, label, " → FOLLOW_SYSTEM (pass to ZUI)");
                return false;
            case BLOCK:
                LogHelper.log(VerboseLevel.INFO, label, " → BLOCK (ignore)");
                param.setResult(true);
                appKeyPendingBlockUp = keyCode;
                return true;
            case CUSTOM:
                appKeySession.active = true;
                appKeySession.keyCode = keyCode;
                appKeySession.downTimeMs = SystemClock.uptimeMillis();
                appKeySession.longFired = false;
                param.setResult(true);
                appKeyPendingBlockUp = keyCode;
                armAppKeyLongTimer(keyCode, key, label);
                LogHelper.log(VerboseLevel.DEBUG, label, " → CUSTOM (await short UP / long timer)");
                return true;
            default:
                return false;
        }
    }

    // ----------------------------------------------------------------
    //  Keyboard detect mode
    // ----------------------------------------------------------------

    private static final long DETECT_CACHE_MS = 100L;
    private volatile boolean keyboardDetectCached = false;
    private long keyboardDetectCacheTime = 0L;

    /** Write keyCode/scanCode + device info for KeyboardDetectActivity polling. */
    public void writeDetectKeyProperties(int keyCode, boolean down, int repeatCount,
                                         KeyEvent event) {
        if (!down || repeatCount != 0) return;
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
        } catch (Throwable ignored) {
        }
    }

    /**
     * KeyboardDetectActivity is foreground — read flag via ContentProvider IPC
     * (SystemProperties from app process is blocked by SELinux on Android 16).
     */
    public boolean isDetectMode() {
        long now = SystemClock.uptimeMillis();
        if (now - keyboardDetectCacheTime >= DETECT_CACHE_MS) {
            keyboardDetectCached = configIPC.isKeyboardDetectActive();
            keyboardDetectCacheTime = now;
        }
        return keyboardDetectCached;
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

    /**
     * Win was held as a modifier (Win+D etc.). Suppress Start Menu on Meta UP and L4 type=21.
     */
    public void noteWinComboDuringMetaSession(KeyEvent event) {
        if (cfg == null || !cfg.zuxKeyboardFuncEnabled) return;
        if (!metaSession.active || metaSession.upHandled) return;
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            return;
        }
        if ((event.getMetaState() & KeyEvent.META_META_MASK) == 0) return;
        if (event.getScanCode() == 0) return;
        if (metaSession.winComboUsed) return;
        metaSession.winComboUsed = true;
        metaSuppressStartMenu = true;
        fnKeyManager.cancelWinLongPressTimer();
        cancelZuiAssistantTimer();
        MetaTrace.decision("Session", "winComboUsed",
                "kc=", String.valueOf(keyCode));
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

    /** Win long-press (≥2s): CUSTOM runs shell script; FOLLOW_SYSTEM launches voice assistant. */
    public void dispatchWinLongCommand() {
        if (cfg == null) return;
        String command = cfg.winLongCommand;
        if (command == null || command.trim().isEmpty()) {
            LogHelper.log(VerboseLevel.INFO, "WinLong CUSTOM: empty script");
            return;
        }
        configIPC.runAppKeyCommand(
                command, cfg.winLongCommandRoot, cfg.winLongCommandSingleton,
                cfg.winLongCommandTimeoutMin);
        LogHelper.log(VerboseLevel.INFO, "WinLong CUSTOM: RUN_COMMAND len=",
                String.valueOf(command.length()));
    }

    /** Mirror ZUI {@code mLaunchAssistantRunnable} — voice assistant on Win long press. */
    public void launchVoiceAssistant() {
        int deviceId = metaSession.deviceId >= 0 ? metaSession.deviceId : 0;
        PassthroughTrace.noteMsg("Policy", "launchVoiceAssistant call dev=" + deviceId);
        MetaTrace.decision("Policy", "launchVoiceAssistant call",
                "policy=", policyInstance != null ? "ok" : "NULL",
                " dev=", String.valueOf(deviceId),
                " prc=", String.valueOf(isPrcRegion()));
        LogHelper.log(VerboseLevel.INFO,
                "launchVoiceAssistant: dev=", String.valueOf(deviceId),
                " prc=", String.valueOf(isPrcRegion()));
        if (policyInstance == null) {
            PassthroughTrace.noteMsg("Policy", "launchVoiceAssistant FAIL policy=null");
            LogHelper.log(VerboseLevel.ERROR, "launchVoiceAssistant: policyInstance null");
            return;
        }
        if (isPrcRegion()) {
            try {
                HookCompat.callMethod(policyInstance, "launchXiaoTianAgent");
                PassthroughTrace.noteMsg("Policy", "launchVoiceAssistant ok (PRC XiaoTian)");
                MetaTrace.decision("Policy", "launchXiaoTianAgent ok");
                LogHelper.log(VerboseLevel.INFO,
                        "launchVoiceAssistant: launchXiaoTianAgent (PRC)");
                return;
            } catch (Throwable t) {
                LogHelper.log(VerboseLevel.WARNING,
                        "launchXiaoTianAgent failed, fallback launchAssist: ",
                        t.getMessage() != null ? t.getMessage() : "");
            }
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

    /** {@code ro.config.lgsi.region != row} — PRC/CN uses 小天 / 乐语音 for AI entry points. */
    private boolean isPrcRegion() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String region = (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, "ro.config.lgsi.region", "");
            return !"row".equalsIgnoreCase(region);
        } catch (Throwable t) {
            return true;
        }
    }
}
