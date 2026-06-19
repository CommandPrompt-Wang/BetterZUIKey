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
     * Initialize ContentResolver via ActivityThread.getSystemContext() pattern.
     * Must be called once after handleLoadPackage.
     */
    public void init() {
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
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.ERROR, "initConfigResolver failed:", e.getMessage());
        }
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
