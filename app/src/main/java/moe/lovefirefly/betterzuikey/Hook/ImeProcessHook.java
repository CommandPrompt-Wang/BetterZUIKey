package moe.lovefirefly.betterzuikey.Hook;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import moe.lovefirefly.betterzuikey.ConfigSyncProvider;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import moe.lovefirefly.betterzuikey.ime.HookConfig;
import moe.lovefirefly.betterzuikey.ime.IMEProfile;
import moe.lovefirefly.betterzuikey.ime.IMEProfileManager;
import moe.lovefirefly.betterzuikey.ime.Strategy;

import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * hook 策略的进程内安装器 —— 在**输入法进程**里跑。
 *
 * <p>流程：
 * <ol>
 *   <li>从 libxposed 远端配置（在 hooked app 中只读）拉 IME profiles，
 *       拿到本包的 {@code strategy=hook} 配置；</li>
 *   <li>加载 libdexkit.so，用 {@link ImeTargetResolver} 按稳定锚点找到 IME 服务类；</li>
 *   <li>hook 配置的入口方法（默认 {@code onKeyDown(int, KeyEvent)}）做观测，
 *       {@code chain.getThisObject()} 天然给出活的 IME 实例 —— 不再需要
 *       老实现里那套 {@code findInstanceByInterface} 猜实例的玩法；</li>
 *   <li>顺带 hook {@code onCreate()} 提前留一份实例引用，供后续「主动调用
 *       内部方法」使用。</li>
 * </ol>
 *
 * <p>所有异常都在这里吞掉：这是宿主进程，绝不能因为适配失败影响输入法本身。
 * 扫描放后台线程，避免拖慢输入法启动。
 */
public final class ImeProcessHook {

    private static final String TAG = "ImeProcessHook";

    private static final Set<String> sQueued = ConcurrentHashMap.newKeySet();

    private static volatile boolean sProfilesAttempted = false;
    private static volatile boolean sProfilesLoaded = false;

    private static volatile Object sInstance;
    private static volatile String sTargetClass;

    private ImeProcessHook() {}

    /** 已捕获的 IME 服务实例（供后续主动调用内部方法）。 */
    public static Object getInstance() {
        return sInstance;
    }

    /** 已解析出的 IME 服务类名。 */
    public static String getTargetClass() {
        return sTargetClass;
    }

    // ------------------------------------------------------------------
    // 入口（由 MainHook.onPackageReady 调用）
    // ------------------------------------------------------------------

