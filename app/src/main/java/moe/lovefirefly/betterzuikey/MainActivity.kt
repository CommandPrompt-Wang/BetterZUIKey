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

    /** 可刷新页面的统一接口 */
    interface Refreshable {
        fun triggerRefresh()
    }

    private lateinit var binding: ActivityMainBinding
    /** 用于页面切换 / onResume 时触发当前可刷新页面的刷新 */
    private fun refreshCurrentPage() {
        val pos = binding.viewPager.currentItem
        (supportFragmentManager.findFragmentByTag("f$pos") as? Refreshable)?.triggerRefresh()
    }
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
                // 切换到可刷新页面时触发刷新
                val frag = supportFragmentManager.findFragmentByTag("f$position")
                (frag as? Refreshable)?.triggerRefresh()
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
        refreshCurrentPage()
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
    class HomeFragment : Fragment(R.layout.fragment_home), Refreshable {
        private var sRootChecked = false
        private var sRootGranted = false
        /** 最近一次 am broadcast 命令的输出（供复制按钮使用）*/
        private var sLastCommandOutput: String? = null
        /** 首次 onResume 跳过下拉动画 */
        private var sFirstResume = true

        // ---- Status card state -------------------------------------------------
        // 0=green (correct scope + root), 1=amber (correct scope, no root),
        // 2=orange (wrong scope), 3=red (not active)
        private fun statusOrdinal(): Int {
            if (!ModuleServiceBridge.isActive()) return 3

            val prefs = requireContext().getSharedPreferences(
                RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
            val myBoot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            val sysAlive = isBootMatch(prefs, "boot_time", myBoot)

            if (!sysAlive) {
                // Module active but no boot_time match → wrong scope
                return 2
            }
            // Hooks confirmed in system_server this boot
            return if (sRootGranted) 0 else 1
        }

        private fun isBootMatch(prefs: android.content.SharedPreferences,
                                key: String, myBoot: Long): Boolean {
            val stored = prefs.getLong(key, 0L)
            return stored > 0L && Math.abs(myBoot - stored) < 5000L
        }

        private fun applyStatusColors(
            card: com.google.android.material.card.MaterialCardView,
            icon: android.widget.ImageView,
            title: android.widget.TextView,
            subtitle: android.widget.TextView
        ) {
            val ctx = requireContext()
            val ord = statusOrdinal()
            val cardBg: Int
            val tint: Int
            val stroke: Int
            val iconRes: Int
            val titleStr: String
            val subStr: String

            when (ord) {
                0 -> { // Green: correct scope + root ok
                    cardBg = androidx.core.content.ContextCompat.getColor(ctx, R.color.status_green_bg)
                    tint = androidx.core.content.ContextCompat.getColor(ctx, R.color.status_green_fg)
                    stroke = tint
                    iconRes = android.R.drawable.ic_dialog_info
                    titleStr = getString(R.string.home_status_active_root_ok)
                    subStr = getString(R.string.home_status_active_root_ok_sub)
                }
                1 -> { // Amber: correct scope, no root
                    cardBg = androidx.core.content.ContextCompat.getColor(ctx, R.color.status_amber_bg)
                    tint = androidx.core.content.ContextCompat.getColor(ctx, R.color.status_amber_fg)
                    stroke = tint
                    iconRes = android.R.drawable.ic_dialog_alert
                    titleStr = getString(R.string.home_status_active_no_root)
                    subStr = getString(R.string.home_status_active_no_root_sub)
                }
                2 -> { // Orange: wrong scope
                    cardBg = androidx.core.content.ContextCompat.getColor(ctx, R.color.status_orange_bg)
                    tint = androidx.core.content.ContextCompat.getColor(ctx, R.color.status_orange_fg)
                    stroke = tint
                    iconRes = android.R.drawable.ic_dialog_alert
                    titleStr = getString(R.string.home_status_wrong_scope)
                    subStr = getString(R.string.home_status_wrong_scope_sub)
                }
                else -> { // Red: module not active
                    cardBg = androidx.core.content.ContextCompat.getColor(ctx, R.color.status_red_bg)
                    tint = androidx.core.content.ContextCompat.getColor(ctx, R.color.status_red_fg)
                    stroke = tint
                    iconRes = android.R.drawable.ic_dialog_alert
                    titleStr = getString(R.string.home_status_inactive)
                    subStr = getString(R.string.home_status_inactive_sub)
                }
            }

            card.setCardBackgroundColor(cardBg)
            card.strokeWidth = 1
            card.strokeColor = stroke
            icon.setImageResource(iconRes)
            icon.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
            title.text = titleStr
            subtitle.text = subStr
        }

        // -----------------------------------------------------------------

        override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            refreshStatus(view)
            checkRoot(view)

            // 下拉刷新模块状态
            val swipe = view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipe_refresh)
            swipe?.setOnRefreshListener {
                doRefresh(view, swipe)
            }
        }

        override fun onResume() {
            super.onResume()
            view?.let { v ->
                if (sFirstResume) {
                    sFirstResume = false
                    // 首次加载：静默刷新，不显示下拉动画
                    refreshStatus(v)
                    checkRoot(v)
                } else {
                    // 从后台恢复 → 显示刷新动画
                    triggerRefresh()
                }
            }
        }

        /** 公开的刷新入口：重新打开应用 / 页面切换时调用 */
        override fun triggerRefresh() {
            view?.let { v ->
                val swipe = v.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipe_refresh)
                if (swipe != null) {
                    doRefresh(v, swipe)
                } else {
                    // 降级：无 SwipeRefreshLayout 时直接刷新
                    sRootChecked = false
                    sRootGranted = false
                    refreshStatus(v)
                    checkRoot(v)
                }
            }
        }

        /** 执行刷新逻辑：重置状态 → 显示动画 → 重新检测 → 停止动画 */
        private fun doRefresh(view: android.view.View, swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout) {
            sRootChecked = false
            sRootGranted = false
            refreshStatus(view)
            swipe.isRefreshing = true
            checkRoot(view) {
                swipe.isRefreshing = false
            }
        }

        private fun refreshStatus(view: android.view.View) {
            // First card: version info
            view.findViewById<android.widget.TextView>(R.id.tv_version)?.text =
                getString(R.string.home_version, BuildConfig.VERSION_NAME)
            view.findViewById<android.widget.TextView>(R.id.tv_description)?.text =
                getString(R.string.home_app_description)
            view.findViewById<android.widget.TextView>(R.id.tv_project_url)?.setOnClickListener {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/CommandPrompt-Wang/BetterZUIKey"))
                startActivity(intent)
            }

            // Apply color-coded status
            val card = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_module_status)
            val icon = view.findViewById<android.widget.ImageView>(R.id.iv_status_icon)
            val title = view.findViewById<android.widget.TextView>(R.id.tv_status_title)
            val subtitle = view.findViewById<android.widget.TextView>(R.id.tv_status_subtitle)

            if (card != null && icon != null && title != null && subtitle != null) {
                applyStatusColors(card, icon, title, subtitle)
            }

            view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_module_status)?.setOnClickListener {
                tryOpenLSPosedManager(view)
            }

            view.findViewById<android.view.View>(R.id.card_help)?.setOnClickListener {
                startActivity(android.content.Intent(requireContext(), HelpActivity::class.java))
            }
        }

        private fun checkRoot(view: android.view.View, onComplete: (() -> Unit)? = null) {
            if (sRootChecked) {
                onComplete?.invoke()
                return
            }
            Thread {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                    proc.inputStream.bufferedReader().use { it.readText() }
                    proc.errorStream.bufferedReader().use { it.readText() }
                    val exit = proc.waitFor()
                    sRootGranted = (exit == 0)
                } catch (e: Exception) {
                    sRootGranted = false
                }
                sRootChecked = true
                // Refresh the card once root check completes
                view.post {
                    val card = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_module_status)
                    val icon = view.findViewById<android.widget.ImageView>(R.id.iv_status_icon)
                    val title = view.findViewById<android.widget.TextView>(R.id.tv_status_title)
                    val subtitle = view.findViewById<android.widget.TextView>(R.id.tv_status_subtitle)
                    if (card != null && icon != null && title != null && subtitle != null) {
                        applyStatusColors(card, icon, title, subtitle)
                    }
                    onComplete?.invoke()
                }
            }.start()
        }

        /** 尝试通过 secret code 广播打开 LSPosed 管理器（5776733 = LSPosed on T9）*/
        private fun tryOpenLSPosedManager(view: android.view.View) {
            Thread {
                var success = false
                val intent = android.content.Intent("android.telephony.action.SECRET_CODE").apply {
                    data = android.net.Uri.parse("android_secret_code://5776733")
                    addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
                    setPackage("android")
                }

                if (sRootGranted) {
                    // 有 su → 用 am broadcast
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf(
                            "su", "-c",
                            "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 -f 0x400000 android"
                        ))
                        val stdout = proc.inputStream.bufferedReader().use { it.readText() }
                        val stderr = proc.errorStream.bufferedReader().use { it.readText() }
                        val exit = proc.waitFor()
                        success = (exit == 0)
                        sLastCommandOutput = "stdout:\n$stdout\nstderr:\n$stderr"
                    } catch (e: Exception) {
                        success = false
                        sLastCommandOutput = e.toString()
                    }
                } else {
                    // 没有 su → 尝试 sendBroadcast
                    try {
                        requireActivity().sendBroadcast(intent)
                        success = true
                        sLastCommandOutput = "sendBroadcast succeeded"
                    } catch (e: SecurityException) {
                        success = false
                        sLastCommandOutput = "SecurityException: ${e.message}"
                    } catch (e: Exception) {
                        success = false
                        sLastCommandOutput = e.toString()
                    }
                }

                if (!success) {
                    view.post { showWarningBanner(view) }
                }
            }.start()
        }

        /** 底部悬浮警告，持续 5s，可复制错误信息 */
        private fun showWarningBanner(view: android.view.View) {
            val activity = requireActivity()
            val banner = activity.findViewById<android.view.View>(R.id.warning_banner)
            banner?.visibility = android.view.View.VISIBLE

            // 复制按钮
            activity.findViewById<android.widget.TextView>(R.id.btn_copy_error)?.setOnClickListener {
                val text = sLastCommandOutput
                    ?: getString(R.string.home_warning_open_lsposed_failed)
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("LSPosed broadcast error", text))
                // 短暂显示"已复制"
                (it as? android.widget.TextView)?.text = "✓"
                it.postDelayed({
                    (it as? android.widget.TextView)?.text = getString(R.string.home_warning_copy_error)
                }, 1500)
            }

            val hideRunnable = Runnable {
                banner?.visibility = android.view.View.GONE
            }
            banner?.postDelayed(hideRunnable, 5000)
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

    /** 在后台线程通过 su 授予 WRITE_SECURE_SETTINGS，避免阻塞 UI */
    private fun grantSecureSettings() {
        Thread {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf(
                    "su", "-c",
                    "pm grant moe.lovefirefly.betterzuikey android.permission.WRITE_SECURE_SETTINGS"
                ))
                // Consume stdout/stderr to prevent pipe buffer deadlock
                proc.inputStream.bufferedReader().use { it.readText() }
                proc.errorStream.bufferedReader().use { it.readText() }
                val exitCode = proc.waitFor()
                if (exitCode != 0) {
                    android.util.Log.w("BetterZUIKey",
                        "su pm grant failed, exit=$exitCode")
                }
            } catch (e: Exception) {
                android.util.Log.e("BetterZUIKey", "su pm grant error", e)
            }
        }.start()
    }
}