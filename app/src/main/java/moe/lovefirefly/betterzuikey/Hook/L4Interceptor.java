package moe.lovefirefly.betterzuikey.Hook;

import android.os.IBinder;
import android.view.KeyEvent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import moe.lovefirefly.betterzuikey.ime.AdapterManager;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

public class L4Interceptor extends XC_MethodHook {

    private final HookContext ctx;

    public L4Interceptor(HookContext ctx) {
        this.ctx = ctx;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        ctx.checkConfigChanged();
        if (ctx.cfg == null || !ctx.cfg.zuxKeyboardFuncEnabled)
            return;

        Object kgEvent = param.args[0];
        try {
            int type = (int) kgEvent.getClass()
                    .getMethod("getKeyGestureType").invoke(kgEvent);
            LogHelper.log(VerboseLevel.DEBUG, "L4: type=", String.valueOf(type));
            // DEBUG: timestamp type=21 gesture
            if (type == 21) {
                KeyInjector.debugProp("debug.bzuikey.time.l4",
                        "type=21 t=" + System.currentTimeMillis()
                        + " up=" + android.os.SystemClock.uptimeMillis());
            }

            boolean blocked = false;
            switch (type) {
                case 300: // Win+S — Global search
                    if (!ctx.r("winS", ctx.cfg.switchWinS).isEnabled())
                        break;
                    if (ctx.applyL4BlockAction(ctx.ra("winS", ctx.cfg.overrideWinS), "L4: Win+S (300)"))
                        blocked = true;
                    break;
                case 302: // Win+A — Hide/show taskbar
                    if (!ctx.r("winA", ctx.cfg.switchWinA).isEnabled())
                        break;
                    if (ctx.applyL4BlockAction(ctx.ra("winA", ctx.cfg.overrideWinA), "L4: Win+A (302)"))
                        blocked = true;
                    break;
                case 305: // Win+W — Force close foreground app
                    if (!ctx.r("winW", ctx.cfg.switchWinW).isEnabled())
                        break;
                    if (ctx.applyL4BlockAction(ctx.ra("winW", ctx.cfg.overrideWinW), "L4: Win+W (305)"))
                        blocked = true;
                    break;
                case 306: // Win+Back — Send ESC
                    if (!ctx.r("winBack", ctx.cfg.switchWinBack).isEnabled())
                        break;
                    if (ctx.applyL4BlockAction(ctx.ra("winBack", ctx.cfg.overrideWinBack), "L4: Win+Back (306)"))
                        blocked = true;
                    break;
                case 307: // Win+E — Open file manager
                    if (!ctx.r("winE", ctx.cfg.switchWinE).isEnabled())
                        break;
                    if (ctx.applyL4BlockAction(ctx.ra("winE", ctx.cfg.overrideWinE), "L4: Win+E (307)"))
                        blocked = true;
                    break;
                case 308: // Ctrl+Shift+T — Toggle touchpad
                    LogHelper.log(VerboseLevel.INFO, "L4: Ctrl+Shift+T (308) ENTERED, switch=",
                            ctx.r("ctrlShiftT", ctx.cfg.switchCtrlShiftT).name(),
                            " override=", ctx.ra("ctrlShiftT", ctx.cfg.overrideCtrlShiftT).name());
                    if (!ctx.r("ctrlShiftT", ctx.cfg.switchCtrlShiftT).isEnabled())
                        break; {
                    Config.OverrideMode cstMode = ctx.ra("ctrlShiftT", ctx.cfg.overrideCtrlShiftT);
                    // 默认: 完全不干预，直接放行
                    if (cstMode == Config.OverrideMode.FOLLOW_SYSTEM) {
                        LogHelper.log(VerboseLevel.DEBUG, "L4: Ctrl+Shift+T (308) → FOLLOW_SYSTEM, pass through");
                        break;
                    }
                    // 非默认: 拦截
                    blocked = true;
                    LogHelper.log(VerboseLevel.INFO, "L4: Ctrl+Shift+T (308) → blocked (mode=", cstMode.name(), ")");
                    if (cstMode == Config.OverrideMode.ZUI) {
                        // ZUI实现: 照抄 case 308 — this.mPolicy.toggleTouchpadFromKey()
                        try {
                            if (ctx.policyInstance != null) {
                                ctx.policyInstance.getClass().getMethod("toggleTouchpadFromKey")
                                        .invoke(ctx.policyInstance);
                                LogHelper.log(VerboseLevel.INFO,
                                        "L4: Ctrl+Shift+T → ZUI (toggleTouchpadFromKey called)");
                            } else {
                                LogHelper.log(VerboseLevel.WARNING,
                                        "L4: Ctrl+Shift+T → ZUI but ctx.policyInstance is null!");
                            }
                        } catch (Exception e) {
                            LogHelper.log(VerboseLevel.ERROR, "L4: toggleTouchpadFromKey reflection failed:",
                                    e.getMessage());
                        }
                    }
                    // OFF/BLOCK/AOSP: 已拦截
                    // OFF → L0 已剥离修饰键，透传给应用
                    // BLOCK → L0 已消费
                    // AOSP → L0 已消费
                }
                    break;
                case 309: // Win+1~8 — Dock bar
                    if (!ctx.r("winNumber", ctx.cfg.switchWinNumber).isEnabled())
                        break;
                    if (ctx.applyL4BlockAction(ctx.ra("winNumber", ctx.cfg.overrideWinNumber), "L4: Win+Num (309)"))
                        blocked = true;
                    break;
                case 310: // Alt+Shift — Switch language
                    if (!ctx.r("altShift", ctx.cfg.switchAltShift).isEnabled())
                        break;
                    if (ctx.applyL4BlockAction(ctx.ra("altShift", ctx.cfg.overrideAltShift), "L4: Alt+Shift (310)"))
                        blocked = true;
                    break;
                case 311: // Ctrl+Shift — Switch IME (or remap → Ctrl+Space)
                    if (!ctx.r("ctrlShift", ctx.cfg.switchCtrlShift).isEnabled())
                        break;
                    // Guard: skip injected events
                    if (ctx.isInjecting()) break;

                    // Only remap/adapt when IME is accepting text input.
                    // Outside text input, fall through to native ZUI behavior.
                    if (ctx.isAcceptingText()) {
                        // 1) Custom adapter binding
                        if (AdapterManager.getAdapterForShortcut("ctrlShift") != null) {
                            LogHelper.log(VerboseLevel.INFO, "L4: Ctrl+Shift (311) → IME adapter");
                            blocked = true;
                            ctx.triggerIMEAdapter("ctrlShift", false);
                        }
                        // 2) Built-in remap: Ctrl+Shift → Ctrl+Space
                        else if (ctx.cfg.ctrlShiftRemapEnabled) {
                            LogHelper.log(VerboseLevel.INFO, "L4: Ctrl+Shift (311) → remap Ctrl+Space");
                            blocked = true;
                            ctx.injectCtrlSpace();
                        }
                        // 3) Native OverrideMode logic (FOLLOW_SYSTEM / BLOCK / ZUI / OFF / AOSP)
                        else {
                            if (ctx.applyL4BlockAction(ctx.ra("ctrlShift", ctx.cfg.overrideCtrlShift),
                                    "L4: Ctrl+Shift (311)"))
                                blocked = true;
                        }
                    } else {
                        // Not in text input — native behavior
                        if (ctx.applyL4BlockAction(ctx.ra("ctrlShift", ctx.cfg.overrideCtrlShift),
                                "L4: Ctrl+Shift (311)"))
                            blocked = true;
                    }
                    break;
                case 312: // Win+Left/Right — Split screen
                    if (!ctx.r("winLeft", ctx.cfg.switchWinLeft).isEnabled())
                        break;
                    if (ctx.applyL4BlockAction(ctx.ra("winLeft", ctx.cfg.overrideWinLeft), "L4: Win+Arrow (312)"))
                        blocked = true;
                    break;
                case 313: // 505 Super Connect key
                    if (!ctx.r("keySuperConnect", ctx.cfg.switchKeySuperConnect).isEnabled())
                        break;
                    if (ctx.applyL4BlockAction(ctx.ra("keySuperConnect", ctx.cfg.overrideSuperConnect),
                            "L4: SuperConnect (313)"))
                        blocked = true;
                    break;
                case 21: // Meta single press → Start Menu
                    if (!ctx.r("metaSingle", ctx.cfg.switchMetaSingle).isEnabled())
                        break;
                    {
                        Config.MetaAction action = ctx.cfg.metaShortPressAction;
                        Config.OverrideMode override = ctx.ra("metaSingle",
                                ctx.cfg.overrideMetaSingle);
                        if (override == Config.OverrideMode.OFF
                                || action == Config.MetaAction.NONE) {
                            LogHelper.log(VerboseLevel.INFO,
                                    "L4: Meta (type=21) → OFF → inject Meta to App");
                            ctx.injectMetaToApp(0, ctx.lastMetaScanCode);
                            param.setResult(-1);
                            return;
                        }
                        if (override == Config.OverrideMode.BLOCK) {
                            LogHelper.log(VerboseLevel.INFO,
                                    "L4: Meta (type=21) → BLOCK → consume");
                            param.setResult(-1);
                            return;
                        }
                    }
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
