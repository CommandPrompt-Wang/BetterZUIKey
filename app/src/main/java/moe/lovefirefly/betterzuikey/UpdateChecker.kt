package moe.lovefirefly.betterzuikey

import android.content.Context
import android.content.Intent
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import moe.lovefirefly.betterzuikey.Config.Config
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val GITHUB1_API = "https://api.github.com/repos/CommandPrompt-Wang/BetterZUIKey/releases/latest"
    private const val GITHUB2_API = "https://api.github.com/repos/Xposed-Modules-Repo/moe.lovefirefly.betterzuikey/releases/latest"
    private const val PERSONAL_API = "https://lovefirefly.moe/moe.lovefirefly.betterzuikey/latest.json"
    private const val PERSONAL_BASE = "https://lovefirefly.moe"

    private const val GITHUB1_RELEASE = "https://github.com/CommandPrompt-Wang/BetterZUIKey/releases/latest"
    private const val GITHUB2_RELEASE = "https://github.com/Xposed-Modules-Repo/moe.lovefirefly.betterzuikey/releases/latest"
    private const val PERSONAL_URL = "https://lovefirefly.moe/posts/betterzuikey/"

    /** All download source URLs (for fallback dialog) */
    val ALL_DOWNLOAD_URLS = listOf(GITHUB1_RELEASE, GITHUB2_RELEASE, PERSONAL_URL)

    /**
     * Check for update and show a dialog if new version found.
     * @param context Activity context for showing dialog
     * @param cfg Current config (will be modified if user clicks "忽略")
     * @param showToast If true and no new version, show toast for latest/failed
     */
    fun check(context: Context, cfg: Config, showToast: Boolean = true) {
        val result = when (cfg.updateChannel) {
            Config.UpdateChannel.AUTO -> checkAuto()
            Config.UpdateChannel.GITHUB1 -> checkGitHub1()
            Config.UpdateChannel.GITHUB2 -> checkGitHub2()
            Config.UpdateChannel.PERSONAL -> checkPersonal()
        }
        when (result) {
            is Result.Latest -> {
                if (showToast) {
                    runOnUi(context) {
                        Toast.makeText(context, context.getString(R.string.update_latest), Toast.LENGTH_LONG).show()
                    }
                }
            }
            is Result.NewVersion -> {
                runOnUi(context) {
                    showUpdateDialog(context, cfg, result)
                }
            }
            is Result.Failed -> {
                if (showToast) {
                    runOnUi(context) {
                        showCheckFailedDialog(context, result)
                    }
                }
            }
        }
    }

    /**
     * Debug: 强制弹出更新对话框（Latest 时伪装成有新版本，Failed 时 Toast）
     */
    fun debugForceDialog(context: Context, cfg: Config) {
        val result = when (cfg.updateChannel) {
            Config.UpdateChannel.AUTO -> checkAuto()
            Config.UpdateChannel.GITHUB1 -> checkGitHub1()
            Config.UpdateChannel.GITHUB2 -> checkGitHub2()
            Config.UpdateChannel.PERSONAL -> checkPersonal()
        }
        runOnUi(context) {
            when (result) {
                is Result.Latest -> {
                    // 伪装：把当前版本当作"新版本"弹出对话框
                    showUpdateDialog(context, cfg,
                        Result.NewVersion(BuildConfig.VERSION_NAME, ""))
                }
                is Result.NewVersion -> {
                    showUpdateDialog(context, cfg, result)
                }
                is Result.Failed -> {
                    showCheckFailedDialog(context, result)
                }
            }
        }
    }

    /**
     * Show the update dialog with channel Spinner and three buttons: 确定 / 忽略 / 取消.
     */
    private fun showUpdateDialog(context: Context, cfg: Config, newVer: Result.NewVersion) {
        val ctx = context
        val channels = listOf(
            ctx.getString(R.string.settings_update_channel_auto),
            ctx.getString(R.string.settings_update_channel_github1),
            ctx.getString(R.string.settings_update_channel_github2),
            ctx.getString(R.string.settings_update_channel_personal),
        )
        val currentChannelIdx = when (cfg.updateChannel) {
            Config.UpdateChannel.AUTO -> 0
            Config.UpdateChannel.GITHUB1 -> 1
            Config.UpdateChannel.GITHUB2 -> 2
            Config.UpdateChannel.PERSONAL -> 3
        }

        val spinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, channels)
            setSelection(currentChannelIdx)
            setPadding(0, dp2px(ctx, 8), 0, dp2px(ctx, 8))
        }

        val label = TextView(ctx).apply {
            text = ctx.getString(R.string.update_dialog_source_label)
            setPadding(dp2px(ctx, 4), 0, 0, dp2px(ctx, 4))
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(ctx, 24), dp2px(ctx, 8), dp2px(ctx, 24), 0)
            addView(label)
            addView(spinner)
        }

        fun selectedChannel(): Config.UpdateChannel = when (spinner.selectedItemPosition) {
            0 -> Config.UpdateChannel.AUTO
            1 -> Config.UpdateChannel.GITHUB1
            2 -> Config.UpdateChannel.GITHUB2
            else -> Config.UpdateChannel.PERSONAL
        }

        val msg = ctx.getString(R.string.update_dialog_message, newVer.version)

        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.update_dialog_title))
            .setMessage(msg)
            .setView(container)
            .setPositiveButton(ctx.getString(R.string.update_dialog_confirm)) { _, _ ->
                startDownload(context, selectedChannel(), newVer.downloadUrl)
            }
            .setNeutralButton(ctx.getString(R.string.update_dialog_ignore)) { _, _ ->
                cfg.updateCheckOnStartup = false
                cfg.save()
                Config.syncToSharedPrefs(ctx, cfg)
            }
            .setNegativeButton(ctx.getString(R.string.update_dialog_cancel), null)
            .setCancelable(true)
            .show()
    }

    /**
     * Start downloading the APK with a progress dialog, then install when complete.
     */
    private fun startDownload(context: Context, channel: Config.UpdateChannel, preFetchedUrl: String? = null) {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        // Build progress views
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val tvTime = TextView(context).apply { textSize = 13f }
        val tvSpeed = TextView(context).apply { textSize = 13f }
        val tvSize = TextView(context).apply { textSize = 13f }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(context, 24), dp2px(context, 8), dp2px(context, 24), 0)
            addView(bar)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tvTime, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(tvSpeed, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
            addView(tvSize)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.update_downloading))
            .setView(container)
            .setNegativeButton(context.getString(R.string.update_dialog_cancel)) { _, _ ->
                cancelled.set(true)
            }
            .setCancelable(false)
            .create()
        dialog.show()

        Thread {
            val startTime = System.currentTimeMillis()
            val downloadResult = downloadApk(channel, context, preFetchedUrl, cancelled = { cancelled.get() }) { downloaded, total ->
                val elapsed = System.currentTimeMillis() - startTime
                runOnUi(context) {
                    if (total > 0) {
                        bar.progress = (downloaded * 100 / total).toInt()
                        bar.isIndeterminate = false
                        val eta = if (downloaded > 0) elapsed * total / downloaded - elapsed else 0L
                        tvTime.text = "${formatTime(elapsed)} / ${formatTime(elapsed + eta)}"
                        val bps = if (elapsed > 0) downloaded * 1000 / elapsed else 0L
                        tvSpeed.text = if (elapsed > 0) "${formatSize(bps)}/s" else ""
                        tvSize.text = "${formatSize(downloaded)} / ${formatSize(total)}"
                    } else {
                        bar.isIndeterminate = true
                        tvTime.text = formatTime(elapsed)
                        val bps = if (elapsed > 0) downloaded * 1000 / elapsed else 0L
                        tvSpeed.text = if (elapsed > 0) "${formatSize(bps)}/s" else ""
                        tvSize.text = formatSize(downloaded)
                    }
                }
           }

            runOnUi(context) {
                dialog.dismiss()
                if (cancelled.get()) return@runOnUi
                when (downloadResult) {
                    is DownloadResult.Success -> installApk(context, downloadResult.file)
                    is DownloadResult.Failed -> showDownloadFailedDialog(context, channel, downloadResult)
                }
            }
        }.start()
    }

    /**
     * Download APK. If channel is AUTO, try all three channels.
     * @param cancelled check if download was cancelled
     * @param onProgress callback with (downloadedBytes, totalBytes)
     */
    private fun downloadApk(
        channel: Config.UpdateChannel,
        context: Context,
        preFetchedUrl: String? = null,
        cancelled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): DownloadResult {
        // Try pre-fetched URL first (avoid duplicate API call)
        if (preFetchedUrl != null) {
            try {
                val file = downloadToFile(context, preFetchedUrl, cancelled, onProgress)
                if (file != null) return DownloadResult.Success(file)
            } catch (e: Exception) {
                // Fall through to channel-based download
            }
        }

        val channels = when (channel) {
            Config.UpdateChannel.AUTO -> listOf(
                Config.UpdateChannel.GITHUB1,
                Config.UpdateChannel.GITHUB2,
                Config.UpdateChannel.PERSONAL
            )
            else -> listOf(channel)
        }

        var lastError = ""
        var lastStackTrace: String? = null
        for (ch in channels) {
            if (cancelled()) return DownloadResult.Failed("Cancelled")
            val (apkUrl, rawJson) = getApkDownloadUrl(ch)
            if (apkUrl == null) {
                // DEBUG: include raw JSON in error
                val jsonInfo = if (rawJson != null) "\n[DEBUG JSON: $rawJson]" else ""
                lastError = "No APK asset found for channel $ch$jsonInfo"
                continue
            }
            try {
                val file = downloadToFile(context, apkUrl, cancelled, onProgress)
                if (file != null) return DownloadResult.Success(file)
                lastError = "Downloaded file is empty"
            } catch (e: Exception) {
                lastError = e.message ?: ""
                lastStackTrace = getStackTrace(e)
            }
        }
        return DownloadResult.Failed(lastError.ifBlank { "Unknown error" }, lastStackTrace)
    }

    /**
     * Get the direct APK download URL for a given channel.
     * DEBUG: also returns raw JSON for troubleshooting.
     */
    private fun getApkDownloadUrl(channel: Config.UpdateChannel): Pair<String?, String?> {
        return try {
            when (channel) {
                Config.UpdateChannel.GITHUB1 -> {
                    val raw = httpGet(GITHUB1_API)
                    val obj = JSONObject(raw)
                    Pair(findApkAsset(obj), raw)
                }
                Config.UpdateChannel.GITHUB2 -> {
                    val raw = httpGet(GITHUB2_API)
                    val obj = JSONObject(raw)
                    Pair(findApkAsset(obj), raw)
                }
                Config.UpdateChannel.PERSONAL -> {
                    val raw = httpGet(PERSONAL_API)
                    val obj = JSONObject(raw)
                    val dl = obj.optString("download", "").takeIf { it.isNotEmpty() }
                    val url = if (dl != null && dl.startsWith("/")) PERSONAL_BASE + dl else dl
                    Pair(url, raw)
                }
                Config.UpdateChannel.AUTO -> {
                    val r = getApkDownloadUrl(Config.UpdateChannel.GITHUB1)
                    if (r.first != null) r
                    else getApkDownloadUrl(Config.UpdateChannel.GITHUB2).let { if (it.first != null) it else r }
                }
            }
        } catch (e: Exception) {
            Pair(null, e.message)
        }
    }

    /**
     * Find APK download URL from GitHub release assets JSON.
     */
    private fun findApkAsset(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk")) {
                return asset.optString("browser_download_url", "").takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /**
     * Download file to cache/apk/ directory with progress and cancellation support.
     */
    private fun downloadToFile(
        context: Context,
        url: String,
        cancelled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File? {
        val dir = File(context.cacheDir, "apk")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "update.apk")

        val connection = URL(url).openConnection()
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.connect()
        val total = connection.contentLengthLong

        connection.getInputStream().use { input ->
            file.outputStream().use { output ->
                val buf = ByteArray(8192)
                var downloaded = 0L
                var lastReport = 0L
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } != -1) {
                    if (cancelled()) return null
                    output.write(buf, 0, bytesRead)
                    downloaded += bytesRead
                    // Throttle UI updates to ~4 per second
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        onProgress(downloaded, if (total > 0) total else downloaded)
                        lastReport = now
                    }
                }
                onProgress(downloaded, if (total > 0) total else downloaded)
            }
        }
        return if (file.exists() && file.length() > 0) file else null
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val min = s / 60
        val sec = s % 60
        return "%02d:%02d".format(min, sec)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    /**
     * Install APK via FileProvider URI.
     */
    private fun installApk(context: Context, file: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            showDownloadFailedDialog(context, Config.UpdateChannel.AUTO,
                DownloadResult.Failed(e.message ?: "", getStackTrace(e)))
        }
    }

    /**
     * Show fallback dialog with error reason, channel selector, and all download links.
     * [重试] re-downloads with the selected channel; [关闭] dismisses.
     */
    private fun showDownloadFailedDialog(context: Context, channel: Config.UpdateChannel, result: DownloadResult.Failed) {
        val channels = listOf(
            context.getString(R.string.settings_update_channel_auto),
            context.getString(R.string.settings_update_channel_github1),
            context.getString(R.string.settings_update_channel_github2),
            context.getString(R.string.settings_update_channel_personal),
        )
        val currentIdx = when (channel) {
            Config.UpdateChannel.AUTO -> 0
            Config.UpdateChannel.GITHUB1 -> 1
            Config.UpdateChannel.GITHUB2 -> 2
            Config.UpdateChannel.PERSONAL -> 3
        }

        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, channels)
            setSelection(currentIdx)
            setPadding(0, dp2px(context, 4), 0, dp2px(context, 4))
        }
        fun selectedChannel(): Config.UpdateChannel = when (spinner.selectedItemPosition) {
            1 -> Config.UpdateChannel.GITHUB1
            2 -> Config.UpdateChannel.GITHUB2
            3 -> Config.UpdateChannel.PERSONAL
            else -> Config.UpdateChannel.AUTO
        }

        val rawReason = result.error.ifBlank { null }
        val reason = rawReason?.let { translateRateLimit(context, it) }
        val sb = StringBuilder()
        if (reason != null) {
            sb.appendLine(context.getString(R.string.update_download_failed_message, reason))
        } else {
            sb.appendLine(context.getString(R.string.update_download_failed_title))
            sb.appendLine(result.stackTrace ?: "")
        }
        sb.appendLine()
        sb.appendLine(context.getString(R.string.update_download_failed_links))
        sb.append(ALL_DOWNLOAD_URLS.joinToString("\n"))

        val textView = TextView(context).apply {
            text = sb.toString()
            setTextIsSelectable(true)
            textSize = 15f
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(context, 24), dp2px(context, 8), dp2px(context, 24), 0)
            addView(textView)
            addView(spinner)
        }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.update_download_failed_title))
            .setView(container)
            .setPositiveButton(context.getString(R.string.update_download_retry)) { _, _ ->
                startDownload(context, selectedChannel())
            }
            .setNegativeButton(context.getString(R.string.update_download_close), null)
            .setCancelable(true)
            .show()
    }

    // ---- Internal check methods ----

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
            val obj = JSONObject(httpGet(GITHUB1_API))
            val tag = obj.getString("tag_name")
            val (code, version) = parseTag(tag)
            if (isNewer(code, version)) {
                val apkUrl = findApkAsset(obj)
                Result.NewVersion(version, obj.optString("html_url"), apkUrl)
            }
            else Result.Latest
        } catch (e: Exception) {
            Result.Failed(e.message ?: "", getStackTrace(e))
        }
    }

    /**
     * Tag format: "{versionCode}-{versionName}" e.g. "10-1.3.0-beta4"
     */
    private fun checkGitHub2(): Result {
        return try {
            val obj = JSONObject(httpGet(GITHUB2_API))
            val tag = obj.getString("tag_name")
            val (code, version) = parseTag(tag)
            if (isNewer(code, version)) {
                val apkUrl = findApkAsset(obj)
                Result.NewVersion(version, obj.optString("html_url"), apkUrl)
            }
            else Result.Latest
        } catch (e: Exception) {
            Result.Failed(e.message ?: "", getStackTrace(e))
        }
    }

    private fun checkPersonal(): Result {
        return try {
            val obj = JSONObject(httpGet(PERSONAL_API))
            val version = obj.getString("version")
            val code = obj.optInt("versionCode", 0)
            val dl = obj.optString("download", "").takeIf { it.isNotEmpty() }
            val apkUrl = if (dl != null && dl.startsWith("/")) PERSONAL_BASE + dl else dl
            if (isNewer(code, version)) Result.NewVersion(version, dl ?: "", apkUrl)
            else Result.Latest
        } catch (e: Exception) {
            Result.Failed(e.message ?: "", getStackTrace(e))
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

    private fun runOnUi(context: Context, block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun dp2px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    /** If the error is a GitHub rate limit, return a user-friendly message. */
    private fun translateRateLimit(context: Context, error: String): String {
        return if (error.contains("API rate limit exceeded"))
            context.getString(R.string.update_ratelimit_hint) else error
    }

    /** HTTP GET with User-Agent (GitHub API requires it). */
    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "BetterZUIKey")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val code = conn.responseCode
        if (code in 200..299) {
            return conn.inputStream.bufferedReader().readText()
        } else {
            val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw java.lang.RuntimeException("HTTP $code: $body")
        }
    }

    private fun getStackTrace(e: Exception): String? {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString().takeIf { it.isNotBlank() }
    }

    /**
     * Show error dialog when update check fails.
     * Common errors show the reason; uncommon/empty show "未知" + stack trace.
     */
    private fun showCheckFailedDialog(context: Context, failed: Result.Failed) {
        val rawReason = failed.error.ifBlank { null }
        val reason = rawReason?.let { translateRateLimit(context, it) }
        val msg = if (reason != null) {
            context.getString(R.string.update_check_failed_reason, reason)
        } else {
            context.getString(R.string.update_check_failed_reason,
                context.getString(R.string.update_check_failed_unknown))
        }
        val fullMsg = if (reason != null) {
            msg
        } else {
            msg + "\n\n" + context.getString(R.string.update_check_failed_stack,
                failed.stackTrace ?: "")
        }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.update_check_failed_title))
            .setMessage(fullMsg)
            .setPositiveButton(context.getString(R.string.dialog_confirm_ok), null)
            .setCancelable(true)
            .show()
    }

    // ---- Result types ----

    sealed class Result {
        data object Latest : Result()
        data class NewVersion(val version: String, val url: String = "", val downloadUrl: String? = null) : Result()
        data class Failed(val error: String, val stackTrace: String? = null) : Result()
    }

    private sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Failed(val error: String, val stackTrace: String? = null) : DownloadResult()
    }
}
