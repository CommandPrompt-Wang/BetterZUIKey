package moe.lovefirefly.betterzuikey

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 最小化 ContentProvider — 仅用于 App → system_server 的跨进程配置变更通知。
 *
 * App 端 cfg.save() 后调用 contentResolver.notifyChange(RELOAD_URI, null)，
 * system_server 端的 ContentObserver 收到通知后热加载配置 JSON。
 *
 * 无需数据库存储，query/insert/update/delete 均为空操作。
 */
class ConfigSyncProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "moe.lovefirefly.betterzuikey.config"
        val RELOAD_URI: Uri = Uri.parse("content://$AUTHORITY/reload")
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
