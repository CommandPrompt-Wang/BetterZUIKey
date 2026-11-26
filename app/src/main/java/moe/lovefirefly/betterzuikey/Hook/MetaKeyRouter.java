package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.Config.IMEBinding;
import moe.lovefirefly.betterzuikey.MetaKeyMap;
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
        LogHelper.log(VerboseLevel.INFO,
                "[WIN-HOT] routeDownL1 kc=", String.valueOf(keyCode),
                " action=", String.valueOf(event.getAction()),
                " repeat=", String.valueOf(event.getRepeatCount()),
                " scan=", String.valueOf(event.getScanCode()),
                " active=", String.valueOf(ctx.metaSession.active),
                " left=", String.valueOf(ctx.metaSession.leftHeld),
                " right=", String.valueOf(ctx.metaSession.rightHeld),
                " passthrough=", String.valueOf(ctx.isMetaPassthrough()),
                " mapped=", String.valueOf(ctx.isMetaMapped()));
        if (ctx.isSyntheticMetaTap(event)) {
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
        LogHelper.log(VerboseLevel.INFO,
                "[WIN-HOT] routeUpL0 decision mode=", metaSingle.name(),
                " shortCandidate=", String.valueOf(ctx.metaSession.isShortPress()),
                " policy=", ctx.policyInstance != null ? "ok" : "null",
                " ksc=", ctx.kscInstance != null ? "ok" : "null",
                " imeWin=", String.valueOf(imeWinActive),
                " zuiMeta=", String.valueOf(zuiMeta));

        ctx.lastMetaScanCode = scanCode;
        if (!ctx.metaSession.active) {
            ctx.beginMetaSession(event);
            MetaTrace.session("Router", "begin (L1 fallback)", ctx);
        } else {
            ctx.beginMetaSession(event);
        }
        MetaTrace.decision("Router", "DOWN L1",
                "metaSingle=", metaSingle.name(),
                " zuiMeta=", String.valueOf(zuiMeta),
                " imeWin=", String.valueOf(imeWinActive));
        if (MetaTrace.isTraceOnly()) {
            return;
        }
        if (ctx.isMetaMapped()) {
            ctx.startMappedMetaOutput(event);
        }
        if (metaSingle == Config.OverrideMode.BLOCK) {
            param.setResult(true);
            MetaTrace.decision("Router", "BLOCK DOWN → consume");
            return;
        }
        if (imeWinActive) {
            armModuleLongPress(true, event);
        } else {
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
        LogHelper.log(VerboseLevel.INFO,
                "[WIN-HOT] routeUpL0 kc=", String.valueOf(keyCode),
                " active=", String.valueOf(ctx.metaSession.active),
                " upHandled=", String.valueOf(ctx.metaSession.upHandled),
                " left=", String.valueOf(ctx.metaSession.leftHeld),
                " right=", String.valueOf(ctx.metaSession.rightHeld),
                " sessionKc=", String.valueOf(ctx.metaSession.keyCode),
                " intercepted=", String.valueOf(ctx.isMetaIntercepted()),
                " passthrough=", String.valueOf(ctx.isMetaPassthrough()),
                " mapped=", String.valueOf(ctx.isMetaMapped()),
                " scan=", String.valueOf(event.getScanCode()));
        if (event.getAction() != KeyEvent.ACTION_UP || event.getRepeatCount() != 0) {
            return false;
        }
        if (ctx.isSyntheticMetaTap(event)) {
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
            if (ctx.hasPassthroughState() && !MetaTrace.isTraceOnly()) {
                boolean emitted = ctx.onPassthroughMetaUp(event);
                if (emitted) {
                    param.setResult(true);
                    return true;
                }
            }
            MetaTrace.decision("Router", "UP L0 ignored", "no active session");
            return false;
        }
        if (ctx.metaSession.upHandled) {
            MetaTrace.decision("Router", "UP L0 ignored", "already handled");
            return false;
        }
        if (!ctx.metaSession.shouldProcessMetaUp(keyCode)) {
            MetaTrace.decision("Router", "UP L0 ignored", "physical side not held",
                    " kc=", String.valueOf(keyCode));
            return false;
        }
        ctx.metaSession.markSideReleased(keyCode);
        if (ctx.isMetaPassthrough() && !MetaTrace.isTraceOnly()) {
            boolean emitted = ctx.onPassthroughMetaUp(event);
            if (ctx.metaSession.leftHeld || ctx.metaSession.rightHeld) {
                if (emitted) {
                    ctx.metaSession.winComboUsed = true;
                }
                MetaTrace.decision("Router", "UP L0 deferred (passthrough)",
                        "other physical Win side still held", " kc=", String.valueOf(keyCode));
                param.setResult(true);
                return true;
            }
            return finishPassthroughUp(param, event, emitted);
        }
        if (ctx.hasPassthroughState() && !MetaTrace.isTraceOnly()) {
            boolean emitted = ctx.onPassthroughMetaUp(event);
            if (!ctx.metaSession.leftHeld && !ctx.metaSession.rightHeld) {
                ctx.releasePassthroughOnReset();
            }
            if (emitted) {
                ctx.metaSession.winComboUsed = true;
            }
        }
        if (ctx.metaSession.leftHeld || ctx.metaSession.rightHeld) {
            MetaTrace.decision("Router", "UP L0 deferred",
                    "other physical Win side still held", " kc=", String.valueOf(keyCode));
            param.setResult(true);
            return true;
        }
        ctx.cancelMetaAssistantCallback();
        ctx.metaSession.upHandled = true;

        // Win 按住期间扣下的外加键：到这里要么补成完整和弦（只按了修饰键、没有触发键），
        // 要么按原顺序单独放行 —— 用户按下去的键不能因为我们扣过就丢了
        ctx.finishWinExtrasOnMetaUp();

        boolean shortPress = ctx.metaSession.isShortPress();
        LogHelper.log(VerboseLevel.INFO,
                "[WIN-HOT] normal UP branch short=", String.valueOf(shortPress),
                " mode=", metaSingle.name(),
                " zuiMeta=", String.valueOf(zuiMeta),
                " imeWin=", String.valueOf(imeWinActive),
                " fnMapped=", String.valueOf(ctx.metaSession.fnMapped),
                " winCombo=", String.valueOf(ctx.metaSession.winComboUsed),
                " policy=", ctx.policyInstance != null ? "ok" : "null");
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

        // BLOCK: physical DOWN was consumed at L1; UP must also be consumed.
        if (metaSingle == Config.OverrideMode.BLOCK) {
            boolean consumed = consumeUp(param, zuiMeta, "BLOCK UP → consume");
            ctx.fnKeyManager.setConsumeMetaUpForNone();
            ctx.fnKeyManager.stopWinLongPressRepeat();
            ctx.metaSession.clear();
            return consumed;
        }

        // IME long fires at 500ms; shortPress threshold is 2s — must consume before menu branches.
        if (ctx.metaSession.longFired) {
            boolean consumed = consumeUp(param, zuiMeta, "longFired UP (IME/voice)");
            ctx.fnKeyManager.stopWinLongPressRepeat();
            ctx.metaSession.clear();
            return consumed;
        }

        if (ctx.isMetaMapped()) {
            boolean standalone = shortPress
                    && !ctx.metaSession.fnMapped
                    && !ctx.metaSession.winComboUsed;
            ctx.fnKeyManager.cancelWinLongPressTimer();
            ctx.cancelZuiAssistantTimer();
            ctx.releaseMappedMetaOutput(standalone);
            ctx.metaSession.clear();
            param.setResult(true);
            return true;
        }

        // Fn mapping consumed a key during this Meta press — Win was a modifier,
        // not a standalone press. Consume UP without opening Start Menu.
        if (ctx.metaSession.fnMapped || ctx.metaSession.winComboUsed) {
            String reason = ctx.metaSession.fnMapped
                    ? "fnMapped → suppress menu" : "winCombo → suppress menu";
            boolean consumed = consumeUp(param, zuiMeta, reason);
            ctx.metaSession.clear();
            return consumed;
        }

        if (shortPress && imeWinActive) {
            boolean consumed = consumeUp(param, zuiMeta, "imeWin short → Start Menu");
            dispatchStartMenu();
            ctx.metaSession.clear();
            return consumed;
        }

        if (shortPress && metaSingle == Config.OverrideMode.AOSP) {
            boolean consumed = consumeUp(param, zuiMeta, "AOSP short → launchAllAppsAction");
            ctx.triggerAospAllApps(ctx.metaSession.displayId);
            ctx.metaSession.clear();
            return consumed;
        }

        if (shortPress && (metaSingle == Config.OverrideMode.FOLLOW_SYSTEM
                || shouldOpenStartMenu(metaSingle))) {
            boolean consumed = consumeUp(param, zuiMeta, "short → Start Menu");
            dispatchStartMenu();
            ctx.metaSession.clear();
            return consumed;
        }

        boolean consumed = consumeUp(param, zuiMeta, "UP L0 → consume withheld Meta");
        ctx.metaSession.clear();
        return consumed;
    }

    private void dispatchStartMenu() {
        ctx.triggerShowAllApps(ctx.metaSession.displayId);
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

    private boolean finishPassthroughUp(HookCompat.HookParam param, KeyEvent event,
                                        boolean emittedPassthrough) {
        boolean standalone = ctx.metaSession.active
                && ctx.metaSession.isShortPress()
                && !ctx.metaSession.longFired
                && !ctx.metaSession.fnMapped
                && !ctx.metaSession.winComboUsed
                && !emittedPassthrough;
        ctx.fnKeyManager.cancelWinLongPressTimer();
        ctx.cancelZuiAssistantTimer();
        if (standalone) {
            MetaTrace.decision("Router", "passthrough UP → inject Meta tap",
                    " kc=", String.valueOf(event.getKeyCode()));
            KeyInjector.injectMetaTap(event);
        } else {
            MetaTrace.decision("Router", "passthrough UP → finish",
                    " emitted=", String.valueOf(emittedPassthrough),
                    " combo=", String.valueOf(ctx.metaSession.winComboUsed),
                    " long=", String.valueOf(ctx.metaSession.longFired),
                    " fn=", String.valueOf(ctx.metaSession.fnMapped));
        }
        ctx.metaSession.clear();
        param.setResult(true);
        return true;
    }

    private void armModuleLongPress(boolean imeWinActive, KeyEvent event) {
        android.os.Handler looperSource = ctx.resolvePolicyHandler();
        final long generation = ctx.metaSession.generation;
        if (imeWinActive) {
            PassthroughTrace.note("Router", "arm IME timer 500ms", event);
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: arm IME long timer 500ms (acceptingText=true)");
            ctx.fnKeyManager.startWinLongPressTimer(looperSource,
                    () -> onModuleLongFiredIme(generation));
        } else if (isWinLongTimerEnabled()) {
            Config.OverrideMode winLong = getWinLongOverride();
            boolean cmd = isWinLongUseCommand();
            PassthroughTrace.note("Router",
                    "arm winLong timer 2000ms override=" + winLong.name()
                            + " cmd=" + cmd, event);
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: arm win long timer 2000ms override=", winLong.name(),
                    " cmd=", String.valueOf(cmd),
                    " dev=", String.valueOf(ctx.metaSession.deviceId));
            ctx.fnKeyManager.startWinLongPressOnce(looperSource,
                    HookContext.ZUI_META_LONG_PRESS_MS,
                    () -> onModuleLongFiredWinLong(generation));
        } else {
            PassthroughTrace.note("Router",
                    "win long pass-through override=" + getWinLongOverride().name(), event);
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: win long pass-through (override=",
                    getWinLongOverride().name(), ")");
        }
    }

    private void onModuleLongFiredIme(long generation) {
        if (!isMetaSessionGenerationActive(generation)) return;
        ctx.metaSession.longFired = true;
        LogHelper.log(VerboseLevel.INFO, "MetaRouter: module long → IME (repeat)");
        ctx.fnKeyManager.startWinLongPressRepeat(ctx.resolvePolicyHandler(),
                () -> {
                    if (isMetaSessionGenerationActive(generation)) {
                        ctx.dispatchWinLongPressIme();
                    }
                });
    }

    private void onModuleLongFiredWinLong(long generation) {
        if (!isMetaSessionGenerationActive(generation) || !isWinLongTimerEnabled()) return;
        dispatchWinLongAction(generation);
    }

    private void dispatchWinLongAction(long generation) {
        if (!isMetaSessionGenerationActive(generation)) return;
        ctx.metaSession.longFired = true;
        if (isWinLongUseCommand()) {
            MetaTrace.decision("Router", "module long → command");
            PassthroughTrace.noteMsg("Router", "win long timer fired → RUN_COMMAND");
            LogHelper.log(VerboseLevel.INFO, "MetaRouter: module long → RUN_COMMAND");
            ctx.dispatchWinLongCommand();
            return;
        }
        Config.OverrideMode mode = getWinLongOverride();
        if (mode == Config.OverrideMode.ZUI || mode == Config.OverrideMode.FOLLOW_SYSTEM) {
            MetaTrace.decision("Router", "module long → voice");
            PassthroughTrace.noteMsg("Router", "voice timer fired → launchVoiceAssistant");
            LogHelper.log(VerboseLevel.INFO, "MetaRouter: module long → voice (2s fired)");
            ctx.launchVoiceAssistant();
        }
    }

    public boolean handleAssistantLongPress() {
        if (MetaTrace.isTraceOnly()) {
            MetaTrace.decision("Assistant", "runnable fired (trace-only pass)");
            return false;
        }
        if (!ctx.isMetaAssistantCallbackCurrent()) {
            MetaTrace.decision("Assistant", "stale/reused callback suppressed");
            return true;
        }
        ctx.checkConfigChanged();
        if (ctx.isMetaIntercepted()) {
            MetaTrace.decision("Assistant", "passthrough/mapped → suppress long-press");
            return true;
        }
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
            final long generation = ctx.metaSession.generation;
            ctx.metaSession.longFired = true;
            LogHelper.log(VerboseLevel.INFO, "MetaRouter: 2s long → IME (repeat)");
            ctx.fnKeyManager.startWinLongPressRepeat(ctx.resolvePolicyHandler(),
                    () -> {
                        if (isMetaSessionGenerationActive(generation)) {
                            ctx.dispatchWinLongPressIme();
                        }
                    });
            return true;
        }

        if (isWinLongUseCommand()) {
            dispatchWinLongAction(ctx.metaSession.generation);
            return true;
        }

        Config.OverrideMode winLong = getWinLongOverride();
        if (winLong == Config.OverrideMode.BLOCK) {
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: 2s long → suppress (winLong=", winLong.name(), ")");
            return true;
        }
        if (winLong == Config.OverrideMode.ZUI) {
            dispatchWinLongAction(ctx.metaSession.generation);
            return true;
        }
        // FOLLOW_SYSTEM: 透传，由系统 / ZUI 原生 case 117 决定
        LogHelper.log(VerboseLevel.INFO,
                "MetaRouter: 2s long → pass-through (winLong=FOLLOW_SYSTEM)");
        return false;
    }

    private boolean isMetaSessionGenerationActive(long generation) {
        return ctx.metaSession.active
                && !ctx.metaSession.upHandled
                && ctx.metaSession.generation == generation;
    }

    private boolean isWinLongUseCommand() {
        if (ctx.cfg == null) return false;
        // 模板可单独指定「执行命令…」（见 ConfigResolver.effectiveUseCommand）
        return ctx.resolver != null
                ? ctx.resolver.effectiveUseCommand("winLongPress")
                : ctx.cfg.winLongUseCommand;
    }

    private Config.OverrideMode getWinLongOverride() {
        if (ctx.cfg == null) return Config.OverrideMode.FOLLOW_SYSTEM;
        return ctx.ra("winLongPress", ctx.cfg.overrideWinLongPress);
    }

    private boolean isWinLongTimerEnabled() {
        if (isWinLongUseCommand()) return true;
        Config.OverrideMode mode = getWinLongOverride();
        return mode == Config.OverrideMode.ZUI || mode == Config.OverrideMode.FOLLOW_SYSTEM;
    }

    private boolean isImeWinActive() {
        return isImeWinActive(ctx);
    }

    static boolean isImeWinActive(HookContext ctx) {
        boolean winBound = ctx.cfg.imeSwitchBinding == IMEBinding.WIN
                || ctx.cfg.languageSwitchBinding == IMEBinding.WIN;
        return winBound && ctx.isAcceptingText();
    }

    static boolean shouldHandleShortUp(Config.OverrideMode metaSingle, boolean imeWinActive) {
        return metaSingle == Config.OverrideMode.BLOCK
                || shouldOpenStartMenu(metaSingle)
                || imeWinActive;
    }

    private static boolean shouldOpenStartMenu(Config.OverrideMode mode) {
        return mode == Config.OverrideMode.ZUI;
    }

    private void logWouldDo(boolean shortPress, Config.OverrideMode metaSingle,
                            boolean zuiMeta, boolean imeWinActive) {
        if (metaSingle == Config.OverrideMode.BLOCK) {
            MetaTrace.decision("Router", "WOULD", "BLOCK UP (any duration)");
        } else if (ctx.metaSession.longFired) {
            MetaTrace.decision("Router", "WOULD", "consume longFired UP (no menu)");
        } else if (ctx.metaSession.fnMapped || ctx.metaSession.winComboUsed) {
            MetaTrace.decision("Router", "WOULD", "consume combo UP (no menu)");
        } else if (shortPress && imeWinActive) {
            MetaTrace.decision("Router", "WOULD", "consume + triggerShowAllApps (imeWin)");
        } else if (shortPress && shouldOpenStartMenu(metaSingle)) {
            MetaTrace.decision("Router", "WOULD", "consume + triggerShowAllApps L0");
        } else {
            MetaTrace.decision("Router", "WOULD", "pass UP (no branch)");
        }
    }
}
