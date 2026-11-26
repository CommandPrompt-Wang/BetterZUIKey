package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;

import org.junit.Test;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.Config.KeyTemplate;
import moe.lovefirefly.betterzuikey.Config.PerKeyOverride;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MetaMappedPriorityTest {
    private HookContext context(Config config, ConfigResolver resolver) {
        return new HookContext(config, resolver, new FnKeyManager(config, null), null, null);
    }

    @Test
    public void enabledShortcutBeatsStandaloneMapping() {
        Config config = new Config();
        config.switchWinE = Config.SwitchState.ON;
        config.overrideWinE = Config.OverrideMode.FOLLOW_SYSTEM;
        HookContext context = context(config, new ConfigResolver(config));

        assertTrue(context.hasConfiguredMetaPriority(KeyEvent.KEYCODE_E, KeyEvent.META_META_ON));
        assertFalse(context.hasConfiguredMetaPriority(KeyEvent.KEYCODE_R, KeyEvent.META_META_ON));
        assertFalse(context.hasConfiguredMetaPriority(KeyEvent.KEYCODE_E,
                KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON));
        config.overrideWinE = Config.OverrideMode.OFF;
        assertFalse(context.hasConfiguredMetaPriority(KeyEvent.KEYCODE_E, KeyEvent.META_META_ON));
    }

    @Test
    public void templateControlsPriorityAndWinTabDoesNotRequireSwitch() {
        Config config = new Config();
        config.switchWinE = Config.SwitchState.ON;
        config.overrideWinE = Config.OverrideMode.FOLLOW_SYSTEM;
        config.switchWinTab = Config.SwitchState.OFF;
        config.overrideWinTab = Config.OverrideMode.ZUI;
        KeyTemplate template = new KeyTemplate("test");
        template.packages.add("example.app");
        PerKeyOverride override = new PerKeyOverride();
        override.overrideMode = Config.OverrideMode.OFF;
        template.put("winE", override);
        config.templates.add(template);
        ConfigResolver resolver = new ConfigResolver(config);
        resolver.setForegroundPackage("example.app");
        HookContext context = context(config, resolver);

        assertFalse(context.hasConfiguredMetaPriority(KeyEvent.KEYCODE_E, KeyEvent.META_META_ON));
        assertTrue(context.hasConfiguredMetaPriority(KeyEvent.KEYCODE_TAB, KeyEvent.META_META_ON));
    }

    @Test
    public void downArrowUsesItsOwnTemplateOverride() {
        Config config = new Config();
        config.switchWinUp = Config.SwitchState.ON;
        config.overrideWinUp = Config.OverrideMode.ZUI;
        KeyTemplate template = new KeyTemplate("test");
        template.packages.add("example.app");
        PerKeyOverride override = new PerKeyOverride();
        override.overrideMode = Config.OverrideMode.OFF;
        template.put("winDown", override);
        config.templates.add(template);
        ConfigResolver resolver = new ConfigResolver(config);
        resolver.setForegroundPackage("example.app");
        HookContext context = context(config, resolver);

        assertTrue(context.hasConfiguredMetaPriority(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_META_ON));
        assertFalse(context.hasConfiguredMetaPriority(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_META_ON));
    }
}
