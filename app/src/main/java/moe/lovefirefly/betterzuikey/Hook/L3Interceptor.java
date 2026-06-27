package moe.lovefirefly.betterzuikey.Hook;

import android.os.IBinder;
import android.view.KeyEvent;
import moe.lovefirefly.betterzuikey.Hook.HookCompat;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

public class L3Interceptor  {

    private final HookContext ctx;

    public L3Interceptor(HookContext ctx) {
        this.ctx = ctx;
    }

        public void intercept(HookCompat.HookParam param) {
        ctx.checkConfigChanged();
        if (ctx.cfg == null || !ctx.cfg.zuxKeyboardFuncEnabled)
            return;

        // DEBUG: confirm L3 hook is alive (writes on every gesture)
        KeyInjector.debugProp("debug.bzuikey.l3.alive", String.valueOf(System.currentTimeMillis()));

        Object kgEvent = param.args[0];
        try {
            int type = (int) kgEvent.getClass()
                    .getMethod("getKeyGestureType").invoke(kgEvent);

            // DEBUG: log ALL gesture types to SystemProperties
            KeyInjector.debugProp("debug.bzuikey.l3.type", String.valueOf(type));

            Config.OverrideMode action = null;
            String label = null;

            switch (type) {
                case 1: // Win+D → goHome()
                    if (!ctx.r("winD", ctx.cfg.switchWinD).isEnabled())
                        break;
                    action = ctx.ra("winD", ctx.cfg.overrideWinD);
                    label = "L3: Win+D (type=1)";
                    break;
                case 2: // Win+Tab → showRecentApps(false) (AOSP独立注册, same effect as ZUI)
                // BLOCK / OFF: suppress AOSP type=2
                {
                    Config.OverrideMode ov2 = ctx.ra("winTab", ctx.cfg.overrideWinTab);
                    KeyInjector.debugProp("debug.bzuikey.l3.wintab", "ENTER override=" + ov2.name());
                    if (ov2 == Config.OverrideMode.BLOCK || ov2 == Config.OverrideMode.OFF) {
                        KeyInjector.debugProp("debug.bzuikey.l3.wintab", "ACTION: BLOCK (mode=" + ov2.name() + ")");
                        LogHelper.log(VerboseLevel.INFO, "L3: Win+Tab (type=2)",
                                "→ BLOCK AOSP showRecentApps (mode=", ov2.name(), ")");
                        param.setResult(null);
                        return;
                    }
                    // ZUI / FOLLOW_SYSTEM: AOSP type=2 same as ZUI, pass through
                    KeyInjector.debugProp("debug.bzuikey.l3.wintab", "ACTION: pass through (mode=" + ov2.name() + ")");
                }
                    break;
                case 7: // Win+I → launchSettings (AOSP default registered)
                    if (!ctx.r("winI", ctx.cfg.switchWinI).isEnabled())
                        break;
                    action = ctx.ra("winI", ctx.cfg.overrideWinI);
                    label = "L3: Win+I (type=7)";
                    break;
                case 8: // Win+N / 514 → 通知面板
                    if (!ctx.r("winN", ctx.cfg.switchWinN).isEnabled())
                        break;
                    action = ctx.ra("winN", ctx.cfg.overrideWinN);
                    label = "L3: Win+N (type=8)";
                    break;
                case 12: // Ctrl+/ → toggleKeyboardShortcutsMenu
                    // No switch — always enabled. Override mode check follows.
                    action = ctx.ra("ctrlSlash", ctx.cfg.overrideCtrlSlash);
                    label = "L3: Ctrl+/ (type=12)";
                    break;
                case 52: // Win+↓ → moveFocusedTaskToDesktop()
                    if (!ctx.r("winDown", ctx.cfg.switchWinUp).isEnabled())
                        break;
                    action = ctx.ra("winDown", ctx.cfg.overrideWinUp);
                    label = "L3: Win+↓ (type=52)";
                    break;
                case 53: // Win+↑ → moveFocusedTaskToFullscreen()
                    if (!ctx.r("winUp", ctx.cfg.switchWinUp).isEnabled())
                        break;
                    action = ctx.ra("winUp", ctx.cfg.overrideWinUp);
                    label = "L3: Win+↑ (type=53)";
                    break;
                case 21: // Meta single press → Start Menu (AOSP triggerShowAllApps)
                    if (!ctx.r("metaSingle", ctx.cfg.switchMetaSingle).isEnabled())
                        break;
                    {
                        Config.MetaAction metaAction = ctx.cfg.metaShortPressAction;
                        Config.OverrideMode metaOverride = ctx.ra("metaSingle",
                                ctx.cfg.overrideMetaSingle);
                        // NONE / OFF / BLOCK: suppress AOSP Start Menu.
                        // Meta itself passes through at L1 so App receives it.
                        if (metaAction == Config.MetaAction.NONE
                                || metaOverride == Config.OverrideMode.OFF
                                || metaOverride == Config.OverrideMode.BLOCK) {
                            LogHelper.log(VerboseLevel.INFO,
                                    "L3: Meta (type=21) → BLOCK (Start Menu suppressed, Meta reached App via L1)");
                            param.setResult(null);
                            return;
                        }
                        // START_MENU / DEFAULT / SWITCH_LANGUAGE / VOICE_ASSIST
                        // → pass through, let ZUI or AOSP handle normally
                        LogHelper.log(VerboseLevel.DEBUG,
                                "L3: Meta (type=21) → pass through (action=",
                                metaAction.name(), ")");
                    }
                    break;
                case 201: // Win+M → ovMinimizeFreeformGroup()
                    if (!ctx.r("winM", ctx.cfg.switchWinM).isEnabled())
                        break;
                    action = ctx.ra("winM", ctx.cfg.overrideWinM);
                    label = "L3: Win+M (type=201)";
                    break;
            }

            if (action == null)
                return;

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
