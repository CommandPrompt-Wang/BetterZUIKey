package moe.lovefirefly.betterzuikey.Hook;

import android.os.IBinder;
import android.view.KeyEvent;
import moe.lovefirefly.betterzuikey.Hook.HookCompat;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.Config.IMEBinding;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import moe.lovefirefly.betterzuikey.ime.IMEProfileManager;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

public class L1Interceptor  {

    private final HookContext ctx;

    public L1Interceptor(HookContext ctx) {
        this.ctx = ctx;
    }

        public void intercept(HookCompat.HookParam param) {
        // checkConfigChanged MUST be before enabled check
        ctx.checkConfigChanged();
        ctx.ensureKscFromHook(param.thisObject);

        // Guard: skip injected events to avoid recursive re-processing
        if (ctx.isInjecting()) return;

        // Detect mode: intercept all keys (before master-switch gate)
        if (ctx.isDetectMode()) {
            param.setResult(true);
            return;
        }

        if (ctx.cfg == null || !ctx.cfg.zuxKeyboardFuncEnabled)
            return;

        KeyEvent event = (KeyEvent) param.args[0];
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int repeatCount = event.getRepeatCount();
        boolean firstDown = down && repeatCount == 0;

        boolean pt = PassthroughTrace.shouldTrace(event);
        if (pt) PassthroughTrace.in("L1", event, ctx);
        try {

        // Alt+Tab OFF mode: strip Alt at L1 (closest to dispatch).
        // L0 also strips, but input system may re-add modifiers between
        // queue and dispatch; this is the authoritative stripping point.
        // Plain Tab passes through to foreground app — Alt DOWN/UP already reached
        // the app, so apps tracking key state can still reconstruct Alt+Tab.
        if (keyCode == KeyEvent.KEYCODE_TAB && firstDown
                && KeyInjector.modifiersMatch(event, false, false, false, true)) {
            if (!ctx.r("altTab", ctx.cfg.switchAltTab).isEnabled())
                return;
            Config.OverrideMode ov = ctx.ra("altTab", ctx.cfg.overrideAltTab);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.INFO, "L1: Alt+Tab → OFF (strip Alt, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_ALT_MASK);
                return;
            }
        }

        // Ctrl+Shift+T — pure Ctrl+Shift+T only (no Meta/Alt)
        if (keyCode == KeyEvent.KEYCODE_T && firstDown
                && KeyInjector.modifiersMatch(event, false, true, true, false)) {
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
                && KeyInjector.modifiersMatch(event, true, false, false, false)) {
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
                && KeyInjector.modifiersMatch(event, true, false, false, false)) {
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
                && KeyInjector.modifiersMatch(event, true, false, false, false)) {
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
                && KeyInjector.modifiersMatch(event, true, false, false, false)) {
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
                && KeyInjector.modifiersMatch(event, true, false, false, false)) {
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
                && KeyInjector.modifiersMatch(event, true, false, false, false)) {
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
                && KeyInjector.modifiersMatch(event, true, false, false, false)) {
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
                && KeyInjector.modifiersMatch(event, true, false, false, false)) {
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
                && KeyInjector.modifiersMatch(event, true, false, false, false)) {
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
                && KeyInjector.modifiersMatch(event, true, false, false, false)) {
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
                && KeyInjector.modifiersMatch(event, true, false, false, false)) {
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
        // Use exact modifier match: Meta only (no Alt/Shift/Ctrl), otherwise
        // Win+Alt+3~6 (AOSP accessibility) would be misrouted here.
        if (firstDown && KeyInjector.modifiersMatch(event, true, false, false, false)
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

        // ================================================================
        // IME Enhancement — unified dispatch
        // imeMasterEnabled && isInputShown → intercept; else pass through
        // ================================================================
        if (ctx.cfg.imeMasterEnabled && ctx.isAcceptingText()) {
            LogHelper.log(VerboseLevel.DEBUG, "L1: IME Enhancement active",
                    " imeSwitch=", ctx.cfg.imeSwitchBinding.name(),
                    " langSwitch=", ctx.cfg.languageSwitchBinding.name());
            // Ctrl+Shift
            if (firstDown && KeyInjector.modifiersMatch(event, false, true, true, false)
                    && keyCode != KeyEvent.KEYCODE_T) {
                LogHelper.log(VerboseLevel.INFO, "L1: Ctrl+Shift detected",
                        " kc=", KeyInjector.keyCodeToString(keyCode),
                        " imeBind=", ctx.cfg.imeSwitchBinding.name(),
                        " langBind=", ctx.cfg.languageSwitchBinding.name());
                if (dispatchIMEBinding(ctx.cfg.imeSwitchBinding, ctx.cfg.languageSwitchBinding,
                        IMEBinding.CTRL_SHIFT, param, "Ctrl+Shift")) return;
            }
            // Alt+Shift
            if (firstDown && KeyInjector.modifiersMatch(event, false, true, false, true)) {
                if (dispatchIMEBinding(ctx.cfg.imeSwitchBinding, ctx.cfg.languageSwitchBinding,
                        IMEBinding.ALT_SHIFT, param, "Alt+Shift")) return;
            }
            // Right Alt
            if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT && firstDown
                    && !event.isShiftPressed() && !event.isCtrlPressed()
                    && !event.isMetaPressed()) {
                if (dispatchIMEBinding(ctx.cfg.imeSwitchBinding, ctx.cfg.languageSwitchBinding,
                        IMEBinding.RIGHT_ALT, param, "Right Alt")) return;
            }
            // Ctrl+Space — intercept here as well (defense-in-depth; L0 also handles)
            if (firstDown && KeyInjector.modifiersMatch(event, false, false, true, false)
                    && keyCode == KeyEvent.KEYCODE_SPACE) {
                if (dispatchIMEBinding(ctx.cfg.imeSwitchBinding, ctx.cfg.languageSwitchBinding,
                        IMEBinding.CTRL_SPACE, param, "Ctrl+Space")) return;
            }
        }

        // Ctrl long-press — Shortcut menu (only intercept DOWN, pass UP through)
        // Ctrl long press: switch-controlled only, no override mode.
        if (firstDown
                && (keyCode == KeyEvent.KEYCODE_CTRL_LEFT
                        || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT)) {
            if (!ctx.r("ctrlLongPress", ctx.cfg.switchCtrlLongPress).isEnabled())
                return;
        }

        // Ctrl+/ — pure Ctrl+/ only (no Meta/Alt/Shift)
        if (keyCode == KeyEvent.KEYCODE_SLASH && firstDown
                && KeyInjector.modifiersMatch(event, false, false, true, false)) {
            Config.OverrideMode ov = ctx.ra("ctrlSlash", ctx.cfg.overrideCtrlSlash);
            if (ov == Config.OverrideMode.OFF) {
                LogHelper.log(VerboseLevel.DEBUG, "L1: Ctrl+/ → OFF (strip Ctrl, pass through)");
                KeyInjector.stripMetaState(event, KeyEvent.META_CTRL_MASK);
                return;
            }
            if (ctx.applyInterceptAction(ov, param, "L1: Ctrl+/"))
                return;
        }

        // Caps Lock (115) — Show toast + pass through
        if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK && firstDown) {
            if (!ctx.r("capsLock", ctx.cfg.switchCapsLock).isEnabled())
                return;
            if (ctx.applyInterceptAction(ctx.ra("capsLock", ctx.cfg.overrideCapsLock), param, "L1: CapsLock"))
                return;
        }

        // Meta key — DOWN only at L1 (UP handled at L0 beforeQueueing)
        if (keyCode == KeyEvent.KEYCODE_META_LEFT
                || keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            MetaTrace.event("L1", event, ctx);
            boolean consumedBefore = param.isReturnEarly();
            MetaKeyRouter router = new MetaKeyRouter(ctx);
            if (down) {
                router.routeDownL1(event, param);
            }
            if (!consumedBefore && param.isReturnEarly()) {
                MetaTrace.hookResult("L1", true);
            }
        }
        } finally {
            if (pt) PassthroughTrace.out("L1", event, param);
        }
    }

    // ---- IME dispatch helper ----

    /** @return true if the event was handled (consumed or intentionally passed through) */
    private boolean dispatchIMEBinding(IMEBinding ime, IMEBinding lang,
                                       IMEBinding combo, HookCompat.HookParam param,
                                       String label) {
        boolean matchIme = (ime == combo);
        boolean matchLang = (lang == combo);
        if (!matchIme && !matchLang) return false;

        if (matchIme) {
            // Switch IME — system-level, no profile needed
            param.setResult(true);
            LogHelper.log(VerboseLevel.INFO, "L1: ", label, " → switch IME");
            String imeName = ctx.switchInputMethod();
            if (ctx.cfg.imeToastEnabled && imeName != null) {
                KeyInjector.showToast(imeName);
            }
            return true;
        }

        // Switch language — IME profile
        param.setResult(true);
        LogHelper.log(VerboseLevel.INFO, "L1: ", label, " → switch language (profile)");
        boolean ok = ctx.triggerIMEProfile();
        if (ctx.cfg.imeToastEnabled) {
            KeyInjector.showToast(ok ? "Language switched" : "No IME profile matched");
        }
        return true;
    }
}
