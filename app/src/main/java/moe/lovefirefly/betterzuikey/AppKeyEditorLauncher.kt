package moe.lovefirefly.betterzuikey

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import moe.lovefirefly.betterzuikey.Utils.LogHelper

/** Start MainActivity + script editor from hook IPC (may run while app is background). */
object AppKeyEditorLauncher {

    fun open(context: Context, appKey: String) {
        if (appKey != "keyApp1" && appKey != "keyApp2") {
            LogHelper.log(LogHelper.VerboseLevel.WARNING, "AppKeyEditor: invalid key=", appKey)
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            putExtra(AppKeyCommandDialog.EXTRA_OPEN_APP_KEY, appKey)
        }
        try {
            context.startActivity(intent)
            LogHelper.log(LogHelper.VerboseLevel.INFO, "AppKeyEditor: startActivity key=", appKey)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val pi = PendingIntent.getActivity(
                        context,
                        appKey.hashCode(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                        )
                        .toBundle()
                    pi.send(null, 0, null, null, null, null, opts)
                    LogHelper.log(LogHelper.VerboseLevel.INFO, "AppKeyEditor: PendingIntent fallback key=", appKey)
                } catch (e2: Exception) {
                    LogHelper.log(LogHelper.VerboseLevel.WARNING, "AppKeyEditor: launch failed key=", appKey, " err=", e2.message ?: "")
                }
            } else {
                LogHelper.log(LogHelper.VerboseLevel.WARNING, "AppKeyEditor: launch failed key=", appKey, " err=", e.message ?: "")
            }
        }
    }
}
