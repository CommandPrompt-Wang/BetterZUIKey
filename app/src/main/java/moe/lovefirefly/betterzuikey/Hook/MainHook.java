/* Migrated: Legacy Xposed API → libxposed API 101 (LSPosed 2.0.0+) */

package moe.lovefirefly.betterzuikey.Hook;

import android.os.IBinder;
import android.view.KeyEvent;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.Region.FeatureHook;
import moe.lovefirefly.betterzuikey.Region.RegionHook;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import moe.lovefirefly.betterzuikey.Utils.ZuiDetector;
import moe.lovefirefly.betterzuikey.ime.AdapterManager;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

public class MainHook extends XposedModule {

    private static final String CLASS_KSC =
            "com.zui.server.input.keyboard.key.policy.KeyboardShortcutController";
    private static final String CLASS_KEY_GESTURE_EVENT =
            "android.hardware.input.KeyGestureEvent";
    public static volatile boolean globalEnabled = false;

    private ClassLoader mClassLoader;
    private HookContext ctx;
    private Config cfg;
    private ConfigIPCManager mConfigIPC;
    private static volatile boolean sInitDone = false;
    private static volatile boolean sSelfHookDone = false;
    private static volatile boolean sBootMarked = false;

    /** Required public no-arg constructor for libxposed. */
    public MainHook() {
        super();
    }

    // ----------------------------------------------------------------
    //  Lifecycle: system_server hooks
    // ----------------------------------------------------------------

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            ClassLoader classLoader = param.getClassLoader();
            if (classLoader == null) return;

            if (!ZuiDetector.INSTANCE.isZuxOS()) {
                LogHelper.log(VerboseLevel.WARNING,
                    "Non-ZUXOS device detected. Module disabled. detail=",
                    ZuiDetector.INSTANCE.getResult().getDetail());
                setError("Non-ZUXOS device, module disabled");
                return;
            }

            if (sInitDone) return;
            sInitDone = true;

            cfg = Config.load();
            mClassLoader = classLoader;
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

            // Init IPC early
            mConfigIPC = new ConfigIPCManager();

            Config ipcConfig = mConfigIPC.init();
            if (ipcConfig != null) {
                ipcConfig.injected = cfg.injected;
                ipcConfig.injectError = cfg.injectError;
                ipcConfig.readSystemSwitchesPublic();
                cfg = ipcConfig;
                LogHelper.currentLevel = cfg.verboseLevel;
                LogHelper.log(VerboseLevel.INFO,
                    "Config replaced with ContentProvider version, templates=",
                    String.valueOf(cfg.templates != null ? cfg.templates.size() : 0));
            }

            try {
                java.io.File dexOptDir = new java.io.File("/data/local/tmp/bzuikey_dex");
                AdapterManager.init(dexOptDir);
                AdapterManager.syncBindings(cfg.imeAdapterBindings);
                LogHelper.log(VerboseLevel.INFO, "AdapterManager initialized with ",
                        String.valueOf(cfg.imeAdapterBindings.size()), " binding(s)");
            } catch (Throwable t) {
                LogHelper.log(VerboseLevel.ERROR, "AdapterManager init failed:", t.getMessage());
            }

            ConfigResolver resolver = new ConfigResolver(cfg);
            FnKeyManager fnKeyManager = new FnKeyManager(cfg, mConfigIPC);
            ForegroundTracker foregroundTracker = new ForegroundTracker(resolver);

            ctx = new HookContext(cfg, resolver, fnKeyManager, foregroundTracker, mConfigIPC);

            LogHelper.log(VerboseLevel.INFO, "Installing RegionHook...");
            RegionHook.install(this, mClassLoader, cfg);
            LogHelper.log(VerboseLevel.INFO, "Installing FeatureHook...");
            FeatureHook.install(this, mClassLoader, cfg);

            LogHelper.log(VerboseLevel.INFO, "Installing foreground tracker...");
            foregroundTracker.install(this, mClassLoader);

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

            LogHelper.log(VerboseLevel.INFO, "Installing L2 debug hook...");
            hookL2_DebugInterceptBeforeDispatching();

