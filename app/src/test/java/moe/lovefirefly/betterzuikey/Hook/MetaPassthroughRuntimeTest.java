package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;

import org.junit.Test;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.KeySide;
import moe.lovefirefly.betterzuikey.MetaKeyMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MetaPassthroughRuntimeTest {
    private HookContext context(Config config) {
        return new HookContext(config, new ConfigResolver(config),
                new FnKeyManager(config, null), null, null);
    }

    private static KeyEvent keyDown(int keyCode, int metaState, int deviceId) {
        return new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0,
                metaState, deviceId, 30, 0);
    }

    public static final class StubKeyGestureEvent {
        private final int[] keycodes;

        StubKeyGestureEvent(int... keycodes) {
            this.keycodes = keycodes;
        }

        public int[] getKeycodes() {
            return keycodes;
        }
    }

    @Test
    public void passthroughPriorityRespectsConfiguredModeAndModifiers() {
        Config config = new Config();
        config.zuxKeyboardFuncEnabled = true;
        config.overrideMetaSingle = Config.OverrideMode.OFF;
        config.switchWinE = Config.SwitchState.ON;
        config.overrideWinE = Config.OverrideMode.ZUI;
        HookContext ctx = context(config);

        assertTrue(ctx.isMetaPassthroughPriorityCombo(
                KeyEvent.KEYCODE_E, KeyEvent.ACTION_DOWN, 0,
                KeyEvent.META_META_ON, true, false));
        assertFalse(ctx.isMetaPassthroughPriorityCombo(
                KeyEvent.KEYCODE_C, KeyEvent.ACTION_DOWN, 0,
                KeyEvent.META_META_ON, true, false));
        assertFalse(ctx.isMetaPassthroughPriorityCombo(
                KeyEvent.KEYCODE_E, KeyEvent.ACTION_DOWN, 0,
                KeyEvent.META_META_ON | KeyEvent.META_SHIFT_ON, true, false));
        assertTrue(ctx.isMetaPassthroughPriorityCombo(
                KeyEvent.KEYCODE_F1, KeyEvent.ACTION_DOWN, 0,
                KeyEvent.META_META_ON, true, true));

        config.overrideWinE = Config.OverrideMode.OFF;
        assertFalse(ctx.isMetaPassthroughPriorityCombo(
                KeyEvent.KEYCODE_E, KeyEvent.ACTION_DOWN, 0,
                KeyEvent.META_META_ON, true, false));
    }

    @Test
    public void replayIdMatchesOnlyVirtualDeviceEvents() {
        Config config = new Config();
        HookContext ctx = context(config);
        ctx.registerPassthroughReplayId(4242);

        assertTrue(ctx.isPassthroughReplayId(-1, 4242));
        assertFalse(ctx.isPassthroughReplayId(3, 4242));
        assertFalse(ctx.isPassthroughReplayId(-1, 9999));
        assertFalse(ctx.isPassthroughReplayId(-1, 0));
    }

    @Test
    public void metaKeyGestureDetectionIdentifiesMeta117And118Only() {
        assertTrue(HookContext.isMetaKeyGestureEvent(
                new StubKeyGestureEvent(KeyEvent.KEYCODE_META_LEFT)));
        assertTrue(HookContext.isMetaKeyGestureEvent(
                new StubKeyGestureEvent(KeyEvent.KEYCODE_META_RIGHT)));
        assertFalse(HookContext.isMetaKeyGestureEvent(
                new StubKeyGestureEvent(KeyEvent.KEYCODE_ALL_APPS)));
    }

    @Test
    public void fnLockShortcutDefaultsToMetaGraveAndBeatsPassthrough() {
        Config config = new Config();
        config.zuxKeyboardFuncEnabled = true;
        config.overrideMetaSingle = Config.OverrideMode.OFF;
        config.fnMasterEnabled = true;
        HookContext ctx = context(config);

        assertTrue(ctx.fnKeyManager.isFnLockShortcutMatch(
                KeyEvent.KEYCODE_GRAVE, KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON, 0));
        assertTrue(ctx.isMetaPassthroughPriorityCombo(
                KeyEvent.KEYCODE_GRAVE, KeyEvent.ACTION_DOWN, 0,
                KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON, true,
                ctx.fnKeyManager.isFnLockShortcutMatch(
                        KeyEvent.KEYCODE_GRAVE,
                        KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON, 0)));

        assertFalse(ctx.fnKeyManager.isFnLockShortcutMatch(
                KeyEvent.KEYCODE_GRAVE,
                KeyEvent.META_META_ON | KeyEvent.META_SHIFT_ON, 0));

        config.fnMasterEnabled = false;
        assertFalse(ctx.fnKeyManager.isFnLockShortcutMatch(
                KeyEvent.KEYCODE_GRAVE, KeyEvent.META_META_ON, 0));
    }

    @Test
    public void fnLockShortcutCanBeCustomizedAndDisabledSafely() {
        Config config = new Config();
        config.fnKeyEnabled = true;
        config.fnLockShortcut = null;
        Config.normalizeFnLock(config);
        assertEquals(Config.DEFAULT_FN_LOCK_SHORTCUT, config.fnLockShortcut);
        assertTrue(config.fnKeyEnabled);

        MetaKeyMap custom = MetaKeyMap.of(
                KeyEvent.KEYCODE_SPACE, KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON, 0, true);
        assertEquals("Win(L)+Space", custom.displayName());
        assertTrue(custom.matches(KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON, 0));
        assertFalse(custom.matches(KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_META_ON | KeyEvent.META_META_RIGHT_ON, 0));

        config.fnLockShortcut = "";
        Config.normalizeFnLock(config);
        assertEquals("", config.fnLockShortcut);
        assertFalse(config.fnKeyEnabled);

        FnKeyManager mgr = new FnKeyManager(config, null);
        config.fnKeyEnabled = true;
        assertFalse(mgr.isEffectiveFnLockEnabled());
        assertFalse(mgr.isFnLockShortcutMatch(
                KeyEvent.KEYCODE_GRAVE, KeyEvent.META_META_ON, 0));
    }

    @Test
    public void distinguishSideToggleNormalizesAndMatchesBothSides() {
        MetaKeyMap sided = MetaKeyMap.of(
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_META_ON | KeyEvent.META_META_RIGHT_ON | KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON,
                0, true, true);
        assertTrue(sided.hasSpecificSide());
        assertEquals("Win(R)+Ctrl(R)+Space", sided.displayName());

        MetaKeyMap sideAgnostic = sided.withoutSideDistinction();
        assertFalse(sideAgnostic.hasSpecificSide());
        assertEquals("Win+Ctrl+Space", sideAgnostic.displayName());
        assertTrue(sideAgnostic.matches(KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON
                        | KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON, 0));
        assertTrue(sideAgnostic.matches(KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_META_ON | KeyEvent.META_META_RIGHT_ON
                        | KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON, 0));

        MetaKeyMap directAgnostic = MetaKeyMap.of(
                KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON,
                0, false, false);
        assertEquals("Ctrl+Enter", directAgnostic.displayName());
        assertFalse(directAgnostic.hasSpecificSide());
    }

    @Test
    public void injectedFnKeyDoesNotTriggerPassthroughMetaReplay() {
        assertFalse(HookContext.shouldProcessPassthroughCompanionDevice(-1));
        assertTrue(HookContext.shouldProcessPassthroughCompanionDevice(1));

        // Simulate Win -> Alt -> VolUp (priority Fn) -> Alt UP -> Win UP:
        // Alt is flushed clean (without Meta), and Win is never emitted.
        MetaPassthroughState<String> state = new MetaPassthroughState<>();
        state.metaDown(new MetaPassthroughState.Event<>("win-down", 1, KeyEvent.KEYCODE_META_LEFT, true, true));
        assertTrue(state.companion(
                new MetaPassthroughState.Event<>("alt-down", 1, KeyEvent.KEYCODE_ALT_LEFT, true, true), false).consumed);

        MetaPassthroughState.Result<String> volUp = state.companion(
                new MetaPassthroughState.Event<>("vol-up", 1, KeyEvent.KEYCODE_VOLUME_UP, true, false), true);
        assertFalse(volUp.consumed);
        assertEquals(1, volUp.output.size());
        assertEquals("alt-down", volUp.output.get(0).event.value);
        assertTrue(volUp.output.get(0).stripMeta);

        MetaPassthroughState.Result<String> altUp = state.companion(
                new MetaPassthroughState.Event<>("alt-up", 1, KeyEvent.KEYCODE_ALT_LEFT, false, true), false);
        assertTrue(altUp.consumed);
        assertEquals(1, altUp.output.size());
        assertTrue(altUp.output.get(0).stripMeta);

        assertTrue(state.metaUp(
                new MetaPassthroughState.Event<>("win-up", 1, KeyEvent.KEYCODE_META_LEFT, false, true)).output.isEmpty());
    }

    @Test
    public void fnToastFormatsActualModifierCombo() {
        assertEquals("F4", FnKeyManager.formatFnToast(KeyEvent.KEYCODE_F4, 0));
        assertEquals("Alt+F4", FnKeyManager.formatFnToast(
                KeyEvent.KEYCODE_F4, KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON));
        assertEquals("Ctrl+Shift+F4", FnKeyManager.formatFnToast(
                KeyEvent.KEYCODE_F4,
                KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON
                        | KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON));
    }

    @Test
    public void languageSwitchToastFormatsSubtypeOrProfileName() {
        assertEquals("简体中文",
                HookContext.formatLanguageSwitchToast(true, true, "简体中文", "Gboard", true));
        assertEquals("搜狗输入法 · 已切换语言",
                HookContext.formatLanguageSwitchToast(true, false, null, "搜狗输入法", true));
        assertEquals("Gboard · Language switched",
                HookContext.formatLanguageSwitchToast(true, true, null, "Gboard", false));
        assertEquals("未匹配到输入法配置",
                HookContext.formatLanguageSwitchToast(false, false, null, null, true));
        assertEquals("No IME profile matched",
                HookContext.formatLanguageSwitchToast(false, false, null, null, false));
    }
}
