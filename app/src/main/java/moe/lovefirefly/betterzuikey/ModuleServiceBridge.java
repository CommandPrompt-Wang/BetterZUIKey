package moe.lovefirefly.betterzuikey;

import android.util.Log;

import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Bridge between the Xposed framework's IXposedService Binder and the module UI.
 *
 * <p>The framework sends a service Binder through {@code XposedProvider} when
 * the module is activated.  This singleton registers an
 * {@link XposedServiceHelper.OnServiceListener} to capture that Binder and
 * exposes an {@link #isActive()} method the UI polls to determine module status.
 *
 * <p>Usage:
 * <ol>
 *   <li>Call {@link #init()} once, early (Application.onCreate).</li>
 *   <li>Call {@link #isActive()} from the UI to check activation.</li>
 * </ol>
 */
public final class ModuleServiceBridge implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "BetterZUIKey";
    private static volatile XposedService sService;
    private static volatile boolean sListenerRegistered;

    private static final ModuleServiceBridge INSTANCE = new ModuleServiceBridge();

    private ModuleServiceBridge() {}

    /**
     * Register the listener with {@link XposedServiceHelper}.
     * Must be called early (Application.onCreate or ContentProvider.onCreate)
     * so the Binder is captured before the UI queries it.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    public static void init() {
        if (sListenerRegistered) {
            Log.i(TAG, "[BRIDGE] init skipped — already registered, isActive=" + (sService != null));
            return;
        }
        synchronized (ModuleServiceBridge.class) {
            if (sListenerRegistered) {
                Log.i(TAG, "[BRIDGE] init skipped (sync) — already registered, isActive=" + (sService != null));
                return;
            }
            sListenerRegistered = true;
            XposedServiceHelper.registerListener(INSTANCE);
            Log.i(TAG, "[BRIDGE] Listener registered with XposedServiceHelper, isActive=" + (sService != null));
        }
    }

    /**
     * Returns {@code true} if the Xposed framework has sent a service Binder,
     * meaning the module is activated and hooks are loaded.
     */
    public static boolean isActive() {
        boolean active = sService != null;
        if (!active) Log.w(TAG, "[BRIDGE] isActive() = false");
        return active;
    }

    /**
     * Returns the active {@link XposedService}, or {@code null} if not connected.
     */
    public static XposedService getService() {
        return sService;
    }

    // -- OnServiceListener callbacks --

    public static List<String> getScope() {
        if (sService == null) return List.of();
        return sService.getScope();
    }

    /**
     * 把字符串写进 libxposed 框架的远端配置（App 侧可写）。
     *
     * <p>hooked 进程里 {@code XposedInterface.getRemotePreferences()} 是只读的，
     * 所以「App 写、hook 读」是官方设计的单向通道。模块未激活（拿不到 Binder）时
     * 静默失败，返回 false。
     */
    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
        int api = 0;
        long props = 0L;
        try { api = service.getApiVersion(); } catch (Throwable t) {
            Log.d(TAG, "[BRIDGE] getApiVersion failed: " + t.getMessage());
        }
        try { props = service.getFrameworkProperties(); } catch (Throwable t) {
            Log.d(TAG, "[BRIDGE] getFrameworkProperties failed: " + t.getMessage());
        }
        boolean apiProtection = (props & XposedService.PROP_RT_API_PROTECTION) != 0;
        Log.i(TAG, "[BRIDGE] Service bound — module ACTIVE, api=" + api
                + " apiProtection=" + apiProtection);
    }

    @Override
    public void onServiceDied(XposedService service) {
        if (sService == service) {
            sService = null;
        }
        Log.w(TAG, "[BRIDGE] Service died — module INACTIVE");
    }
}
