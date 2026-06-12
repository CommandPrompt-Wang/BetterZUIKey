package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * ZUI 专用物理键 (501-521) 拦截处理
 * 根据 Config 中的启用开关决定是否拦截
 */
public class ZUIKeyHook extends XC_MethodHook {

    private final Config mConfig;

    public ZUIKeyHook(Config config) {
        this.mConfig = config;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        super.beforeHookedMethod(param);

        // 全局开关检查
        if (mConfig == null || !mConfig.zuxKeyboardFuncEnabled) return;

        KeyEvent event = (KeyEvent) param.args[0];
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean firstDown = down && event.getRepeatCount() == 0;

        if (!firstDown) return; // 只处理首次按下

        Config.OverrideMode action = null;
        String label = null;

        switch (keyCode) {
            case 501: // 静音键
                if (!mConfig.switchKeyMute.isEnabled()) return;
                action = mConfig.overrideMute; label = "ZUIKey: Mute (501)"; break;
            case 502: // 触控板开关
                if (!mConfig.switchKeyTouchpad.isEnabled()) return;
                action = mConfig.overrideTouchpad; label = "ZUIKey: Touchpad (502)"; break;
            case 504: // 分屏键
                if (!mConfig.switchKeySplitScreen.isEnabled()) return;
                action = mConfig.overrideSplitScreen; label = "ZUIKey: SplitScreen (504)"; break;
            case 505: // 超级互联
                if (!mConfig.switchKeySuperConnect.isEnabled()) return;
                action = mConfig.overrideSuperConnect; label = "ZUIKey: SuperConnect (505)"; break;
            case 507: // App1 — 自定义行为
                if (!mConfig.switchKeyApp1.isEnabled()) return;
                action = mConfig.app1LongPressOverride; label = "ZUIKey: App1 (507)"; break;
            case 508: // App2 — 自定义行为
                if (!mConfig.switchKeyApp2.isEnabled()) return;
                action = mConfig.app2LongPressOverride; label = "ZUIKey: App2 (508)"; break;
            case 509: // 搜索键
                if (!mConfig.switchKeySearch.isEnabled()) return;
                action = mConfig.overrideSearch; label = "ZUIKey: Search (509)"; break;
            case 510: // 设置键
                if (!mConfig.switchKeySettings.isEnabled()) return;
                action = mConfig.overrideSettings; label = "ZUIKey: Settings (510)"; break;
            case 511: // Fn 锁定切换
                if (!mConfig.switchKeyFnLock.isEnabled()) return;
                action = mConfig.overrideFnLock; label = "ZUIKey: FnLock (511)"; break;
            case 512: // 键盘背光
                if (!mConfig.switchKeyBacklight.isEnabled()) return;
                action = mConfig.overrideBacklight; label = "ZUIKey: Backlight (512)"; break;
            case 514: // 触控板上移 — 打开通知面板
                if (!mConfig.switchKeyTpUp.isEnabled()) return;
                action = mConfig.overrideTpUp; label = "ZUIKey: TouchpadUp (514)"; break;
            case 515: // 锁屏键
                if (!mConfig.switchKeyScreenLock.isEnabled()) return;
                action = mConfig.overrideScreenLock; label = "ZUIKey: ScreenLock (515)"; break;
            case 520: // 键盘恢复 — 禁用物理键盘
                if (!mConfig.switchKeyKeyboardRestore.isEnabled()) return;
                action = mConfig.overrideKeyboardRestore; label = "ZUIKey: KeyboardRestore (520)"; break;
            case 521: // 键盘翻转 — 启用物理键盘 + 弹出屏幕键盘
                if (!mConfig.switchKeyKeyboardReverse.isEnabled()) return;
                action = mConfig.overrideKeyboardReverse; label = "ZUIKey: KeyboardReverse (521)"; break;
            default:
                return;
        }

        // 根据 OverrideMode 决定行为
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
                // 不设 result，让事件自然流向 AOSP
                break;
            default:
                break;
        }
    }
}
