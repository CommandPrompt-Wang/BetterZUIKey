/* Refactored: Fn → FnKeyManager, KeyInjector, ConfigIPC, ForegroundTracker, HookContext, L0-L4 interceptors */

package moe.lovefirefly.betterzuikey.Hook;

import android.os.IBinder;
import android.view.KeyEvent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.Region.FeatureHook;
import moe.lovefirefly.betterzuikey.Region.RegionHook;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import moe.lovefirefly.betterzuikey.Utils.ZuiDetector;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

public class MainHook implements IXposedHookLoadPackage {

    private static final String CLASS_KSC =
            "com.zui.server.input.keyboard.key.policy.KeyboardShortcutController";
    private static final String CLASS_KEY_GESTURE_EVENT =
            "android.hardware.input.KeyGestureEvent";

    public static volatile boolean globalEnabled = false;

    private ClassLoader mClassLoader;
    private HookContext ctx;
    private Config cfg;
    private static volatile boolean sInitDone = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam.processName == null) return;

            LogHelper.log(VerboseLevel.DEBUG, "handleLoadPackage called, process=", lpparam.processName);

            if (!lpparam.processName.equals("system_server")
                    && !lpparam.processName.equals("android")
                    && !lpparam.processName.equals("system")) {
                LogHelper.log(VerboseLevel.DEBUG, "Skipping non-system process: ", lpparam.processName);
                return;
            }

            if (!ZuiDetector.INSTANCE.isZuxOS()) {
                LogHelper.log(VerboseLevel.WARNING,
                    "Non-ZUXOS device detected. Module disabled. detail=", ZuiDetector.INSTANCE.getResult().getDetail());
                setError("Non-ZUXOS device, module disabled");
                return;
            }

            if (sInitDone) return;
            sInitDone = true;

            cfg = Config.load();
            mClassLoader = lpparam.classLoader;
            globalEnabled = cfg.zuxKeyboardFuncEnabled;

            LogHelper.log(VerboseLevel.INFO, "Module loaded! Ciallo～(∠・ω< )⌒★");
            LogHelper.log(VerboseLevel.INFO, "Config loaded from:", Config.CONFIG_PATH,
                    " zux=", String.valueOf(cfg.zuxKeyboardFuncEnabled));

            if (!cfg.isSystemDetected()) {
                LogHelper.log(VerboseLevel.INFO, "First run: detecting system capabilities...");
                cfg.detectFromSystem(mClassLoader);
                cfg.save();
            } else {
                LogHelper.log(VerboseLevel.INFO, "Refreshing system switch states...");
                cfg.readSystemSwitchesPublic();
                cfg.save();
            }

            ConfigResolver resolver = new ConfigResolver(cfg);
            FnKeyManager fnKeyManager = new FnKeyManager(cfg);
            ForegroundTracker foregroundTracker = new ForegroundTracker(resolver);
            ConfigIPCManager configIPC = new ConfigIPCManager();

            // Pull the real config via ContentProvider Binder IPC.
            // Config.load() above reads from /data/data/<pkg>/config.json which
            // system_server (uid=1000) cannot traverse.  The ContentProvider
            // bypasses file-permission issues and returns the config as JSON.
            Config ipcConfig = configIPC.init();
            if (ipcConfig != null) {
                // Preserve injected status (set by detectFromSystem / readSystemSwitches
                // on the file-loaded config above).
                ipcConfig.injected = cfg.injected;
                ipcConfig.injectError = cfg.injectError;
                // Re-read system switch states to reflect current hardware state
                ipcConfig.readSystemSwitchesPublic();
                cfg = ipcConfig;
                LogHelper.currentLevel = cfg.verboseLevel;
                // Re-create resolver with the real config so templates are available
                resolver = new ConfigResolver(cfg);
                foregroundTracker.setResolver(resolver);
                LogHelper.log(VerboseLevel.INFO,
                    "Config replaced with ContentProvider version, templates=",
                    String.valueOf(cfg.templates != null ? cfg.templates.size() : 0));
            }

            ctx = new HookContext(cfg, resolver, fnKeyManager, foregroundTracker, configIPC);

            LogHelper.log(VerboseLevel.INFO, "Installing RegionHook...");
            RegionHook.install(mClassLoader, cfg);
            LogHelper.log(VerboseLevel.INFO, "Installing FeatureHook...");
            FeatureHook.install(mClassLoader, cfg);

            LogHelper.log(VerboseLevel.INFO, "Installing foreground tracker...");
            foregroundTracker.install(mClassLoader);

            LogHelper.log(VerboseLevel.INFO, "Installing constructor hook...");
            hookConstructor();

            LogHelper.log(VerboseLevel.INFO, "Installing L0 hook...");
            hookL0_BeforeQueueing();
            LogHelper.log(VerboseLevel.INFO, "Installing L1 hook...");
            hookL1_BeforeDispatching();
            LogHelper.log(VerboseLevel.INFO, "Installing L4 hook...");
            hookL4_HandleKeyGestureEvent();
            LogHelper.log(VerboseLevel.INFO, "Installing L3 hook (PhoneWindowManager)...");
            hookL3_PhoneWindowManager();

            cfg.injected = true;
            cfg.injectError = "";
            LogHelper.log(VerboseLevel.INFO, "All hooks installed successfully!");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Hook installation failed:", t.getMessage());
            setError("Injection error: " + t.toString() + "\n\n" + KeyInjector.stackTraceToString(t));
        }
    }

    private void setError(String msg) {
        try {
            if (cfg == null) cfg = Config.load();
            cfg.injected = false;
            cfg.injectError = msg;
        } catch (Exception ignored) { }
    }

    private void hookConstructor() {
        try {
            XposedHelpers.findAndHookConstructor(
                    CLASS_KSC, mClassLoader,
                    android.content.Context.class, android.os.Handler.class,
                    "com.zui.server.input.keyboard.key.policy.KeyboardZuiKeyInputPolicy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            ctx.kscInstance = param.thisObject;
                            ctx.policyInstance = param.args[2];
                            LogHelper.log(VerboseLevel.INFO,
                                    "KeyboardShortcutController and policy instance captured!");
                        }
                    });
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook constructor:", t.getMessage());
        }
    }

    private void hookL0_BeforeQueueing() {
        try {
            XposedHelpers.findAndHookMethod(
                    CLASS_KSC, mClassLoader,
                    "interceptSystemKeysAndShortcutsBeforeQueueing",
                    KeyEvent.class, int.class, boolean.class,
                    new L0Interceptor(ctx));
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook L0:", t.getMessage());
        }
    }

    private void hookL1_BeforeDispatching() {
        try {
            XposedHelpers.findAndHookMethod(
                    CLASS_KSC, mClassLoader,
                    "interceptSystemKeysAndShortcutsBeforeDispatching",
                    KeyEvent.class, int.class,
                    new L1Interceptor(ctx));
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook L1:", t.getMessage());
        }
    }

    private void hookL4_HandleKeyGestureEvent() {
        try {
            Class<?> kgClass = XposedHelpers.findClass(CLASS_KEY_GESTURE_EVENT, mClassLoader);
            XposedHelpers.findAndHookMethod(
                    CLASS_KSC, mClassLoader, "handleKeyGestureEvent",
                    kgClass, IBinder.class,
                    new L4Interceptor(ctx));
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook L4:", t.getMessage());
        }
    }

    private void hookL3_PhoneWindowManager() {
        String[] classCandidates = {
            "com.zui.server.policy.ZuiPhoneWindowManager",
            "com.android.server.policy.PhoneWindowManager",
        };

        for (String className : classCandidates) {
            try {
                Class<?> kgClass = XposedHelpers.findClass(CLASS_KEY_GESTURE_EVENT, mClassLoader);
                if (kgClass == null) {
                    kgClass = Class.forName("android.hardware.input.KeyGestureEvent");
                }

                XposedHelpers.findAndHookMethod(
                        className, mClassLoader, "handleKeyGestureEvent",
                        kgClass, IBinder.class,
                        new L3Interceptor(ctx));
                LogHelper.log(VerboseLevel.INFO, "L3 hook installed: ", className);
                return;
            } catch (Throwable t) {
                LogHelper.log(VerboseLevel.DEBUG, "L3: ", className, " not available (",
                        t.getMessage(), ")");
            }
        }
        LogHelper.log(VerboseLevel.WARNING,
                "L3 hook: PhoneWindowManager not found, AOSP shortcuts cannot be blocked");
    }
}