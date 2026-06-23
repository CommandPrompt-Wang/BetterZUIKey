package moe.lovefirefly.betterzuikey.Hook;

import android.os.SystemClock;
import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import moe.lovefirefly.betterzuikey.ime.IMEDispatcher;
import moe.lovefirefly.betterzuikey.ime.IMEAdapter;
import moe.lovefirefly.betterzuikey.ime.AdapterManager;
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
        // Notify static Config holders so they don't operate on stale references
        moe.lovefirefly.betterzuikey.Region.RegionHook.updateConfig(cfg);
        moe.lovefirefly.betterzuikey.Region.FeatureHook.updateConfig(cfg);
        // Sync IME adapter bindings
        AdapterManager.syncBindings(cfg.imeAdapterBindings);
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
     * Inject a key event sequence produced by an IME adapter.
     *
     * @param adapter the adapter that produced the events
     * @return true if all events were dispatched
     */
    public boolean injectAdapterEvents(IMEAdapter adapter) {
        if (adapter == null) return false;
        java.util.List<KeyEvent> events = adapter.onShortcutTriggered();
        if (events == null || events.isEmpty()) return false;
        LogHelper.log(VerboseLevel.INFO, "IME injection: adapter '",
                adapter.getDisplayName(), "' → ", String.valueOf(events.size()), " events");
        return IMEDispatcher.injectKeyEvents(events);
    }

    /**
     * Inject a single KeyEvent through the guarded pipeline.
     */
    public boolean injectKeyEvent(KeyEvent event) {
        return IMEDispatcher.injectKeyEvent(event);
    }

    /**
     * Look up and trigger the IME adapter bound to a shortcut key.
     * If no custom adapter is configured and {@code fallbackToRemap} is true,
     * falls back to injecting Ctrl+Space.
     *
     * @param shortcutKey  the config shortcut key (e.g. "ctrlShift")
     * @param fallbackToRemap  whether to fall back to Ctrl+Space remapping
     * @return true if an adapter or remap was triggered
     */
    public boolean triggerIMEAdapter(String shortcutKey, boolean fallbackToRemap) {
        // Try custom adapter first
        IMEAdapter adapter = AdapterManager.getAdapterForShortcut(shortcutKey);
        if (adapter != null) {
            LogHelper.log(VerboseLevel.INFO, "IME: shortcut '",
                    shortcutKey, "' → adapter '", adapter.getDisplayName(), "'");
            return injectAdapterEvents(adapter);
        }
        // Fallback to Ctrl+Space remap
        if (fallbackToRemap) {
            LogHelper.log(VerboseLevel.INFO, "IME: shortcut '",
                    shortcutKey, "' → remap Ctrl+Space");
            return injectCtrlSpace();
        }
        return false;
    }

    // ----------------------------------------------------------------
    //  Meta key injection to App
    // ----------------------------------------------------------------

    /**
     * Inject raw Meta key events (DOWN + UP) to the focused application.
     * Uses the guarded {@link IMEDispatcher} pipeline so all hook layers
     * skip the injected events.
     *
     * Called from L1 when Meta short press action is NONE or
     * override mode is OFF/BLOCK — ZUI is blocked (param.setResult=true)
     * and L3 suppresses AOSP Start Menu (type=21), but the app still
     * receives synthetic Meta key events (e.g. for Emacs, terminal
     * shortcuts, or custom app-level bindings).
     *
     * @param deviceId the input device ID from the original physical event,
     *                 passed through to keep the synthetic event credible
     */
    public void injectMetaToApp(int deviceId) {
        // Match KeyInjector.injectKeyDown pattern — proper constructor
        long now = android.os.SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_META_LEFT,
                0, 0, deviceId, 0, 0);
        KeyEvent up = new KeyEvent(now + 50, now + 50,
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_META_LEFT,
                0, 0, deviceId, 0, 0);
        IMEDispatcher.injectKeyEvents(java.util.Arrays.asList(down, up));
        LogHelper.log(VerboseLevel.INFO, "Meta → injected to App via IMEDispatcher (deviceId=",
                String.valueOf(deviceId), ")");
    }
}
