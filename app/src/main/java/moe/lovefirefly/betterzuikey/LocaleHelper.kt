package moe.lovefirefly.betterzuikey

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Utils.LogHelper
import moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel
import java.util.Locale

/**
 * 应用内语言切换，与 Android 13+ 系统「应用语言」共用 [AppCompatDelegate.setApplicationLocales]。
 *
 * 语言映射：
 *   ""      → 跟随系统（空 LocaleList）
 *   "en-US" → English (US)
 *   "zh-CN" → 简体中文
 */
object LocaleHelper {

    data class LocaleEntry(
        /** BCP-47 语言标签，"" 表示跟随系统 */
        val tag: String
    )

    val ENTRIES = listOf(
        LocaleEntry(""),
        LocaleEntry("en-US"),
        LocaleEntry("zh-CN"),
    )

    fun getDisplayName(context: Context, tag: String): String = when (tag) {
        ""      -> context.getString(R.string.locale_follow_system)
        "en-US" -> context.getString(R.string.locale_en_us)
        "zh-CN" -> context.getString(R.string.locale_zh_cn)
        else    -> tag
    }

    /** 上次已应用的 Config tag（"" / en-US / zh-CN），避免重复 setApplicationLocales */
    private var lastAppliedTag: String? = null

    /** 应用内刚切换语言；AppCompat 会 recreate，onResume 勿再 sync/recreate */
    private var pendingUserLocaleChange = false

    /**
     * 读取 AppCompat / 系统 per-app 语言存储，映射为 Config 用的 tag。
     * 空列表 → ""（跟随系统）。
     */
    @JvmStatic
    fun tagFromAppLocales(): String {
        val list = AppCompatDelegate.getApplicationLocales()
        if (list.isEmpty) return ""
        val raw = list[0]?.toLanguageTag().orEmpty()
        return normalizeSupportedTag(raw)
    }

    /**
     * 将系统存储与 [Config.localeOverride] 对齐（仅用于进程冷启动或从系统设置返回）。
     * 勿在 [setApplicationLocales] 之后立即调用——存储可能尚未更新，会把 Config 写回旧值。
     */
    @JvmStatic
    fun syncFromSystemStorage(context: Context): String {
        val fromApp = tagFromAppLocales()
        val cfg = Config.load()
        LogHelper.log(
            VerboseLevel.DEBUG, "LocaleHelper: syncFromSystemStorage",
            " app=", fromApp.ifBlank { "(follow system)" },
            " cfg=", cfg.localeOverride.ifBlank { "(follow system)" }
        )
        if (cfg.localeOverride != fromApp) {
            cfg.localeOverride = fromApp
            cfg.save()
            Config.syncToSharedPrefs(context, cfg)
            LogHelper.log(
                VerboseLevel.INFO, "LocaleHelper: config updated from app locales →",
                fromApp.ifBlank { "(follow system)" }
            )
        }
        return fromApp
    }

    /**
     * 启动时应用语言。进程首次调用时与系统存储 reconciling；之后仅按 Config 应用（避免覆盖刚写入的值）。
     */
    @JvmStatic
    fun applyFromConfig(context: Context) {
        val cfgTag = Config.load().localeOverride
        val tag = if (lastAppliedTag == null && tagFromAppLocales() != cfgTag) {
            syncFromSystemStorage(context)
        } else {
            cfgTag
        }
        LogHelper.log(
            VerboseLevel.DEBUG, "LocaleHelper: applyFromConfig",
            " tag=", tag.ifBlank { "(follow system)" },
            " lastApplied=", lastAppliedTag?.ifBlank { "(follow system)" } ?: "null"
        )
        applyTag(tag)
    }

    /** 应用内下拉切换；写入 Config + setApplicationLocales（AppCompat 负责 recreate）。 */
    @JvmStatic
    fun applyAndRecreate(context: Context, tag: String) {
        LogHelper.log(
            VerboseLevel.INFO, "LocaleHelper: applyAndRecreate",
            tag.ifBlank { "(follow system)" }
        )
        pendingUserLocaleChange = true
        val cfg = Config.load()
        cfg.localeOverride = tag
        cfg.save()
        Config.syncToSharedPrefs(context, cfg)
        applyTag(tag, force = true)
    }

    /** onResume：若刚在应用内切换语言，跳过外部 sync，避免与 AppCompat recreate 打架。 */
    @JvmStatic
    fun consumePendingUserLocaleChange(): Boolean {
        if (!pendingUserLocaleChange) return false
        pendingUserLocaleChange = false
        LogHelper.log(VerboseLevel.DEBUG, "LocaleHelper: consume pending user locale change")
        return true
    }

    @JvmStatic
    fun currentEntryDisplay(context: Context, cfg: Config): String {
        return getDisplayName(context, cfg.localeOverride)
    }

    private fun applyTag(tag: String, force: Boolean = false) {
        if (!force && tag == lastAppliedTag) {
            LogHelper.log(VerboseLevel.DEBUG, "LocaleHelper: applyTag skipped (unchanged)")
            return
        }
        lastAppliedTag = tag
        LogHelper.log(
            VerboseLevel.DEBUG, "LocaleHelper: applyTag",
            tag.ifBlank { "(follow system)" }
        )

        if (tag.isBlank()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.create(Locale.forLanguageTag(tag))
            )
        }
    }

    private fun normalizeSupportedTag(raw: String): String = when {
        raw.isBlank() -> ""
        raw.startsWith("zh", ignoreCase = true) -> "zh-CN"
        raw.startsWith("en", ignoreCase = true) -> "en-US"
        else -> ""
    }
}
