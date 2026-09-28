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
        return mode;
    }

    /**
     * 该 key 当前是否走「映射到…」（目标统一取全局 {@code Config.metaSingleMap}）。
     *
     * <p>优先级：
     * <ol>
     *   <li>模板对这条 key 没表态 → 用全局 {@code metaSingleMapEnabled}（与以前一致）；</li>
     *   <li>模板显式选了「映射到…」（{@code useMap} 非 null）→ 按它；</li>
     *   <li>模板显式选了标准五档 → 映射让位（模板只能表达五档时，它说「关闭」就真是放行）。</li>
     * </ol>
     */
    public boolean usesMap(String key) {
        if (mActiveTemplate != null) {
            PerKeyOverride ov = mActiveTemplate.get(key);
            if (ov != null) {
                if (ov.useMap != null) {
                    LogHelper.log(VerboseLevel.DEBUG, "Template override map: ",
                        key, " → ", String.valueOf(ov.useMap),
                        " (template=", mActiveTemplate.name, ")");
                    return ov.useMap;
                }
                if (ov.overrideMode != null) return false;
            }
        }
        return mConfig.metaSingleMapEnabled;
    }

    /**
     * 当前生效的「映射到…」目标：模板有自己的目标就用它，否则回退全局。
     * 模板之间、以及与全局之间互不影响。
     */
    public String effectiveMapTarget(String key) {
        if (mActiveTemplate != null) {
            PerKeyOverride ov = mActiveTemplate.get(key);
            if (ov != null && ov.mapTarget != null && !ov.mapTarget.trim().isEmpty()) {
                return ov.mapTarget;
            }
        }
        return mConfig.metaSingleMap;
    }

    /**
     * 智能键（keyApp1 / keyApp2）当前生效的三档模式。
     * 兼容旧数据：模板当年只能拿五档表达，这里把 BLOCK/OFF→忽略、其余→跟随系统/自定义。
     */
    public Config.AppKeyMode effectiveAppKeyMode(String key) {
        if (mActiveTemplate != null) {
            PerKeyOverride ov = mActiveTemplate.get(key);
            if (ov != null) {
                if (ov.appKeyMode != null) return ov.appKeyMode;
                if (ov.overrideMode != null) {
                    switch (ov.overrideMode) {
                        case BLOCK:
                        case OFF:
                            return Config.AppKeyMode.BLOCK;
                        case FOLLOW_SYSTEM:
                        case ZUI:
                        case AOSP:
                            return Config.AppKeyMode.FOLLOW_SYSTEM;
                        default:
                            return Config.AppKeyMode.CUSTOM;
                    }
                }
            }
        }
        if ("keyApp1".equals(key)) return mConfig.app1Mode;
        if ("keyApp2".equals(key)) return mConfig.app2Mode;
        return Config.AppKeyMode.FOLLOW_SYSTEM;
    }

    /**
     * 当前是否执行命令（winLongPress / keyApp1 / keyApp2）。
     *
     * <p>与 {@link #usesMap} 同规矩：模板显式表态优先；模板选了标准档位就让位；都没表态才看全局。
     */
    public boolean effectiveUseCommand(String key) {
        if (mActiveTemplate != null) {
            PerKeyOverride ov = mActiveTemplate.get(key);
            if (ov != null) {
                if (ov.useCommand != null) return ov.useCommand;
                if (ov.appKeyMode != null) return ov.appKeyMode == Config.AppKeyMode.CUSTOM;
                if (ov.overrideMode != null) return false;
            }
        }
        if ("keyApp1".equals(key)) return mConfig.app1Mode == Config.AppKeyMode.CUSTOM;
        if ("keyApp2".equals(key)) return mConfig.app2Mode == Config.AppKeyMode.CUSTOM;
        if ("winLongPress".equals(key)) return mConfig.winLongUseCommand;
        return false;
    }

    /** 当前生效的命令脚本：模板自带就用模板的，否则用全局那条。 */
    public String effectiveCommand(String key) {
        if (mActiveTemplate != null) {
            PerKeyOverride ov = mActiveTemplate.get(key);
            if (ov != null && ov.command != null) return ov.command;
        }
        if ("keyApp1".equals(key)) return mConfig.app1Command;
        if ("keyApp2".equals(key)) return mConfig.app2Command;
        if ("winLongPress".equals(key)) return mConfig.winLongCommand;
        return "";
    }

    public boolean effectiveCommandRoot(String key) {
        if (mActiveTemplate != null) {
            PerKeyOverride ov = mActiveTemplate.get(key);
            if (ov != null && ov.commandRoot != null) return ov.commandRoot;
        }
        if ("keyApp1".equals(key)) return mConfig.app1CommandRoot;
        if ("keyApp2".equals(key)) return mConfig.app2CommandRoot;
        if ("winLongPress".equals(key)) return mConfig.winLongCommandRoot;
        return false;
    }

    public boolean effectiveCommandSingleton(String key) {
        if (mActiveTemplate != null) {
            PerKeyOverride ov = mActiveTemplate.get(key);
            if (ov != null && ov.commandSingleton != null) return ov.commandSingleton;
        }
        if ("keyApp1".equals(key)) return mConfig.app1CommandSingleton;
        if ("keyApp2".equals(key)) return mConfig.app2CommandSingleton;
        if ("winLongPress".equals(key)) return mConfig.winLongCommandSingleton;
        return true;
    }

    public int effectiveCommandTimeoutMin(String key) {
        if (mActiveTemplate != null) {
            PerKeyOverride ov = mActiveTemplate.get(key);
            if (ov != null && ov.commandTimeoutMin != null) return ov.commandTimeoutMin;
        }
        if ("keyApp1".equals(key)) return mConfig.app1CommandTimeoutMin;
        if ("keyApp2".equals(key)) return mConfig.app2CommandTimeoutMin;
        if ("winLongPress".equals(key)) return mConfig.winLongCommandTimeoutMin;
        return 1;
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
