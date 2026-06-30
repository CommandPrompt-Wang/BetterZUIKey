package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.Config.IMEBinding;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * Win/Meta routing.
 * <p>
 * DOWN (incl. repeat) → L1 {@link #routeDownL1}. UP → L0 {@link #routeUpL0} only
 * (ZUI consumes Meta UP in beforeQueueing; it never reaches beforeDispatching).
 */
public class MetaKeyRouter {

    private final HookContext ctx;

    public MetaKeyRouter(HookContext ctx) {
        this.ctx = ctx;
    }

    /** L1: first DOWN + repeat DOWN (long-press timer). */
    public void routeDownL1(KeyEvent event, HookCompat.HookParam param) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_META_LEFT
                && keyCode != KeyEvent.KEYCODE_META_RIGHT) {
            return;
        }
        int scanCode = event.getScanCode();
        if (scanCode == 0) {
            MetaTrace.decision("Router", "skip sc=0 (synthetic/injected)");
            return;
        }

        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int repeatCount = event.getRepeatCount();
        if (!down || repeatCount != 0) {
            return;
        }

        Config.OverrideMode metaSingle = ctx.ra("metaSingle", ctx.cfg.overrideMetaSingle);
        boolean zuiMeta = scanCode == HookContext.ZUI_META_SCAN_CODE;
        boolean imeWinActive = isImeWinActive();

        ctx.lastMetaScanCode = scanCode;
        if (!ctx.metaSession.active) {
            ctx.metaSession.begin(event);
            MetaTrace.session("Router", "begin (L1 fallback)", ctx);
        }
        MetaTrace.decision("Router", "DOWN L1",
                "metaSingle=", metaSingle.name(),
                " zuiMeta=", String.valueOf(zuiMeta),
                " imeWin=", String.valueOf(imeWinActive));
        if (MetaTrace.isTraceOnly()) {
            return;
        }
        if (metaSingle == Config.OverrideMode.BLOCK) {
            param.setResult(true);
            MetaTrace.decision("Router", "BLOCK DOWN → consume");
            return;
        }
        // IME long-press (500ms) fires on ALL keyboards — ZUI handles voice
        // natively, not IME switching. Voice timer (2s) only on non-zuiMeta
        // keyboards; zuiMeta ones rely on our hook of mLaunchAssistantRunnable.
        if (imeWinActive) {
            armModuleLongPress(true, event);
        } else if (!zuiMeta) {
            armModuleLongPress(false, event);
        }
    }

    /**
     * L0: Meta UP (repeatCount=0). This is the only layer that receives physical UP.
     *
     * @return true if the event was consumed (caller should return from L0)
     */
    public boolean routeUpL0(KeyEvent event, HookCompat.HookParam param) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_META_LEFT
                && keyCode != KeyEvent.KEYCODE_META_RIGHT) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_UP || event.getRepeatCount() != 0) {
            return false;
        }
        int scanCode = event.getScanCode();
        if (scanCode == 0) {
            MetaTrace.decision("Router", "skip UP sc=0");
            return false;
        }

        Config.OverrideMode metaSingle = ctx.ra("metaSingle", ctx.cfg.overrideMetaSingle);
        boolean zuiMeta = scanCode == HookContext.ZUI_META_SCAN_CODE;
        boolean imeWinActive = isImeWinActive();

        MetaTrace.event("Router", event, ctx);
        if (!ctx.metaSession.active) {
            MetaTrace.decision("Router", "UP L0 ignored", "no active session");
            return false;
        }
        if (ctx.metaSession.keyCode >= 0 && keyCode != ctx.metaSession.keyCode) {
            MetaTrace.decision("Router", "UP L0 ignored", "kc mismatch event=",
                    String.valueOf(keyCode),
                    " sess=", String.valueOf(ctx.metaSession.keyCode));
            return false;
        }
        if (ctx.metaSession.upHandled) {
            MetaTrace.decision("Router", "UP L0 ignored", "already handled");
            return false;
        }
        ctx.metaSession.upHandled = true;

        boolean shortPress = ctx.metaSession.isShortPress();
        MetaTrace.decision("Router", "UP L0",
                "short=", String.valueOf(shortPress),
                " metaSingle=", metaSingle.name(),
                " zuiMeta=", String.valueOf(zuiMeta));

        if (MetaTrace.isTraceOnly()) {
            logWouldDo(shortPress, metaSingle, zuiMeta, imeWinActive);
            ctx.metaSession.clear();
            MetaTrace.session("Router", "clear (trace-only)", ctx);
            return false;
        }

        boolean consumed = false;

        // BLOCK: physical DOWN was consumed at L1; UP must also be consumed.
        if (metaSingle == Config.OverrideMode.BLOCK) {
            consumed = consumeUp(param, zuiMeta, "BLOCK UP → consume");
            ctx.fnKeyManager.setConsumeMetaUpForNone();
            ctx.fnKeyManager.stopWinLongPressRepeat();
            ctx.metaSession.clear();
            return consumed;
        }

        // IME long fires at 500ms; shortPress threshold is 2s — must consume before menu branches.
        if (ctx.metaSession.longFired) {
            consumed = consumeUp(param, zuiMeta, "longFired UP (IME/voice)");
            ctx.fnKeyManager.stopWinLongPressRepeat();
            ctx.metaSession.clear();
            return consumed;
        }

        // Fn mapping consumed a key during this Meta press — Win was a modifier,
        // not a standalone press. Consume UP without opening Start Menu.
        if (ctx.metaSession.fnMapped) {
            consumed = consumeUp(param, zuiMeta, "fnMapped → suppress menu");
            ctx.metaSession.clear();
            return consumed;
        }

        if (shortPress && imeWinActive) {
            consumed = consumeUp(param, zuiMeta, "imeWin short → Start Menu");
            dispatchStartMenu();
            ctx.metaSession.clear();
            return consumed;
        }

        if (shortPress && shouldOpenStartMenu(metaSingle)) {
            consumed = consumeUp(param, zuiMeta, "short → Start Menu");
            dispatchStartMenu();
            ctx.metaSession.clear();
            return consumed;
        }

        if (shortPress && !zuiMeta && ctx.fnKeyManager.isWinLongPressPending()) {
            ctx.fnKeyManager.cancelWinLongPressTimer();
        }
        MetaTrace.decision("Router", "pass UP L0", "no branch matched");
        ctx.metaSession.clear();
        return consumed;
    }

    private void dispatchStartMenu() {
        ctx.triggerShowAllApps(0);
    }

    private boolean consumeUp(HookCompat.HookParam param, boolean zuiMeta, String reason) {
        if (zuiMeta) {
            ctx.cancelZuiAssistantTimer();
        }
        ctx.fnKeyManager.cancelWinLongPressTimer();
        param.setResult(true);
        MetaTrace.decision("Router", "consume UP", reason);
        return true;
    }

    private void armModuleLongPress(boolean imeWinActive, KeyEvent event) {
        android.os.Handler looperSource = ctx.resolvePolicyHandler();
        if (imeWinActive) {
            PassthroughTrace.note("Router", "arm IME timer 500ms", event);
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: arm IME long timer 500ms (acceptingText=true)");
            ctx.fnKeyManager.startWinLongPressTimer(looperSource, () -> onModuleLongFiredIme());
        } else if (isVoiceLongPressEnabled()) {
            Config.OverrideMode winLong = ctx.ra("winLongPress", ctx.cfg.overrideWinLongPress);
            PassthroughTrace.note("Router",
                    "arm voice timer 2000ms winLong=" + winLong.name(), event);
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: arm voice long timer 2000ms winLong=", winLong.name(),
                    " dev=", String.valueOf(ctx.metaSession.deviceId));
            ctx.fnKeyManager.startWinLongPressOnce(looperSource,
                    HookContext.ZUI_META_LONG_PRESS_MS,
                    this::onModuleLongFiredVoice);
        } else {
            Config.OverrideMode winLong = ctx.ra("winLongPress", ctx.cfg.overrideWinLongPress);
            PassthroughTrace.note("Router",
                    "voice disabled winLong=" + winLong.name(), event);
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: voice long disabled (winLongPress OFF/BLOCK)");
        }
    }

    private void onModuleLongFiredIme() {
        ctx.metaSession.longFired = true;
        LogHelper.log(VerboseLevel.INFO, "MetaRouter: module long → IME (repeat)");
        ctx.fnKeyManager.startWinLongPressRepeat(ctx.resolvePolicyHandler(),
                () -> ctx.dispatchWinLongPressIme());
    }

    private void onModuleLongFiredVoice() {
        if (!isVoiceLongPressEnabled()) return;
        ctx.metaSession.longFired = true;
        MetaTrace.decision("Router", "module long → voice");
        PassthroughTrace.noteMsg("Router", "voice timer fired → launchVoiceAssistant");
        LogHelper.log(VerboseLevel.INFO, "MetaRouter: module long → voice (2s fired)");
        ctx.launchVoiceAssistant();
    }

    public boolean handleAssistantLongPress() {
        if (MetaTrace.isTraceOnly()) {
            MetaTrace.decision("Assistant", "runnable fired (trace-only pass)");
            return false;
        }
        ctx.checkConfigChanged();
        if (ctx.cfg == null || !ctx.cfg.zuxKeyboardFuncEnabled) {
            return false;
        }

        boolean imeWinActive = isImeWinActive();
        LogHelper.log(VerboseLevel.INFO,
                "MetaRouter: assistantRunnable acceptingText=",
                String.valueOf(ctx.isAcceptingText()),
                " imeWin=", String.valueOf(imeWinActive),
                " deviceId=", String.valueOf(ctx.metaSession.deviceId));

        if (imeWinActive) {
            ctx.metaSession.longFired = true;
            LogHelper.log(VerboseLevel.INFO, "MetaRouter: 2s long → IME (repeat)");
            ctx.fnKeyManager.startWinLongPressRepeat(ctx.resolvePolicyHandler(),
                    () -> ctx.dispatchWinLongPressIme());
            return true;
        }

        Config.OverrideMode winLong = ctx.ra("winLongPress", ctx.cfg.overrideWinLongPress);
        if (winLong == Config.OverrideMode.OFF || winLong == Config.OverrideMode.BLOCK) {
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: 2s long → suppress voice (winLong=", winLong.name(), ")");
            return true;
        }
        ctx.metaSession.longFired = true;
        if (winLong == Config.OverrideMode.ZUI || winLong == Config.OverrideMode.AOSP) {
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: 2s long → voice (winLong=", winLong.name(), ")");
        } else {
            // FOLLOW_SYSTEM: ZUI native only handles scanCode=787345; module must call for others.
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: 2s long → voice (winLong=FOLLOW_SYSTEM, module)");
        }
        ctx.launchVoiceAssistant();
        return true;
    }

    private boolean isImeWinActive() {
        return isImeWinActive(ctx);
    }

    static boolean isImeWinActive(HookContext ctx) {
        boolean winBound = ctx.cfg.imeSwitchBinding == IMEBinding.WIN
                || ctx.cfg.languageSwitchBinding == IMEBinding.WIN;
        return winBound && ctx.cfg.imeMasterEnabled && ctx.isAcceptingText();
    }

    static boolean shouldHandleShortUp(Config.OverrideMode metaSingle, boolean imeWinActive) {
        return metaSingle == Config.OverrideMode.BLOCK
                || shouldOpenStartMenu(metaSingle)
                || imeWinActive;
    }

    private boolean isVoiceLongPressEnabled() {
        Config.OverrideMode mode = ctx.ra("winLongPress", ctx.cfg.overrideWinLongPress);
        return mode != Config.OverrideMode.OFF && mode != Config.OverrideMode.BLOCK;
    }

    private static boolean shouldOpenStartMenu(Config.OverrideMode mode) {
        return mode == Config.OverrideMode.ZUI
                || mode == Config.OverrideMode.FOLLOW_SYSTEM
                || mode == Config.OverrideMode.AOSP;
    }

    private void logWouldDo(boolean shortPress, Config.OverrideMode metaSingle,
                            boolean zuiMeta, boolean imeWinActive) {
        if (metaSingle == Config.OverrideMode.BLOCK) {
            MetaTrace.decision("Router", "WOULD", "BLOCK UP (any duration)");
        } else if (ctx.metaSession.longFired) {
            MetaTrace.decision("Router", "WOULD", "consume longFired UP (no menu)");
        } else if (shortPress && imeWinActive) {
            MetaTrace.decision("Router", "WOULD", "consume + triggerShowAllApps (imeWin)");
        } else if (shortPress && shouldOpenStartMenu(metaSingle)) {
            MetaTrace.decision("Router", "WOULD", "consume + triggerShowAllApps L0");
        } else {
            MetaTrace.decision("Router", "WOULD", "pass UP (no branch)");
        }
    }
}
