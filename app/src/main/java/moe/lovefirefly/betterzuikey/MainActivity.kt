package moe.lovefirefly.betterzuikey

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.color.DynamicColors
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityMainBinding
import moe.lovefirefly.betterzuikey.Utils.ZuiDetector

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val titles by lazy {
        arrayOf(
            getString(R.string.tab_home),
            getString(R.string.tab_shortcuts),
            getString(R.string.tab_templates),
            getString(R.string.tab_settings)
        )
    }
    /** 记录创建时应用的主题/语言状态，onResume 时检测变化并 recreate */
    private var appliedDynamicColor: Boolean = false
    private var appliedNightMode: Int = 0
    private var appliedLocaleTag: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        applyLocale()
        super.onCreate(savedInstanceState)
        val cfg = Config.load()
        appliedDynamicColor = cfg.dynamicColorEnabled
        appliedNightMode = cfg.nightMode
        appliedLocaleTag = cfg.localeOverride
        if (appliedDynamicColor) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 0=主页  1=快捷键  2=模板  3=设置
        val adapter = TabAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 3

        // 自动通过 su 授予 WRITE_SECURE_SETTINGS
        grantSecureSettings()

        // 首次启动 → 显示使用协议对话框
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (!prefs.getBoolean("agreement_shown", false)) {
            showAgreementDialog(
                onAccept = { prefs.edit().putBoolean("agreement_shown", true).apply() },
                canExit = true
            )
        }

        // 调试：长按标题栏 3s 弹出协议对话框（不可退出）
        var longPressRunnable: Runnable? = null
        binding.toolbar.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val r = Runnable { showAgreementDialog(onAccept = { }, canExit = false) }
                    longPressRunnable = r
                    binding.toolbar.postDelayed(r, 3000)
                    false  // 不消费，让 toolbar 正常处理（不影响点击、滚动等）
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { binding.toolbar.removeCallbacks(it) }
                    longPressRunnable = null
                    false
                }
                else -> false
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_home      -> 0
                R.id.nav_shortcuts -> 1
                R.id.nav_templates -> 2
                R.id.nav_settings  -> 3
                else -> return@setOnItemSelectedListener false
            }
            binding.viewPager.setCurrentItem(target, false)
            true
        }

        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNav.menu.getItem(position).isChecked = true
                binding.toolbar.title = titles[position]
            }
        })
    }

    /** 使用协议对话框：首次启动强制阅读帮助文档 */
    private fun showAgreementDialog(onAccept: () -> Unit, canExit: Boolean) {
        val msg = getString(R.string.agreement_body)

        val tv = android.widget.TextView(this).apply {
            setText(msg)
            setTextIsSelectable(false)
            setPadding(48, 32, 48, 0)
            setLineSpacing(4f, 1.1f)
            textSize = 15f
        }

        val titleView = android.widget.TextView(this).apply {
            text = getString(R.string.agreement_header)
            setTextColor(0xFF_D32F2F.toInt())
            setPadding(48, 16, 48, 0)
            textSize = 16f
            android.graphics.Typeface.DEFAULT_BOLD.also { setTypeface(it) }
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(titleView)
            addView(tv)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agreement_title))
            .setView(container)
            .setNegativeButton(if (canExit) getString(R.string.agreement_btn_exit) else getString(R.string.agreement_btn_close)) { _, _ ->
                if (canExit) finish()
            }
            .setPositiveButton(getString(R.string.agreement_btn_read_doc)) { _, _ ->
                onAccept()
                startActivity(android.content.Intent(this, HelpActivity::class.java))
            }
            .setCancelable(false)
            .create()

        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
            ?.setTextColor(0xFF_757575.toInt())
    }

    override fun onResume() {
        super.onResume()
        val cfg = Config.load()
        if (cfg.dynamicColorEnabled != appliedDynamicColor || cfg.nightMode != appliedNightMode || cfg.localeOverride != appliedLocaleTag) {
            recreate()
        }
    }

    private class TabAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 4
        override fun getItemId(position: Int): Long = position.toLong()
        override fun containsItem(itemId: Long): Boolean = itemId in 0L..3L
        override fun createFragment(pos: Int): Fragment = when (pos) {
            0 -> HomeFragment()
            1 -> GlobalFragment()
            2 -> TemplatesFragment()
            3 -> SettingsFragment()
            else -> throw IllegalArgumentException()
        }
    }

    /** 主页 Fragment */
    class HomeFragment : Fragment(R.layout.fragment_home) {
        override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            refreshStatus(view)
        }

        override fun onResume() {
            super.onResume()
            view?.let { refreshStatus(it) }
        }

        private fun refreshStatus(view: android.view.View) {
            val cfg = Config.load()
            val r = ZuiDetector.result
            view.findViewById<android.widget.TextView>(R.id.tv_status)?.text =
                getString(R.string.home_version_template)
            view.findViewById<android.widget.TextView>(R.id.tv_module_status)?.text =
                if (r.isZux) getString(R.string.home_module_status_active, r.detail)
                else getString(R.string.home_module_status_inactive_detail, r.detail)

            view.findViewById<android.view.View>(R.id.card_help)?.setOnClickListener {
                startActivity(android.content.Intent(requireContext(), HelpActivity::class.java))
            }
        }
    }

        companion object {
        /** 在 setContentView 之前调用，按配置应用夜间模式 */
        fun applyTheme() {
            val cfg = Config.load()
            val nightMode = when (cfg.nightMode) {
                1 -> AppCompatDelegate.MODE_NIGHT_YES
                2 -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }

        /** 在 setContentView 之前调用，按配置应用语言设置 */
        fun applyLocale() {
            LocaleHelper.applyFromConfig()
        }
    }

    private fun grantSecureSettings() {
        try {
            Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "pm grant moe.lovefirefly.betterzuikey android.permission.WRITE_SECURE_SETTINGS"
            ))
        } catch (_: Exception) { }
    }
}