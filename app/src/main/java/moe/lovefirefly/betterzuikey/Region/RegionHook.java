package moe.lovefirefly.betterzuikey.Region;

import android.view.KeyEvent;

import io.github.libxposed.api.XposedModule;
import moe.lovefirefly.betterzuikey.Hook.HookCompat;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * 区域切换 + 键盘身份 Hook。
 *
 * 三层覆写：
 *   1. SystemProperties — 覆写 ro.config.lgsi.region / countrycode
 *   2. KeyboardConstants 缓存 — 反射覆写静态字段
 *   3. KeyEvent ScanCode — 覆写键盘固件上报的 MSC_SCAN
 */
public class RegionHook {

    private static Config sConfig;
    private static RegionProfile sCurrentProfile = RegionProfile.DEFAULT;
    private static boolean sInstalled = false;
    private static XposedModule sModule;

    public static void install(XposedModule module, ClassLoader classLoader, Config config) {
        if (sInstalled) return;
        sModule = module;
        sConfig = config;
        sCurrentProfile = config.regionOverride != null
                ? config.regionOverride
                : RegionProfile.DEFAULT;

        LogHelper.log(VerboseLevel.INFO, "RegionHook: installing, profile=",
                sCurrentProfile.name(),
                ", scanCode=", String.valueOf(config.keyboardScanCode));

        hookSystemProperties();
        tryInvalidateKeyboardConstantsCache(classLoader);
        if (config.keyboardScanCode != 0) {
            hookScanCode();
        }

        sInstalled = true;
    }

    public static void updateProfile(RegionProfile profile) {
        sCurrentProfile = profile;
        LogHelper.log(VerboseLevel.INFO, "RegionHook: profile updated to ", profile.name());
    }

    public static void updateConfig(Config config) {
        sConfig = config;
        if (config.regionOverride != null) {
            sCurrentProfile = config.regionOverride;
        } else {
            sCurrentProfile = RegionProfile.DEFAULT;
        }
        LogHelper.log(VerboseLevel.DEBUG, "RegionHook: config hot-reloaded, profile=",
                sCurrentProfile.name());
    }

    // ----------------------------------------------------------------
    //  Hook SystemProperties.get()
    // ----------------------------------------------------------------

    private static void hookSystemProperties() {
        try {
            HookCompat.hookMethod(
                    sModule,
                    "android.os.SystemProperties",
                    null,
                    "get",
                    new HookCompat.HookCallback() {
                        @Override
                        protected void afterHookedMethod(HookCompat.HookParam param) {
                            if (!moe.lovefirefly.betterzuikey.Hook.MainHook.globalEnabled) return;
                            if (sCurrentProfile == RegionProfile.DEFAULT) return;

                            String key = (String) param.args[0];
                            String original = (String) param.getResult();

                            switch (key) {
                                case "ro.config.lgsi.region":
                                    param.setResult(spoofRegion(original));
                                    break;
                                case "ro.config.lgsi.countrycode":
                                    param.setResult(spoofCountryCode(original));
                                    break;
                            }
                        }
                    },
                    String.class,
                    String.class
            );
            LogHelper.log(VerboseLevel.INFO, "RegionHook: SystemProperties.get() hooked");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "RegionHook: failed to hook SystemProperties:", t.getMessage());
        }
    }

    // ----------------------------------------------------------------
    //  属性覆写逻辑
    // ----------------------------------------------------------------

    private static String spoofRegion(String original) {
        switch (sCurrentProfile) {
            case ROW:
                return "ROW";
            case CHINA:
                return "CN";
            case KOREA:
                return original;
            case CUSTOM:
                if (sConfig != null && sConfig.regionCustomValue != null
                        && !sConfig.regionCustomValue.isEmpty()) {
                    return sConfig.regionCustomValue;
                }
                return original;
            default:
                return original;
        }
    }

    private static String spoofCountryCode(String original) {
        if (sConfig != null && sConfig.countryOverride != null
                && !sConfig.countryOverride.isEmpty()) {
            return sConfig.countryOverride;
        }
        if (sCurrentProfile == RegionProfile.KOREA) {
            return "KR";
        }
        return original;
    }

    // ----------------------------------------------------------------
    //  使 KeyboardConstants 缓存失效
    // ----------------------------------------------------------------

    private static void tryInvalidateKeyboardConstantsCache(ClassLoader classLoader) {
        try {
            Class<?> kcClass = classLoader.loadClass(
                    "com.zui.server.input.keyboard.key.policy.KeyboardConstants");

            trySetStaticBoolean(kcClass, "isRowProduct", sCurrentProfile.isRow());
            trySetStaticBoolean(kcClass, "IS_ROW_PRODUCT", sCurrentProfile.isRow());

            LogHelper.log(VerboseLevel.INFO, "RegionHook: KeyboardConstants cache invalidated");
        } catch (ClassNotFoundException e) {
            LogHelper.log(VerboseLevel.DEBUG, "RegionHook: KeyboardConstants not found (non-ZUI?)");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "RegionHook: failed to patch KeyboardConstants:",
                    t.getMessage());
        }
    }

    private static void trySetStaticBoolean(Class<?> clazz, String fieldName, boolean value) {
        try {
            HookCompat.setStaticBooleanField(clazz, fieldName, value);
            LogHelper.log(VerboseLevel.DEBUG, "RegionHook: set ", fieldName, "=", String.valueOf(value));
        } catch (NoSuchFieldError ignored) {
        }
    }

    // ----------------------------------------------------------------
    //  ScanCode 覆写
    // ----------------------------------------------------------------

    private static void hookScanCode() {
        try {
            HookCompat.hookMethod(
                    sModule,
                    KeyEvent.class,
                    "getScanCode",
                    new HookCompat.HookCallback() {
                        @Override
                        protected void afterHookedMethod(HookCompat.HookParam param) {
                            if (!moe.lovefirefly.betterzuikey.Hook.MainHook.globalEnabled) return;
                            KeyEvent event = (KeyEvent) param.thisObject;
                            int keyCode = event.getKeyCode();
                            if ((keyCode == KeyEvent.KEYCODE_META_LEFT
                                    || keyCode == KeyEvent.KEYCODE_META_RIGHT)
                                    && sConfig.keyboardScanCode != 0) {
                                param.setResult(sConfig.keyboardScanCode);
                            }
                        }
                    }
            );
            LogHelper.log(VerboseLevel.INFO, "RegionHook: KeyEvent.getScanCode() hooked, ",
                    "spoofing scanCode=", String.valueOf(sConfig.keyboardScanCode));
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "RegionHook: failed to hook getScanCode:",
                    t.getMessage());
        }
    }
}