            cfg.injected = true;
            cfg.injectError = "";
            LogHelper.log(VerboseLevel.INFO, "All hooks installed successfully!");
            // Retry boot mark until the app process is up and ContentProvider responds
            if (!sBootMarked) {
                sBootMarked = true;
                markBootDelayed(mConfigIPC);
            }
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Hook installation failed:", t.getMessage());
            setError("Injection error: " + t.toString() + "\n\n" + KeyInjector.stackTraceToString(t));
        }
    }

    // ----------------------------------------------------------------
    //  Lifecycle: app process hooks
    //  isFirstPackage() prevents duplicate installation across packages.
    //
    //  NOTE: Self-check is now handled by ModuleServiceBridge +
    //  XposedProvider (libxposed-service official API), not by hooking
    //  ModuleStatus here.  onPackageReady is NOT called for the module's
    //  own UI package in libxposed / LSPosed.
    // ----------------------------------------------------------------

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (sSelfHookDone) return;
        if (!param.isFirstPackage()) return;

        sSelfHookDone = true;

        // Boot mark: onPackageReady fires for non-system_server scopes (QQ, etc.)
        if (!sBootMarked) {
            sBootMarked = true;
            if (mConfigIPC == null) {
                mConfigIPC = new ConfigIPCManager();
                mConfigIPC.init();
            }
            markBootDelayed(mConfigIPC);
        }
        // Placeholder for future per-app-process hooks (e.g. startActivity interception).
        // Self-check is provided by ModuleServiceBridge via XposedProvider IPC.
        LogHelper.log(VerboseLevel.INFO, "onPackageReady: first package = ",
                param.getPackageName());
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    private void setError(String msg) {
        try {
            if (cfg == null) cfg = Config.load();
            cfg.injected = false;
            cfg.injectError = msg;
        } catch (Exception ignored) { }
    }

    /**
     * Retry writing a boot-time marker every 5 s for up to 1 min.
     * - system_server (UID 1000) → writes boot_time   → UI shows green
     * - any other process (wrong scope) → writes boot_time_app → UI shows yellow
     * Early-boot ContentProvider calls may fail; retries guarantee delivery.
     */
    private void markBootDelayed(ConfigIPCManager configIPC) {
        final boolean isSystem = android.os.Process.myUid() == android.os.Process.SYSTEM_UID;
        final String label = isSystem ? "system" : "app(uid=" + android.os.Process.myUid() + ")";
        LogHelper.log(VerboseLevel.INFO, "Boot mark: process=", label);

        final int delay = 5_000;
        final int maxTries = 12;
        android.os.Handler handler = new android.os.Handler(
                android.os.Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            int tries = 0;
            @Override
            public void run() {
                tries++;
                if (isSystem) {
                    configIPC.sendBootMark();
                } else {
                    configIPC.sendBootMarkApp();
                }
                if (tries < maxTries) {
                    handler.postDelayed(this, delay);
                } else {
                    LogHelper.log(VerboseLevel.INFO, "Boot mark retry finished (",
                            label, ", ", String.valueOf(maxTries), " attempts)");
                }
            }
        }, delay);
    }

    // ----------------------------------------------------------------
    //  Hook installers (use HookCompat to bridge old callback API)
    // ----------------------------------------------------------------

    private void hookConstructor() {
        try {
            Class<?> policyClass = HookCompat.findClass(
                    "com.zui.server.input.keyboard.key.policy.KeyboardZuiKeyInputPolicy",
                    mClassLoader);
            HookCompat.hookConstructor(
                    this, CLASS_KSC, mClassLoader,
                    new Class<?>[]{android.content.Context.class, android.os.Handler.class, policyClass},
                    hparam -> {
                        ctx.kscInstance = hparam.thisObject;
                        ctx.policyInstance = hparam.args[2];
                        LogHelper.log(VerboseLevel.INFO,
                                "KeyboardShortcutController and policy instance captured!");
                    });
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook constructor:", t.getMessage());
        }
    }

    private void hookL0_BeforeQueueing() {
        try {
            final L0Interceptor interceptor = new L0Interceptor(ctx);
            HookCompat.hookMethod(
                    this, CLASS_KSC, mClassLoader,
                    "interceptSystemKeysAndShortcutsBeforeQueueing",
                    new HookCompat.HookCallback() {
                        @Override
                        protected void beforeHookedMethod(HookCompat.HookParam param) {
                            interceptor.intercept(param);
                        }
                    },
                    KeyEvent.class, int.class, boolean.class);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook L0:", t.getMessage());
        }
    }

    private void hookL1_BeforeDispatching() {
        try {
            final L1Interceptor interceptor = new L1Interceptor(ctx);
            HookCompat.hookMethod(
                    this, CLASS_KSC, mClassLoader,
                    "interceptSystemKeysAndShortcutsBeforeDispatching",
                    new HookCompat.HookCallback() {
                        @Override
                        protected void beforeHookedMethod(HookCompat.HookParam param) {
                            interceptor.intercept(param);
                        }
                    },
                    KeyEvent.class, int.class);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook L1:", t.getMessage());
        }
    }

    private void hookL4_HandleKeyGestureEvent() {
        try {
            Class<?> kgClass = HookCompat.findClass(CLASS_KEY_GESTURE_EVENT, mClassLoader);
            final L4Interceptor interceptor = new L4Interceptor(ctx);
            HookCompat.hookMethod(
                    this, CLASS_KSC, mClassLoader, "handleKeyGestureEvent",
                    new HookCompat.HookCallback() {
                        @Override
                        protected void beforeHookedMethod(HookCompat.HookParam param) {
                            interceptor.intercept(param);
                        }
                    },
                    kgClass, IBinder.class);
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
                Class<?> kgClass = HookCompat.findClass(CLASS_KEY_GESTURE_EVENT, mClassLoader);
                if (kgClass == null) {
                    kgClass = Class.forName("android.hardware.input.KeyGestureEvent");
                }

                final L3Interceptor interceptor = new L3Interceptor(ctx);
                HookCompat.hookMethod(
                        this, className, mClassLoader, "handleKeyGestureEvent",
                        new HookCompat.HookCallback() {
                            @Override
                            protected void beforeHookedMethod(HookCompat.HookParam param) {
                                interceptor.intercept(param);
                            }
                        },
                        kgClass, IBinder.class);
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

    private void hookL2_DebugInterceptBeforeDispatching() {
        try {
            HookCompat.hookMethod(
                    this, "com.android.server.policy.PhoneWindowManager", mClassLoader,
                    "interceptKeyBeforeDispatching",
                    new HookCompat.HookCallback() {
                        @Override
                        protected void beforeHookedMethod(HookCompat.HookParam param) {
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
                    },
                    IBinder.class, KeyEvent.class, int.class);
            LogHelper.log(VerboseLevel.INFO, "L2 debug hook installed: PhoneWindowManager.interceptKeyBeforeDispatching");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "L2 hook failed:", t.getMessage());
        }
    }
}
