package moe.lovefirefly.betterzuikey.Hook;

import android.os.IBinder;
import android.view.KeyEvent;
import moe.lovefirefly.betterzuikey.Hook.HookCompat;

import moe.lovefirefly.betterzuikey.Config.Config;
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
        if (ctx.cfg == null || !ctx.cfg.zuxKeyboardFuncEnabled)
            return;
        KeyEvent event = (KeyEvent) param.args[0];
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int repeatCount = event.getRepeatCount();

        // TRACE: log every Meta event at L0
        if (keyCode == KeyEvent.KEYCODE_META_LEFT
                || keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            LogHelper.log(VerboseLevel.INFO, "[L0] ", (down ? "D" : "U"),
                    " sc=", String.valueOf(event.getScanCode()),
                    " r=", String.valueOf(repeatCount));
        }

        // Keyboard detect mode: intercept all keys, prevent ZUI function key
        // side-effects
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
            } catch (Throwable ignored) {
            }
        }
        if (ctx.isDetectMode()) {
            param.setResult(true);
            return;
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
        // Ctrl+Space — pure Ctrl+Space only (no Meta/Alt/Shift)
        if (keyCode == KeyEvent.KEYCODE_SPACE && down
                && KeyInjector.modifiersMatch(event, false, false, true, false) && repeatCount == 0) {
            if (!ctx.r("ctrlSpace", ctx.cfg.switchCtrlSpace).isEnabled())
                return;
            // Guard: skip injected events
            if (ctx.isInjecting()) return;

            // Dual delivery: when IME is accepting text, block original and
            // deliver to both IME (switch language) and App (e.g. code completion).
            if (ctx.cfg.ctrlSpaceDualDelivery && ctx.isAcceptingText()) {
                LogHelper.log(VerboseLevel.INFO,
                    "L0: Ctrl+Space → dual delivery (IME + App)");
                param.setResult(true);  // block original
                // IME injection (Ctrl+Space, via guarded pipeline)
                ctx.injectCtrlSpace();
                // App also gets it — since injectCtrlSpace uses the guarded
                // pipeline, it reaches IME first. For true dual delivery,
                // injectToApp path would be used; currently falls back to
                // normal pipeline injection.
                return;
            }
            if (ctx.applyInterceptAction(ctx.ra("ctrlSpace", ctx.cfg.overrideCtrlSpace), param, "L0: Ctrl+Space"))
                return;
        }
        // Ctrl+Enter — pure Ctrl+Enter only (no Meta/Alt/Shift)
        if (keyCode == KeyEvent.KEYCODE_ENTER && down
                && KeyInjector.modifiersMatch(event, false, false, true, false) && repeatCount == 0) {
            if (!ctx.r("ctrlEnter", ctx.cfg.switchCtrlEnter).isEnabled())
                return;
            if (ctx.applyInterceptAction(ctx.ra("ctrlEnter", ctx.cfg.overrideCtrlEnter), param, "L0: Ctrl+Enter"))
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
        // Fn section: FnLock toggle, Meta UP, Fn key mapping, UP injection
        if (ctx.fnKeyManager.processFnSection(keyCode, down, repeatCount, event, param, ctx.kscInstance))
            return;
        // 520 — Keyboard restore (disable physical keyboard)
        if (keyCode == 520 && down && repeatCount == 0) {
            if (!ctx.r("keyKeyboardRestore", ctx.cfg.switchKeyKeyboardRestore).isEnabled())
                return;
            if (ctx.applyInterceptAction(ctx.ra("keyKeyboardRestore", ctx.cfg.overrideKeyboardRestore), param,
                    "L0: key 520"))
                return;
        }
        // 521 — Keyboard flip (enable physical keyboard + show IME)
        if (keyCode == 521 && down && repeatCount == 0) {
            if (!ctx.r("keyKeyboardReverse", ctx.cfg.switchKeyKeyboardReverse).isEnabled())
                return;
            if (ctx.applyInterceptAction(ctx.ra("keyKeyboardReverse", ctx.cfg.overrideKeyboardReverse), param,
                    "L0: key 521"))
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
    }
}
