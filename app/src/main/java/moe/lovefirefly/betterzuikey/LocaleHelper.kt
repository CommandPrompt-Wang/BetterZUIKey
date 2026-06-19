package moe.lovefirefly.betterzuikey

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import moe.lovefirefly.betterzuikey.Config.Config
import java.util.Locale

/**
 * 应用内语言切换工具。
 *
 * 语言映射表（语言代码 | 语言自称 | 英文名）：
 *   ""      → 跟随系统 (Follow System)
 *   "en-US" → English (English (US))
 *   "zh-CN" → 简体中文 (Chinese Simp.)
 *
 * 回退链：zh-HK → zh-CN → en
 * Android 7.0+ 会将 zh-HK (Hant) 和 zh-CN (Hans) 视为不同脚本，
 * 因此 zh-HK 设备选择"跟随系统"时不会自动回退到 values-zh-rCN/。
 * resolveSystemLocale() 强制所有 zh-* → zh-CN 来修复此问题。
 */
object LocaleHelper {

    data class LocaleEntry(
        /** BCP-47 语言标签，"" 表示跟随系统 */
        val tag: String,
        /** 下拉列表显示文本：语言自称 (English Name) */
        val displayName: String
    )

    /** 支持的语言列表，按显示顺序排列 */
    val ENTRIES = listOf(
        LocaleEntry("",      "跟随系统 (Follow System)"),
        LocaleEntry("en-US", "English (English (US))"),
        LocaleEntry("zh-CN", "简体中文 (Chinese Simp.)"),
    )

    /** 上次已应用的 effective tag，避免在 onCreate 入口重复 setApplicationLocales 导致循环 recreate */
    private var lastAppliedTag: String? = null

    // ── 公开 API ──

    /**
     * 从 Config 读取语言设置并应用到进程。
     * 在 Activity.onCreate() 的 super.onCreate() 之前调用。
     * 自动跳过已应用的语言，避免循环 recreate。
     */
    fun applyFromConfig() {
        val cfg = Config.load()
        val tag = if (cfg.localeOverride.isEmpty()) resolveSystemLocale() else cfg.localeOverride
        val effective = tag.ifBlank { "en" } // "" = follow system → fallback is English (values/)
        if (effective == lastAppliedTag) return  // already applied, skip to prevent cascading recreates
        lastAppliedTag = effective

        if (tag.isBlank()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            val locale = Locale.forLanguageTag(tag)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(locale))
        }
    }

    /**
     * 用户手动切换语言时调用。
     * 与 applyFromConfig 不同：始终强制执行（更新 lastAppliedTag），
     * AppCompatDelegate 会自动触发 Activity recreate。
     */
    fun applyAndRecreate(tag: String) {
        val effective = tag.ifBlank { "en" }
        lastAppliedTag = effective

        if (tag.isBlank()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            val locale = Locale.forLanguageTag(tag)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(locale))
        }
        // AppCompatDelegate.setApplicationLocales() triggers activity recreation internally.
        // Do NOT call recreate() ourselves — it would race with AppCompat's deferred apply.
    }

    /** 获取当前 Config 设置对应的显示文本 */
    fun currentEntryDisplay(cfg: Config): String {
        return ENTRIES.firstOrNull { it.tag == cfg.localeOverride }?.displayName
            ?: ENTRIES[0].displayName
    }

    // ── 内部 ──

    /**
     * 解析系统语言到我们支持的标签。
     *
     * 回退链：zh-* → zh-CN → "" (en via values/)
     * 原因：Android 7.0+ 将 zh-HK (Hant) 和 zh-CN (Hans) 视为不同脚本，
     * "跟随系统" 时 zh-HK 会直接跳到 values/ (en)，跳过 values-zh-rCN/。
     */
    private fun resolveSystemLocale(): String {
        val sysLang = Locale.getDefault().language
        // 所有中文变体统一用 zh-CN
        if (sysLang == "zh") return "zh-CN"
        // 其他语言：跟随系统（Android 资源回退到 values/）
        return ""
    }
}
