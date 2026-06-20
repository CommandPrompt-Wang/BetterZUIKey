package moe.lovefirefly.betterzuikey.Hook;

import android.content.ContentResolver;
import android.os.Bundle;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * Manages config synchronization between the module's UI process and system_server
 * via ContentProvider.call() — Binder IPC, no file permission / SELinux issues.
 */
public class ConfigIPCManager {

    private ContentResolver mConfigResolver = null;
    private String mLastConfigSync = "";

    /**
     * Initialize ContentResolver via ActivityThread.getSystemContext() pattern
     * and pull the initial config via Binder IPC.
     * Must be called once after handleLoadPackage.
     *
     * @return the initial Config from ContentProvider, or null if unavailable.
     *         Caller MUST replace the file-loaded (likely default) Config with this one.
     */
    public Config init() {
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            android.content.Context sysCtx = (android.content.Context)
                    at.getClass().getMethod("getSystemContext").invoke(at);
            mConfigResolver = sysCtx.getContentResolver();
            // Read initial config via Binder IPC
            String sync = pullConfigSync();
            mLastConfigSync = sync;
            LogHelper.log(VerboseLevel.INFO, "ContentResolver IPC initialized, config_sync len=",
                    String.valueOf(sync.length()));

            // Parse and return the IPC-pulled config so caller can replace
            // the file-loaded default (which fails due to app-private dir permissions).
            if (!sync.isEmpty()) {
                Config ipcConfig = Config.fromJson(sync);
                if (ipcConfig != null) {
                    LogHelper.log(VerboseLevel.INFO,
                        "Config loaded via ContentProvider IPC, templates=",
                        String.valueOf(ipcConfig.templates != null ? ipcConfig.templates.size() : 0));
                    return ipcConfig;
                }
            }
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.ERROR, "initConfigResolver failed:", e.getMessage());
        }
        return null;
    }

    /**
     * Pull config JSON via ContentProvider.call() — Binder IPC, no file permissions needed.
     * @return config JSON string, or "" on failure
     */
    private String pullConfigSync() {
        try {
            if (mConfigResolver == null) return "";
            Bundle result = mConfigResolver.call(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.METHOD_GET_SYNC,
                    null, null);
            if (result == null) return "";
            return result.getString(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.KEY_CONFIG_JSON, "");
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.WARNING, "pullConfigSync failed:", e.getMessage());
            return "";
        }
    }

    /**
     * Push the current in-memory Config to the app process via ContentProvider,
     * so it can be persisted to SharedPreferences.  Used when system_server
     * mutates Config directly (e.g. FnLock toggle) and needs the app to save it.
     */
    public void pushUpdate(Config cfg) {
        try {
            if (mConfigResolver == null) return;
            String json = Config.toJson(cfg);
            Bundle extras = new Bundle();
            extras.putString(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.KEY_CONFIG_JSON, json);
            mConfigResolver.call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.METHOD_SET_CONFIG,
                null, extras);
            mLastConfigSync = json;
            LogHelper.log(VerboseLevel.INFO, "Config pushed to app via IPC, len=",
                String.valueOf(json.length()));
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.ERROR, "pushConfigUpdate failed:", e.getMessage());
        }
    }

    /**
     * Check for config changes via ContentProvider.call().
     * Called from every hook entry point (L0/L1/L3/L4).
     *
     * @return the new Config if changed, null otherwise
     */
    public Config checkChanged() {
        try {
            String sync = pullConfigSync();
            if (sync.isEmpty() || sync.equals(mLastConfigSync)) return null;
            mLastConfigSync = sync;
            Config newCfg = Config.fromJson(sync);
            if (newCfg != null) {
                LogHelper.log(VerboseLevel.INFO, "Config hot-reloaded via ContentProvider, len=",
                        String.valueOf(sync.length()));
            }
            return newCfg;
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.WARNING, "checkConfigChanged failed:", e.getMessage());
            return null;
        }
    }
}