    public static void installFromRemoteConfig(XposedModule module, String packageName,
            ClassLoader appCl, ApplicationInfo hostInfo) {
        if (module == null || packageName == null || appCl == null || hostInfo == null) return;
        // 每个进程只读一次远端配置：之后对每个被作用域的包只是一次 map 查询，
        // 不会给无关 app 的启动路径增加 binder 开销。
        if (!ensureProfilesLoaded(module)) return;
        IMEProfile profile = IMEProfileManager.getProfileForIME(packageName);
        if (profile == null || profile.getStrategy() != Strategy.hook) return;
        if (!sQueued.add(packageName)) return;
        Thread t = new Thread(() ->
                install(module, packageName, appCl, hostInfo, profile), "bzk-ime-" + packageName);
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------

    private static void install(XposedModule module, String packageName, ClassLoader appCl,
            ApplicationInfo hostInfo, IMEProfile profile) {
        long t0 = SystemClock.uptimeMillis();
        try {
            ApplicationInfo moduleInfo = null;
            try {
                moduleInfo = module.getModuleApplicationInfo();
            } catch (Throwable t) {
                LogHelper.log(VerboseLevel.WARNING, TAG, ": getModuleApplicationInfo failed: ",
                        t.getMessage());
            }
            if (!DexKitLoader.ensureLoaded(moduleInfo, hostInfo.dataDir)) {
                LogHelper.log(VerboseLevel.ERROR, TAG, ": dexkit not loaded, abort ", packageName);
                return;
            }

            String apkPath = hostInfo.sourceDir;
            if (apkPath == null) {
                LogHelper.log(VerboseLevel.ERROR, TAG, ": no sourceDir for ", packageName);
                return;
            }

            DexKitCacheStore cache = null;
            if (hostInfo.dataDir != null) {
                cache = new DexKitCacheStore(new File(hostInfo.dataDir,
                        "code_cache/dexkit/ime-cache.json"));
            }

            String className = ImeTargetResolver.resolveImeServiceClass(
                    appTag(packageName, hostInfo), apkPath, cache);
            if (className == null) {
                LogHelper.log(VerboseLevel.WARNING, TAG, ": IME service class not found for ",
                        packageName);
                return;
            }
            sTargetClass = className;

            Class<?> clazz = Class.forName(className, false, appCl);
            HookConfig hookCfg = profile.getHook();
            String entry = (hookCfg != null && hookCfg.getEntry() != null
                    && !hookCfg.getEntry().isEmpty()) ? hookCfg.getEntry() : "onKeyDown";

            LogHelper.log(VerboseLevel.INFO, TAG, ": ", packageName, " target=", className,
                    " entry=", entry, " resolve=", String.valueOf(SystemClock.uptimeMillis() - t0),
                    "ms cache=", (cache != null ? "on" : "off"),
                    " dexkit=", DexKitLoader.getSource(),
                    " apiProtection=", String.valueOf(isApiProtectionOn(module)));
            logClassSummary(packageName, clazz);

            hookEntry(module, clazz, entry, packageName);
            hookOnCreate(module, clazz, packageName);

            if (hookCfg != null && hookCfg.getInvoke() != null && !hookCfg.getInvoke().isEmpty()) {
                LogHelper.log(VerboseLevel.INFO, TAG, ": invoke target configured (",
                        hookCfg.getInvoke(), ") — 主动调用将在后续版本接入");
            }
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, TAG, ": install failed for ", packageName,
                    ": ", t.toString());
        }
    }

    // ------------------------------------------------------------------
    // 配置（hooked 进程里 getRemotePreferences 只读可用，无需 ContentProvider 授权）
    // ------------------------------------------------------------------

