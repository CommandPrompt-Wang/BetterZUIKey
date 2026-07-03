package moe.lovefirefly.betterzuikey

import android.content.Context
import android.widget.Toast
import moe.lovefirefly.betterzuikey.Config.Config
import org.json.JSONObject
import java.net.URL

object UpdateChecker {

    private const val GITHUB1_API = "https://api.github.com/repos/CommandPrompt-Wang/BetterZUIKey/releases/latest"
    private const val GITHUB2_API = "https://api.github.com/repos/Xposed-Modules-Repo/moe.lovefirefly.betterzuikey/releases/latest"
    private const val PERSONAL_API = "https://lovefirefly.moe/moe.lovefirefly.betterzuikey/latest.json"

    fun check(context: Context, cfg: Config, showToast: Boolean = true): Result {
        val result = when (cfg.updateChannel) {
            Config.UpdateChannel.AUTO -> checkAuto()
            Config.UpdateChannel.GITHUB1 -> checkGitHub1()
            Config.UpdateChannel.GITHUB2 -> checkGitHub2()
            Config.UpdateChannel.PERSONAL -> checkPersonal()
        }
        if (showToast) {
            val msg = when (result) {
                is Result.Latest -> context.getString(R.string.update_latest)
                is Result.NewVersion -> context.getString(R.string.update_new_version, result.version)
                is Result.Failed -> context.getString(R.string.update_failed) + ": " + result.error
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
        return result
    }

    private fun checkAuto(): Result {
        val r1 = checkGitHub1()
        if (r1 !is Result.Failed) return r1
        val r2 = checkGitHub2()
        if (r2 !is Result.Failed) return r2
        val r3 = checkPersonal()
        if (r3 !is Result.Failed) return r3
        return r1
    }

    /**
     * Tag format: "{versionCode}-v{versionName}" e.g. "10-v1.3.0-beta4"
     * Legacy format (no prefix): "v{versionName}" e.g. "v1.3.0-beta3"
     */
    private fun checkGitHub1(): Result {
        return try {
            val obj = JSONObject(URL(GITHUB1_API).readText())
            val tag = obj.getString("tag_name")
            val (code, version) = parseTag(tag)
            if (isNewer(code, version)) Result.NewVersion(version, obj.optString("html_url"))
            else Result.Latest
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Unknown error")
        }
    }

    /**
     * Tag format: "{versionCode}-{versionName}" e.g. "10-1.3.0-beta4"
     */
    private fun checkGitHub2(): Result {
        return try {
            val obj = JSONObject(URL(GITHUB2_API).readText())
            val tag = obj.getString("tag_name")
            val (code, version) = parseTag(tag)
            if (isNewer(code, version)) Result.NewVersion(version, obj.optString("html_url"))
            else Result.Latest
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Unknown error")
        }
    }

    private fun checkPersonal(): Result {
        return try {
            val obj = JSONObject(URL(PERSONAL_API).readText())
            val version = obj.getString("version")
            val code = obj.optInt("versionCode", 0)
            if (isNewer(code, version)) Result.NewVersion(version, obj.optString("download"))
            else Result.Latest
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Unknown error")
        }
    }

    /** Parse tag like "10-v1.3.0-beta4" or "v1.3.0-beta3" → Pair(versionCode, versionName) */
    private fun parseTag(tag: String): Pair<Int, String> {
        val dashIdx = tag.indexOf('-')
        return if (dashIdx > 0 && tag.substring(0, dashIdx).all { it.isDigit() }) {
            val code = tag.substring(0, dashIdx).toInt()
            val version = tag.substring(dashIdx + 1).removePrefix("v")
            Pair(code, version)
        } else {
            // Legacy format: no versionCode prefix
            Pair(0, tag.removePrefix("v"))
        }
    }

    /** Compare using versionCode if available, otherwise parse versionName segments */
    private fun isNewer(remoteCode: Int, remoteVersion: String): Boolean {
        if (remoteCode > 0) return BuildConfig.VERSION_CODE < remoteCode
        // Fallback for legacy tags without versionCode
        val r = remoteVersion.replace("-beta", ".").replace("-rc", ".").split(".").mapNotNull { it.toIntOrNull() }
        val l = BuildConfig.VERSION_NAME.replace("-beta", ".").replace("-rc", ".").split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    sealed class Result {
        data object Latest : Result()
        data class NewVersion(val version: String, val url: String = "") : Result()
        data class Failed(val error: String) : Result()
    }
}
