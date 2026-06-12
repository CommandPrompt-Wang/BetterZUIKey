package moe.lovefirefly.betterzuikey.Region;

import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

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
 *
 * 影响范围：
 *   - ROW: Ctrl+Shift/Alt+Shift 切输入法，Meta 短按切语言/长按助手
 *   - 中国: Meta 按住连续切语言，联想乐语音 AI
 *   - 韩国: Alt_RIGHT(58) 切韩语
 *   - ScanCode: 控制 Meta 键三级分发走向
 */
public class RegionHook {

    private static Config sConfig;
    private static RegionProfile sCurrentProfile = RegionProfile.DEFAULT;
    private static boolean sInstalled = false;

    /**
     * 安装区域 Hook。
     * 应在 Config 加载之后调用。
     */
    public static void install(ClassLoader classLoader, Config config) {
        if (sInstalled) return;
        sConfig = config;
        sCurrentProfile = config.regionOverride != null
                ? config.regionOverride
                : RegionProfile.DEFAULT;

        LogHelper.log(VerboseLevel.INFO, "RegionHook: installing, profile=",
                sCurrentProfile.name(),
                ", scanCode=", String.valueOf(config.keyboardScanCode));

        // 总是安装 SystemProperties hook（profile 可运行时切换）
        hookSystemProperties();
        // 尝试覆写 KeyboardConstants 缓存
        tryInvalidateKeyboardConstantsCache(classLoader);
        // ScanCode 覆写（仅当 keyboardScanCode != 0）
        if (config.keyboardScanCode != 0) {
            hookScanCode();
        }

        sInstalled = true;
    }

    /**
     * 更新区域 profile（运行时切换）。
     */
    public static void updateProfile(RegionProfile profile) {
        sCurrentProfile = profile;
        LogHelper.log(VerboseLevel.INFO, "RegionHook: profile updated to ", profile.name());
    }

    // ----------------------------------------------------------------
    //  Hook SystemProperties.get()
    // ----------------------------------------------------------------

    private static void hookSystemProperties() {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.os.SystemProperties",
                    null,
                    "get",
                    String.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
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
                    }
            );
            LogHelper.log(VerboseLevel.INFO, "RegionHook: SystemProperties.get() hooked");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "RegionHook: failed to hook SystemProperties:", t.getMessage());
        }
    }

    // ----------------------------------------------------------------
    //  属性覆写逻辑
    // ----------------------------------------------------------------

    /**
     * 覆写 {@code ro.config.lgsi.region}。
     *
     * ZUI 的 isRowProduct 逻辑：
     *   !TextUtils.isEmpty(value) && !value.equals("CN") → ROW
     *
     * ROW 产品返回非空且不等于 "CN" 的值；
     * 中国产品返回空或 "CN"。
     */
    private static String spoofRegion(String original) {
        switch (sCurrentProfile) {
            case ROW:
                return "ROW";
            case CHINA:
                return "CN";
            case KOREA:
                // 韩国设备可能是 ROW 也可能是独立区域，
                // 保守起见先不改变 region，只通过 countrycode 触发 KR 逻辑
                return original;
            case CUSTOM:
                // 用户自定义值
                if (sConfig != null && sConfig.regionCustomValue != null
                        && !sConfig.regionCustomValue.isEmpty()) {
                    return sConfig.regionCustomValue;
                }
                return original;
            default:
                return original;
        }
    }

    /**
     * 覆写 {@code ro.config.lgsi.countrycode}。
     *
     * ZUI 的韩国检测逻辑：
     *   value.toUpperCase().equals("KR") → 启用 Alt_RIGHT 切韩语
     */
    private static String spoofCountryCode(String original) {
        // 优先使用 countryOverride（独立于 region 设置）
        if (sConfig != null && sConfig.countryOverride != null
                && !sConfig.countryOverride.isEmpty()) {
            return sConfig.countryOverride;
        }
        // 兼容旧行为：regionOverride=KOREA 时也返回 "KR"
        if (sCurrentProfile == RegionProfile.KOREA) {
            return "KR";
        }
        return original;
    }

    // ----------------------------------------------------------------
    //  使 KeyboardConstants 缓存失效
    // ----------------------------------------------------------------

    /**
     * 尝试反射设置 KeyboardConstants 中的静态缓存字段。
     * 由于 ZUI 可能在类加载时就已经缓存了 isRowProduct 等值，
     * 仅 Hook SystemProperties 可能不够 —— 需要直接覆写静态字段。
     */
    private static void tryInvalidateKeyboardConstantsCache(ClassLoader classLoader) {
        try {
            Class<?> kcClass = classLoader.loadClass(
                    "com.zui.server.input.keyboard.key.policy.KeyboardConstants");

            // 尝试覆写 isRowProduct 静态字段（可能是 boolean 或 Boolean）
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
            XposedHelpers.setStaticBooleanField(clazz, fieldName, value);
            LogHelper.log(VerboseLevel.DEBUG, "RegionHook: set ", fieldName, "=", String.valueOf(value));
        } catch (NoSuchFieldError ignored) {
            // 字段不存在，跳过
        }
    }

    // ----------------------------------------------------------------
    //  ScanCode 覆写
    // ----------------------------------------------------------------

    /**
     * Hook {@code KeyEvent.getScanCode()} — 仅当 keyCode 为 Meta(117) 且
     * 配置了非零 keyboardScanCode 时生效。
     *
     * ZUI 通过 {@code event.getScanCode() == 787345} 判断是否为特殊键盘固件。
     * 国区键盘固件不上报 MSC_SCAN，getScanCode() 恒为 0。
     * 覆写此值可以强制进入 ROW/非ROW 的 Meta 三级分发分支。
     */
    private static void hookScanCode() {
        try {
            XposedHelpers.findAndHookMethod(
                    KeyEvent.class,
                    "getScanCode",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            KeyEvent event = (KeyEvent) param.thisObject;
                            // 仅覆写 Meta 键 (117/118) 的 scanCode
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
