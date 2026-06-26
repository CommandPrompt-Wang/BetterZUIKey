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
import moe.lovefirefly.betterzuikey.ime.AdapterManager;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

public class MainHook implements IXposedHookLoadPackage {

    private static final String CLASS_KSC =
            "com.zui.server.input.keyboard.key.policy.KeyboardShortcutController";
    private static final String CLASS_KEY_GESTURE_EVENT =
            "android.hardware.input.KeyGestureEvent";
    private static final String OWN_PACKAGE = "moe.lovefirefly.betterzuikey";

    public static volatile boolean globalEnabled = false;

    private ClassLoader mClassLoader;
    private HookContext ctx;
    private Config cfg;
    private static volatile boolean sInitDone = false;
    private static volatile boolean sSelfHookDone = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam.processName == null) return;

            // ―― Self-check hook: when our own app process loads, hook ModuleStatus
            // to prove the module is loaded.  Runs BEFORE the process-name filter
            // (our app is not system_server).
            if (!sSelfHookDone && OWN_PACKAGE.equals(lpparam.packageName)) {
                sSelfHookDone = true;
                try {
                    XposedHelpers.findAndHookMethod(
                        OWN_PACKAGE + ".ModuleStatus",
                        lpparam.classLoader, "isLoaded",
                        de.robv.android.xposed.XC_MethodReplacement.returnConstant(true));
                    LogHelper.log(VerboseLevel.INFO, "Self-check hook installed: ModuleStatus.isLoaded() → true");
                } catch (Throwable t) {
                    LogHelper.log(VerboseLevel.ERROR, "Self-check hook failed:", t.getMessage());
                }
            }

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

            // Init IPC early so we can get the real config before constructing
            // anything that captures a cfg reference (FnKeyManager, HookContext).
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
                LogHelper.log(VerboseLevel.INFO,
                    "Config replaced with ContentProvider version, templates=",
                    String.valueOf(cfg.templates != null ? cfg.templates.size() : 0));
            }

            // Initialize AdapterManager with dex optimization dir from our own app.
            // We run in system_server so we can't use our own context directly,
            // but we can fall back to /data/local/tmp for dex opt.
            try {
                java.io.File dexOptDir = new java.io.File("/data/local/tmp/bzuikey_dex");
                AdapterManager.init(dexOptDir);
                AdapterManager.syncBindings(cfg.imeAdapterBindings);
                LogHelper.log(VerboseLevel.INFO, "AdapterManager initialized with ",
                        String.valueOf(cfg.imeAdapterBindings.size()), " binding(s)");
            } catch (Throwable t) {
                LogHelper.log(VerboseLevel.ERROR, "AdapterManager init failed:", t.getMessage());
            }

            // Construct all collaborators with the real (IPC) config.
            // Order matters: fnKeyManager and resolver must see the final cfg.
            ConfigResolver resolver = new ConfigResolver(cfg);
            FnKeyManager fnKeyManager = new FnKeyManager(cfg, configIPC);
            ForegroundTracker foregroundTracker = new ForegroundTracker(resolver);

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

            LogHelper.log(VerboseLevel.INFO, "Installing L2 debug hook (PhoneWindowManager.interceptKeyBeforeDispatching)...");
            hookL2_DebugInterceptBeforeDispatching();

            cfg.injected = true;
            cfg.injectError = "";
            writeInjectedStatus(true);
            LogHelper.log(VerboseLevel.INFO, "All hooks installed successfully!");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Hook installation failed:", t.getMessage());
            setError("Injection error: " + t.toString() + "\n\n" + KeyInjector.stackTraceToString(t));
            writeInjectedStatus(false);
        }
    }

    private void setError(String msg) {
        try {
            if (cfg == null) cfg = Config.load();
            cfg.injected = false;
            cfg.injectError = msg;
        } catch (Exception ignored) { }
    }

    /** Write injection status to Settings.System so the app can read it cross-process. */
    private void writeInjectedStatus(boolean injected) {
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            android.content.Context sysCtx = (android.content.Context)
                    at.getClass().getMethod("getSystemContext").invoke(at);
            android.provider.Settings.System.putInt(
                    sysCtx.getContentResolver(), "bzuikey_injected", injected ? 1 : 0);
            LogHelper.log(VerboseLevel.INFO, "Injection status written to Settings.System: ",
                    injected ? "1" : "0");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to write injection status:", t.getMessage());
        }
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

    /** Debug hook: timestamp Meta events at AOSP interceptKeyBeforeDispatching. */
    private void hookL2_DebugInterceptBeforeDispatching() {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.policy.PhoneWindowManager", mClassLoader,
                    "interceptKeyBeforeDispatching",
                    IBinder.class, KeyEvent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            KeyEvent event = (KeyEvent) param.args[1];
                            int kc = event.getKeyCode();
                            if (kc == KeyEvent.KEYCODE_META_LEFT
                                    || kc == KeyEvent.KEYCODE_META_RIGHT) {
                                LogHelper.log(VerboseLevel.INFO, "[L2] ",
                                        (event.getAction() == KeyEvent.ACTION_DOWN ? "D" : "U"),
                                        " sc=", String.valueOf(event.getScanCode()),
                                        " r=", String.valueOf(event.getRepeatCount()));
                            }
                        }
                    });
            LogHelper.log(VerboseLevel.INFO, "L2 debug hook installed: PhoneWindowManager.interceptKeyBeforeDispatching");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "L2 hook failed:", t.getMessage());
        }
    }
}