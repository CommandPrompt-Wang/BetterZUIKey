package moe.lovefirefly.betterzuikey

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object TermuxPermissionHelper {

    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    enum class GrantResult {
        SUCCESS,
        UNCERTAIN,
        FAILED,
    }

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun adbGrantCommand(packageName: String): String =
        "adb shell pm grant $packageName $PERMISSION"

    fun suGrantCommand(packageName: String): String =
        "su -c 'pm grant $packageName $PERMISSION'"

    /** Try {@code su -c pm grant …}. Returns null when su succeeds and permission is granted. */
    fun tryGrantViaSu(context: Context): String? {
        val cmd = "pm grant ${context.packageName} $PERMISSION"
        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().use { it.readText().trim() }
            val exit = proc.waitFor()
            when {
                exit == 0 && isGranted(context) -> null
                exit == 0 -> output.ifEmpty { "su ok but permission not granted" }
                else -> output.ifEmpty { "su exit=$exit" }
            }
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    fun requestGrantViaProxy(context: Context) {
        context.contentResolver.call(
            ConfigSyncProvider.RELOAD_URI,
            "setGrantTermuxRequest",
            null,
            null,
        )
    }
}
