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

        ctx.lastMetaScanCode = scanCode;
        if (!ctx.metaSession.active) {
            ctx.beginMetaSession(event);
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
        // 放行模式绕过了 ZUI 的 Meta 处理，zuiMeta 键盘也必须由模块自己武装，
        // 否则语音助手的 2s 长按会整个失效。
        if (imeWinActive) {
            armModuleLongPress(true, event);
        } else if (!zuiMeta || ctx.isMetaIntercepted()) {
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
        if (ctx.isSyntheticMetaTap(event)) {
            return false;
        }
        int scanCode = event.getScanCode();
        if (scanCode == 0) {
            MetaTrace.decision("Router", "skip UP sc=0");
            return false;
        }
        if (ctx.isMetaIntercepted()) {
            return consumePassthroughUp(param, event);
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

        // Win 按住期间扣下的外加键：到这里要么补成完整和弦（只按了修饰键、没有触发键），
        // 要么按原顺序单独放行 —— 用户按下去的键不能因为我们扣过就丢了
        ctx.finishWinExtrasOnMetaUp();

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
        if (ctx.metaSession.fnMapped || ctx.metaSession.winComboUsed) {
            String reason = ctx.metaSession.fnMapped
                    ? "fnMapped → suppress menu" : "winCombo → suppress menu";
            consumed = consumeUp(param, zuiMeta, reason);
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

    /**
     * 放行 / 映射模式（{@code metaSingle = 关闭 | 映射到…}）的 UP 决策。
     * <p>
     * 物理 DOWN 已在 L1 被扣押，所以这里必须消费物理 UP（否则应用会收到一个没有
     * DOWN 的孤立 UP，修饰键状态就卡住了）。是否把 Win 交给前台应用取决于这一轮
     * 到底是「单按」还是「组合键」：
     * <ul>
     *   <li>独立单击（未长按 / 未被 Fn 映射 / 期间没有别的键按下）→ 按模式决定：
     *       <b>关闭</b>注入一对合成的 Meta DOWN/UP（等价于把 Win 单击放行给应用）；
     *       <b>映射到…</b>改为注入配置好的按键 / 组合键（Win 单击被替换掉）；</li>
     *   <li>Win+字母（{@link HookContext#noteWinComboDuringMetaSession} 已置
     *       {@code winComboUsed}）/ 长按已触发 / Fn 映射过 → 应用完全看不到 Win，
     *       组合键对应用保持原子（否则远端桌面会把 Win 当独立按键 → 弹开始菜单）。</li>
     * </ul>
     */
    private boolean consumePassthroughUp(HookCompat.HookParam param, KeyEvent event) {
        boolean standalone = ctx.metaSession.active
                && ctx.metaSession.isShortPress()
                && !ctx.metaSession.longFired
                && !ctx.metaSession.fnMapped
                && !ctx.metaSession.winComboUsed;
        ctx.fnKeyManager.cancelWinLongPressTimer();
        ctx.cancelZuiAssistantTimer();
        if (standalone) {
            if (ctx.isMetaMapped()) {
                MetaKeyMap map = MetaKeyMap.parse(ctx.effectiveMapTarget());
                MetaTrace.decision("Router", "mapped UP → inject mapped key",
                        " kc=", String.valueOf(map.getKeyCode()),
                        " ctrl=", String.valueOf(map.getCtrl()),
                        " shift=", String.valueOf(map.getShift()),
                        " alt=", String.valueOf(map.getAlt()));
                ctx.armRemapInject();
                KeyInjector.injectCombo(map, ctx.metaSession.deviceId);
            } else {
                MetaTrace.decision("Router", "passthrough UP → inject Meta tap",
                        " kc=", String.valueOf(event.getKeyCode()));
                KeyInjector.injectMetaTap(event);
            }
        } else {
            MetaTrace.decision("Router", "passthrough UP → swallow Win",
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
        if (imeWinActive) {
            PassthroughTrace.note("Router", "arm IME timer 500ms", event);
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: arm IME long timer 500ms (acceptingText=true)");
            ctx.fnKeyManager.startWinLongPressTimer(looperSource, () -> onModuleLongFiredIme());
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
                    this::onModuleLongFiredWinLong);
        } else {
            PassthroughTrace.note("Router",
                    "win long pass-through override=" + getWinLongOverride().name(), event);
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: win long pass-through (override=",
                    getWinLongOverride().name(), ")");
        }
    }

    private void onModuleLongFiredIme() {
        ctx.metaSession.longFired = true;
        LogHelper.log(VerboseLevel.INFO, "MetaRouter: module long → IME (repeat)");
        ctx.fnKeyManager.startWinLongPressRepeat(ctx.resolvePolicyHandler(),
                () -> ctx.dispatchWinLongPressIme());
    }

    private void onModuleLongFiredWinLong() {
        if (!isWinLongTimerEnabled()) return;
        dispatchWinLongAction();
    }

    private void dispatchWinLongAction() {
        ctx.metaSession.longFired = true;
        if (isWinLongUseCommand()) {
            MetaTrace.decision("Router", "module long → command");
            PassthroughTrace.noteMsg("Router", "win long timer fired → RUN_COMMAND");
            LogHelper.log(VerboseLevel.INFO, "MetaRouter: module long → RUN_COMMAND");
            ctx.dispatchWinLongCommand();
            return;
        }
        Config.OverrideMode mode = getWinLongOverride();
        if (mode == Config.OverrideMode.ZUI
                || (ctx.isMetaIntercepted() && mode == Config.OverrideMode.FOLLOW_SYSTEM)) {
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
            ctx.metaSession.longFired = true;
            LogHelper.log(VerboseLevel.INFO, "MetaRouter: 2s long → IME (repeat)");
            ctx.fnKeyManager.startWinLongPressRepeat(ctx.resolvePolicyHandler(),
                    () -> ctx.dispatchWinLongPressIme());
            return true;
        }

        if (isWinLongUseCommand()) {
            dispatchWinLongAction();
            return true;
        }

        Config.OverrideMode winLong = getWinLongOverride();
        if (winLong == Config.OverrideMode.BLOCK) {
            LogHelper.log(VerboseLevel.INFO,
                    "MetaRouter: 2s long → suppress (winLong=", winLong.name(), ")");
            return true;
        }
        if (winLong == Config.OverrideMode.ZUI) {
            dispatchWinLongAction();
            return true;
        }
        // FOLLOW_SYSTEM: 透传，由系统 / ZUI 原生 case 117 决定
        LogHelper.log(VerboseLevel.INFO,
                "MetaRouter: 2s long → pass-through (winLong=FOLLOW_SYSTEM)");
        return false;
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
        if (mode == Config.OverrideMode.ZUI) return true;
        // 放行 / 映射模式绕过了 ZUI 自己针对 Meta 的长按处理，「保持默认」必须由模块
        // 复刻系统默认行为（长按 → 语音助手），否则语音助手会整个消失。
        return ctx.isMetaIntercepted() && mode == Config.OverrideMode.FOLLOW_SYSTEM;
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