    /**
     * 每个进程只拉一次 IME profiles。
     *
     * <p>hooked app 里 {@code getRemotePreferences()} 是**只读**的（libxposed 101 明确
     * 如此），正好用来读配置：没有 ContentProvider 的 UID 白名单问题，也不需要
     * 任何文件权限。写回一律由宿主自己写自己的 data 目录完成。
     */
    private static synchronized boolean ensureProfilesLoaded(XposedModule module) {
        if (sProfilesAttempted) return sProfilesLoaded;
        sProfilesAttempted = true;
        try {
            SharedPreferences sp = module.getRemotePreferences(ConfigSyncProvider.REMOTE_PREF_GROUP);
            if (sp == null) return false;
            String json = sp.getString(ConfigSyncProvider.KEY_IME_PROFILES, "[]");
            if (json == null || json.length() <= 2) {
                LogHelper.log(VerboseLevel.DEBUG, TAG, ": no IME profiles in remote prefs");
                return false;
            }
            IMEProfileManager.clear();
            IMEProfileManager.loadFromJsonArray(json);
            sProfilesLoaded = true;
            LogHelper.log(VerboseLevel.INFO, TAG, ": IME profiles loaded (",
                    String.valueOf(IMEProfileManager.getProfileCount()), ")");
        } catch (UnsupportedOperationException e) {
            LogHelper.log(VerboseLevel.WARNING, TAG, ": remote preferences unsupported: ",
                    e.getMessage());
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.WARNING, TAG, ": load profiles failed: ", t.getMessage());
        }
        return sProfilesLoaded;
    }

    /**
     * 框架是否启用了 Xposed API 调用保护（{@code PROP_RT_API_PROTECTION}）。
     *
     * <p>该位为真时，框架禁止「通过反射或动态加载的代码」访问 Xposed API。
     * 本模块所有 API 调用（hook / chain / 远端配置）都发生在 module ClassLoader
     * 自身的类里，属于被允许的范畴；这里只把它记进日志，方便排查。
     */
    private static boolean isApiProtectionOn(XposedModule module) {
        try {
            return (module.getFrameworkProperties() & XposedInterface.PROP_RT_API_PROTECTION) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String appTag(String packageName, ApplicationInfo hostInfo) {
        long version = 0L;
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            android.content.Context sysCtx = (android.content.Context) at.getClass()
                    .getMethod("getSystemContext").invoke(at);
            PackageInfo pi = sysCtx.getPackageManager().getPackageInfo(packageName, 0);
            version = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable ignored) {
            // 拿不到版本号就退化成 APK mtime，至少能区分大多数升级
        }
        if (version == 0L && hostInfo != null && hostInfo.sourceDir != null) {
            version = new File(hostInfo.sourceDir).lastModified();
        }
        return packageName + ":" + version;
    }

    // ------------------------------------------------------------------
    // Hook 安装
    // ------------------------------------------------------------------

    /** hook 入口方法：只观测，不改行为（骨架阶段的观测点）。 */
    private static void hookEntry(XposedModule module, Class<?> clazz, String entry,
            String packageName) {
        Method target = findTwoArgKeyMethod(clazz, entry);
        if (target == null) {
            LogHelper.log(VerboseLevel.WARNING, TAG, ": entry ", entry,
                    "(int, KeyEvent) not found on ", clazz.getName());
            return;
        }
        try {
            module.hook(target).intercept(chain -> {
                try {
                    Object self = chain.getThisObject();
                    if (self != null) sInstance = self;
                    Object a0 = chain.getArg(0);
                    Object a1 = chain.getArg(1);
                    int keyCode = a0 instanceof Integer ? (Integer) a0 : -1;
                    if (a1 instanceof KeyEvent) {
                        KeyEvent ev = (KeyEvent) a1;
                        LogHelper.log(VerboseLevel.INFO, TAG, ": ", packageName, " ", entry,
                                " kc=", String.valueOf(keyCode),
                                " action=", (ev.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP"),
                                " meta=0x", Integer.toHexString(ev.getMetaState()),
                                " scan=", String.valueOf(ev.getScanCode()),
                                " src=0x", Integer.toHexString(ev.getSource()),
                                " inst=", (self != null ? "1" : "0"));
                    } else {
                        LogHelper.log(VerboseLevel.INFO, TAG, ": ", packageName, " ", entry,
                                " kc=", String.valueOf(keyCode));
                    }
                } catch (Throwable inner) {
                    // 观测失败绝不影响宿主
                }
                return chain.proceed();
            });
            LogHelper.log(VerboseLevel.INFO, TAG, ": hooked ", clazz.getName(), "#", entry);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, TAG, ": hook ", entry, " failed: ", t.getMessage());
        }
    }

    /** hook onCreate()：提前留一份实例引用。 */
    private static void hookOnCreate(XposedModule module, Class<?> clazz, String packageName) {
        Method target = findNoArgMethod(clazz, "onCreate");
        if (target == null) return;
        try {
            module.hook(target).intercept(chain -> {
                try {
                    Object self = chain.getThisObject();
                    if (self != null) {
                        sInstance = self;
                        LogHelper.log(VerboseLevel.INFO, TAG, ": ", packageName,
                                " instance captured from onCreate");
                    }
                } catch (Throwable inner) {
                    // ignore
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, TAG, ": onCreate hook failed: ", t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 反射辅助（只用于框架 override 名，不用于找混淆方法）
    // ------------------------------------------------------------------

    private static Method findTwoArgKeyMethod(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (m.getName().equals(name) && pt.length == 2
                        && pt[0] == int.class && KeyEvent.class.isAssignableFrom(pt[1])) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        LogHelper.log(VerboseLevel.WARNING, TAG, ": ", name,
                "(int, KeyEvent) not declared on ", clazz.getName());
        return null;
    }

    private static Method findNoArgMethod(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** 日志：该类里保留了原名的按键处理 override，便于确认锚点。 */
    private static void logClassSummary(String packageName, Class<?> clazz) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 2 && m.getName().startsWith("onKey")) {
                    sb.append(m.getName()).append(' ');
                }
            }
            LogHelper.log(VerboseLevel.INFO, TAG, ": ", packageName,
                    " declaredMethods=", String.valueOf(clazz.getDeclaredMethods().length),
                    " keyHandlers=[", sb.toString().trim(), "]");
        } catch (Throwable ignored) {
            // 诊断日志失败无所谓
        }
    }
}
