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

        val helpText = resources.openRawResource(R.raw.help)
            .use { BufferedReader(InputStreamReader(it)).readText() }

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
