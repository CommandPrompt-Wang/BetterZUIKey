package moe.lovefirefly.betterzuikey.Config;

import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * 运行时配置解析器。
 * 根据当前前台 app 包名，合并全局配置与匹配的应用模板。
 *
 * 用法：
 *   ConfigResolver resolver = new ConfigResolver(cfg);
 *   resolver.setForegroundPackage("com.tencent.mobileqq");
 *   SwitchState s = resolver.effectiveSwitchState(cfg.switchWinD, "winD");
 *   Action a = resolver.effectiveAction(cfg.overrideWinD, "winD");
 */
public class ConfigResolver {

    private final Config mConfig;
    private KeyTemplate mActiveTemplate;

    public ConfigResolver(Config config) {
        this.mConfig = config;
    }

    /**
     * 设置当前前台包名。内部查找首个匹配的模板。
     */
    public void setForegroundPackage(String packageName) {
        KeyTemplate prev = mActiveTemplate;
        mActiveTemplate = findTemplate(packageName);
        if (mActiveTemplate != prev) {
            if (mActiveTemplate != null) {
                LogHelper.log(VerboseLevel.DEBUG, "Template matched: ",
                    mActiveTemplate.name, " → ", packageName,
                    " (overrides=", String.valueOf(mActiveTemplate.overrides.size()), ")");
            } else if (prev != null) {
                LogHelper.log(VerboseLevel.DEBUG, "Template unmatched: ",
                    prev.name, " (pkg=", packageName, ")");
            }
        }
    }

    /**
     * 获取生效的 SwitchState：
     * 模板有覆写 → 用模板；否则回退全局。
     */
    public Config.SwitchState effectiveSwitchState(Config.SwitchState globalValue, String key) {
        if (mActiveTemplate == null) return globalValue;
        PerKeyOverride ov = mActiveTemplate.get(key);
        if (ov != null && ov.switchState != null) {
            LogHelper.log(VerboseLevel.DEBUG, "Template override switch: ",
                key, " → ", ov.switchState.name(),
                " (template=", mActiveTemplate.name, ")");
            return ov.switchState;
        }
        return globalValue;
    }

    /**
     * 获取生效的 Action：
     * 模板有覆写 → 用模板；否则回退全局。
     */
    public Config.OverrideMode effectiveAction(Config.OverrideMode globalValue, String key) {
        Config.OverrideMode mode;
        if (mActiveTemplate == null) {
            mode = globalValue;
        } else {
            PerKeyOverride ov = mActiveTemplate.get(key);
            if (ov != null && ov.overrideMode != null) {
                LogHelper.log(VerboseLevel.DEBUG, "Template override action: ",
                    key, " → ", ov.overrideMode.name(),
                    " (template=", mActiveTemplate.name, ")");
                mode = ov.overrideMode;
            } else {
                mode = globalValue;
            }
        }
        return normalizeMetaSingle(key, mode);
    }

    /** metaSingle no longer supports OFF (passthrough); legacy OFF → BLOCK. */
    private static Config.OverrideMode normalizeMetaSingle(String key, Config.OverrideMode mode) {
        if ("metaSingle".equals(key) && mode == Config.OverrideMode.OFF) {
            return Config.OverrideMode.BLOCK;
        }
        return mode;
    }

    /**
     * 当前是否匹配到某个模板。
     */
    public boolean hasActiveTemplate() {
        return mActiveTemplate != null;
    }

    /**
     * 获取当前匹配的模板（可能为 null）。
     */
    public KeyTemplate getActiveTemplate() {
        return mActiveTemplate;
    }

    private KeyTemplate findTemplate(String packageName) {
        if (packageName == null || mConfig.templates == null) return null;
        for (KeyTemplate t : mConfig.templates) {
            if (t.matches(packageName)) return t;
        }
        return null;
    }
}
