package moe.lovefirefly.betterzuikey.Region;

import android.content.Intent;

import io.github.libxposed.api.XposedModule;
import moe.lovefirefly.betterzuikey.Hook.HookCompat;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * 区域差异化功能 Hook — AI 代理 & 文件管理器。
 */
public class FeatureHook {

    private static Config sConfig;
    private static boolean sInstalled = false;
    private static XposedModule sModule;

    private static final String CLASS_KSC =
            "com.zui.server.input.keyboard.key.policy.KeyboardShortcutController";

    public static void install(XposedModule module, ClassLoader classLoader, Config config) {
        if (sInstalled) return;
        sModule = module;
        sConfig = config;

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

    public static void updateConfig(Config config) {
        sConfig = config;
        LogHelper.log(VerboseLevel.DEBUG, "FeatureHook: config hot-reloaded, ai=",
                config.aiAgent.name(), ", file=", config.fileManager.name());
    }

    // ----------------------------------------------------------------
    //  AI 代理 Hook
    // ----------------------------------------------------------------

    private static void hookAiAgent(Class<?> kscClass) {
        tryHookAiMethod(kscClass, "launchAiNow", "launchXiaoTianAgent",
                sConfig.aiAgent == Config.AiAgent.LENOVO_LE_YU_YIN,
                sConfig.aiAgent == Config.AiAgent.NONE);

        tryHookAiMethod(kscClass, "launchXiaoTianAgent", "launchAiNow",
                sConfig.aiAgent == Config.AiAgent.ZUI_AI_NOW,
                sConfig.aiAgent == Config.AiAgent.NONE);
    }

    private static void tryHookAiMethod(Class<?> kscClass, String methodName,
                                         String actualTargetMethod, boolean redirect, boolean block) {
        try {
            HookCompat.hookMethod(sModule, kscClass, methodName,
                    new HookCompat.HookCallback() {
                        @Override
                        protected void beforeHookedMethod(HookCompat.HookParam param) {
                            if (!moe.lovefirefly.betterzuikey.Hook.MainHook.globalEnabled) return;
                            if (block) {
                                LogHelper.log(VerboseLevel.INFO, "FeatureHook: ", methodName, "→ BLOCKED");
                                param.setResult(null);
                                return;
                            }
                            if (redirect) {
                                LogHelper.log(VerboseLevel.INFO, "FeatureHook: ", methodName,
                                        "→ redirected to ", actualTargetMethod);
                                param.setResult(null);
                                try {
                                    HookCompat.callMethod(param.thisObject, actualTargetMethod);
                                } catch (Throwable t) {
                                    LogHelper.log(VerboseLevel.ERROR, "FeatureHook: redirect ",
                                            methodName, "→", actualTargetMethod, " failed: ", t.getMessage());
                                }
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

    private static void hookFileManager(Class<?> kscClass) {
        final String targetPkg;
        switch (sConfig.fileManager) {
            case GOOGLE_FILES:
                targetPkg = "com.google.android.apps.nbu.files";
                break;
            case ZUI_FILES:
                targetPkg = "com.zui.filemanager";
                break;
            case NONE:
                targetPkg = null;
                break;
            default:
                return;
        }

        try {
            HookCompat.hookMethod(sModule, kscClass, "startActivityByPkgName",
                    new HookCompat.HookCallback() {
                        @Override
                        protected void beforeHookedMethod(HookCompat.HookParam param) {
                            if (!moe.lovefirefly.betterzuikey.Hook.MainHook.globalEnabled) return;
                            if (targetPkg == null) {
                                LogHelper.log(VerboseLevel.INFO, "FeatureHook: fileManager → BLOCKED");
                                param.setResult(null);
                                return;
                            }
                            String original = (String) param.args[0];
                            if (!targetPkg.equals(original)) {
                                LogHelper.log(VerboseLevel.INFO, "FeatureHook: fileManager ",
                                        original, "→", targetPkg);
                                param.args[0] = targetPkg;
                            }
                        }
                    },
                    String.class);
            LogHelper.log(VerboseLevel.INFO, "FeatureHook: hooked startActivityByPkgName");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "FeatureHook: startActivityByPkgName not found (",
                    t.getMessage(), ")");
        }
    }
}
