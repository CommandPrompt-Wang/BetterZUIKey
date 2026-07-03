package moe.lovefirefly.betterzuikey

import android.content.Intent
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
    /** 需要 onDestroy 时取消的 pending Runnable */
    private var longPressRunnable: Runnable? = null
    /** 上次 showWarningBanner 显示的错误信息（供复制按钮使用） */
    var lastWarningMessage: String? = null

    /** 红色告警横幅（安全事件，如检测到队列注入） */
    fun showWarningBannerRed(message: String) {
        showWarningBanner(message, timeoutMs = 10000,
            buttonText = getString(R.string.dialog_confirm_ok),
            onButtonClick = {
                // 清除告警 flag
                getSharedPreferences(RemotePrefProvider.PREF_FILE, MODE_PRIVATE)
                    .edit().remove("sys_write_alert").apply()
                findViewById<android.view.View>(R.id.warning_banner)?.visibility = android.view.View.GONE
            },
            bgColor = 0xFF_D32F2F.toInt())
    }

    /** 底部悬浮警告横幅
     *  @param message 显示文本
     *  @param timeoutMs 自动消失时间（ms）
     *  @param buttonText 按钮文案，null=不显示按钮
     *  @param onButtonClick 按钮点击回调
     *  @param copyable 是否显示复制按钮（旧接口兼容）
     *  @param bgColor 背景色，null=默认琥珀色 */
    fun showWarningBanner(message: String,
                          timeoutMs: Long = 5000,
                          buttonText: String? = null,
                          onButtonClick: (() -> Unit)? = null,
                          copyable: Boolean = true,
                          bgColor: Int? = null) {
        lastWarningMessage = message
        val banner = findViewById<android.view.View>(R.id.warning_banner) ?: return
        if (bgColor != null) banner.setBackgroundColor(bgColor)
        val tv = findViewById<android.widget.TextView>(R.id.tv_warning_text)
        tv?.text = message
        banner.visibility = android.view.View.VISIBLE

        val btn = findViewById<android.widget.TextView>(R.id.btn_copy_error) ?: return
        if (buttonText != null) {
            btn.visibility = android.view.View.VISIBLE
            btn.text = buttonText
            btn.setOnClickListener { onButtonClick?.invoke() }
        } else if (copyable) {
            btn.visibility = android.view.View.VISIBLE
            btn.text = getString(R.string.home_warning_copy_error)
            btn.setOnClickListener {
                val text = lastWarningMessage ?: message
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("BetterZUIKey error", text))
                (it as? android.widget.TextView)?.text = "✓"
                it.postDelayed({ (it as? android.widget.TextView)?.text =
                    getString(R.string.home_warning_copy_error) }, 1500)
            }
        } else {
            btn.visibility = android.view.View.GONE
        }

        val hideRunnable = Runnable { banner.visibility = android.view.View.GONE }
        banner.postDelayed(hideRunnable, timeoutMs)
    }

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
        applyLocale(this)
        super.onCreate(savedInstanceState)
        val cfg = Config.load()
        Config.lastLoadError?.let { showWarningBanner(it); Config.lastLoadError = null }
        if (cfg.updateCheckOnStartup) {
            Thread { UpdateChecker.check(this, cfg) }.start()
        }
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

        // 首次启动 → 显示使用协议对话框
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (!prefs.getBoolean("agreement_shown", false)) {
            showAgreementDialog(
                onAccept = { prefs.edit().putBoolean("agreement_shown", true).apply() },
                canExit = true
            )
        }

        // 调试：长按标题栏 3s 弹出协议对话框（不可退出）
        binding.toolbar.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val r = Runnable {
                        val items = arrayOf(
                            getString(R.string.agreement_title),
                            "Reset Secure Permission Warning"
                        )
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Debug")
                            .setItems(items) { _, which ->
                                when (which) {
                                    0 -> showAgreementDialog(onAccept = { }, canExit = false)
                                    1 -> {
                                        getSharedPreferences(RemotePrefProvider.PREF_FILE, MODE_PRIVATE)
                                            .edit().remove("secure_perm_dismissed").apply()
                                        android.widget.Toast.makeText(this@MainActivity,
                                            "Secure permission warning reset", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.show()
                    }
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

        handleOpenAppKeyEditorIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenAppKeyEditorIntent(intent)
    }

    private fun handleOpenAppKeyEditorIntent(intent: Intent?) {
        val appKey = intent?.getStringExtra(AppKeyCommandDialog.EXTRA_OPEN_APP_KEY) ?: return
        intent.removeExtra(AppKeyCommandDialog.EXTRA_OPEN_APP_KEY)
        moe.lovefirefly.betterzuikey.Utils.LogHelper.log(
            moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel.INFO,
            "AppKeyEditor: MainActivity intent key=", appKey,
        )
        openAppKeyEditor(appKey)
    }

    private fun openAppKeyEditor(appKey: String) {
        fun showDialog() {
            AppKeyCommandDialog.show(this, appKey)
        }
        fun tryScroll(retry: Int = 0) {
            val frag = supportFragmentManager.findFragmentByTag("f$TAB_SHORTCUTS") as? GlobalFragment
            if (frag != null && frag.isAdded) {
                frag.navigateToShortcutKey(appKey, onReady = ::showDialog)
            } else if (retry < 12) {
                binding.root.postDelayed({ tryScroll(retry + 1) }, 50)
            } else {
                showDialog()
            }
        }
        binding.root.post {
            switchToShortcutsTab()
            binding.root.post { tryScroll() }
        }
    }

    private fun switchToShortcutsTab() {
        if (binding.viewPager.currentItem == TAB_SHORTCUTS) return
        binding.viewPager.setCurrentItem(TAB_SHORTCUTS, false)
        binding.bottomNav.menu.getItem(TAB_SHORTCUTS).isChecked = true
        binding.toolbar.title = titles[TAB_SHORTCUTS]
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
        Config.lastLoadError?.let { showWarningBanner(it); Config.lastLoadError = null }

        if (LocaleHelper.consumePendingUserLocaleChange()) {
            appliedLocaleTag = cfg.localeOverride
            return
        }

        refreshCurrentPage()

        val synced = LocaleHelper.syncFromSystemStorage(this)
        if (synced != appliedLocaleTag) {
            appliedLocaleTag = synced
            recreate()
            return
        }

        if (cfg.dynamicColorEnabled != appliedDynamicColor || cfg.nightMode != appliedNightMode) {
            recreate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressRunnable?.let { binding.toolbar.removeCallbacks(it) }
        findViewById<android.view.View>(R.id.warning_banner)?.clearAnimation()
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
            val ctx = context ?: return 3
            if (!ModuleServiceBridge.isActive()) return 3

            val prefs = ctx.getSharedPreferences(
                RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
            val myBoot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            val sysAlive = isBootMatch(prefs, "boot_time", myBoot)

            if (!sysAlive) {
                // boot_time 可能因 ContentProvider 未就绪而写入失败
                // 如果模块确认活跃，App 进程自行补写（App 进程拥有 SP 直接写权限）
                if (prefs.getLong("boot_time", 0L) == 0L) {
                    prefs.edit().putLong("boot_time", myBoot).apply()
                    // 重试匹配
                    if (isBootMatch(prefs, "boot_time", myBoot)) {
                        return if (sRootGranted) 0 else 1
                    }
                }
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
            if (!isAdded) return
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
                view.post {
                    if (!isAdded) {
                        onComplete?.invoke()
                        return@post
                    }
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

        /** 尝试打开 LSPosed 管理器：
         * ① su -c am broadcast (root，唯一有效途径)
         * ② [仅无 root 时] sendBroadcast + 直接打开 org.lsposed.manager（做做样子）
         * 全部失败才显示警告 */
        private fun tryOpenLSPosedManager(view: android.view.View) {
            Thread {
                if (sRootGranted) {
                    // ① su -c am broadcast (root, 最快)
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf(
                            "su", "-c",
                            "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 -f 0x400000 android"
                        ))
                        proc.inputStream.bufferedReader().use { it.readText() }
                        proc.errorStream.bufferedReader().use { it.readText() }
                        val exit = proc.waitFor()
                        if (exit == 0) { sLastCommandOutput = "OK"; return@Thread }
                    } catch (_: Exception) {}
                }
                // ② system_server am broadcast (system UID, 100% 可靠)
                try {
                    requireContext().contentResolver.call(
                        ConfigSyncProvider.RELOAD_URI,
                        "setLsposedOpenRequest", null,
                        android.os.Bundle().apply { putBoolean("requested", true) })
                    sLastCommandOutput = "OK"
                    view.post {
                        if (!isAdded) return@post
                        (requireActivity() as MainActivity).showWarningBanner(
                            getString(R.string.warn_write_deferred), copyable = false, timeoutMs = 1500)
                    }
                } catch (_: Exception) {}
            }.start()
        }
    }

        companion object {
        const val TAB_SHORTCUTS = 1

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
        fun applyLocale(context: android.content.Context) {
            LocaleHelper.applyFromConfig(context)
        }
    }

}
