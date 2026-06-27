package moe.lovefirefly.betterzuikey;

import android.util.Log;

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
        if (sListenerRegistered) return;
        synchronized (ModuleServiceBridge.class) {
            if (sListenerRegistered) return;
            sListenerRegistered = true;
            XposedServiceHelper.registerListener(INSTANCE);
            Log.d(TAG, "[BRIDGE] Listener registered with XposedServiceHelper");
        }
    }

    /**
     * Returns {@code true} if the Xposed framework has sent a service Binder,
     * meaning the module is activated and hooks are loaded.
     */
    public static boolean isActive() {
        return sService != null;
    }

    /**
     * Returns the active {@link XposedService}, or {@code null} if not connected.
     */
    public static XposedService getService() {
        return sService;
    }

    // -- OnServiceListener callbacks --

    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
        int api = 0;
        try { api = service.getApiVersion(); } catch (Throwable ignored) {}
        Log.i(TAG, "[BRIDGE] Service bound — module ACTIVE, api=" + api);
    }

    @Override
    public void onServiceDied(XposedService service) {
        if (sService == service) {
            sService = null;
        }
        Log.w(TAG, "[BRIDGE] Service died — module INACTIVE");
    }
}
