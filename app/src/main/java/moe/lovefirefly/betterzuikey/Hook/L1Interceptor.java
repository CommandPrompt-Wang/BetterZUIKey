package moe.lovefirefly.betterzuikey.Hook;

import android.os.IBinder;
import android.view.KeyEvent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

public class L1Interceptor extends XC_MethodHook {

    private final HookContext ctx;

    public L1Interceptor(HookContext ctx) {
        this.ctx = ctx;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        // checkConfigChanged MUST be before enabled check
        ctx.checkConfigChanged();
        if (ctx.cfg == null || !ctx.cfg.zuxKeyboardFuncEnabled)
            return;
        ctx.foregroundTracker.refresh();

        // Detect mode: intercept all keys
        if (ctx.isDetectMode()) {
            param.setResult(true);
            return;
        }

        KeyEvent event = (KeyEvent) param.args[0];
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int repeatCount = event.getRepeatCount();
        boolean firstDown = down && repeatCount == 0;

        // Ctrl+Shift+T — Toggle touchpad
        if (keyCode == KeyEvent.KEYCODE_T && firstDown
                && event.isCtrlPressed() && event.isShiftPressed()) {
            if (!ctx.r("ctrlShiftT", ctx.cfg.switchCtrlShiftT).isEnabled())
                return;
            Config.OverrideMode cstModeL1 = ctx.ra("ctrlShiftT", ctx.cfg.overrideCtrlShiftT);
            // ZUI mode: do NOT intercept in L1 — let KeyGestureEvent type=308
            // be generated so L4 can handle it (calling toggleTouchpadFromKey).
            if (cstModeL1 == Config.OverrideMode.ZUI) {
                LogHelper.log(VerboseLevel.INFO, "L1: Ctrl+Shift+T → ZUI (pass through to L4)");
                return;
            }
            // OFF mode: strip modifiers at L1 (closest to dispatch).
            // L0 also strips, but input system may re-add modifiers between
            // queue and dispatch; this is the authoritative stripping point.
            if (cstModeL1 == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Ctrl+Shift+T → OFF (strip modifiers, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_CTRL_MASK | KeyEvent.META_SHIFT_MASK);
                return;
            }
            if (ctx.applyInterceptAction(cstModeL1, param, "L1: Ctrl+Shift+T"))
                return;
        }

        // Win+S — Global search
        if (keyCode == KeyEvent.KEYCODE_S && firstDown
                && event.isMetaPressed()) {
            if (!ctx.r("winS", ctx.cfg.switchWinS).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winS", ctx.cfg.overrideWinS);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+S → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            // ZUI: let event flow through so L4 can handle KeyGestureEvent type=300
            if (ov == Config.OverrideMode.ZUI) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+S → ZUI (pass through to L4)");
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Win+S", KeyEvent.KEYCODE_S))
                return;
        }

        // Win+Tab — Recents (ZUI L0 calls launchRecent, L3 type=2 showRecentApps as
        // fallback)
        // Note: Win+Tab has NO system switch, skip switchWinTab check
        if (keyCode == KeyEvent.KEYCODE_TAB && firstDown
                && event.isMetaPressed()) {
            Config.OverrideMode ov = ctx.ra("winTab", ctx.cfg.overrideWinTab);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+Tab → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            if (ov == Config.OverrideMode.ZUI || ov == Config.OverrideMode.FOLLOW_SYSTEM) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+Tab → ", ov.name(), " (pass through)");
                return;
            }
            // BLOCK: consume at L1 (L0 already consumed, this is defense-in-depth)
            if (ov == Config.OverrideMode.BLOCK) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+Tab → BLOCK (consume)");
                param.setResult(true);
                return;
            }
        }

        // Win+A — Hide/show taskbar (L4 type=302)
        if (keyCode == KeyEvent.KEYCODE_A && firstDown
                && event.isMetaPressed()) {
            if (!ctx.r("winA", ctx.cfg.switchWinA).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winA", ctx.cfg.overrideWinA);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+A → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            // ZUI / FOLLOW_SYSTEM: pass through, L4 handles KeyGestureEvent type=302
            if (ov == Config.OverrideMode.ZUI || ov == Config.OverrideMode.FOLLOW_SYSTEM) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+A → ", ov.name(), " (pass through to L4)");
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Win+A", KeyEvent.KEYCODE_A))
                return;
        }

        // Win+D — Back to desktop (L3 AOSP PhoneWindowManager type=1 → goHome())
        if (keyCode == KeyEvent.KEYCODE_D && firstDown
                && event.isMetaPressed()) {
            LogHelper.log(VerboseLevel.INFO, "L1: Win+D detected",
                    " switch=", ctx.r("winD", ctx.cfg.switchWinD).name(),
                    " override=", ctx.ra("winD", ctx.cfg.overrideWinD).name());
            if (!ctx.r("winD", ctx.cfg.switchWinD).isEnabled()) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+D → switch disabled, block");
                param.setResult(true);
                return;
            }
            Config.OverrideMode ov = ctx.ra("winD", ctx.cfg.overrideWinD);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+D → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Win+D", KeyEvent.KEYCODE_D))
                return;
        }

