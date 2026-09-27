package moe.lovefirefly.betterzuikey.Hook;

import android.os.IBinder;
import android.view.KeyEvent;
import moe.lovefirefly.betterzuikey.Hook.HookCompat;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.ime.IMEDispatcher;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

public class L0Interceptor  {

    private final HookContext ctx;

    public L0Interceptor(HookContext ctx) {
        this.ctx = ctx;
    }

    public void intercept(HookCompat.HookParam param) {
        // checkConfigChanged MUST be before enabled check,
        // otherwise closing the master switch deadlocks the hook forever
        ctx.checkConfigChanged();
        ctx.ensureKscFromHook(param.thisObject);

        // Guard: skip injected events to avoid recursive re-processing
        if (ctx.isInjecting()) return;

        KeyEvent event = (KeyEvent) param.args[0];
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int repeatCount = event.getRepeatCount();

        // Keyboard detect page: intercept ALL keys before shortcuts / Fn / ZUI
        if (ctx.isDetectMode()) {
            ctx.writeDetectKeyProperties(keyCode, down, repeatCount, event);
            param.setResult(true);
            return;
        }

        // 设置页正在录制「映射到…」：模块和 ZUI 全都不处理、不消费，
        // 让按键原样落到弹窗的输入框（否则录不到已被占用的组合）。
        // 同时把这一路看到的键上报给弹窗 —— 亮度键 / CapsLock / 单独的 Win
        // 会被系统或 ZUI 在到达应用窗口前吃掉，只有这里看得到。
        if (ctx.isShortcutRecording()) {
            if (down && repeatCount == 0) {
                ctx.configIPC.appendRecordedKey(keyCode, event.getMetaState());
            }
            param.setResult(false);
            return;
        }

        // 模块自己为「映射到…」注入的组合键：整体放行，避免二次消费
        if (ctx.isRemapInjecting(event)) return;

        if (ctx.cfg == null || !ctx.cfg.zuxKeyboardFuncEnabled)
            return;

        ctx.noteWinComboDuringMetaSession(event);

        boolean pt = PassthroughTrace.shouldTrace(event);
        if (pt) PassthroughTrace.in("L0", event, ctx);
        try {

        // Meta key — DOWN at L1; UP at L0 only (never reaches beforeDispatching)
        if (keyCode == KeyEvent.KEYCODE_META_LEFT
                || keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            // 放行模式（metaSingle == OFF）注入的合成 Meta 点击：原样放行，不参与扣押
            if (ctx.isSyntheticMetaTap(event)) {
                param.setResult(false);
                return;
            }
            MetaTrace.event("L0", event, ctx);
            int scanCode = event.getScanCode();
            boolean physical = scanCode != 0;

            // 放行模式（metaSingle == OFF）：跳过 ZUI 对 Meta 的一切处理（含 ROW 键盘
            // scanCode=787345 时的切语言注入）。但**保留模块自己的记账**，否则
            // 「Meta 按住 + 又来一个新键 = 组合键」就无从判定：
            //   DOWN → 开 session（DOWN 由 L1 扣押，应用看不到）
            //   UP   → routeUpL0 决策：仅当这轮是独立单击时，才注入一对合成的
            //          Meta DOWN/UP 放行给前台应用；组合键 / 长按 → 应用完全看不到 Win
            if (ctx.isMetaIntercepted()) {
                if (down && repeatCount == 0 && physical) {
                    ctx.metaStartMenuDispatched = false;
                    ctx.metaSuppressStartMenu = false;
                    if (ctx.metaSession.begin(event)) {
                        MetaTrace.session("L0", "begin (passthrough)", ctx);
                    }
                } else if (!down && repeatCount == 0 && physical) {
                    if (new MetaKeyRouter(ctx).routeUpL0(event, param)) {
                        MetaTrace.hookResult("L0", true);
                        return;
                    }
                }
                param.setResult(false);   // 重复 DOWN / 无 scanCode：跳过 ZUI、不消费
                return;
            }

            if (down && repeatCount == 0 && scanCode != 0) {
                ctx.metaStartMenuDispatched = false;
                ctx.metaSuppressStartMenu = false;
                if (ctx.metaSession.begin(event)) {
                    MetaTrace.session("L0", "begin", ctx);
                }
            }
            if (!down && repeatCount == 0 && scanCode != 0) {
                if (new MetaKeyRouter(ctx).routeUpL0(event, param)) {
                    MetaTrace.hookResult("L0", true);
                    return;
                }
            }
            if (!down && repeatCount == 0 && ctx.metaSession.active
                    && ctx.metaSession.keyCode >= 0
                    && keyCode != ctx.metaSession.keyCode) {
                MetaTrace.decision("L0", "pass UP (other Meta side)",
                        "event kc=", String.valueOf(keyCode),
                        " sess kc=", String.valueOf(ctx.metaSession.keyCode));
            }
        }

        // Win+Tab — Recents (ZUI L0: launchRecent("wintab"))
        // Note: Win+Tab has NO system switch, so we skip switchWinTab check;
        // use overrideWinTab alone to control behavior
        // Win+Tab — pure Meta+Tab only (no Alt/Shift/Ctrl)
        if (keyCode == KeyEvent.KEYCODE_TAB && down
                && KeyInjector.modifiersMatch(event, true, false, false, false) && repeatCount == 0) {
            Config.OverrideMode ov = ctx.ra("winTab", ctx.cfg.overrideWinTab);
            KeyInjector.debugProp("debug.bzuikey.l0.wintab", "ENTER override=" + ov.name());
            if (ov == Config.OverrideMode.OFF) {
                KeyInjector.debugProp("debug.bzuikey.l0.wintab", "ACTION: OFF (strip Meta, consume UP later)");
                LogHelper.log(VerboseLevel.INFO, "L0: Win+Tab → OFF (strip Meta, consume UP later)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                ctx.fnKeyManager.setWinTabOffActive(true);
                return;
            }
            // ZUI / FOLLOW_SYSTEM: let ZUI L0 call launchRecent("wintab")
            if (ov == Config.OverrideMode.ZUI || ov == Config.OverrideMode.FOLLOW_SYSTEM) {
                KeyInjector.debugProp("debug.bzuikey.l0.wintab",
                        "ACTION: " + ov.name() + " (pass through, let ZUI handle)");
                LogHelper.log(VerboseLevel.INFO, "L0: Win+Tab → ", ov.name(), " (pass through, let ZUI handle)");
                return;
            }
            // BLOCK: consume entirely
            KeyInjector.debugProp("debug.bzuikey.l0.wintab", "ACTION: BLOCK (consume, UP later)");
            LogHelper.log(VerboseLevel.INFO, "L0: Win+Tab → BLOCK (consume, UP later)");
            ctx.fnKeyManager.setWinTabBlockActive(true);
            param.setResult(true);
            return;
        }
        // Win+Tab / Win+P / generic BLOCK UP cleanup (delegated to FnKeyManager)
        if (ctx.fnKeyManager.consumeComboUp(keyCode, down, event, param))
            return;
        // Alt+Tab — pure Alt+Tab only (no Meta/Shift/Ctrl)
        if (keyCode == KeyEvent.KEYCODE_TAB && down
                && KeyInjector.modifiersMatch(event, false, false, false, true) && repeatCount == 0) {
            if (!ctx.r("altTab", ctx.cfg.switchAltTab).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("altTab", ctx.cfg.overrideAltTab);
            // OFF: strip Alt so ZUI L0 doesn't call launchRecent("alttab").
            // Plain Tab passes through to foreground app (which already received Alt DOWN,
            // so apps tracking key state can still reconstruct Alt+Tab).
            // L1 also strips as defense against InputDispatcher re-computing modifiers.
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L0: Alt+Tab → OFF (strip Alt, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_ALT_MASK);
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L0: Alt+Tab"))
                return;
        }
        // Win+L — pure Meta+L only (no Alt/Shift/Ctrl)
        if (keyCode == KeyEvent.KEYCODE_L && down
                && KeyInjector.modifiersMatch(event, true, false, false, false) && repeatCount == 0) {
            if (!ctx.r("winL", ctx.cfg.switchWinL).isEnabled())
                return;
            Config.OverrideMode ovL = ctx.ra("winL", ctx.cfg.overrideWinL);
            // OFF: strip Meta so ZUI doesn't lock, plain L reaches foreground app
            if (ovL == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L0: Win+L → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            // ZUI / FOLLOW_SYSTEM: let ZUI L0 handle lock screen natively
            if (ovL == Config.OverrideMode.ZUI || ovL == Config.OverrideMode.FOLLOW_SYSTEM) {
                LogHelper.log(VerboseLevel.INFO, "L0: Win+L → ", ovL.name(), " (pass through, let ZUI lock)");
                return;
            }
            // BLOCK / AOSP: intercept via applyInterceptAction
            if (ctx.applyInterceptAction(ovL, param, "L0: Win+L", KeyEvent.KEYCODE_L))
                return;
        }
        // Win+P — pure Meta+P only (no Alt/Shift/Ctrl)
        if (keyCode == KeyEvent.KEYCODE_P && down
                && KeyInjector.modifiersMatch(event, true, false, false, false) && repeatCount == 0) {
            if (!ctx.r("winP", ctx.cfg.switchWinP).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winP", ctx.cfg.overrideWinP);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L0: Win+P → OFF (block physical, inject clean P)");
                param.setResult(true); // block physical DOWN
                ctx.fnKeyManager.setWinPOffActive(true);
                KeyInjector.injectKeyDown(KeyEvent.KEYCODE_P, 0, event.getDeviceId());
                return;
            }
            // ZUI / FOLLOW_SYSTEM: let ZUI L0 call switchPcMode() inline
            if (ov == Config.OverrideMode.ZUI || ov == Config.OverrideMode.FOLLOW_SYSTEM) {
                LogHelper.log(VerboseLevel.INFO, "L0: Win+P → ", ov.name(), " (pass through, let ZUI handle)");
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L0: Win+P", KeyEvent.KEYCODE_P))
                return;
        }
        // Win+Back — pure Meta+Back only (no Alt/Shift/Ctrl)
        if (keyCode == KeyEvent.KEYCODE_BACK && down
                && KeyInjector.modifiersMatch(event, true, false, false, false) && repeatCount == 0) {
            if (!ctx.r("winBack", ctx.cfg.switchWinBack).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winBack", ctx.cfg.overrideWinBack);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L0: Win+Back → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            // ZUI: let event flow through so L4 can handle KeyGestureEvent type=306
            if (ov == Config.OverrideMode.ZUI) {
                LogHelper.log(VerboseLevel.INFO, "L0: Win+Back → ZUI (pass through to L4)");
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L0: Win+Back", KeyEvent.KEYCODE_BACK))
                return;
        }
        // Ctrl+Enter — pure Ctrl+Enter only (no Meta/Alt/Shift)
        if (keyCode == KeyEvent.KEYCODE_ENTER && down
                && KeyInjector.modifiersMatch(event, false, false, true, false) && repeatCount == 0) {
            if (!ctx.r("ctrlEnter", ctx.cfg.switchCtrlEnter).isEnabled())
                return;
            Config.OverrideMode ovCe = ctx.ra("ctrlEnter", ctx.cfg.overrideCtrlEnter);
            switch (ovCe) {
                case ZUI:
                    // Insert newline: block physical Ctrl+Enter, commit "\n" via InputConnection
                    LogHelper.log(VerboseLevel.INFO, "L0: Ctrl+Enter → ZUI (commit newline)");
                    param.setResult(true);
                    ctx.fnKeyManager.setCtrlEnterZuiActive(true);
                    if (IMEDispatcher.commitTextToInputConnection("\n")) {
                        LogHelper.log(VerboseLevel.INFO, "L0: Ctrl+Enter → commitText OK");
                        ctx.fnKeyManager.setCtrlEnterZuiNeedUpInjection(false);
                    } else {
                        // Fallback: inject clean Enter when InputConnection unavailable
                        LogHelper.log(VerboseLevel.INFO, "L0: Ctrl+Enter → commitText failed, inject clean Enter fallback");
                        KeyInjector.injectKeyDown(KeyEvent.KEYCODE_ENTER, 0, event.getDeviceId());
                        ctx.fnKeyManager.setCtrlEnterZuiNeedUpInjection(true);
                    }
                    return;
                case BLOCK:
                    // Consume entirely
                    LogHelper.log(VerboseLevel.INFO, "L0: Ctrl+Enter → BLOCK");
                    param.setResult(true);
                    return;
                case OFF:
                case FOLLOW_SYSTEM:
                default:
                    // Pass-through to app (ZUI won't block since switch is ON)
                    LogHelper.log(VerboseLevel.INFO, "L0: Ctrl+Enter → ", ovCe.name(), " (pass through)");
                    return;
            }
        }
        // Ctrl+Enter UP cleanup (ZUI mode — block physical UP)
        if (keyCode == KeyEvent.KEYCODE_ENTER && !down
                && repeatCount == 0
                && ctx.fnKeyManager.isCtrlEnterZuiActive()) {
            ctx.fnKeyManager.setCtrlEnterZuiActive(false);
            param.setResult(true);  // block physical UP
            if (ctx.fnKeyManager.isCtrlEnterZuiNeedUpInjection()) {
                // Fallback path: clean Enter was injected, need matching UP
                KeyInjector.injectKeyUp(KeyEvent.KEYCODE_ENTER, 0, event.getDeviceId());
                ctx.fnKeyManager.setCtrlEnterZuiNeedUpInjection(false);
            }
            LogHelper.log(VerboseLevel.INFO, "L0: Ctrl+Enter UP → ZUI (consumed)");
            return;
        }
        // Ctrl+/ — OFF: strip Ctrl so ZUI doesn't recognize combo.
        // Ctrl+/ has NO system switch gate in ZUI (hardcoded),
        // and no Config switch either (always enabled).
        // pure Ctrl+/ only (no Meta/Alt/Shift)
        if (keyCode == KeyEvent.KEYCODE_SLASH && KeyInjector.modifiersMatch(event, false, false, true, false)) {
            Config.OverrideMode ov = ctx.ra("ctrlSlash", ctx.cfg.overrideCtrlSlash);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.DEBUG, "L0: Ctrl+/ → OFF (strip Ctrl, no system switch)");
                KeyInjector.stripMetaState(event, KeyEvent.META_CTRL_MASK);
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L0: Ctrl+/", KeyEvent.KEYCODE_SLASH))
                return;
        }
        // Ctrl+Shift+T DOWN — pure Ctrl+Shift+T only (no Meta/Alt)
        if (keyCode == KeyEvent.KEYCODE_T && down && repeatCount == 0
                && KeyInjector.modifiersMatch(event, false, true, true, false)) {
            if (!ctx.r("ctrlShiftT", ctx.cfg.switchCtrlShiftT).isEnabled())
                return;
            Config.OverrideMode cstMode = ctx.ra("ctrlShiftT", ctx.cfg.overrideCtrlShiftT);
            // FOLLOW_SYSTEM / OFF: pass through, L1 handles the real logic
            if (cstMode == Config.OverrideMode.FOLLOW_SYSTEM
                    || cstMode == Config.OverrideMode.OFF)
                return;

            switch (cstMode) {
                case BLOCK:
                    LogHelper.log(VerboseLevel.INFO, "L0: Ctrl+Shift+T → BLOCK (consume)");
                    param.setResult(true);
                    return;
                case ZUI:
                    // ZUI实现: 不消费，让原函数正常派发 KeyGestureEvent → L4 拦截并复刻
                    LogHelper.log(VerboseLevel.INFO, "L0: Ctrl+Shift+T DOWN → ZUI (pass through, L4 will handle)");
                    return;
                default:
                    return;
            }
        }
        // Ctrl+Shift+T UP — pure Ctrl+Shift+T only (no Meta/Alt)
        if (keyCode == KeyEvent.KEYCODE_T && !down
                && KeyInjector.modifiersMatch(event, false, true, true, false)) {
            if (!ctx.r("ctrlShiftT", ctx.cfg.switchCtrlShiftT).isEnabled())
                return;
            Config.OverrideMode cstModeUp = ctx.ra("ctrlShiftT", ctx.cfg.overrideCtrlShiftT);
            switch (cstModeUp) {
                case OFF:
                    // OFF: strip modifiers so app receives clean 'T' UP
                    LogHelper.log(VerboseLevel.INFO, "L0: Ctrl+Shift+T UP → OFF (strip modifiers)");
                    KeyInjector.stripMetaState(event, KeyEvent.META_CTRL_MASK | KeyEvent.META_SHIFT_MASK);
                    return;
                default:
                    // FOLLOW_SYSTEM / BLOCK / ZUI / AOSP:
                    // ZUI handles gesture on DOWN; consume UP to prevent stray KeyUp leaking to app
                    LogHelper.log(VerboseLevel.DEBUG, "L0: Ctrl+Shift+T UP → consume (mode=", cstModeUp.name(), ")");
                    param.setResult(true);
                    return;
            }
        }
        // 507/508 — smart keys: capture before Fn mapping (507→F11) can steal the event
        if (ctx.handleZuiAppKey(keyCode, down, repeatCount, param)) {
            PassthroughTrace.note("L0", "AppKey consumed", event);
            return;
        }
        // Fn section: FnLock toggle, Meta UP, Fn key mapping, UP injection
        if (ctx.fnKeyManager.processFnSection(keyCode, down, repeatCount, event, param, ctx.kscInstance)) {
            PassthroughTrace.note("L0", "FnSection consumed", event);
            return;
        }
        // Win+Alt+3 — Bounce keys (AOSP native)
        // Settings.Secure 控制实际开关，Config.SwitchState 只是 UI 投射。
        // Hook 行为完全由 override mode (spin) 决定，不允许 SwitchState 守卫。
        if (keyCode == KeyEvent.KEYCODE_3 && down
                && KeyInjector.modifiersMatch(event, true, false, false, true) && repeatCount == 0) {
            LogHelper.log(VerboseLevel.INFO, "L0: Win+Alt+3 reach",
                    " switch=", String.valueOf(ctx.cfg.switchAospBounceKeys),
                    " override=", String.valueOf(ctx.cfg.overrideAospBounceKeys),
                    " meta=", String.valueOf(event.isMetaPressed()),
                    " alt=", String.valueOf(event.isAltPressed()));
            if (ctx.applyInterceptAction(ctx.ra("aospBounceKeys", ctx.cfg.overrideAospBounceKeys), param,
                    "L0: Win+Alt+3"))
                return;
        }
        // Win+Alt+4 — Mouse keys (AOSP native)
        if (keyCode == KeyEvent.KEYCODE_4 && down
                && KeyInjector.modifiersMatch(event, true, false, false, true) && repeatCount == 0) {
            LogHelper.log(VerboseLevel.INFO, "L0: Win+Alt+4 reach",
                    " switch=", String.valueOf(ctx.cfg.switchAospMouseKeys),
                    " override=", String.valueOf(ctx.cfg.overrideAospMouseKeys));
            if (ctx.applyInterceptAction(ctx.ra("aospMouseKeys", ctx.cfg.overrideAospMouseKeys), param,
                    "L0: Win+Alt+4"))
                return;
        }
        // Win+Alt+5 — Sticky keys (AOSP native)
        if (keyCode == KeyEvent.KEYCODE_5 && down
                && KeyInjector.modifiersMatch(event, true, false, false, true) && repeatCount == 0) {
            LogHelper.log(VerboseLevel.INFO, "L0: Win+Alt+5 reach",
                    " switch=", String.valueOf(ctx.cfg.switchAospStickyKeys),
                    " override=", String.valueOf(ctx.cfg.overrideAospStickyKeys));
            if (ctx.applyInterceptAction(ctx.ra("aospStickyKeys", ctx.cfg.overrideAospStickyKeys), param,
                    "L0: Win+Alt+5"))
                return;
        }
        // Win+Alt+6 — Slow keys (AOSP native)
        if (keyCode == KeyEvent.KEYCODE_6 && down
                && KeyInjector.modifiersMatch(event, true, false, false, true) && repeatCount == 0) {
            LogHelper.log(VerboseLevel.INFO, "L0: Win+Alt+6 reach",
                    " switch=", String.valueOf(ctx.cfg.switchAospSlowKeys),
                    " override=", String.valueOf(ctx.cfg.overrideAospSlowKeys));
            if (ctx.applyInterceptAction(ctx.ra("aospSlowKeys", ctx.cfg.overrideAospSlowKeys), param, "L0: Win+Alt+6"))
                return;
        }
        } finally {
            if (pt) PassthroughTrace.out("L0", event, param);
        }
    }
}
