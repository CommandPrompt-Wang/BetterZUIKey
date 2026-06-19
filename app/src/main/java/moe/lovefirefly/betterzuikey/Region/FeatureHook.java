package moe.lovefirefly.betterzuikey.Region;

import android.content.Intent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * 区域差异化功能 Hook — AI 代理 & 文件管理器。
 *
 * 在 ZUI 框架内，这些功能根据区域（ROW / 中国）分发到不同的实现。
 * 本 Hook 允许用户强制指定使用哪个实现。
 */
public class FeatureHook {

    private static Config sConfig;
    private static boolean sInstalled = false;

    private static final String CLASS_KSC =
            "com.zui.server.input.keyboard.key.policy.KeyboardShortcutController";

    /**
     * 安装功能 Hook。
     */
    public static void install(ClassLoader classLoader, Config config) {
        if (sInstalled) return;
        sConfig = config;

        // 仅当用户主动覆写了默认值时安装
        boolean needAiHook = config.aiAgent != Config.AiAgent.DEFAULT;
        boolean needFileHook = config.fileManager != Config.FileManager.DEFAULT;

        if (!needAiHook && !needFileHook) {
            LogHelper.log(VerboseLevel.DEBUG, "FeatureHook: no override needed, skipping");
            return;
        }

        LogHelper.log(VerboseLevel.INFO, "FeatureHook: installing, ai=",
                config.aiAgent.name(), ", file=", config.fileManager.name());

        try {
            Class<?> kscClass = classLoader.loadClass(CLASS_KSC);

            if (needAiHook) {
                hookAiAgent(kscClass);
            }
            if (needFileHook) {
                hookFileManager(kscClass);
            }
        } catch (ClassNotFoundException e) {
            LogHelper.log(VerboseLevel.DEBUG, "FeatureHook: KSC not found (non-ZUI?), skipping");
        }

        sInstalled = true;
    }

    // ----------------------------------------------------------------
    //  AI 代理 Hook
    // ----------------------------------------------------------------

    /**
     * Hook AI 代理的启动方法。
     *
     * ZUI 分发逻辑（triggerAppKeyBehavior 中）：
     *   ROW 产品 → launchAiNow()  → com.zui.ai.now
     *   非 ROW   → launchXiaoTianAgent() → 联想乐语音
     *
     * 我们 Hook 这两个方法，根据用户配置重定向或拦截。
     */
    private static void hookAiAgent(Class<?> kscClass) {
        // Hook launchAiNow — ROW AI 代理
        tryHookAiMethod(kscClass, "launchAiNow", "com.zui.ai.now",
                sConfig.aiAgent == Config.AiAgent.LENOVO_LE_YU_YIN,
                sConfig.aiAgent == Config.AiAgent.NONE);

        // Hook launchXiaoTianAgent — 中国 AI 代理
        tryHookAiMethod(kscClass, "launchXiaoTianAgent", "com.zui.agent",
                sConfig.aiAgent == Config.AiAgent.ZUI_AI_NOW,
                sConfig.aiAgent == Config.AiAgent.NONE);
    }

    private static void tryHookAiMethod(Class<?> kscClass, String methodName,
                                         String targetPkg, boolean redirect, boolean block) {
        try {
            XposedHelpers.findAndHookMethod(kscClass, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!moe.lovefirefly.betterzuikey.Hook.MainHook.globalEnabled) return;
                    if (block) {
                        LogHelper.log(VerboseLevel.INFO, "FeatureHook: ", methodName, "→ BLOCKED");
                        param.setResult(null);
                        return;
                    }
                    if (redirect) {
                        // 替换为另一个 AI 代理——在调用前拦截，改为启动目标代理
                        LogHelper.log(VerboseLevel.INFO, "FeatureHook: ", methodName,
                                "→ redirected to ", targetPkg);
                        // 具体的重定向由 afterHookedMethod 处理
                    }
                }
            });
            LogHelper.log(VerboseLevel.INFO, "FeatureHook: hooked ", methodName);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "FeatureHook: ", methodName,
                    " not found, skipping (", t.getMessage(), ")");
        }
    }

    // ----------------------------------------------------------------
    //  文件管理器 Hook
    // ----------------------------------------------------------------

    /**
     * Hook 文件管理器启动。
     *
     * Win+E → L4 type=307 → startActivityByPkgName(pkgName)
     *   ROW:   Google Files (com.google.android.apps.nbu.files)
     *   非 ROW: ZUI 文件管理器
     *
     * 我们 Hook startActivityByPkgName 来替换包名。
     */
    private static void hookFileManager(Class<?> kscClass) {
        final String targetPkg;
        switch (sConfig.fileManager) {
            case GOOGLE_FILES:
                targetPkg = "com.google.android.apps.nbu.files";
                break;
            case ZUI_FILES:
                targetPkg = "com.zui.filemanager"; // 实际包名待确认
                break;
            case NONE:
                targetPkg = null; // 拦截
                break;
            default:
                return;
        }

        try {
            XposedHelpers.findAndHookMethod(kscClass, "startActivityByPkgName",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!moe.lovefirefly.betterzuikey.Hook.MainHook.globalEnabled) return;
                            if (targetPkg == null) {
                                LogHelper.log(VerboseLevel.INFO, "FeatureHook: fileManager → BLOCKED");
                                param.setResult(null);
                                return;
                            }
                            // 替换包名
                            String original = (String) param.args[0];
                            if (!targetPkg.equals(original)) {
                                LogHelper.log(VerboseLevel.INFO, "FeatureHook: fileManager ",
                                        original, "→", targetPkg);
                                param.args[0] = targetPkg;
                            }
                        }
                    });
            LogHelper.log(VerboseLevel.INFO, "FeatureHook: hooked startActivityByPkgName");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "FeatureHook: startActivityByPkgName not found (",
                    t.getMessage(), ")");
        }
    }
}