        // Win+I — Open Settings
        if (keyCode == KeyEvent.KEYCODE_I && firstDown
                && event.isMetaPressed()) {
            if (!ctx.r("winI", ctx.cfg.switchWinI).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winI", ctx.cfg.overrideWinI);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+I → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            // ZUI / AOSP / FOLLOW_SYSTEM: pass through to L3 type=7 (AOSP launchSettings)
            if (ov == Config.OverrideMode.ZUI || ov == Config.OverrideMode.AOSP
                    || ov == Config.OverrideMode.FOLLOW_SYSTEM) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+I → ", ov.name(), " (pass through to L3)");
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Win+I", KeyEvent.KEYCODE_I))
                return;
        }

        // Win+E — Open file manager (L4 type=307)
        if (keyCode == KeyEvent.KEYCODE_E && firstDown
                && event.isMetaPressed()) {
            if (!ctx.r("winE", ctx.cfg.switchWinE).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winE", ctx.cfg.overrideWinE);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+E → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            // ZUI / FOLLOW_SYSTEM: pass through, L4 handles KeyGestureEvent type=307
            if (ov == Config.OverrideMode.ZUI || ov == Config.OverrideMode.FOLLOW_SYSTEM) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+E → ", ov.name(), " (pass through to L4)");
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Win+E", KeyEvent.KEYCODE_E))
                return;
        }

        // Win+N — Notification panel
        if (keyCode == KeyEvent.KEYCODE_N && firstDown
                && event.isMetaPressed()) {
            if (!ctx.r("winN", ctx.cfg.switchWinN).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winN", ctx.cfg.overrideWinN);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+N → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Win+N", KeyEvent.KEYCODE_N))
                return;
        }

        // Win+M — Minimize freeform window (L3 AOSP type=201)
        if (keyCode == KeyEvent.KEYCODE_M && firstDown
                && event.isMetaPressed()) {
            if (!ctx.r("winM", ctx.cfg.switchWinM).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winM", ctx.cfg.overrideWinM);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+M → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            // ZUI / AOSP / FOLLOW_SYSTEM: pass through to L3 type=201
            if (ov == Config.OverrideMode.ZUI || ov == Config.OverrideMode.AOSP
                    || ov == Config.OverrideMode.FOLLOW_SYSTEM) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+M → ", ov.name(), " (pass through to L3)");
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Win+M", KeyEvent.KEYCODE_M))
                return;
        }

        // Win+↑ — Maximize window (L3 AOSP type=53)
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && firstDown
                && event.isMetaPressed()) {
            if (!ctx.r("winUp", ctx.cfg.switchWinUp).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winUp", ctx.cfg.overrideWinUp);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+↑ → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            if (ov == Config.OverrideMode.ZUI || ov == Config.OverrideMode.AOSP
                    || ov == Config.OverrideMode.FOLLOW_SYSTEM) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+↑ → ", ov.name(), " (pass through to L3)");
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Win+↑"))
                return;
        }

        // Win+↓ — Restore/minimize window (L3 AOSP type=52)
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && firstDown
                && event.isMetaPressed()) {
            if (!ctx.r("winDown", ctx.cfg.switchWinUp).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winDown", ctx.cfg.overrideWinUp);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+↓ → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            if (ov == Config.OverrideMode.ZUI || ov == Config.OverrideMode.AOSP
                    || ov == Config.OverrideMode.FOLLOW_SYSTEM) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+↓ → ", ov.name(), " (pass through to L3)");
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Win+↓"))
                return;
        }

