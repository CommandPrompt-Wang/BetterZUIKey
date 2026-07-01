package moe.lovefirefly.betterzuikey.Hook;

/**
 * L4 key-gesture intercept (handleKeyGesture).
 *
 * <p>505 SuperConnect (type=313) 等 50x 物理键相关 gesture 拦截已暂时移除，见 {@link ZUIKeyHook}。
 */
import android.os.IBinder;
import android.view.KeyEvent;
import moe.lovefirefly.betterzuikey.Hook.HookCompat;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

public class L4Interceptor  {

    private final HookContext ctx;

    public L4Interceptor(HookContext ctx) {
        this.ctx = ctx;
    }

        public void intercept(HookCompat.HookParam param) {
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
                case 310: // Alt+Shift — IME enhancement
                case 311: // Ctrl+Shift — IME enhancement
                    {
                        Config.IMEBinding binding = (type == 310)
                                ? ctx.cfg.languageSwitchBinding
                                : ctx.cfg.imeSwitchBinding;
                        if (binding == Config.IMEBinding.OFF) {
                            LogHelper.log(VerboseLevel.INFO,
                                    "L4: type=", String.valueOf(type),
                                    " → OFF (block ZUI action)");
                            blocked = true;
                        }
                    }
                    break;
                case 312: // Win+Left/Right — Split screen
                    if (!ctx.r("winLeft", ctx.cfg.switchWinLeft).isEnabled())
                        break;
                    if (ctx.applyL4BlockAction(ctx.ra("winLeft", ctx.cfg.overrideWinLeft), "L4: Win+Arrow (312)"))
                        blocked = true;
                    break;
                case 21: // Meta single press → Start Menu
                    {
                        Config.OverrideMode override = ctx.ra("metaSingle",
                                ctx.cfg.overrideMetaSingle);
                        MetaTrace.decision("L4", "type=21",
                                "override=", override.name(),
                                " lastSc=", String.valueOf(ctx.lastMetaScanCode),
                                " traceOnly=", String.valueOf(MetaTrace.isTraceOnly()));
                        if (MetaTrace.isTraceOnly()) {
                            break;
                        }
                        if (override == Config.OverrideMode.BLOCK) {
                            MetaTrace.decision("L4", "BLOCK type=21", "BLOCK");
                            param.setResult(-1);
                            return;
                        }
                        if (ctx.metaStartMenuDispatched) {
                            MetaTrace.decision("L4", "BLOCK type=21 duplicate",
                                    "already dispatched at L0");
                            param.setResult(-1);
                            return;
                        }
                        MetaTrace.decision("L4", "pass type=21", override.name());
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
