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

    private static final String PKG = "moe.lovefirefly.betterzuikey";
    private static final String MAIN_ACTIVITY = PKG + ".MainActivity";

    ContentResolver mConfigResolver = null;
    private android.content.Context mSystemContext = null;
    private String mLastConfigSync = "";

    /**
     * Initialize ContentResolver via ActivityThread.getSystemContext() pattern
     * and pull the initial config via Binder IPC.
     * Must be called once after handleLoadPackage.
     *
     * @return the initial Config from ContentProvider, or null if unavailable.
     *         Caller MUST replace the file-loaded (likely default) Config with this one.
     */
    /** Expose resolver for direct calls (used by ESC check watcher in MainHook). */
    public ContentResolver getResolver() { return mConfigResolver; }

    public Config init() {
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            android.content.Context sysCtx = (android.content.Context)
                    at.getClass().getMethod("getSystemContext").invoke(at);
            mSystemContext = sysCtx;
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
     * Write the current boot time to SharedPreferences so the UI can verify
     * that hooks were installed in system_server this boot cycle.
     */
    public void sendBootMark() {
        sendBootMarkInternal(moe.lovefirefly.betterzuikey.ConfigSyncProvider.METHOD_BOOT_MARK);
    }

    /**
     * Write a boot-time marker for a NON-system_server process (wrong scope).
     * The UI shows yellow instead of red when only this marker exists.
     */
    public void sendBootMarkApp() {
        sendBootMarkInternal(moe.lovefirefly.betterzuikey.ConfigSyncProvider.METHOD_BOOT_MARK_APP);
    }

    /** Write ESC→BACK detection result to SharedPreferences via ContentProvider. */
    public void sendEscCheckResult(boolean detected) {
        if (mConfigResolver == null) return;
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putBoolean(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.KEY_ESC_RESULT, detected);
            mConfigResolver.call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.METHOD_ESC_CHECK_RESULT,
                null, extras);
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.WARNING, "sendEscCheckResult failed:", e.getMessage());
        }
    }

    private void sendBootMarkInternal(String method) {
        if (mConfigResolver == null) {
            LogHelper.log(VerboseLevel.WARNING, "sendBootMark(", method, "): mConfigResolver is null");
            return;
        }
        try {
            android.os.Bundle result = mConfigResolver.call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                method, null, null);
            LogHelper.log(VerboseLevel.INFO, "Boot mark (", method, ") sent, result=",
                    result != null ? "ok" : "null");
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.WARNING, "sendBootMark(", method, ") failed:", e.getMessage());
        }
    }

    /** Read keyboard-detect Activity flag via ContentProvider (app → system_server). */
    public boolean isKeyboardDetectActive() {
        try {
            if (mConfigResolver == null) return false;
            Bundle result = mConfigResolver.call(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.METHOD_GET_KEYBOARD_DETECT,
                    null, null);
            if (result == null) return false;
            return result.getBoolean(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.KEY_KEYBOARD_DETECT, false);
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.DEBUG, "isKeyboardDetectActive failed:", e.getMessage());
            return false;
        }
    }

    /**
     * Run a smart-key shell script in the module app process via ContentProvider IPC.
     * Default execution uses {@code /system/bin/sh -c}; {@code root=true} uses {@code su -c}.
     */
    public void runAppKeyCommand(String script, boolean root, boolean singleton, int timeoutMin) {
        if (mConfigResolver == null || script == null || script.trim().isEmpty()) return;
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.KEY_APP_KEY_SCRIPT, script);
            extras.putBoolean(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.KEY_APP_KEY_ROOT, root);
            extras.putBoolean(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.KEY_APP_KEY_SINGLETON, singleton);
            extras.putInt(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.KEY_APP_KEY_TIMEOUT_MIN,
                    timeoutMin > 0 ? timeoutMin : 1);
            mConfigResolver.call(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.METHOD_RUN_APP_KEY_COMMAND,
                    null, extras);
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.WARNING, "runAppKeyCommand failed:", e.getMessage());
        }
    }

    /** Bring up the module script editor for keyApp1 / keyApp2. */
    public void openAppKeyCommandEditor(String appKey) {
        if (appKey == null) {
            LogHelper.log(VerboseLevel.WARNING, "AppKeyIPC: open editor skipped appKey=null");
            return;
        }
        if (!"keyApp1".equals(appKey) && !"keyApp2".equals(appKey)) {
            LogHelper.log(VerboseLevel.WARNING, "AppKeyIPC: open editor invalid appKey=", appKey);
            return;
        }
        if (launchEditorFromSystem(appKey)) {
            return;
        }
        if (mConfigResolver == null) {
            LogHelper.log(VerboseLevel.WARNING, "AppKeyIPC: open editor fallback skipped resolver=null");
            return;
        }
        try {
            LogHelper.log(VerboseLevel.INFO, "AppKeyIPC: open editor app-process fallback appKey=", appKey);
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.KEY_OPEN_APP_KEY_EDITOR, appKey);
            mConfigResolver.call(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.METHOD_OPEN_APP_KEY_EDITOR,
                    null, extras);
            LogHelper.log(VerboseLevel.INFO, "AppKeyIPC: open editor fallback IPC returned appKey=", appKey);
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.WARNING, "AppKeyIPC: open editor failed appKey=", appKey,
                    " err=", e.getMessage());
        }
    }

    /**
     * Start MainActivity from system_server — bypasses app-process background-activity limits.
     */
    private boolean launchEditorFromSystem(String appKey) {
        if (mSystemContext == null) {
            LogHelper.log(VerboseLevel.WARNING, "AppKeyIPC: system launch skipped (no system context)");
            return false;
        }
        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(PKG, MAIN_ACTIVITY);
            intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            intent.putExtra(
                    moe.lovefirefly.betterzuikey.AppKeyCommandDialog.EXTRA_OPEN_APP_KEY, appKey);

            int userId = 0;
            try {
                userId = (Integer) Class.forName("android.app.ActivityManager")
                        .getMethod("getCurrentUser").invoke(null);
            } catch (Throwable ignored) {
                try {
                    userId = (Integer) android.os.UserHandle.class
                            .getMethod("getUserId", int.class)
                            .invoke(null, android.os.Process.myUid());
                } catch (Throwable ignored2) {
                }
            }

            Object userHandle = android.os.UserHandle.class
                    .getMethod("of", int.class).invoke(null, userId);

            mSystemContext.getClass()
                    .getMethod("startActivityAsUser",
                            android.content.Intent.class, android.os.UserHandle.class)
                    .invoke(mSystemContext, intent, userHandle);
            LogHelper.log(VerboseLevel.INFO, "AppKeyIPC: system startActivity user=",
                    String.valueOf(userId), " appKey=", appKey);
            return true;
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.WARNING, "AppKeyIPC: system launch failed appKey=", appKey,
                    " err=", e.getMessage());
            return false;
        }
    }

    /** Pull full IME profiles JSON via ContentProvider — called once at boot. */
    public String pullProfiles() {
        try {
            if (mConfigResolver == null) return "[]";
            Bundle result = mConfigResolver.call(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                    "getProfiles", null, null);
            if (result == null) return "[]";
            return result.getString("profiles_json", "[]");
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.WARNING, "pullProfiles failed:", e.getMessage());
            return "[]";
        }
    }

    /** Pull and consume the profile change delta queue (frontend-computed). */
    public String pullProfileChanges() {
        try {
            if (mConfigResolver == null) return "[]";
            Bundle result = mConfigResolver.call(
                    moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                    "getProfileChanges", null, null);
            if (result == null) return "[]";
            return result.getString("changes", "[]");
        } catch (Exception e) {
            return "[]";
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
