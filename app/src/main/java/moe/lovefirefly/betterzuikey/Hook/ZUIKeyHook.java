package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;

import moe.lovefirefly.betterzuikey.Hook.HookCompat;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * ZUI 专用物理键 (501-521) 集中映射表（文档/备用）。
 * 实际拦截分散在 L0（501–515, 520–521）、L1（510）、L4 type=313（505）。
 */
public class ZUIKeyHook {

    private final Config mConfig;

    public ZUIKeyHook(Config config) {
        this.mConfig = config;
    }

    public void intercept(HookCompat.HookParam param) {
        if (mConfig == null || !mConfig.zuxKeyboardFuncEnabled) return;

        KeyEvent event = (KeyEvent) param.args[0];
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean firstDown = down && event.getRepeatCount() == 0;

        if (!firstDown) return;

        Config.OverrideMode action = null;
        String label = null;

        switch (keyCode) {
            case 501:
                if (!mConfig.switchKeyMute.isEnabled()) return;
                action = mConfig.overrideMute; label = "ZUIKey: Mute (501)"; break;
            case 502:
                if (!mConfig.switchKeyTouchpad.isEnabled()) return;
                action = mConfig.overrideTouchpad; label = "ZUIKey: Touchpad (502)"; break;
            case 504:
                if (!mConfig.switchKeySplitScreen.isEnabled()) return;
                action = mConfig.overrideSplitScreen; label = "ZUIKey: SplitScreen (504)"; break;
            case 505:
                if (!mConfig.switchKeySuperConnect.isEnabled()) return;
                action = mConfig.overrideSuperConnect; label = "ZUIKey: SuperConnect (505)"; break;
            case 507:
                if (!mConfig.switchKeyApp1.isEnabled()) return;
                action = mConfig.app1LongPressOverride; label = "ZUIKey: App1 (507)"; break;
            case 508:
                if (!mConfig.switchKeyApp2.isEnabled()) return;
                action = mConfig.app2LongPressOverride; label = "ZUIKey: App2 (508)"; break;
            case 509:
                if (!mConfig.switchKeySearch.isEnabled()) return;
                action = mConfig.overrideSearch; label = "ZUIKey: Search (509)"; break;
            case 510:
                if (!mConfig.switchKeySettings.isEnabled()) return;
                action = mConfig.overrideSettings; label = "ZUIKey: Settings (510)"; break;
            case 511:
                if (!mConfig.switchKeyFnLock.isEnabled()) return;
                action = mConfig.overrideFnLock; label = "ZUIKey: FnLock (511)"; break;
            case 512:
                if (!mConfig.switchKeyBacklight.isEnabled()) return;
                action = mConfig.overrideBacklight; label = "ZUIKey: Backlight (512)"; break;
            case 514:
                if (!mConfig.switchKeyTpUp.isEnabled()) return;
                action = mConfig.overrideTpUp; label = "ZUIKey: TouchpadUp (514)"; break;
            case 515:
                if (!mConfig.switchKeyScreenLock.isEnabled()) return;
                action = mConfig.overrideScreenLock; label = "ZUIKey: ScreenLock (515)"; break;
            case 520:
                if (!mConfig.switchKeyKeyboardRestore.isEnabled()) return;
                action = mConfig.overrideKeyboardRestore; label = "ZUIKey: KeyboardRestore (520)"; break;
            case 521:
                if (!mConfig.switchKeyKeyboardReverse.isEnabled()) return;
                action = mConfig.overrideKeyboardReverse; label = "ZUIKey: KeyboardReverse (521)"; break;
            default:
                return;
        }

        switch (action) {
            case BLOCK:
            case ZUI:
                LogHelper.log(VerboseLevel.INFO, label, "→ ZUI/BLOCK (intercept)");
                param.setResult(true);
                break;
            case OFF:
                LogHelper.log(VerboseLevel.DEBUG, label, "→ OFF (pass through)");
                param.setResult(false);
                break;
            case AOSP:
                LogHelper.log(VerboseLevel.DEBUG, label, "→ AOSP (delegate to system)");
                break;
            default:
                break;
        }
    }
}
