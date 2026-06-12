package moe.lovefirefly.betterzuikey

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

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
        const val KEY_CONFIG_JSON = "config_json"
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
