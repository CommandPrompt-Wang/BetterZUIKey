package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;

import moe.lovefirefly.betterzuikey.Hook.HookCompat;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * ZUI App 自定义键 (507/508) 映射表（文档/备用）。
 * 实际拦截在 L0；本类未被 {@code MainHook} 引用。
 */
public class ZUIKeyHook {

    private final Config mConfig;

    public ZUIKeyHook(Config config) {
        this.mConfig = config;
    }

    @SuppressWarnings("deprecation")
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
            case 507:
                if (!mConfig.switchKeyApp1.isEnabled()) return;
                action = mConfig.app1LongPressOverride; label = "ZUIKey: App1 (507)"; break;
            case 508:
                if (!mConfig.switchKeyApp2.isEnabled()) return;
                action = mConfig.app2LongPressOverride; label = "ZUIKey: App2 (508)"; break;
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
