package moe.lovefirefly.betterzuikey

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import moe.lovefirefly.betterzuikey.Utils.LogHelper

/**
 * ContentProvider for App → system_server cross-process config sync via Binder IPC.
 *
 * Bypasses file permission / SELinux issues that plague XSharedPreferences file polling.
 *
 * App side: cfg.save() + Config.syncToSharedPrefs() writes JSON to SharedPreferences,
 *   then calls contentResolver.call(RELOAD_URI, "notifySync", null, null) to push.
 *
 * system_server side: calls contentResolver.call(RELOAD_URI, "getSync", null, null)
 *   to pull the latest JSON via Binder IPC (no file access needed).
 */
class ConfigSyncProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "moe.lovefirefly.betterzuikey.config"
        @JvmField
        val RELOAD_URI: Uri = Uri.parse("content://$AUTHORITY/reload")
        const val METHOD_GET_SYNC = "getSync"
        const val METHOD_NOTIFY_SYNC = "notifySync"
        const val METHOD_SET_CONFIG = "setConfig"
        const val METHOD_BOOT_MARK = "bootMark"
        const val METHOD_BOOT_MARK_APP = "bootMarkApp"
        const val METHOD_ESC_CHECK_RESULT = "escCheckResult"
        const val METHOD_SET_KEYBOARD_DETECT = "setKeyboardDetect"
        const val METHOD_GET_KEYBOARD_DETECT = "getKeyboardDetect"
        const val METHOD_RUN_APP_KEY_COMMAND = "runAppKeyCommand"
        const val METHOD_OPEN_APP_KEY_EDITOR = "openAppKeyCommandEditor"
        const val KEY_OPEN_APP_KEY_EDITOR = "open_app_key_editor"
        const val KEY_CONFIG_JSON = "config_json"
        const val KEY_BOOT_TIME = "boot_time"
        const val KEY_BOOT_TIME_APP = "boot_time_app"
        const val KEY_ESC_RESULT = "esc_check_result"
        const val KEY_KEYBOARD_DETECT = "keyboard_detect_active"
        const val KEY_APP_KEY_SCRIPT = "app_key_script"
        const val KEY_APP_KEY_ROOT = "app_key_root"
        const val KEY_APP_KEY_SINGLETON = "app_key_singleton"
        const val KEY_APP_KEY_TIMEOUT_MIN = "app_key_timeout_min"
        private const val PREF_KEYBOARD_DETECT = "keyboard_detect_active"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            METHOD_GET_SYNC -> {
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                val json = prefs?.getString("config_sync", "") ?: ""
                Bundle().apply { putString(KEY_CONFIG_JSON, json) }
            }
            METHOD_NOTIFY_SYNC -> {
                context?.contentResolver?.notifyChange(RELOAD_URI, null)
                null
            }
            METHOD_SET_CONFIG -> {
                val json = extras?.getString(KEY_CONFIG_JSON) ?: return@call null
                context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                    ?.edit()?.putString("config_sync", json)?.commit()
                null
            }
            METHOD_BOOT_MARK -> {
                val bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
                context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                    ?.edit()?.putLong(KEY_BOOT_TIME, bootTime)?.commit()
                null
            }
            METHOD_BOOT_MARK_APP -> {
                // Written when hooks run in a non-system_server process (wrong scope).
                // Lets the UI show yellow instead of red.
                val bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
                context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                    ?.edit()?.putLong(KEY_BOOT_TIME_APP, bootTime)?.commit()
                null
            }
            METHOD_ESC_CHECK_RESULT -> {
                val detected = extras?.getBoolean(KEY_ESC_RESULT, false) ?: false
                context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                    ?.edit()?.putBoolean(KEY_ESC_RESULT, detected)
                    ?.putBoolean("esc_check_requested", false)
                    ?.apply()
                null
            }
            METHOD_SET_KEYBOARD_DETECT -> {
                val active = extras?.getBoolean(KEY_KEYBOARD_DETECT, false) ?: false
                context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                    ?.edit()?.putBoolean(PREF_KEYBOARD_DETECT, active)?.commit()
                null
            }
            METHOD_GET_KEYBOARD_DETECT -> {
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                val active = prefs?.getBoolean(PREF_KEYBOARD_DETECT, false) ?: false
                Bundle().apply { putBoolean(KEY_KEYBOARD_DETECT, active) }
            }
            METHOD_RUN_APP_KEY_COMMAND -> {
                val script = extras?.getString(KEY_APP_KEY_SCRIPT) ?: return@call null
                val root = extras?.getBoolean(KEY_APP_KEY_ROOT, false) ?: false
                val singleton = extras?.getBoolean(KEY_APP_KEY_SINGLETON, true) ?: true
                val timeoutMin = extras?.getInt(KEY_APP_KEY_TIMEOUT_MIN, 1) ?: 1
                val ctx = context ?: return@call null
                AppKeyCommandExecutor.runAsync(ctx, script, root, singleton, timeoutMin)
                null
            }
            METHOD_OPEN_APP_KEY_EDITOR -> {
                val appKey = extras?.getString(KEY_OPEN_APP_KEY_EDITOR) ?: run {
                    LogHelper.log(LogHelper.VerboseLevel.WARNING, "AppKeyEditor: IPC missing appKey")
                    return@call null
                }
                if (appKey != "keyApp1" && appKey != "keyApp2") {
                    LogHelper.log(LogHelper.VerboseLevel.WARNING, "AppKeyEditor: IPC invalid appKey=", appKey)
                    return@call null
                }
                val ctx = context ?: run {
                    LogHelper.log(LogHelper.VerboseLevel.WARNING, "AppKeyEditor: IPC context null")
                    return@call null
                }
                LogHelper.log(LogHelper.VerboseLevel.INFO, "AppKeyEditor: IPC received appKey=", appKey)
                AppKeyEditorLauncher.open(ctx, appKey)
                null
            }
            "getEscRequest" -> {
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                val requested = prefs?.getBoolean("esc_check_requested", false) ?: false
                Bundle().apply { putBoolean("requested", requested) }
            }
            "setEscRequest" -> {
                context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                    ?.edit()?.putBoolean("esc_check_requested", true)?.apply()
                null
            }
            "appendSysWriteQueue" -> {
                // App 追加写入请求（通过 CP 而非直接写 SP，避免与 drain 竞态）
                val key = extras?.getString("key") ?: return@call null
                val value = extras?.getInt("val", -1) ?: -1
                if (value < 0) return@call null
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                val oldQ = prefs?.getString("sys_write_queue", "[]") ?: "[]"
                val entry = "{\"k\":\"$key\",\"v\":$value}"
                val newQ = if (oldQ == "[]") "[$entry]" else oldQ.dropLast(1) + ",$entry]"
                prefs?.edit()?.putString("sys_write_queue", newQ)?.apply()
                null
            }
            "getLsposedOpenRequest" -> {
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                val requested = prefs?.getBoolean("lsposed_open_requested", false) ?: false
                Bundle().apply { putBoolean("requested", requested) }
            }
            "setLsposedOpenRequest" -> {
                val requested = extras?.getBoolean("requested", true) ?: true
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                if (requested) prefs?.edit()?.putBoolean("lsposed_open_requested", true)?.apply()
                else prefs?.edit()?.remove("lsposed_open_requested")?.apply()
                null
            }
            "setSysWriteAlert" -> {
                context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                    ?.edit()?.putBoolean("sys_write_alert", true)?.apply()
                null
            }
            "getGrantSecureRequest" -> {
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                Bundle().apply { putBoolean("requested",
                    prefs?.contains("grant_secure_requested") ?: false) }
            }
            "setGrantSecureRequest" -> {
                context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                    ?.edit()?.putBoolean("grant_secure_requested", true)?.apply()
                null
            }
            "clearGrantSecureRequest" -> {
                context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                    ?.edit()?.remove("grant_secure_requested")?.apply()
                null
            }
            "setGrantTermuxRequest" -> {
                context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                    ?.edit()?.putBoolean("grant_termux_requested", true)?.apply()
                null
            }
            "getGrantTermuxRequest" -> {
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                Bundle().apply { putBoolean("requested",
                    prefs?.getBoolean("grant_termux_requested", false) ?: false) }
            }
            "clearGrantTermuxRequest" -> {
                context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                    ?.edit()?.remove("grant_termux_requested")?.apply()
                null
            }
            "drainSysWriteQueue" -> {
                // system_server 原子读+清空（与 appendSysWriteQueue 都在 CP call() 内串行化）
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                val json = prefs?.getString("sys_write_queue", "[]") ?: "[]"
                prefs?.edit()?.remove("sys_write_queue")?.commit()
                Bundle().apply { putString("queue", json) }
            }
            "getProfiles" -> {
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                val json = prefs?.getString("ime_profiles", "[]") ?: "[]"
                Bundle().apply { putString("profiles_json", json) }
            }
            // Consume and return the delta queue, then clear it
            "getProfileChanges" -> {
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                val json = prefs?.getString("ime_changes", "[]") ?: "[]"
                if (json != "[]") {
                    prefs?.edit()?.putString("ime_changes", "[]")?.commit()
                }
                Bundle().apply { putString("changes", json) }
            }
            // Append a change operation to the queue
            "appendProfileChange" -> {
                val changeJson = extras?.getString("change") ?: return@call null
                val prefs = context?.getSharedPreferences(
                    RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                val old = prefs?.getString("ime_changes", "[]") ?: "[]"
                val entry = if (old == "[]") "[$changeJson]" else old.dropLast(1) + ",$changeJson]"
                prefs?.edit()?.putString("ime_changes", entry)?.commit()
                null
            }
            else -> null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