        // Win+W — Force close foreground app (L4 type=305)
        if (keyCode == KeyEvent.KEYCODE_W && firstDown
                && event.isMetaPressed()) {
            if (!ctx.r("winW", ctx.cfg.switchWinW).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winW", ctx.cfg.overrideWinW);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+W → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            // ZUI / FOLLOW_SYSTEM: pass through, L4 handles KeyGestureEvent type=305
            if (ov == Config.OverrideMode.ZUI || ov == Config.OverrideMode.FOLLOW_SYSTEM) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+W → ", ov.name(), " (pass through to L4)");
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Win+W", KeyEvent.KEYCODE_W))
                return;
        }

        // Win+1~8 — Dock bar (L4 type=309)
        if (firstDown && event.isMetaPressed()
                && keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_8) {
            if (!ctx.r("winNumber", ctx.cfg.switchWinNumber).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("winNumber", ctx.cfg.overrideWinNumber);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+", KeyInjector.keyCodeToString(keyCode),
                        " → OFF (strip Meta, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_META_MASK);
                return;
            }
            // ZUI / FOLLOW_SYSTEM: pass through, L4 handles KeyGestureEvent type=309
            if (ov == Config.OverrideMode.ZUI || ov == Config.OverrideMode.FOLLOW_SYSTEM) {
                LogHelper.log(VerboseLevel.INFO, "L1: Win+", KeyInjector.keyCodeToString(keyCode), " → ", ov.name(),
                        " (pass through to L4)");
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Win+Num", keyCode))
                return;
        }

        // Ctrl+Shift — Switch IME
        if (firstDown && event.isCtrlPressed() && event.isShiftPressed()
                && keyCode != KeyEvent.KEYCODE_T) {
            if (!ctx.r("ctrlShift", ctx.cfg.switchCtrlShift).isEnabled())
                return;
            if (!ctx.cfg.rowInputMethodSwitch)
                return;
            if (ctx.applyInterceptAction(ctx.ra("ctrlShift", ctx.cfg.overrideCtrlShift), param, "L1: Ctrl+Shift"))
                return;
        }

        // Alt+Shift — Switch language
        if (firstDown && event.isAltPressed() && event.isShiftPressed()) {
            if (!ctx.r("altShift", ctx.cfg.switchAltShift).isEnabled())
                return;
            if (!ctx.cfg.rowLanguageSwitch)
                return;
            if (ctx.applyInterceptAction(ctx.ra("altShift", ctx.cfg.overrideAltShift), param, "L1: Alt+Shift"))
                return;
        }

        // Ctrl long-press — Shortcut menu (only intercept DOWN, pass UP through)
        // Ctrl long press: switch-controlled only, no override mode.
        if (firstDown
                && (keyCode == KeyEvent.KEYCODE_CTRL_LEFT
                        || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT)) {
            if (!ctx.r("ctrlLongPress", ctx.cfg.switchCtrlLongPress).isEnabled())
                return;
        }

        // Ctrl+/ — Shortcut menu (OFF: strip Ctrl, pass through)
        // No Config switch — always enabled.
        if (keyCode == KeyEvent.KEYCODE_SLASH && firstDown
                && event.isCtrlPressed()) {
            Config.OverrideMode ov = ctx.ra("ctrlSlash", ctx.cfg.overrideCtrlSlash);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.DEBUG, "L1: Ctrl+/ → OFF (strip Ctrl, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_CTRL_MASK);
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Ctrl+/"))
                return;
        }

        // 510 Settings key bug fix
        if (keyCode == 510 && firstDown) {
            if (!ctx.r("keySettings", ctx.cfg.switchKeySettings).isEnabled())
                return;
            if (ctx.applyInterceptAction(ctx.ra("keySettings", ctx.cfg.overrideSettings), param, "L1: key 510"))
                return;
        }

        // Print Screen (120) — Region screenshot (short) / Full screenshot (long press
        // >=2s)
        if (keyCode == KeyEvent.KEYCODE_SYSRQ && firstDown) {
            if (!ctx.r("printScreenShort", ctx.cfg.switchPrintScreenShort).isEnabled()
                    && !ctx.r("printScreenLong", ctx.cfg.switchPrintScreenLong).isEnabled())
                return;
            if (ctx.applyInterceptAction(ctx.ra("printScreenShort", ctx.cfg.overridePrintScreenShort), param,
                    "L1: PrintScreen"))
                return;
        }

        // Caps Lock (115) — Show toast + pass through
        if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK && firstDown) {
            if (!ctx.r("capsLock", ctx.cfg.switchCapsLock).isEnabled())
                return;
            if (ctx.applyInterceptAction(ctx.ra("capsLock", ctx.cfg.overrideCapsLock), param, "L1: CapsLock"))
                return;
        }

        // Alt_RIGHT (58) — KR language switch
        if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT && firstDown
                && !event.isShiftPressed() && !event.isCtrlPressed()
                && !event.isMetaPressed()) {
            if (!ctx.r("altRightKR", ctx.cfg.switchAltRightKR).isEnabled())
                return;
            if (!ctx.cfg.krAltRightSwitch)
                return;
            if (ctx.applyInterceptAction(ctx.ra("altRightKR", ctx.cfg.overrideAltRightKR), param, "L1: Alt_RIGHT KR"))
                return;
        }

        // Meta single press — Start menu
        if ((keyCode == KeyEvent.KEYCODE_META_LEFT
                || keyCode == KeyEvent.KEYCODE_META_RIGHT)
                && down && repeatCount == 0) {
            if (!ctx.r("metaSingle", ctx.cfg.switchMetaSingle).isEnabled())
                return;
            // 根据 MetaAction 配置分发（短按行为由 ZUI 长按计时器决定，
            // 此处仅控制是否放行到 AOSP type=21 开始菜单）
            Config.MetaAction action = ctx.cfg.metaShortPressAction;
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
