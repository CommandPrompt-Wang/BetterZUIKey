package moe.lovefirefly.betterzuikey

import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityHelpBinding
import java.io.BufferedReader
import java.io.InputStreamReader

class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    /** app:// 协议 → Activity 映射 */
    private val pageMap = mapOf(
        "fnsettings"     to FnSettingsActivity::class.java,
        "keyboarddetect" to KeyboardDetectActivity::class.java,
        "profilemanage"  to ProfileManageActivity::class.java,
        "appearance"     to AppearanceSettingsActivity::class.java,
        "aosp"           to AospSettingsActivity::class.java,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupHelpContent()
    }

    /**
     * 根据当前语言环境加载对应的帮助文档。
     * 命名规则: help_{lang}.md（如 help_zh.md, help_en.md）
     * 回退链：locale exact → locale lang → en → 内置提示
     */
    private fun loadHelpForLocale(): String {
        val locale = java.util.Locale.getDefault()
        val lang = locale.language
        // 尝试 help_{lang}_{region}.md（如 help_zh_CN.md）
        val fullTag = locale.toLanguageTag().replace("-", "_").lowercase()
        val fullResId = resources.getIdentifier("help_$fullTag", "raw", packageName)
        if (fullResId != 0) {
            return resources.openRawResource(fullResId)
                .use { BufferedReader(InputStreamReader(it)).readText() }
        }
        // 尝试 help_{lang}.md（如 help_zh.md, help_en.md）
        val langResId = resources.getIdentifier("help_$lang", "raw", packageName)
        if (langResId != 0) {
            return resources.openRawResource(langResId)
                .use { BufferedReader(InputStreamReader(it)).readText() }
        }
        // 最终回退：English help_en.md
        val enResId = resources.getIdentifier("help_en", "raw", packageName)
        if (enResId != 0) {
            return resources.openRawResource(enResId)
                .use { BufferedReader(InputStreamReader(it)).readText() }
        }
        // 什么都没有 — 显示提示
        return getString(R.string.help_no_document)
    }

    private fun setupToolbar() {
        binding.toolbar.apply {
            setNavigationOnClickListener { finish() }
        }
    }

    private fun setupHelpContent() {
        val markwon = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .usePlugin(LinkifyPlugin.create())
            .build()

        val helpText = loadHelpForLocale()

        markwon.setMarkdown(binding.tvHelp, helpText)

        // 自定义 MovementMethod：拦截 app:// 链接，其余交给系统
        binding.tvHelp.movementMethod = object : LinkMovementMethod() {
            override fun onTouchEvent(widget: TextView, buffer: android.text.Spannable, event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) {
                    val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
                    val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
                    val layout = widget.layout
                    val line = layout.getLineForVertical(y)
                    val off = layout.getOffsetForHorizontal(line, x.toFloat())
                    val spans = buffer.getSpans(off, off, URLSpan::class.java)
                    if (spans.isNotEmpty()) {
                        val url = spans[0].url
                        if (url.startsWith("app://")) {
                            val page = url.removePrefix("app://")
                            pageMap[page]?.let {
                                startActivity(Intent(this@HelpActivity, it))
                            }
                            return true
                        }
                    }
                }
                return super.onTouchEvent(widget, buffer, event)
            }
        }
    }
}
