package moe.lovefirefly.betterzuikey

import android.content.Context
import android.content.Intent
import moe.lovefirefly.betterzuikey.Utils.LogHelper

/**
 * Opens ZUI smart-key settings — same intent chain as
 * [com.zui.server.input.keyboard.key.policy.KeyboardZuiKeyInputPolicy.startKbAppShortcutPage].
 */
object AppKeySystemSettingsLauncher {

    private const val ACTION_KB_APP_FUNCTIONS = "com.zui.settings.KEYBOARD_APP_FUNCTIONS"
    private const val EXTRA_ITEM_KEY = "item_key"

    fun open(context: Context, appKey: String) {
        val itemKey = when (appKey) {
            "keyApp1" -> "shortcut_app1"
            "keyApp2" -> "shortcut_app2"
            else -> {
                LogHelper.log(LogHelper.VerboseLevel.WARNING, "AppKeySettings: invalid key=", appKey)
                return
            }
        }
        val intent = Intent(ACTION_KB_APP_FUNCTIONS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_ITEM_KEY, itemKey)
        }
        try {
            context.startActivity(intent)
            LogHelper.log(LogHelper.VerboseLevel.INFO, "AppKeySettings: startActivity item_key=", itemKey)
        } catch (e: Exception) {
            LogHelper.log(
                LogHelper.VerboseLevel.WARNING,
                "AppKeySettings: launch failed item_key=", itemKey,
                " err=", e.message ?: "",
            )
        }
    }
}
