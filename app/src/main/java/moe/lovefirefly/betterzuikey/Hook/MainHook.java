/* Migrated: Legacy Xposed API → libxposed API 101 (LSPosed 2.0.0+) */

package moe.lovefirefly.betterzuikey.Hook;

import android.os.IBinder;
import android.view.KeyEvent;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Config.ConfigResolver;
import moe.lovefirefly.betterzuikey.Region.FeatureHook;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import moe.lovefirefly.betterzuikey.Utils.ZuiDetector;
import moe.lovefirefly.betterzuikey.ime.IMEProfileManager;
import moe.lovefirefly.betterzuikey.ime.IMEProfile;
import moe.lovefirefly.betterzuikey.ime.IMEDispatcher;
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

            // Init IME profiles via dedicated ContentProvider IPC (once at boot, no hot-reload)
            try {
                IMEProfileManager.clear();
                String profilesJson = mConfigIPC.pullProfiles();
                LogHelper.log(VerboseLevel.INFO, "IMEProfileManager: IPC pull len=",
                        String.valueOf(profilesJson.length()));
                IMEProfileManager.loadFromJsonArray(profilesJson);
                LogHelper.log(VerboseLevel.INFO, "IMEProfileManager initialized with ",
                        String.valueOf(IMEProfileManager.getProfileCount()), " profile(s)");
            } catch (Throwable t) {
                LogHelper.log(VerboseLevel.ERROR, "IMEProfileManager init failed:", t.getMessage());
            }

            IMEDispatcher.initClassLoader(mClassLoader);
            LogHelper.log(VerboseLevel.INFO, "IMEDispatcher initialized with system ClassLoader");

            ConfigResolver resolver = new ConfigResolver(cfg);
            FnKeyManager fnKeyManager = new FnKeyManager(cfg, mConfigIPC);
            ForegroundTracker foregroundTracker = new ForegroundTracker(resolver);

            ctx = new HookContext(cfg, resolver, fnKeyManager, foregroundTracker, mConfigIPC);

            LogHelper.log(VerboseLevel.INFO, "Installing FeatureHook...");
            FeatureHook.install(this, mClassLoader, cfg);

            LogHelper.log(VerboseLevel.INFO, "Installing foreground tracker...");
            foregroundTracker.install(this, mClassLoader);

            LogHelper.log(VerboseLevel.INFO, "Installing constructor hook...");
            hookConstructor();
            hookLaunchAssistantRunnable();

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
            if (MetaTrace.isTraceOnly()) {
                LogHelper.log(VerboseLevel.INFO,
                        "MetaTrace TRACE_ONLY=true — Meta routing observe-only, no consume/inject/menu");
            }

            cfg.injected = true;
            cfg.injectError = "";

            LogHelper.log(VerboseLevel.INFO, "All hooks installed successfully!");
            // Retry boot mark until the app process is up and ContentProvider responds
            if (!sBootMarked) {
                sBootMarked = true;
                markBootDelayed(mConfigIPC);
            }
            // Start ESC→BACK detection watcher (async, system_server UID, no su needed)
            startEscCheckWatcher();
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
        String packageName = param.getPackageName();
        ClassLoader appCl = param.getClassLoader();
        LogHelper.log(VerboseLevel.INFO, "onPackageReady: package=", packageName,
                " firstPackage=", String.valueOf(param.isFirstPackage()));

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
        LogHelper.log(VerboseLevel.INFO, "onPackageReady: first package = ",
                param.getPackageName());
    }

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
                    // All attempts failed — reset flag so future triggers can retry
                    sBootMarked = false;
                }
            }
        }, delay);
    }

    // ----------------------------------------------------------------
    //  ESC→BACK async detection watcher
    //  App writes SP flag → system_server reads ABX file (system UID),
    //  byte-searches for ESC(111)→BACK(4) strings (ABX stores strings in plain UTF-8),
    //  sends result back via ContentProvider → App picks up from SharedPreferences.
    // ----------------------------------------------------------------

    private static final String ESC_CHECK_SP_KEY = "esc_check_requested";
    /** Secure keys allowed through the async write queue (whitelist). */
    private static final java.util.Set<String> ALLOWED_SECURE_KEYS =
        java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(
            "accessibility_bounce_keys",
            "accessibility_mouse_keys_enabled",
            "accessibility_sticky_keys",
            "accessibility_slow_keys"
        )));

    private void startEscCheckWatcher() {
        final android.os.Handler handler = new android.os.Handler(
                android.os.Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                processEscCheckRequest();
                processSysWriteRequest();
                processLsposedOpenRequest();
                processGrantSecureRequest();
                processGrantTermuxRequest();
                handler.postDelayed(this, 1000); // 1s polling (Binder IPC overhead <1ms)
            }
        }, 5000);
    }

    /** Process pending ESC check request if any. */
    private void processEscCheckRequest() {
        if (mConfigIPC == null || mConfigIPC.getResolver() == null) return;
        try {
            android.os.Bundle result = mConfigIPC.getResolver().call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                "getEscRequest", null, null);
            if (result == null || !result.getBoolean("requested", false)) return;
        } catch (Exception e) {
            return;
        }

        LogHelper.log(VerboseLevel.INFO, "ESC check: request detected, scanning ABX...");
        boolean detected = detectEscToBackSystem();
        mConfigIPC.sendEscCheckResult(detected);
        LogHelper.log(VerboseLevel.INFO,
                "ESC check: result sent → ", detected ? "DETECTED" : "not found");
    }

    /** Atomically drain and process pending Settings.System write queue.
     *  Single ContentProvider call reads + clears to avoid race with app writes. */
    private void processSysWriteRequest() {
        if (mConfigIPC == null || mConfigIPC.getResolver() == null) return;
        try {
            android.os.Bundle req = mConfigIPC.getResolver().call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                "drainSysWriteQueue", null, null);
            if (req == null) return;
            String json = req.getString("queue", "[]");
            if ("[]".equals(json)) return;

            LogHelper.log(VerboseLevel.INFO, "SysWrite: processing queue: ", json);
            int i = 0;
            while ((i = json.indexOf("{\"k\":\"", i)) >= 0) {
                int kStart = i + 6;
                int kEnd = json.indexOf("\"", kStart);
                if (kEnd < 0) break;
                String key = json.substring(kStart, kEnd);
                int vStart = json.indexOf("\"v\":", kEnd) + 4;
                int vEnd = json.indexOf("}", vStart);
                if (vEnd < 0) break;
                int val = Integer.parseInt(json.substring(vStart, vEnd).trim());
                String sysKey = Config.SWITCH_KEY_MAP.get(key);
                if (sysKey != null) {
                    android.provider.Settings.System.putInt(mConfigIPC.getResolver(), sysKey, val);
                } else if (ALLOWED_SECURE_KEYS.contains(key)) {
                    android.provider.Settings.Secure.putInt(mConfigIPC.getResolver(), key, val);
                } else {
                    // Unknown key → potential attack, alert the app
                    LogHelper.log(VerboseLevel.WARNING, "SysWrite: REJECTED unknown key: ", key);
                    android.os.Bundle alert = new android.os.Bundle();
                    alert.putBoolean("alert", true);
                    mConfigIPC.getResolver().call(
                        moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                        "setSysWriteAlert", null, alert);
                }
                i = vEnd + 1;
            }
        } catch (Exception e) { }
    }

    /** Process pending WRITE_SECURE_SETTINGS grant request via system_server's own pm. */
    private void processGrantSecureRequest() {
        if (mConfigIPC == null || mConfigIPC.getResolver() == null) return;
        try {
            android.os.Bundle req = mConfigIPC.getResolver().call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                "getGrantSecureRequest", null, null);
            if (req == null || !req.getBoolean("requested", false)) return;

            // Clear flag BEFORE granting (grant is idempotent, flag isn't)
            mConfigIPC.getResolver().call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                "clearGrantSecureRequest", null, null);

            LogHelper.log(VerboseLevel.INFO, "GrantSecure: granting via PackageManager...");
            Object pm = Class.forName("android.app.ActivityThread")
                    .getMethod("getPackageManager").invoke(null);
            pm.getClass().getMethod("grantRuntimePermission",
                    String.class, String.class, int.class)
                    .invoke(pm, "moe.lovefirefly.betterzuikey",
                            "android.permission.WRITE_SECURE_SETTINGS",
                            0 /* userId 0 = owner */);
            LogHelper.log(VerboseLevel.INFO, "GrantSecure: granted via Binder");
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.INFO, "GrantSecure: failed:", e.getMessage());
        }
    }

    /** Process pending com.termux.permission.RUN_COMMAND grant via system_server pm. */
    private void processGrantTermuxRequest() {
        if (mConfigIPC == null || mConfigIPC.getResolver() == null) return;
        try {
            android.os.Bundle req = mConfigIPC.getResolver().call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                "getGrantTermuxRequest", null, null);
            if (req == null || !req.getBoolean("requested", false)) return;

            mConfigIPC.getResolver().call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                "clearGrantTermuxRequest", null, null);

            LogHelper.log(VerboseLevel.INFO, "GrantTermux: granting RUN_COMMAND via PackageManager...");
            Object pm = Class.forName("android.app.ActivityThread")
                    .getMethod("getPackageManager").invoke(null);
            pm.getClass().getMethod("grantRuntimePermission",
                    String.class, String.class, int.class)
                    .invoke(pm, "moe.lovefirefly.betterzuikey",
                            "com.termux.permission.RUN_COMMAND",
                            0 /* userId 0 = owner */);
            LogHelper.log(VerboseLevel.INFO, "GrantTermux: granted via Binder");
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.INFO, "GrantTermux: failed:", e.getMessage());
        }
    }

    /** Process pending LSPosed open request via system_server's own Context.
     *  No shell needed — system_server IS the platform. */
    private void processLsposedOpenRequest() {
        if (mConfigIPC == null || mConfigIPC.getResolver() == null) return;
        try {
            android.os.Bundle req = mConfigIPC.getResolver().call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                "getLsposedOpenRequest", null, null);
            if (req == null || !req.getBoolean("requested", false)) return;

            // Clear FIRST to avoid re-entry
            android.os.Bundle clear = new android.os.Bundle();
            clear.putBoolean("requested", false);
            mConfigIPC.getResolver().call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                "setLsposedOpenRequest", null, clear);

            LogHelper.log(VerboseLevel.INFO, "LSPosed: broadcasting via system context...");
            // Get system context (same as ConfigIPCManager.init)
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            android.content.Context ctx = (android.content.Context)
                    at.getClass().getMethod("getSystemContext").invoke(at);

            android.content.Intent intent = new android.content.Intent(
                    "android.telephony.action.SECRET_CODE");
            intent.setData(android.net.Uri.parse("android_secret_code://5776733"));
            intent.addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND);
            intent.setPackage("android");

            ctx.sendBroadcast(intent);
            LogHelper.log(VerboseLevel.INFO, "LSPosed: broadcast sent");
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.INFO, "LSPosed: broadcast failed:",
                    e.getMessage());
        }
    }

    /** Detect ESC(111)→BACK(4) remapping. Uses Android Binary XML parser
     *  since system_server has no su in PATH and ABX byte-search is unreliable. */
    private static boolean detectEscToBackSystem() {
        // Android Binary XML native parser (system_server IS the framework)
        try {
            java.io.InputStream is = new java.io.FileInputStream(
                    "/data/system/input-manager-state.xml");
            // android.util.Xml.newBinaryPullParser() is a hidden API — use reflection
            Class<?> xmlClass = Class.forName("android.util.Xml");
            java.lang.reflect.Method newParser = xmlClass.getMethod("newBinaryPullParser");
            org.xmlpull.v1.XmlPullParser parser =
                    (org.xmlpull.v1.XmlPullParser) newParser.invoke(null);
            parser.setInput(is, "UTF-8");

            int eventType = parser.getEventType();
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG
                        && "remap".equals(parser.getName())) {
                    String fromKey = parser.getAttributeValue(null, "from-key");
                    String toKey = parser.getAttributeValue(null, "to-key");
                    if ("111".equals(fromKey) && "4".equals(toKey)) {
                        is.close();
                        return true;
                    }
                }
                eventType = parser.next();
            }
            is.close();
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.INFO,
                    "ESC check: ABX parse failed:", e.getMessage());
        }
        return false;
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
                        installAssistantRunnableHook(hparam.args[2]);
                        LogHelper.log(VerboseLevel.INFO,
                                "KeyboardShortcutController and policy instance captured!");
                    });
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "Failed to hook constructor:", t.getMessage());
        }
    }

    /** Hook ZUI Meta long-press branch ({@code mLaunchAssistantRunnable}, 2s). */
    private void hookLaunchAssistantRunnable() {
        try {
            Class<?> policyClass = HookCompat.findClass(
                    "com.zui.server.input.keyboard.key.policy.KeyboardZuiKeyInputPolicy",
                    mClassLoader);
            HookCompat.hookConstructor(
                    this, policyClass.getName(), mClassLoader,
                    new Class<?>[]{android.content.Context.class},
                    hparam -> {
                        Object policy = hparam.thisObject;
                        ctx.policyInstance = policy;
                        installAssistantRunnableHook(policy);
                    });
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR,
                    "Failed to hook policy constructor:", t.getMessage());
        }
    }

    private volatile boolean mAssistantRunnableHooked = false;

    private void installAssistantRunnableHook(Object policy) {
        if (mAssistantRunnableHooked || policy == null) return;
        try {
            Object runnable = HookCompat.getObjectField(policy, "mLaunchAssistantRunnable");
            if (runnable == null) return;
            final MetaKeyRouter router = new MetaKeyRouter(ctx);
            HookCompat.hookMethod(
                    this, runnable.getClass(), "run",
                    new HookCompat.HookCallback() {
                        @Override
                        protected void beforeHookedMethod(HookCompat.HookParam param) {
                            if (router.handleAssistantLongPress()) {
                                param.setResult(null);
                            }
                        }
                    });
            mAssistantRunnableHooked = true;
            LogHelper.log(VerboseLevel.INFO,
                    "mLaunchAssistantRunnable hook installed (ZUI Meta long branch)");
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR,
                    "Failed to hook mLaunchAssistantRunnable:", t.getMessage());
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
                            if (PassthroughTrace.shouldTrace(event)) {
                                PassthroughTrace.in("L2", event, ctx);
                            } else if (event.getKeyCode() == KeyEvent.KEYCODE_META_LEFT
                                    || event.getKeyCode() == KeyEvent.KEYCODE_META_RIGHT) {
                                MetaTrace.event("L2", event, ctx);
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
