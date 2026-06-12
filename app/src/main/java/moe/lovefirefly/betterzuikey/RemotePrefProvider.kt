package moe.lovefirefly.betterzuikey

import com.crossbowffs.remotepreferences.RemotePreferenceFile
import com.crossbowffs.remotepreferences.RemotePreferenceProvider

/**
 * Exposes SharedPreferences to system_server via ContentProvider.
 * Used by MainHook to read OverrideMode changes instantly.
 */
class RemotePrefProvider : RemotePreferenceProvider(
    "${BuildConfig.APPLICATION_ID}.prefs",
    arrayOf(RemotePreferenceFile(PREF_FILE, true))
) {
    companion object {
        const val PREF_FILE = "betterzuikey_config"
    }
}
