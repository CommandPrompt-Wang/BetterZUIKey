package moe.lovefirefly.betterzuikey.Hook;

import android.os.SystemClock;
import android.view.KeyEvent;

import moe.lovefirefly.betterzuikey.Hook.HookCompat;


import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.MetaKeyMap;
import moe.lovefirefly.betterzuikey.ShortcutMeta;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import moe.lovefirefly.betterzuikey.ime.IMEDispatcher;
import moe.lovefirefly.betterzuikey.ime.IMEProfile;
import moe.lovefirefly.betterzuikey.ime.IMEProfileManager;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * Shared mutable state and convenience methods for all interceptor classes.
 * Passed to L0/L1/L3/L4 interceptors on construction.
 */
public class HookContext {

    // ---- Mutable state (updated by checkConfigChanged / constructor hook) ----

    public volatile Config cfg;
    public volatile ConfigResolver resolver;
    public volatile Object kscInstance;
    public volatile Object policyInstance;

    /** Lenovo keyboard firmware scanCode handled in ZUI case 117. */
    public static final int ZUI_META_SCAN_CODE = 787345;
    /** ZUI short vs long Meta threshold (ms), matches mLaunchAssistantRunnable delay. */
    public static final long ZUI_META_LONG_PRESS_MS = 2000L;

    /** Per-press Win/Meta session — reset on DOWN, cleared after UP handling. */
    public final MetaKeySession metaSession = new MetaKeySession();

    /** ScanCode of the last physical Meta DOWN (for L4 fallback). */
    public volatile int lastMetaScanCode = 0;

    /** Set when L0 triggers Start Menu; suppress duplicate type=21. */
    public volatile boolean metaStartMenuDispatched = false;

    /** Meta UP must not open Start Menu (Win was used as modifier). Cleared on next Meta DOWN. */
    public volatile boolean metaSuppressStartMenu = false;

    /**
     * True if this Meta event is the synthetic "single press" tap injected by
     * {@code metaSingle = OFF} (&quot;放行&quot;) mode.
     * <p>
     * Injected events are normalized by InputDispatcher to {@code deviceId = -1};
     * physical events always carry a real device id. This makes the check fully
     * structural — no timing window, so a quick tap followed by a real
     * {@code Win+X} combo can never be misclassified (which would leak the Win
     * key into the combo).
     */
    public boolean isSyntheticMetaTap(KeyEvent event) {
        return event != null && event.getDeviceId() < 0;
    }

    // ----------------------------------------------------------------
    //  「映射到…」注入守卫
    // ----------------------------------------------------------------

    /** 模块自己注入的映射按键的放行截止时刻（uptimeMillis）；0 = 未武装。 */
    private volatile long remapInjectUntil = 0L;

    /** 在 {@code KeyInjector.injectCombo()} 之前调用，给注入的事件开一个放行窗口。 */
    public void armRemapInject() {
        remapInjectUntil = android.os.SystemClock.uptimeMillis() + 300L;
    }

    /**
     * 是否为模块自己为「映射到…」注入的按键。
     *
     * <p>注入的事件会再次流经 L0/L1，必须整体放行 —— 否则映射出来的组合键会被
     * 模块自己的快捷键表再消费一次（例如把 Win 映射成 Ctrl+Shift+T，反而触发了
     * 触控板开关）。
     *
     * <p>判据用 {@code deviceId < 0}（InputDispatcher 对注入事件的归一化结果，
     * 物理键盘永远是正数），再叠一个 300ms 的短窗口，真实按键不会误伤。
     */
    public boolean isRemapInjecting(KeyEvent event) {
        long until = remapInjectUntil;
        if (until == 0L || android.os.SystemClock.uptimeMillis() > until) return false;
        return event != null && event.getDeviceId() < 0;
    }

    /** UP cleanup after 507/508 was blocked on DOWN. */
    public volatile int appKeyPendingBlockUp = 0;

    /**
     * IME modifier-chord session (Ctrl+Shift / Alt+Shift).
     * Passive tracking only — modifiers always pass through so Ctrl+Shift+Arrow
     * selection keeps working. Fires on a clean release; any non-modifier key
     * while armed contaminates and cancels the pending IME action.
     */
    public final ImeChordSession imeChord = new ImeChordSession();

    public static final class ImeChordSession {
        public volatile boolean armed;
        public volatile boolean contaminated;
        public volatile Config.IMEBinding chord;

        public void reset() {
            armed = false;
            contaminated = false;
            chord = null;
        }
    }

    /** Per-press 507/508 session for short vs long (CUSTOM). */
    public final AppKeySession appKeySession = new AppKeySession();

    public static final class AppKeySession {
        public volatile boolean active;
        public volatile int keyCode;
        public volatile long downTimeMs;
        /** Long-press timer fired (editor opened). */
        public volatile boolean longFired;
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
                LogHelper.log(VerboseLevel.DEBUG, label,
                        " long timer stale (session inactive or kc mismatch)");
                return;
            }
            appKeySession.longFired = true;
            long held = SystemClock.uptimeMillis() - appKeySession.downTimeMs;
            LogHelper.log(VerboseLevel.DEBUG, label,
                    " long timer fired held=", String.valueOf(held), "ms → open editor appKey=", appKey);
            configIPC.openAppKeyCommandEditor(appKey);
        };
        handler.postDelayed(appKeyLongRunnable, ZUI_META_LONG_PRESS_MS);
        LogHelper.log(VerboseLevel.DEBUG, label,
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
            } catch (Throwable t) {
                LogHelper.log(VerboseLevel.DEBUG, "resolvePolicyHandler ksc mHandler:", t.getMessage());
            }
        }
        if (handler == null && policyInstance != null) {
            try {
                handler = (android.os.Handler) HookCompat.getObjectField(policyInstance, "mHandler");
            } catch (Throwable t) {
                LogHelper.log(VerboseLevel.DEBUG, "resolvePolicyHandler policy mHandler:", t.getMessage());
            }
        }
        if (handler == null) {
            handler = getFallbackMainHandler();
            LogHelper.log(VerboseLevel.DEBUG, "AppKey: resolvePolicyHandler → main looper fallback");
        }
        return handler;
    }

    /**
     * 开始一轮 Meta 按住。
     *
     * <p>若上一轮没等到 Meta UP（丢事件）就又有新的一轮，先把扣押的外加键补发掉 ——
     * 用户按过的键不能因为我们扣过就丢了。
     */
    public boolean beginMetaSession(KeyEvent event) {
        if (!pendingExtras.isEmpty()) flushPendingExtras("new Meta DOWN");
        return metaSession.begin(event);
    }

    public static final class MetaKeySession {
        public volatile boolean active;
        public volatile boolean upHandled;
        public volatile long downTimeMs;
        public volatile int scanCode;
        public volatile int keyCode = -1;
        public volatile int deviceId = -1;
        /** Voice or IME long-press fired during this press. */
        public volatile boolean longFired;
        /** A top-row key was Fn-mapped during this Meta press (Win consumed as modifier). */
        public volatile boolean fnMapped;
        /** Another key was pressed with Win held (Win+D etc.). */
        public volatile boolean winComboUsed;
        /**
         * 本轮已并进「映射到…」目标的那个按键（0 = 没有）。
         *
         * <p>它的 DOWN 重复与 UP 必须继续被吃掉，否则应用会收到
         * 一个没有 DOWN 的孤立 UP（修饰键状态错乱）。
         */
        public volatile int mergedKeyCode = 0;

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
            mergedKeyCode = 0;
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
            // mergedKeyCode 故意不在这里清：合并键的 UP 可能晚于 Meta 的 UP
            // （见 consumeMergedComboKey）。它由 begin() 或那个键自己的 UP 清除。
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

    /**
     * metaSingle == OFF → 真·放行（passthrough）。
     *
     * <p>含义：模块和框架都不消费 Meta，物理 DOWN/UP 原样派发给前台应用，
     * 不弹出开始菜单/程序坞。
     *
     * <p>为什么需要 {@code MainHook} 里的 KeyGestureController hook：
     * AOSP {@code KeyGestureController.interceptSystemKeysAndShortcuts()} 对
     * keyCode 117/118 是<b>硬编码</b>消费（DOWN 记账、UP 生成 type=21 →
     * {@code triggerShowAllApps}）。它位于 L1 之后、且注入事件也会被它吃掉，
     * 所以只靠模块在 L0/L1 放行/重注入都不可能把 Meta 送到应用。
     * 必须在 KeyGestureController 处一并放行。
     *
     * <p>还需要 {@code MainHook.hookPhoneWindowManagerSystemKeys()}：
     * {@code PhoneWindowManager.interceptSystemKeysAndShortcuts()} 的尾部是
     * {@code (metaState & META_META_ON) != 0 → return true}，即「Win 按下时事件不给应用」。
     * Meta 键自身的 metaState 就带 {@code META_META_ON}，所以即使 KGC 已放行，
     * 它仍会把物理 Meta 吞掉 —— 这是「放行了但应用还是收不到」的根因。
     */
    public boolean isMetaPassthrough() {
        if (cfg == null || !cfg.zuxKeyboardFuncEnabled) return false;
        // 该 key 是否走「映射到…」由 resolver 统一判定（全局开关 + 模板覆写）
        if (resolver != null && resolver.usesMap("metaSingle")) return false;
        return ra("metaSingle", cfg.overrideMetaSingle) == Config.OverrideMode.OFF;
    }

    /**
     * metaSingle == 映射到… → 独立单击 Win 时，改为向应用注入映射的按键 / 组合键。
     *
     * <p>与放行模式共用同一套「扣押 DOWN、UP 再决定」的骨架，区别只在最后发什么：
     * 放行发一对还原后的 Meta，映射发 {@link Config#metaSingleMap 指定的组合键}。
     * 映射目标为空时不算数（回落给系统，避免出现「Win 单按什么都不做」的死状态）。
     */
    /** 当前生效的「映射到…」目标（模板可自带目标，否则用全局）。 */
    public String effectiveMapTarget() {
        return resolver != null ? resolver.effectiveMapTarget("metaSingle") : cfg.metaSingleMap;
    }

    public boolean isMetaMapped() {
        if (cfg == null || !cfg.zuxKeyboardFuncEnabled) return false;
        if (!MetaKeyMap.parse(effectiveMapTarget()).isSet()) return false;
        // 全局开启、或模板显式选了「映射到…」都算；模板选了标准五档则映射让位
        return resolver != null && resolver.usesMap("metaSingle");
    }

    /**
     * Meta 是否被模块接管 —— 关闭（放行）或 映射到…。
     *
     * <p>这两种模式都需要：绕过 ZUI 对 Meta 的原生处理（KGC / PWM / L0 / L1 全部跳过），
     * 把物理 DOWN 扣押起来，等到 UP 时再决定放行原样 Meta 还是发送映射目标。
     */
    public boolean isMetaIntercepted() {
        return isMetaPassthrough() || isMetaMapped();
    }

    // ----------------------------------------------------------------
    //  Config hot-reload
    // ----------------------------------------------------------------

    /** Check for profile delta changes — runs every keypress, lightweight when empty. */
    public void hotReloadProfiles() {
        try {
            String changes = configIPC.pullProfileChanges();
            if (!"[]".equals(changes)) {
                if (changes.contains("\"reload\"")) {
                    String full = configIPC.pullProfiles();
                    if (!"[]".equals(full)) {
                        IMEProfileManager.clear();
                        IMEProfileManager.loadFromJsonArray(full);
                    }
                }
                IMEProfileManager.applyChanges(changes);
                LogHelper.log(VerboseLevel.INFO, "Profiles hot-reloaded, count=",
                        String.valueOf(IMEProfileManager.getProfileCount()));
            }
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "Profiles hot-reload skipped:", t.getMessage());
        }
    }

    /** Check for config changes via ContentProvider IPC. Called from every hook entry. */
    public void checkConfigChanged() {
        hotReloadProfiles();
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

        // When Fn is active (FnLock ON or Win held), let Fn section map
        // 507→F11 instead of treating it as a smart key.
        if (cfg.fnMasterEnabled && fnKeyManager.isFnKeyboardDevice(
                ((KeyEvent) param.args[0]).getDeviceId())) {
            boolean winHeld = ((KeyEvent) param.args[0]).isMetaPressed();
            if (cfg.fnKeyEnabled || winHeld) {
                LogHelper.log(VerboseLevel.DEBUG, "AppKey:",
                        keyCode == 507 ? "507" : "508",
                        " → skipped (Fn active: fnOn=", String.valueOf(cfg.fnKeyEnabled),
                        " win=", winHeld ? "1" : "0", ")");
                return false;
            }
        }

        String key = keyCode == 507 ? "keyApp1" : "keyApp2";
        Config.SwitchState sw = keyCode == 507 ? cfg.switchKeyApp1 : cfg.switchKeyApp2;
        // 模板可对智能键单独指定三档（见 ConfigResolver.effectiveAppKeyMode）
        Config.AppKeyMode mode = resolver != null
                ? resolver.effectiveAppKeyMode(key)
                : (keyCode == 507 ? cfg.app1Mode : cfg.app2Mode);
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
                    Config.AppKeyMode upMode = mode;
                    long held = SystemClock.uptimeMillis() - appKeySession.downTimeMs;
                    if (upMode == Config.AppKeyMode.CUSTOM) {
                        if (appKeySession.longFired) {
                            LogHelper.log(VerboseLevel.INFO, label,
                                    " → CUSTOM UP after long (skip short) held=", String.valueOf(held), "ms");
                        } else {
                            // 命令内容 / root / 单例 / 超时都可由模板独立覆写
                            String command = resolver != null ? resolver.effectiveCommand(key)
                                    : (keyCode == 507 ? cfg.app1Command : cfg.app2Command);
                            boolean commandRoot = resolver != null ? resolver.effectiveCommandRoot(key)
                                    : (keyCode == 507 ? cfg.app1CommandRoot : cfg.app2CommandRoot);
                            boolean commandSingleton = resolver != null ? resolver.effectiveCommandSingleton(key)
                                    : (keyCode == 507 ? cfg.app1CommandSingleton : cfg.app2CommandSingleton);
                            int commandTimeoutMin = resolver != null ? resolver.effectiveCommandTimeoutMin(key)
                                    : (keyCode == 507 ? cfg.app1CommandTimeoutMin : cfg.app2CommandTimeoutMin);
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
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "writeDetectKeyProperties failed:", t.getMessage());
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
    //  Shortcut recording — 设置页「映射到…」正在录键
    // ----------------------------------------------------------------

    private static final long RECORD_CACHE_MS = 100L;
    private volatile boolean shortcutRecordingCached = false;
    private long shortcutRecordCacheTime = 0L;

    /**
     * 设置页正在录制快捷键 → 模块对**所有**按键都撒手：不拦截、不映射、不消费，
     * 让按键原样落到前台 Activity。
     *
     * <p>必须如此，否则录不到「已经被模块占用的组合」：比如想录 Ctrl+Shift+T，
     * 模块会在 L1 先把它当触控板开关消费掉，弹窗里永远等不到这个键。
     * （与 {@link #isDetectMode()} 的区别：那个是<b>消费</b>掉按键防止误输入，
     * 这个必须<b>放行</b>，因为弹窗里的输入框要真的收到按键。）
     */
    public boolean isShortcutRecording() {
        long now = SystemClock.uptimeMillis();
        if (now - shortcutRecordCacheTime >= RECORD_CACHE_MS) {
            shortcutRecordingCached = configIPC.isShortcutRecording();
            shortcutRecordCacheTime = now;
        }
        return shortcutRecordingCached;
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
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "getCurrentIMEName failed:", t.getMessage());
        }
        return null;
    }

    public boolean triggerIMEProfile() {
        // Cooldown guard: prevent rapid re-triggering (e.g. injected event loops)
        if (IMEDispatcher.isInProfileCooldown()) {
            LogHelper.log(VerboseLevel.DEBUG, "IME: triggerIMEProfile skipped — cooldown");
            return false;
        }
        IMEDispatcher.markProfileTriggered();

        String imePkg = IMEDispatcher.getCurrentIMEPackage();
        if (imePkg != null) imePkg = imePkg.trim();
        // 语言轮转顺序（framework 策略用）：**按输入法**取（没有才回退全局兜底），
        // Config 随 IPC 到 system_server，触发前灌给 dispatcher（它自己不持 Config）
        Config c = cfg;
        String order = null;
        if (c != null && c.imeSubtypeOrders != null && imePkg != null) {
            order = c.imeSubtypeOrders.get(imePkg);
        }
        if (order == null || order.trim().isEmpty()) {
            order = (c != null ? c.imeSubtypeOrder : null);
        }
        IMEDispatcher.setSubtypeOrder(order);
        // 只有该输入法开了「覆盖默认轮转顺序」才走顺序轮转；否则保持框架原生的"最近两门"
        boolean override = false;
        if (c != null && c.imeSubtypeOrderOverride != null && imePkg != null) {
            final Boolean b = c.imeSubtypeOrderOverride.get(imePkg);
            override = (b != null && b);
        }
        IMEDispatcher.setSubtypeOrderEnabled(override);
        LogHelper.log(VerboseLevel.INFO, "IME: triggerIMEProfile — current IME package=",
                imePkg != null ? imePkg : "<null>",
                " profiles loaded=", String.valueOf(IMEProfileManager.getProfileCount()));
        IMEProfile profile = IMEProfileManager.getProfileForIME(imePkg);
        if (profile != null) {
            LogHelper.log(VerboseLevel.INFO, "IME: profile found — name=",
                    profile.getName() != null ? profile.getName() : "?",
                    " strategy=", profile.getStrategy() != null ? profile.getStrategy().name() : "null");
        } else {
            LogHelper.log(VerboseLevel.WARNING, "IME: NO profile matched for '",
                    imePkg != null ? imePkg : "<null>", "'");
        }
        if (IMEProfileManager.executeForIME(imePkg)) {
            LogHelper.log(VerboseLevel.INFO, "IME: profile strategy executed for '",
                    imePkg != null ? imePkg : "<null>", "'");
            return true;
        }
        LogHelper.log(VerboseLevel.WARNING, "IME: executeForIME returned false");
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
     *
     * <p>顺带做「兜底合并」：这个 Win+键 组合模块**没有生效中的特殊处理**时（表里没这个
     * 条目，例如 Win+R；或条目没生效，例如 Win+D 被设为「关闭」），就把它并进 Win 单击
     * 映射的那条组合键里（{@code Ctrl(R)+Shift(R)+`+R}）。有特殊处理的键一律不碰 ——
     * 例如 Win+E 交给系统开文件管理。
     *
     * <p>这一步只做「模块已知处理之外」的兜底，Fn 映射区、修饰键本身都不参与。
     *
     * @return true 表示事件已被消费，调用方不得再让它往下走
     */
    public boolean noteWinComboDuringMetaSession(KeyEvent event) {
        if (cfg == null || !cfg.zuxKeyboardFuncEnabled) return false;
        if (!metaSession.active || metaSession.upHandled) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return false;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            return false;
        }
        if ((event.getMetaState() & KeyEvent.META_META_MASK) == 0) {
            // 有些键盘/来源在 Win 按住时不给别的键带 Meta 位 —— 那样就谈不上「Win+键」
            LogHelper.log(VerboseLevel.DEBUG,
                    "MetaRouter: key during Meta session without META bit kc=",
                    String.valueOf(keyCode));
            return false;
        }
        if (event.getScanCode() == 0) return false;
        if (!metaSession.winComboUsed) {
            metaSession.winComboUsed = true;
            metaSuppressStartMenu = true;
            fnKeyManager.cancelWinLongPressTimer();
            cancelZuiAssistantTimer();
            MetaTrace.decision("Session", "winComboUsed",
                    "kc=", String.valueOf(keyCode));
        }
        // 修饰键：先扣押，等和弦发射时按顺序补进去（别让它抢在映射前缀前面到达）
        if (isModifierKeyCode(keyCode)) {
            return handleMetaExtraModifier(event);
        }
        // 一轮 Meta 按住里只合并第一个键：再来的键按老样子走各自的路
        if (metaSession.mergedKeyCode != 0) return false;
        return tryMergeIntoTarget(event);
    }

    /**
     * 把「模块没有特殊处理的 Win+键」并进 Win 单击的映射目标，见
     * {@link #noteWinComboDuringMetaSession}。
     *
     * @return true 表示已注入合并和弦并消费掉这个物理按键
     */
    private boolean tryMergeIntoTarget(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (!isMetaMapped()) {
            flushPendingExtras("Win 单击未设为映射到…");
            return false;
        }
        // 修饰键由 handleMetaExtraModifier 扣押，不会走到这里；留一道保险
        if (isModifierKeyCode(keyCode)) return false;
        // Fn 映射区（顶行 / ZUI 虚拟键 / 音量亮度…）是 FnKeyManager 的地盘，别抢
        if (fnKeyManager.getFnTarget(event) != 0) {
            LogHelper.log(VerboseLevel.DEBUG,
                    "MetaRouter: Win+key not merged (Fn 映射区) kc=", String.valueOf(keyCode));
            flushPendingExtras("Fn 映射区");
            return false;
        }

        String id = ShortcutMeta.winComboKeyId(keyCode);
        Config.SwitchState sw = id == null ? null : r(id, ShortcutMeta.getSwitch(cfg, id));
        Config.OverrideMode mode = id == null ? null : ra(id, ShortcutMeta.getOverride(cfg, id));
        // 快捷键表里的 Win+X 都是「纯 Win+键」规则（既有分支一律用 modifiersMatch(...,纯Meta...)），
        // 所以只有没按别的修饰键时它们才真的在跑；按了 Ctrl/Shift/Alt 就没有特殊处理，照样合并。
        boolean pureMeta = (event.getMetaState()
                & (KeyEvent.META_CTRL_MASK | KeyEvent.META_SHIFT_MASK | KeyEvent.META_ALT_MASK)) == 0;
        boolean hasHandling = pureMeta && !ShortcutMeta.shouldMergeWinCombo(keyCode, sw, mode);
        if (hasHandling) {
            LogHelper.log(VerboseLevel.INFO, "MetaRouter: Win+key kept (has handling) kc=",
                    String.valueOf(keyCode),
                    " id=", String.valueOf(id),
                    " sw=", String.valueOf(sw),
                    " mode=", String.valueOf(mode));
            flushPendingExtras("有特殊处理");
            return false;
        }

        MetaKeyMap map = MetaKeyMap.parse(effectiveMapTarget());
        if (!map.isSet()) {
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: Win+key merge skipped (no map target) kc=",
                    String.valueOf(keyCode));
            flushPendingExtras("映射目标为空");
            return false;
        }

        emitMergedChord(map, keyCode, event.getScanCode(), String.valueOf(id));
        return true;
    }

    // ── Win 按住期间被扣押的外加键 ────────────────────────────────────
    //
    // 用户按住 Win 时按下的修饰键不能就这么发出去：它会先于「映射到…」的前缀到达，
    // 远端看到的是 Ctrl(L)+Ctrl(R)+Alt(R)+`，顺序反了。所以先扣押下来，等和弦发射时
    // 按**按下的先后顺序**补在前缀之后（前缀在前、外加键在后）。

    /** 扣押中的一个外加键。 */
    public static final class PendingExtra {
        /** 已扣押、还没补发（用户可能已经松手）。 */
        static final int STATE_HELD = 0;
        /** 已按「仍按着」补发 DOWN → 它的物理 UP 必须放行，否则修饰键会卡住。 */
        static final int STATE_EMITTED = 1;
        final int keyCode;
        final int scanCode;
        int state = STATE_HELD;
        /** 补发时是否还按着：还按着只发 DOWN，已松手就发一对 DOWN/UP。 */
        boolean stillDown = true;

        PendingExtra(int keyCode, int scanCode) {
            this.keyCode = keyCode;
            this.scanCode = scanCode;
        }
    }

    // L0（按键策略线程）与 L1（InputDispatcher）都可能碰到它，用同步表 + 快照遍历
    private final java.util.List<PendingExtra> pendingExtras =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private PendingExtra findPendingExtra(int keyCode, int state) {
        for (int i = 0; i < pendingExtras.size(); i++) {
            PendingExtra e = pendingExtras.get(i);
            if (e.keyCode == keyCode && e.state == state) return e;
        }
        return null;
    }

    /**
     * 扣押 / 放行「Win 按住期间按下的修饰键」（Ctrl / Shift / Alt）。
     *
     * <p>DOWN 一律扣押；UP 看情况：还没轮到补发就吃掉（并记下已松手），
     * 已经替它补发过 DOWN 的则放行，让应用收到那一下抬起。
     *
     * @return true = 事件已被消费
     */
    public boolean handleMetaExtraModifier(KeyEvent event) {
        if (!isMetaMapped()) return false;
        if (!metaSession.active || metaSession.upHandled) return false;
        if (event.getScanCode() == 0) return false;
        int keyCode = event.getKeyCode();
        if (!isModifierKeyCode(keyCode)) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() != 0) return true;
            if (findPendingExtra(keyCode, PendingExtra.STATE_HELD) == null) {
                pendingExtras.add(new PendingExtra(keyCode, event.getScanCode()));
                MetaTrace.decision("Session", "withhold extra modifier",
                        "kc=", String.valueOf(keyCode));
            }
            return true;
        }

        PendingExtra emitted = findPendingExtra(keyCode, PendingExtra.STATE_EMITTED);
        if (emitted != null) {
            pendingExtras.remove(emitted);
            MetaTrace.decision("Session", "release emitted extra",
                    "kc=", String.valueOf(keyCode));
            return false;
        }
        PendingExtra pending = findPendingExtra(keyCode, PendingExtra.STATE_HELD);
        if (pending == null) return false;
        pending.stillDown = false;
        MetaTrace.decision("Session", "withheld extra released early",
                "kc=", String.valueOf(keyCode));
        return true;
    }

    /**
     * 发射合并和弦：映射前缀 + 主键 + 扣押的外加键（按按下顺序）+ 触发键。
     *
     * @param triggerKeyCode 触发合并的那个键；≤0 表示没有（例如松 Win 时把只按了修饰键的
     *                       情况补成和弦），此时不记账
     */
    private void emitMergedChord(MetaKeyMap map, int triggerKeyCode, int triggerScanCode,
                                 String id) {
        if (triggerKeyCode > 0) metaSession.mergedKeyCode = triggerKeyCode;
        MetaTrace.decision("Session", "merge into mapped combo",
                "extra kc=", String.valueOf(triggerKeyCode),
                " map kc=", String.valueOf(map.getKeyCode()),
                " id=", String.valueOf(id));
        LogHelper.log(VerboseLevel.INFO, "MetaRouter: Win+key merged (no handling) extra=",
                String.valueOf(triggerKeyCode),
                " id=", String.valueOf(id),
                " map=", map.serialize(),
                " withheld=", String.valueOf(pendingExtras.size()));
        armRemapInject();
        // 传给注入器的是快照，避免遍历期间被另一个线程改到
        KeyInjector.injectMergedCombo(map, new java.util.ArrayList<>(pendingExtras),
                triggerKeyCode, triggerScanCode, metaSession.deviceId);
        // 还按着的外加键：DOWN 已替它发过，等物理 UP 放行；已松手的就此了结
        for (PendingExtra e : new java.util.ArrayList<>(pendingExtras)) {
            if (e.stillDown) e.state = PendingExtra.STATE_EMITTED;
            else pendingExtras.remove(e);
        }
    }

    /**
     * 把扣押的键按原顺序单独补发（不并进前缀）。
     *
     * <p>用于「这个 Win+键 有特殊处理」或「没有映射目标」这类不合并的情形 ——
     * 用户按下去的键不能因为我们扣过就凭空消失。
     */
    private void flushPendingExtras(String reason) {
        if (pendingExtras.isEmpty()) return;
        MetaTrace.decision("Session", "flush withheld extras",
                "n=", String.valueOf(pendingExtras.size()),
                " why=", reason);
        armRemapInject();
        for (PendingExtra e : new java.util.ArrayList<>(pendingExtras)) {
            KeyInjector.injectExtraKey(e.keyCode, e.scanCode, metaSession.deviceId, e.stillDown);
            if (e.stillDown) {
                e.state = PendingExtra.STATE_EMITTED;   // 等它的物理 UP 来放行
            } else {
                pendingExtras.remove(e);
            }
        }
    }

    /**
     * Meta 抬起时的收尾：扣押着的东西要么补成完整和弦（只按了修饰键、没触发键的情况），
     * 要么按原顺序单独放行，绝不能丢。
     */
    public void finishWinExtrasOnMetaUp() {
        if (pendingExtras.isEmpty()) return;
        if (metaSession.mergedKeyCode != 0) {
            flushPendingExtras("和弦已发过");
            return;
        }
        MetaKeyMap map = MetaKeyMap.parse(effectiveMapTarget());
        if (!map.isSet()) {
            flushPendingExtras("映射目标为空");
            return;
        }
        emitMergedChord(map, 0, 0, "meta-up");
    }

    /**
     * 已被并进映射和弦的那个按键的后续事件（DOWN 重复 / UP）—— 继续吃掉。
     *
     * <p>标记不随 Meta 会话清除：用户可能先松 Win 再松这个键，那一下 UP 也必须吃掉，
     * 否则应用会收到一个没有 DOWN 的孤立 UP。真正清除它的时机是它的 UP；
     * 若真遇到「又来了一个首次按下」说明上一轮 UP 丢了，就地放弃吞键、交回正常流程。
     *
     * @return true 表示事件应被消费
     */
    public boolean consumeMergedComboKey(KeyEvent event) {
        int merged = metaSession.mergedKeyCode;
        if (merged == 0 || event.getKeyCode() != merged) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            metaSession.mergedKeyCode = 0;
            MetaTrace.decision("Session", "merged key UP consumed",
                    "kc=", String.valueOf(merged));
            return true;
        }
        if (event.getRepeatCount() == 0) {
            metaSession.mergedKeyCode = 0;
            MetaTrace.decision("Session", "merged key re-DOWN → stop swallowing",
                    "kc=", String.valueOf(merged));
            return false;
        }
        return true;
    }

    /**
     * L0 的扣押收口：先处理被扣押外加键的抬起，再处理和弦触发键的重复 / 抬起。
     *
     * @return true = 事件应被消费
     */
    public boolean consumeWithheldMetaKeys(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && handleMetaExtraModifier(event)) {
            return true;
        }
        return consumeMergedComboKey(event);
    }

    private static boolean isModifierKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_CTRL_LEFT
                || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT
                || keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
                || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
                || keyCode == KeyEvent.KEYCODE_ALT_LEFT
                || keyCode == KeyEvent.KEYCODE_ALT_RIGHT;
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
        if (cfg == null || !isAcceptingText()) return;
        if (cfg.imeSwitchBinding == Config.IMEBinding.WIN) {
            LogHelper.log(VerboseLevel.INFO, "Win long → switch IME");
            String imeName = switchInputMethod();
            if (cfg.imeToastEnabled && imeName != null) {
                KeyInjector.showToast(imeName);
            }
        } else if (cfg.languageSwitchBinding == Config.IMEBinding.WIN) {
            LogHelper.log(VerboseLevel.INFO, "Win long → switch language (profile)");
            boolean ok = triggerIMEProfile();
            if (cfg.imeToastEnabled && !ok) {
                KeyInjector.showToast("system_server: No IME profile matched");
            }
        }
    }

    /** Win long-press (≥2s): CUSTOM runs shell script; FOLLOW_SYSTEM launches voice assistant. */
    public void dispatchWinLongCommand() {
        if (cfg == null) return;
        String command = resolver != null ? resolver.effectiveCommand("winLongPress") : cfg.winLongCommand;
        if (command == null || command.trim().isEmpty()) {
            LogHelper.log(VerboseLevel.INFO, "WinLong CUSTOM: empty script");
            return;
        }
        configIPC.runAppKeyCommand(
                command,
                resolver != null ? resolver.effectiveCommandRoot("winLongPress") : cfg.winLongCommandRoot,
                resolver != null ? resolver.effectiveCommandSingleton("winLongPress") : cfg.winLongCommandSingleton,
                resolver != null ? resolver.effectiveCommandTimeoutMin("winLongPress") : cfg.winLongCommandTimeoutMin);
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
