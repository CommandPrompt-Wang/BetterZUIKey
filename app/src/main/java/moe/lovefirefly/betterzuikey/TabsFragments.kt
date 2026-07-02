package moe.lovefirefly.betterzuikey

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Spinner
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Config.KeyTemplate
import moe.lovefirefly.betterzuikey.Config.PerKeyOverride
import moe.lovefirefly.betterzuikey.Utils.LogHelper
import moe.lovefirefly.betterzuikey.databinding.FragmentRecyclerBinding
import moe.lovefirefly.betterzuikey.databinding.ItemShortcutRowBinding
import moe.lovefirefly.betterzuikey.databinding.FragmentTemplatesBinding
import moe.lovefirefly.betterzuikey.databinding.ItemTemplateRowBinding

/**
 * 全局配置 Tab — 显示所有快捷键的系统开关。
 * 支持下拉刷新和 su 自授权。
 */
class GlobalFragment : Fragment(R.layout.fragment_recycler), MainActivity.Refreshable {

    /** 系统设置写入权限状态：null=未检测, true=可写, false=不可写 */
    companion object {
        var canWriteSystemSettings: Boolean? = null
        /** AOSP 辅助键 → Settings.Secure key 映射 */
        val AOSP_SECURE_KEY_MAP = mapOf(
            "aospBounceKeys" to "accessibility_bounce_keys",
            "aospMouseKeys"  to "accessibility_mouse_keys_enabled",
            "aospStickyKeys" to "accessibility_sticky_keys",
            "aospSlowKeys"   to "accessibility_slow_keys",
        )
    }

    private lateinit var adapter: ShortcutAdapter
    private lateinit var binding: FragmentRecyclerBinding
    private var cachedConfig: Config? = null
    /** 首次初始化标志 — 只设一次 LayoutManager/Adapter/SwipeRefresh/PermissionCheck */
    private var firstInitDone = false
    /** AOSP 辅助键 Settings.Secure 缓存（后台线程读取，避免主线程 ANR） */
    private var aospSecureCache: Map<String, Boolean> = emptyMap()
    /** System putInt 降级（失败后走 su/代理） */
    private var putIntDeadSys = false
    /** Secure putInt 降级（失败后走 su/代理，授权后可恢复） */
    private var putIntDeadSec = false
    /** WRITE_SECURE_SETTINGS 权限缺失提示的"不再提示" */
    private var securePermDismissed = false

    private fun reloadConfig(): Config {
        if (!isAdded) return cachedConfig ?: Config.load()
        val ctx = requireContext()
        val appCtx = ctx.applicationContext
        val cfg = Config.load()
        // 配置读取异常 → 显示 warning banner
        Config.lastLoadError?.let { err ->
            (requireActivity() as? MainActivity)?.showWarningBanner(err)
            Config.lastLoadError = null
        }
        cfg.syncSwitchesFromSystem(ctx)
        cfg.save()
        Config.syncToSharedPrefs(ctx, cfg)
        cachedConfig = cfg
        // 后台读取 Settings.Secure（避免主线程 ANR）
        Thread {
            val cache = mutableMapOf<String, Boolean>()
            for ((shortcutKey, secureKey) in AOSP_SECURE_KEY_MAP) {
                try {
                    cache[shortcutKey] = Settings.Secure.getInt(
                        appCtx.contentResolver, secureKey) == 1
                } catch (_: Exception) { cache[shortcutKey] = false }
            }
            aospSecureCache = cache
            view?.post {
                if (!isAdded || !::adapter.isInitialized) return@post
                adapter.applyFilters(binding.searchView.query?.toString() ?: "")
            }
        }.start()
        return cfg
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentRecyclerBinding.bind(view)
        val recycler = binding.recycler

        if (!firstInitDone) {
            firstInitDone = true
            recycler.layoutManager = LinearLayoutManager(requireContext())
            adapter = ShortcutAdapter()
            recycler.adapter = adapter

            binding.swipeRefresh.setOnRefreshListener {
                triggerRefresh()
            }

            // Search
            binding.searchView.setOnQueryTextListener(object : android.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?): Boolean { adapter.applyFilters(q ?: ""); return true }
                override fun onQueryTextChange(q: String?): Boolean { adapter.applyFilters(q ?: ""); return true }
            })

            // Filter button
            binding.btnFilter.setOnClickListener { showFilterDialog() }

            // 加载 Secure 权限提示的"不再提示"状态
            securePermDismissed = requireContext().getSharedPreferences(
                RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                .getBoolean("secure_perm_dismissed", false)
        }

        reloadConfig()
        adapter.applyFilters(binding.searchView.query?.toString() ?: "")
    }

    // ── Filter state ──
    private var filterSwitchOff = true
    private var filterSwitchOn = true
    private var filterNoSwitch = true
    private var filterModeDefault = true
    private var filterModeAOSP = true
    private var filterModeZUI = true
    private var filterModeOFF = true
    private var filterModeBLOCK = true

    private fun showFilterDialog() {
        val ctx = requireContext()
        val items = arrayOf(
            "系统开关关", "系统开关开", "无系统开关",  // group 0-2
            "默认", "AOSP", "ZUI", "关闭", "忽略"     // group 3-7
        )
        val checked = booleanArrayOf(
            filterSwitchOff, filterSwitchOn, filterNoSwitch,
            filterModeDefault, filterModeAOSP, filterModeZUI, filterModeOFF, filterModeBLOCK
        )
        val groupNames = arrayOf("系统开关状态", "覆写模式")
        val groupSizes = intArrayOf(3, 5)

        AlertDialog.Builder(ctx)
            .setTitle("筛选快捷键")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> filterSwitchOff = isChecked
                    1 -> filterSwitchOn = isChecked
                    2 -> filterNoSwitch = isChecked
                    3 -> filterModeDefault = isChecked
                    4 -> filterModeAOSP = isChecked
                    5 -> filterModeZUI = isChecked
                    6 -> filterModeOFF = isChecked
                    7 -> filterModeBLOCK = isChecked
                }
            }
            .setPositiveButton("确定") { _, _ ->
                adapter.applyFilters(binding.searchView.query?.toString() ?: "")
            }
            .setNegativeButton("重置") { _, _ ->
                filterSwitchOff = true; filterSwitchOn = true; filterNoSwitch = true
                filterModeDefault = true; filterModeAOSP = true; filterModeZUI = true
                filterModeOFF = true; filterModeBLOCK = true
                adapter.applyFilters(binding.searchView.query?.toString() ?: "")
            }
            .show()
    }

    /** 实现 Refreshable 接口 —— 供页面切换 / 应用恢复时自动刷新 */
    override fun triggerRefresh() {
        if (!isAdded) return
        val v = view ?: return
        binding.swipeRefresh.isRefreshing = true
        canWriteSystemSettings = null
        checkWritePermission()
        reloadConfig()
        if (::adapter.isInitialized) {
            adapter.applyFilters(binding.searchView.query?.toString() ?: "")
        }
        binding.swipeRefresh.isRefreshing = false
    }

    /** 切换到快捷键列表中的指定项（长按智能键打开编辑器时）。 */
    fun navigateToShortcutKey(appKey: String, onReady: () -> Unit = {}) {
        if (!isAdded || !::adapter.isInitialized) {
            onReady()
            return
        }
        var pos = adapter.indexOfKey(appKey)
        if (pos < 0) {
            binding.searchView.setQuery("", false)
            resetFiltersForReveal()
            adapter.applyFilters("")
            pos = adapter.indexOfKey(appKey)
        }
        if (pos < 0) {
            onReady()
            return
        }
        binding.recycler.stopScroll()
        (binding.recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
            pos,
            (binding.recycler.height * 0.25f).toInt().coerceAtLeast(0),
        )
        binding.recycler.post { onReady() }
    }

    private fun resetFiltersForReveal() {
        filterSwitchOff = true
        filterSwitchOn = true
        filterNoSwitch = true
        filterModeDefault = true
        filterModeAOSP = true
        filterModeZUI = true
        filterModeOFF = true
        filterModeBLOCK = true
    }

    /** 切回此 Tab 时统一刷新（开关状态 + 写入权限 + 安全告警） */
    override fun onResume() {
        super.onResume()
        if (!isAdded) return
        val v = view ?: return
        // 检测 sys_write_queue 篡改告警
        val alertPrefs = requireContext().getSharedPreferences(
            RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
        if (alertPrefs.getBoolean("sys_write_alert", false)) {
            alertPrefs.edit().remove("sys_write_alert").apply()
            (requireActivity() as? MainActivity)?.showWarningBannerRed(
                getString(R.string.warn_queue_injection))
        }
        canWriteSystemSettings = null // 重新检测 su 可用性
        checkWritePermission()
        if (::adapter.isInitialized) {
            reloadConfig()
            adapter.applyFilters(binding.searchView.query?.toString() ?: "")
        }
    }

    /** WRITE_SECURE_SETTINGS 权限缺失提示卡片 */
    private fun showSecurePermissionCard() {
        if (securePermDismissed) return
        val v = view ?: return
        val card = v.findViewById<CardView>(R.id.cardWriteWarning) ?: return
        val ctx = requireContext()
        val cmd1 = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"
        val cmd2 = "su -c 'pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS'"

        v.findViewById<TextView>(R.id.tvSecurePermTitle)?.text = getString(R.string.secure_perm_card_title)
        v.findViewById<TextView>(R.id.tvSecurePermMsg)?.text =
            getString(R.string.secure_perm_card_msg, cmd1, cmd2)
        v.findViewById<CheckBox>(R.id.cbSecurePermNever)?.text = getString(R.string.secure_perm_card_never)

        v.findViewById<Button>(R.id.btnSecurePermTry)?.text = getString(R.string.secure_perm_card_try)
        v.findViewById<Button>(R.id.btnSecurePermTry)?.setOnClickListener {
            try { ctx.contentResolver.call(ConfigSyncProvider.RELOAD_URI,
                "setGrantSecureRequest", null, null) } catch (_: Exception) {}
            (requireActivity() as? MainActivity)?.showWarningBanner(
                getString(R.string.warn_write_deferred), copyable = false, timeoutMs = 1500)
            dismissSecurePermCard(card)
        }

        v.findViewById<Button>(R.id.btnSecurePermClose)?.text = getString(R.string.secure_perm_card_close)
        v.findViewById<Button>(R.id.btnSecurePermClose)?.setOnClickListener {
            dismissSecurePermCard(card)
        }

        card.visibility = View.VISIBLE
    }

    private fun dismissSecurePermCard(card: CardView) {
        card.visibility = View.GONE
        val cb = view?.findViewById<CheckBox>(R.id.cbSecurePermNever)
        if (cb?.isChecked == true) {
            securePermDismissed = true
            requireContext().getSharedPreferences(RemotePrefProvider.PREF_FILE, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("secure_perm_dismissed", true).apply()
        }
    }

    /** 检测 su 可用性。ZUI 上 putInt 必定失败，写入走 su 或 system_server。 */
    private fun checkWritePermission() {
        if (canWriteSystemSettings != null) return
        canWriteSystemSettings = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exit = p.waitFor(); p.destroy(); exit == 0
        } catch (_: Exception) { false }
    }

    /** RecyclerView Adapter — 48 条静态数据，直接字段访问，不使用反射 */
    inner class ShortcutAdapter : RecyclerView.Adapter<ShortcutAdapter.VH>() {

        /** 所有 Spinner 统一固定最小宽度（px），按全部可能文本的最宽值一次性计算 */
        private var spinnerFixedMinWidth: Int = 0
        /** 当前展开的 Spinner 所在 position，-1 表示无 */
        private var openSpinnerPos = -1
        /** 搜索过滤后的列表 */
        private var filtered: List<ShortcutMeta> = ShortcutMeta.ALL
        /** 当前搜索关键词 */
        private var searchQuery: String = ""

        fun indexOfKey(key: String): Int = filtered.indexOfFirst { it.key == key }

        fun applyFilters(query: String) {
            if (!this@GlobalFragment.isAdded) return
            val ctx = this@GlobalFragment.context ?: return
            searchQuery = query.trim()
            val cfg = cachedConfig
            filtered = ShortcutMeta.ALL.filter { meta ->
                // ── Text search (AND tokens) ──
                val tokens = searchQuery.split("+", " ", "\t").map { it.trim() }.filter { it.isNotEmpty() }
                val text = meta.displayName(ctx) +
                    (if (meta.descResId != 0) " " + ctx.getString(meta.descResId) else "")
                val textOk = tokens.isEmpty() || tokens.all { token -> text.contains(token, ignoreCase = true) }
                if (!textOk) return@filter false

                // ── Switch state filter (OR within group) ──
                val isAospSecure = meta.key in AOSP_SECURE_KEY_MAP
                val hasSwitch = meta.showSwitch || meta.hasSystemSwitch || isAospSecure
                val switchOn = if (cfg != null) {
                    if (isAospSecure) aospSecureCache[meta.key] ?: false
                    else ShortcutMeta.getSwitch(cfg, meta.key).isEnabled
                } else false
                val switchOk = (filterSwitchOn && hasSwitch && switchOn) ||
                               (filterSwitchOff && hasSwitch && !switchOn) ||
                               (filterNoSwitch && !hasSwitch)
                if (!switchOk) return@filter false

                // ── Override mode filter (OR within group) ──
                val modeOk = if (cfg != null && meta.key == "winLongPress") {
                    when (ShortcutMeta.getWinLongPressUiMode(cfg)) {
                        WinLongPressUiMode.FOLLOW_SYSTEM -> filterModeDefault
                        WinLongPressUiMode.ZUI -> filterModeZUI
                        WinLongPressUiMode.BLOCK -> filterModeBLOCK
                        WinLongPressUiMode.CUSTOM -> filterModeZUI
                    }
                } else if (cfg != null && ShortcutMeta.usesAppKeyMode(meta.key)) {
                    val appMode = ShortcutMeta.getAppKeyMode(cfg, meta.key)
                    (filterModeDefault && appMode == Config.AppKeyMode.FOLLOW_SYSTEM) ||
                            (filterModeBLOCK && appMode == Config.AppKeyMode.BLOCK) ||
                            (filterModeZUI && appMode == Config.AppKeyMode.CUSTOM)
                } else {
                    val mode = if (cfg != null) {
                        val overrideKey = if (meta.key == "ctrlCard") "ctrlSlash" else meta.key
                        ShortcutMeta.getOverride(cfg, overrideKey)
                    } else Config.OverrideMode.FOLLOW_SYSTEM
                    (filterModeDefault && (mode == Config.OverrideMode.FOLLOW_SYSTEM || mode == Config.OverrideMode.ZUI)) ||
                            (filterModeAOSP && mode == Config.OverrideMode.AOSP) ||
                            (filterModeZUI && mode == Config.OverrideMode.ZUI) ||
                            (filterModeOFF && mode == Config.OverrideMode.OFF) ||
                            (filterModeBLOCK && mode == Config.OverrideMode.BLOCK)
                }
                modeOk
            }
            openSpinnerPos = -1
            notifyDataSetChanged()
        }

        override fun getItemCount() = filtered.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemShortcutRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            // 首次创建时，根据所有 OverrideMode 显示名称计算最大文本宽度，统一设置
            if (spinnerFixedMinWidth == 0) {
                val ctx = parent.context
                val paint = b.spAction.paint
                val maxTextW = maxOf(
                    Config.OverrideMode.entries.maxOf {
                        paint.measureText(it.displayName(ctx)).toInt()
                    },
                    Config.AppKeyMode.entries.maxOf {
                        paint.measureText(it.displayName(ctx)).toInt()
                    },
                    WinLongPressUiMode.entries.maxOf {
                        paint.measureText(it.displayName(ctx)).toInt()
                    },
                )
                // 额外留出 dropdown 图标 + 内边距空间
                spinnerFixedMinWidth = maxTextW + b.spAction.paddingLeft +
                    b.spAction.paddingRight + 48
            }
            // 固定宽度（不仅是 minWidth），防止 setText 不同文本时宽度抖动
            b.spAction.minWidth = spinnerFixedMinWidth
            b.spAction.maxWidth = spinnerFixedMinWidth
            b.spAction.dropDownWidth = spinnerFixedMinWidth
            b.spAction.layoutParams?.let { lp ->
                lp.width = spinnerFixedMinWidth
                b.spAction.layoutParams = lp
            }
            // 同时约束外层 TextInputLayout
            b.tilAction.minWidth = spinnerFixedMinWidth
            b.tilAction.layoutParams?.let { lp ->
                lp.width = spinnerFixedMinWidth
                b.tilAction.layoutParams = lp
            }
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(filtered[position], position)
        }

        inner class VH(private val b: ItemShortcutRowBinding) : RecyclerView.ViewHolder(b.root) {

            fun bind(meta: ShortcutMeta, pos: Int) {
                val cfg = cachedConfig ?: return
                // ctrlCard: switch → ctrlLongPress, spinner → ctrlSlash
                val isCtrlCard = meta.key == "ctrlCard"
                val switchKey = if (isCtrlCard) "ctrlLongPress" else meta.key
                val overrideKey = if (isCtrlCard) "ctrlSlash" else meta.key
                val switchState = ShortcutMeta.getSwitch(cfg, switchKey)
                val overrideMode = ShortcutMeta.getOverride(cfg, overrideKey)

                // ── 始终先清空所有视图状态，防止 RecyclerView 复用残留旧数据 ──
                b.tvName.text = meta.displayName(requireContext())
                b.tvDesc.text = meta.displayDesc(requireContext())
                if (meta.key == "winLongPress") {
                    val imeHoldsWin = cfg.imeSwitchBinding == Config.IMEBinding.WIN
                            || cfg.languageSwitchBinding == Config.IMEBinding.WIN
                    if (imeHoldsWin) {
                        val base = meta.displayDesc(requireContext())
                        val warn = requireContext().getString(R.string.ime_win_voice_warn, cfg.metaKeyLabel)
                        val sp = android.text.SpannableString("$base\n⚠ $warn")
                        sp.setSpan(android.text.style.ForegroundColorSpan(0xFFCC8800.toInt()),
                            base.length, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        b.tvDesc.text = sp
                    }
                }
                b.tvDesc.visibility = View.VISIBLE
                b.swEnabled.setOnCheckedChangeListener(null)
                b.spAction.setOnItemClickListener(null)
                b.spAction.setOnClickListener(null)
                b.root.setOnClickListener(null)
                b.root.setOnLongClickListener(null)

                // ── Win 长按：四档 Spin；智能键：三档 Spin ──
                if (meta.key == "winLongPress") {
                    b.swEnabled.visibility = View.GONE
                    bindWinLongSpin(meta, cfg, b, pos)
                    return
                }
                if (ShortcutMeta.usesAppKeyMode(meta.key)) {
                    b.swEnabled.visibility = View.GONE
                    bindAppKeySpin(meta, cfg, b, pos)
                    return
                }

                // ── 开关（系统开关 或 ctrlCard 的 Ctrl 长按开关）──
                val isAospSecure = meta.key in AOSP_SECURE_KEY_MAP
                if (meta.showSwitch || isAospSecure) {
                    b.swEnabled.visibility = View.VISIBLE
                    if (isAospSecure) {
                        // AOSP 辅助键：直接读写 Settings.Secure
                        val secureKey = AOSP_SECURE_KEY_MAP[meta.key]!!
                        b.swEnabled.isEnabled = true
                        b.swEnabled.isChecked = aospSecureCache[meta.key] ?: false
                        var aospListener: android.widget.CompoundButton.OnCheckedChangeListener? = null
                        aospListener = android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
                            val valInt = if (isChecked) 1 else 0
                            var permMissing = false
                            var ok = if (putIntDeadSec) false else try {
                                Settings.Secure.putInt(requireContext().contentResolver, secureKey, valInt)
                                true
                            } catch (_: SecurityException) { permMissing = true; false }
                            // 缺少 WRITE_SECURE_SETTINGS 权限 → 首次弹提示卡片，跳过后续
                            if (permMissing && !putIntDeadSec) {
                                putIntDeadSec = true
                                showSecurePermissionCard()
                                b.root.postDelayed({
                                    if (!isAdded) return@postDelayed
                                    putIntDeadSec = false
                                }, 3000)
                            }
                            // ① putInt 失败 + 有 root → 尝试 su
                            if (!ok) {
                                // ① 尝试 su（有 root 直接写，不需要 WRITE_SECURE_SETTINGS）
                                ok = try {
                                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c",
                                        "settings put secure $secureKey $valInt"))
                                    p.waitFor()
                                    p.exitValue() == 0
                                } catch (_: Exception) { false }
                            }
                            if (!ok) {
                                putIntDeadSec = true
                                // ② su 也失败 → system_server 代理
                                b.swEnabled.setOnCheckedChangeListener(null)
                                b.swEnabled.isChecked = !isChecked
                                b.swEnabled.isEnabled = false
                                b.swEnabled.setOnCheckedChangeListener(aospListener)
                                (requireActivity() as? MainActivity)?.showWarningBanner(
                                    getString(R.string.warn_write_deferred), copyable = false, timeoutMs = 1500)
                                try {
                                    val extras = android.os.Bundle()
                                    extras.putString("key", secureKey)
                                    extras.putInt("val", valInt)
                                    requireContext().contentResolver.call(
                                        ConfigSyncProvider.RELOAD_URI,
                                        "appendSysWriteQueue", null, extras)
                                } catch (_: Exception) {}
                                b.root.postDelayed({
                                    if (!isAdded) return@postDelayed
                                    b.swEnabled.isEnabled = true
                                    b.swEnabled.setOnCheckedChangeListener(null)
                                    b.swEnabled.isChecked = try {
                                        Settings.Secure.getInt(requireContext().contentResolver, secureKey) == 1
                                    } catch (_: Exception) { !isChecked }
                                    b.swEnabled.setOnCheckedChangeListener(aospListener)
                                }, 3000)
                            }
                        }
                        b.swEnabled.setOnCheckedChangeListener(aospListener)
                    } else {
                        b.swEnabled.isEnabled = switchState.isUserToggleable
                        b.swEnabled.isChecked = switchState.isEnabled
                        // 先写入系统，失败则还原 UI 开关
                        var listener: android.widget.CompoundButton.OnCheckedChangeListener? = null
                        listener = android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
                            val err = if (putIntDeadSys) "cached" else Config.writeSystemSwitch(requireContext(), switchKey, isChecked)
                            if (err != null) {
                                putIntDeadSys = true
                                LogHelper.log(LogHelper.VerboseLevel.WARNING,
                                    "Failed to sync switch to system:", err)
                                // 还原 UI（临时 detach listener 避免递归）
                                b.swEnabled.setOnCheckedChangeListener(null)
                                b.swEnabled.isChecked = !isChecked
                                b.swEnabled.setOnCheckedChangeListener(listener)
                                // system_server 代理写入，弹底部横幅提示
                                (requireActivity() as? MainActivity)?.showWarningBanner(
                                    getString(R.string.warn_write_deferred), copyable = false, timeoutMs = 1500)
                                    // 灰置开关，防止连续点击
                                    b.swEnabled.isEnabled = false
                                    // 通过 ContentProvider 追加到写入队列（与 drain 串行化，无竞态）
                                    try {
                                        val extras = android.os.Bundle()
                                        extras.putString("key", switchKey)
                                        extras.putInt("val", if (isChecked) 1 else 0)
                                        requireContext().contentResolver.call(
                                            ConfigSyncProvider.RELOAD_URI,
                                            "appendSysWriteQueue", null, extras)
                                    } catch (_: Exception) {}
                                    // 写入完成后恢复开关 + 刷新 UI
                                    b.root.postDelayed({
                                        if (!isAdded) return@postDelayed
                                        cfg.syncSwitchesFromSystem(requireContext())
                                        val newVal = ShortcutMeta.getSwitch(cfg, switchKey)
                                        LogHelper.log(LogHelper.VerboseLevel.INFO,
                                            "Proxy refresh: key=", switchKey,
                                            " val=", newVal.name,
                                            " enabled=", java.lang.Boolean.toString(newVal.isEnabled))
                                        b.swEnabled.isEnabled = true
                                        b.swEnabled.setOnCheckedChangeListener(null)
                                        b.swEnabled.isChecked = newVal.isEnabled
                                        b.swEnabled.setOnCheckedChangeListener(listener)
                                    }, 1500)
                            } else {
                                val newState = if (isChecked) Config.SwitchState.ON else Config.SwitchState.OFF
                                ShortcutMeta.setSwitch(cfg, switchKey, newState)
                                for (gk in meta.groupKeys) {
                                    ShortcutMeta.setSwitch(cfg, gk, newState)
                                }
                                cfg.syncSwitchesFromSystem(requireContext())
                                cfg.save()
                                Config.syncToSharedPrefs(requireContext(), cfg)
                            }
                        }
                        b.swEnabled.setOnCheckedChangeListener(listener)
                    }
                } else {
                    b.swEnabled.visibility = View.GONE
                }

                // ── 行为覆写下拉 ──
                val availableModes = Config.OverrideMode.entries
                    .filter { it.isAvailable(meta) && (it != Config.OverrideMode.AOSP || meta.showAospOption) }
                val enabledModes = availableModes.map { true }
                val modeNames = availableModes.map { it.displayName(requireContext()) }
                b.spAction.setAdapter(null)
                b.spAction.setAdapter(object : android.widget.ArrayAdapter<String>(
                    requireContext(), R.layout.dropdown_item_wrap, modeNames) {
                    override fun isEnabled(position: Int) = enabledModes[position]
                    override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                        val v = super.getDropDownView(position, convertView, parent)
                        if (!isEnabled(position)) {
                            (v as? android.widget.TextView)?.let {
                                it.paintFlags = it.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                            }
                        }
                        return v
                    }
                })
                b.spAction.threshold = Int.MAX_VALUE
                b.spAction.setText(overrideMode.displayName(requireContext()), false)
                b.spAction.isEnabled = true
                b.tilAction.isEnabled = true

                // ── 卡片点击行为（统一由 ShortcutMeta 控制）──
                val resolvedClick = meta.cardClick.resolve(
                    hasSpinner = true, hasSwitch = meta.showSwitch || isAospSecure)
                b.root.setOnClickListener {
                    // 同一张 card：toggle（展开↔收回）；不同 card：直接切
                    if (openSpinnerPos == pos) {
                        openSpinnerPos = -1
                        return@setOnClickListener
                    }
                    when (resolvedClick) {
                        CardClickBehavior.EXPAND_SPIN -> {
                            b.spAction.showDropDown()
                            openSpinnerPos = pos
                        }
                        CardClickBehavior.SWITCH -> b.swEnabled.toggle()
                        CardClickBehavior.NONE -> { /* 仅水波纹 */ }
                        else -> {}
                    }
                }

                b.spAction.setOnItemClickListener { _, _, itemPos, _ ->
                    openSpinnerPos = -1
                    val nm = availableModes[itemPos]
                    ShortcutMeta.setOverride(cfg, overrideKey, nm)
                    cfg.save()
                    Config.syncToSharedPrefs(requireContext(), cfg)
                    LogHelper.log(LogHelper.VerboseLevel.INFO,
                        "Notify config change: override ", overrideKey, " → ", nm.name)
                    // OnSpinSelectedNonDefault: Spinner 选中非默认项时的附加行为
                    when (meta.onSpinSelectedNonDefault) {
                        OnSpinSelectedNonDefault.SWITCH_ON -> {
                            if (b.swEnabled.visibility == View.VISIBLE
                                && b.swEnabled.isEnabled && !b.swEnabled.isChecked) {
                                b.swEnabled.isChecked = true
                            }
                        }
                        OnSpinSelectedNonDefault.SWITCH_OFF -> {
                            if (b.swEnabled.visibility == View.VISIBLE
                                && b.swEnabled.isEnabled && b.swEnabled.isChecked) {
                                b.swEnabled.isChecked = false
                            }
                        }
                        OnSpinSelectedNonDefault.NOTHING -> { /* 不操作开关 */ }
                    }
                }
            }

            private fun bindAppKeySpin(
                meta: ShortcutMeta,
                cfg: Config,
                b: ItemShortcutRowBinding,
                pos: Int
            ) {
                val ctx = requireContext()
                val appModes = Config.AppKeyMode.entries
                val modeLabels = appModes.map { it.displayName(ctx) }
                val currentMode = ShortcutMeta.getAppKeyMode(cfg, meta.key)

                b.spAction.setAdapter(null)
                b.spAction.setAdapter(ArrayAdapter(ctx, R.layout.dropdown_item_wrap, modeLabels))
                b.spAction.threshold = Int.MAX_VALUE
                b.spAction.setText(currentMode.displayName(ctx), false)
                b.spAction.isEnabled = true
                b.tilAction.isEnabled = true
                b.tilAction.visibility = View.VISIBLE

                b.root.setOnClickListener {
                    if (openSpinnerPos == pos) {
                        openSpinnerPos = -1
                        return@setOnClickListener
                    }
                    b.spAction.showDropDown()
                    openSpinnerPos = pos
                }
                b.root.setOnLongClickListener {
                    AppKeySystemSettingsLauncher.open(ctx, meta.key)
                    true
                }

                b.spAction.setOnItemClickListener { _, _, itemPos, _ ->
                    openSpinnerPos = -1
                    val selected = appModes[itemPos]
                    ShortcutMeta.setAppKeyMode(cfg, meta.key, selected)
                    cfg.save()
                    Config.syncToSharedPrefs(ctx, cfg)
                    b.spAction.setText(selected.displayName(ctx), false)
                    LogHelper.log(LogHelper.VerboseLevel.INFO,
                        "App key mode: ", meta.key, " → ", selected.name)
                    if (selected == Config.AppKeyMode.CUSTOM) {
                        AppKeyCommandDialog.show(ctx, meta.key) {
                            notifyItemChanged(pos)
                        }
                    }
                }
            }

            private fun bindWinLongSpin(
                meta: ShortcutMeta,
                cfg: Config,
                b: ItemShortcutRowBinding,
                pos: Int
            ) {
                val ctx = requireContext()
                val modes = WinLongPressUiMode.entries
                val modeLabels = modes.map { it.displayName(ctx) }
                val currentMode = ShortcutMeta.getWinLongPressUiMode(cfg)

                b.spAction.setAdapter(null)
                b.spAction.setAdapter(ArrayAdapter(ctx, R.layout.dropdown_item_wrap, modeLabels))
                b.spAction.threshold = Int.MAX_VALUE
                b.spAction.setText(currentMode.displayName(ctx), false)
                b.spAction.isEnabled = true
                b.tilAction.isEnabled = true
                b.tilAction.visibility = View.VISIBLE

                b.root.setOnClickListener {
                    if (openSpinnerPos == pos) {
                        openSpinnerPos = -1
                        return@setOnClickListener
                    }
                    b.spAction.showDropDown()
                    openSpinnerPos = pos
                }
                b.root.setOnLongClickListener {
                    if (ShortcutMeta.getWinLongPressUiMode(cfg) == WinLongPressUiMode.CUSTOM) {
                        AppKeyCommandDialog.show(ctx, meta.key) {
                            notifyItemChanged(pos)
                        }
                    }
                    true
                }

                b.spAction.setOnItemClickListener { _, _, itemPos, _ ->
                    openSpinnerPos = -1
                    val selected = modes[itemPos]
                    ShortcutMeta.setWinLongPressUiMode(cfg, selected)
                    cfg.save()
                    Config.syncToSharedPrefs(ctx, cfg)
                    b.spAction.setText(selected.displayName(ctx), false)
                    LogHelper.log(LogHelper.VerboseLevel.INFO,
                        "Win long mode: ", selected.name)
                    if (selected == WinLongPressUiMode.CUSTOM) {
                        AppKeyCommandDialog.show(ctx, meta.key) {
                            notifyItemChanged(pos)
                        }
                    }
                }
            }
        }
    }
}

/** 
 * 设置 Tab — 全局开关列表。
 */
class SettingsFragment : Fragment(R.layout.fragment_recycler) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentRecyclerBinding.bind(view)
        binding.swipeRefresh.isEnabled = false  // 设置页不需要下拉刷新
        binding.searchView.visibility = View.GONE   // 设置页不需要搜索
        binding.btnFilter.visibility = View.GONE    // 设置页不需要筛选
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = SettingsAdapter(this)
    }

    class SettingsAdapter(private val host: Fragment) : RecyclerView.Adapter<SettingsAdapter.VH>() {

        /** 当前展开的 Combo 位置，-1 表示全部收起。卡片点击时用于 toggle。 */
        private var openComboPos = -1

        // ── 卡片类型：Tap=点击跳转, Switch=开关, Combo=下拉多选 ──
        sealed class SettingItem(val label: String, val desc: String = "") {
            class Tap(label: String, desc: String = "", val onClick: (VH) -> Unit) : SettingItem(label, desc)
            class Switch(label: String, desc: String = "", val getChecked: () -> Boolean, val onChanged: (Boolean) -> Unit) : SettingItem(label, desc)
            class Combo(label: String, desc: String = "", val getOptions: () -> List<String>, val getCurrentText: () -> String, val onSelected: (Int) -> Unit) : SettingItem(label, desc)
        }

        /** 通用自定义值输入对话框。apply 对输入值进行转换并写回 Config。 */
        private fun showCustomValueDialog(
            @androidx.annotation.StringRes titleRes: Int,
            @androidx.annotation.StringRes msgRes: Int,
            @androidx.annotation.StringRes hintRes: Int,
            currentValue: String,
            blockEmpty: Boolean = false,
            apply: (Config, String) -> Unit
        ) {
            val ctx = host.requireContext()
            val cfg = Config.load()
            val input = android.widget.EditText(ctx)
            input.hint = ctx.getString(hintRes)
            input.setText(currentValue)

            AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(titleRes))
                .setMessage(ctx.getString(msgRes))
                .setView(input)
                .setPositiveButton(ctx.getString(R.string.dialog_confirm_ok)) { _, _ ->
                    val v = input.text.toString().trim()
                    if (!blockEmpty || v.isNotEmpty()) {
                        apply(cfg, v)
                        cfg.save()
                        Config.syncToSharedPrefs(ctx, cfg)
                    }
                    notifyDataSetChanged()
                }
                .setOnCancelListener { notifyDataSetChanged() }
                .setNegativeButton(ctx.getString(R.string.dialog_confirm_cancel)) { _, _ -> notifyDataSetChanged() }
                .show()
        }

        private fun getSysProp(key: String, fallback: String): String {
            return try {
                val sp = Class.forName("android.os.SystemProperties")
                sp.getMethod("get", String::class.java, String::class.java)
                    .invoke(null, key, fallback) as? String ?: fallback
            } catch (_: Exception) { fallback }
        }

        private val items: List<SettingItem> = run {
            val ctx = host.requireContext()
            val cfg = Config.load()
            listOf(
                SettingItem.Switch(ctx.getString(R.string.settings_master_switch), ctx.getString(R.string.settings_master_switch_desc),
                    getChecked = { cfg.zuxKeyboardFuncEnabled },
                    onChanged = { cfg.zuxKeyboardFuncEnabled = it; cfg.save(); Config.syncToSharedPrefs(host.requireContext(), cfg) }
                ),
                SettingItem.Tap(ctx.getString(R.string.settings_fn_entry), ctx.getString(R.string.settings_fn_entry_desc),
                    onClick = {
                        host.startActivity(Intent(host.requireContext(), FnSettingsActivity::class.java))
                    }
                ),
                SettingItem.Tap(ctx.getString(R.string.settings_ime_entry), ctx.getString(R.string.settings_ime_entry_desc),
                    onClick = {
                        host.startActivity(Intent(host.requireContext(), IMESettingsActivity::class.java))
                    }
                ),
                SettingItem.Tap(ctx.getString(R.string.settings_termux_perm_entry), ctx.getString(R.string.settings_termux_perm_entry_desc),
                    onClick = {
                        TermuxPermissionDialog.show(host.requireContext())
                    }
                ),
                SettingItem.Tap(ctx.getString(R.string.settings_appearance_entry), ctx.getString(R.string.settings_appearance_entry_desc),
                    onClick = {
                        host.startActivity(Intent(host.requireContext(), AppearanceSettingsActivity::class.java))
                    }
                ),
                SettingItem.Combo(ctx.getString(R.string.settings_log_level), ctx.getString(R.string.settings_log_level_desc),
                    getOptions = {
                        moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel.values().map { it.label }
                    },
                    getCurrentText = { cfg.verboseLevel.label },
                    onSelected = { idx ->
                        val levels = moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel.values()
                        cfg.verboseLevel = levels[idx]
                        moe.lovefirefly.betterzuikey.Utils.LogHelper.currentLevel = cfg.verboseLevel
                        cfg.save()
                        Config.syncToSharedPrefs(host.requireContext(), cfg)
                    }
                ),
                SettingItem.Combo(ctx.getString(R.string.settings_language), ctx.getString(R.string.settings_language_desc),
                    getOptions = {
                        LocaleHelper.ENTRIES.map { LocaleHelper.getDisplayName(ctx, it.tag) }
                    },
                    getCurrentText = {
                        LocaleHelper.currentEntryDisplay(ctx, cfg)
                    },
                    onSelected = { idx ->
                        val tag = LocaleHelper.ENTRIES[idx].tag
                        LocaleHelper.applyAndRecreate(host.requireContext(), tag)
                    }
                ),
                SettingItem.Combo(ctx.getString(R.string.settings_meta_label), ctx.getString(R.string.settings_meta_label_desc),
                    getOptions = { listOf("Win", "Meta") },
                    getCurrentText = { cfg.metaKeyLabel },
                    onSelected = { idx ->
                        cfg.metaKeyLabel = if (idx == 0) "Win" else "Meta"
                        cfg.save()
                        Config.syncToSharedPrefs(host.requireContext(), cfg)
                    }
                ),
            )
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemShortcutRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(val b: ItemShortcutRowBinding) : RecyclerView.ViewHolder(b.root) {

            fun bind(item: SettingItem) {
                val ctx = host.requireContext()

                b.tvName.text = item.label
                b.tvDesc.text = item.desc
                b.tvDesc.visibility = if (item.desc.isBlank()) View.GONE else View.VISIBLE

                // 清除旧的监听器
                b.root.setOnClickListener(null)
                b.swEnabled.setOnCheckedChangeListener(null)
                b.spAction.setOnClickListener(null)
                b.spAction.setOnItemClickListener(null)

                when (item) {
                    is SettingItem.Tap -> {
                        // ── Tap 型：纯点击卡片，右侧显示箭头 ──
                        b.tilAction.visibility = View.GONE
                        b.swEnabled.visibility = View.GONE
                        b.ivChevron.visibility = View.VISIBLE
                        b.root.setOnClickListener { item.onClick(this) }
                    }
                    is SettingItem.Switch -> {
                        // ── Switch 型：右侧显示开关，点击卡片切换 ──
                        b.tilAction.visibility = View.GONE
                        b.swEnabled.visibility = View.VISIBLE
                        b.ivChevron.visibility = View.GONE
                        b.swEnabled.isChecked = item.getChecked()
                        b.swEnabled.setOnCheckedChangeListener { _, checked ->
                            item.onChanged(checked)
                        }
                        b.root.setOnClickListener {
                            b.swEnabled.toggle()
                        }
                    }
                    is SettingItem.Combo -> {
                        // ── Combo 型：右侧显示下拉菜单，点击卡片展开/收回 ──
                        b.tilAction.visibility = View.VISIBLE
                        b.swEnabled.visibility = View.GONE
                        b.ivChevron.visibility = View.GONE

                        val options = item.getOptions()
                        val curText = item.getCurrentText()
                        val adapter = android.widget.ArrayAdapter(ctx, R.layout.dropdown_item_wrap, options)

                        b.spAction.setAdapter(adapter)
                        b.spAction.threshold = Int.MAX_VALUE
                        b.spAction.setText(curText, false)
                        b.spAction.setOnItemClickListener { _, _, pos, _ ->
                            openComboPos = -1
                            item.onSelected(pos)
                            b.spAction.setText(options[pos], false)
                        }
                        b.root.setOnClickListener {
                            if (openComboPos == position) {
                                // 同一张 card：收回
                                openComboPos = -1
                                b.spAction.dismissDropDown()
                            } else {
                                openComboPos = position
                                b.spAction.requestFocus()
                                b.spAction.post { b.spAction.showDropDown() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 应用模板 Tab — RecyclerView + FAB + 重命名 + App 选择器。
 */
class TemplatesFragment : Fragment(R.layout.fragment_templates) {

    private lateinit var binding: FragmentTemplatesBinding
    private lateinit var adapter: TemplateAdapter
    private var pendingPickerPos = -1

    /** 加载配置、应用变更、保存并同步到 SharedPrefs（供 system_server 读取） */
    private fun mutateConfig(block: (Config) -> Unit) {
        val cfg = Config.load()
        block(cfg)
        cfg.save()
        Config.syncToSharedPrefs(requireContext(), cfg)
    }

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val pkgs = result.data?.getStringArrayListExtra("packages") ?: return@registerForActivityResult
            if (pendingPickerPos < 0) return@registerForActivityResult
            mutateConfig { cfg ->
                if (pendingPickerPos < cfg.templates.size) {
                    cfg.templates[pendingPickerPos].packages.clear()
                    cfg.templates[pendingPickerPos].packages.addAll(pkgs)
                }
            }
            adapter.refresh()
            pendingPickerPos = -1
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentTemplatesBinding.bind(view)

        adapter = TemplateAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        // Long-press drag to reorder
        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView, src: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    @Suppress("DEPRECATION")
                    val from = src.adapterPosition
                    @Suppress("DEPRECATION")
                    val to = target.adapterPosition
                    if (from == to) return false
                    // Swap in-memory without full refresh to keep drag alive
                    val temp = adapter.filtered[from]
                    adapter.filtered[from] = adapter.filtered[to]
                    adapter.filtered[to] = temp
                    adapter.notifyItemMoved(from, to)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
                override fun isLongPressDragEnabled() = true

                override fun onChildDraw(
                    canvas: android.graphics.Canvas, recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
                ) {
                    if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                        viewHolder.itemView.apply {
                            elevation = 12f
                            scaleX = 0.98f
                            scaleY = 0.98f
                            translationZ = 12f
                        }
                    }
                    super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    viewHolder.itemView.apply {
                        elevation = 0f
                        scaleX = 1f
                        scaleY = 1f
                        translationZ = 0f
                    }
                    super.clearView(recyclerView, viewHolder)
                    // Persist the new order to config
                    // Ensure all items have IDs before persisting (old templates may lack UUIDs)
                    for (t in adapter.filtered) {
                        if (t.id == null) t.id = java.util.UUID.randomUUID().toString()
                    }
                    val ids = adapter.filtered.map { it.id }
                    mutateConfig { c ->
                        val old = c.templates.toList()
                        c.templates.clear()
                        for (id in ids) {
                            old.firstOrNull { it.id == id }?.let { c.templates.add(it) }
                        }
                    }
                }
            })
        touchHelper.attachToRecyclerView(binding.recycler)

        binding.searchView.setOnQueryTextListener(object : android.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean { adapter.filter(q ?: ""); return true }
            override fun onQueryTextChange(q: String?): Boolean { adapter.filter(q ?: ""); return true }
        })

        binding.fabAddTemplate.setOnClickListener {
            showNameDialog("") { name ->
                mutateConfig { cfg -> cfg.templates.add(KeyTemplate(name)) }
                adapter.refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            adapter.refresh()
        }
    }

    // ── TemplateAdapter ──

    inner class TemplateAdapter : RecyclerView.Adapter<TemplateAdapter.VH>() {

        private var allItems: List<KeyTemplate> = emptyList()
        internal val filtered: MutableList<KeyTemplate> = mutableListOf()
        internal var searchQuery: String = ""

        init { refresh() }

        fun refresh() {
            allItems = Config.load().templates.toList()
            // Fix null IDs for old templates
            for (t in allItems) {
                if (t.id == null) t.id = java.util.UUID.randomUUID().toString()
            }
            filter(searchQuery)
            binding.tvEmpty.visibility = if (allItems.isEmpty()) View.VISIBLE else View.GONE
        }

        fun filter(query: String?) {
            searchQuery = (query ?: "").trim()
            filtered.clear()
            filtered.addAll(if (searchQuery.isEmpty()) allItems
            else allItems.filter { it.name.contains(searchQuery, ignoreCase = true) })
            notifyDataSetChanged()
        }

        override fun getItemCount() = filtered.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTemplateRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = filtered[position]
            val realPos = allItems.indexOf(t)
            holder.bind(t, realPos)
        }

        inner class VH(private val b: ItemTemplateRowBinding) : RecyclerView.ViewHolder(b.root) {

            fun bind(t: KeyTemplate, pos: Int) {
                b.tvTemplateName.text = t.name
                b.swEnabled.isChecked = t.enabled

                // 点击卡片 → 打开编辑器
                b.root.setOnClickListener {
                    val intent = Intent(requireContext(), TemplateEditorActivity::class.java)
                    intent.putExtra("template_index", pos)
                    startActivity(intent)
                }

                b.swEnabled.setOnCheckedChangeListener { _, checked ->
                    mutateConfig { cfg ->
                        if (pos < cfg.templates.size) cfg.templates[pos].enabled = checked
                    }
                }

                b.tvRename.setOnClickListener {
                    showNameDialog(t.name) { newName ->
                        mutateConfig { cfg ->
                            if (pos < cfg.templates.size) cfg.templates[pos].name = newName
                        }
                        adapter.refresh()
                    }
                }

                val count = t.packages.size
                b.tvApps.text = getString(R.string.templates_select_apps, count)
                b.tvApps.setOnClickListener {
                    pendingPickerPos = pos
                    val intent = Intent(requireContext(), AppPickerActivity::class.java)
                    intent.putExtra("template_index", pos)
                    intent.putStringArrayListExtra("preselected", ArrayList(t.packages))
                    appPickerLauncher.launch(intent)
                }

                b.etMoveCount.setText("1")
                b.etMoveCount2.setText("1")

                fun moveTemplate(delta: Int) {
                    val n = when {
                        delta < 0 -> b.etMoveCount.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: 1
                        else -> b.etMoveCount2.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: 1
                    }
                    val oldPos = allItems.indexOfFirst { it.id == t.id }
                    if (oldPos < 0) return
                    val newPos = if (delta < 0) (oldPos - n).coerceAtLeast(0)
                                 else (oldPos + n).coerceAtMost(allItems.size - 1)
                    if (oldPos == newPos) return
                    mutateConfig { cfg ->
                        cfg.templates.add(newPos, cfg.templates.removeAt(oldPos))
                    }
                    allItems = Config.load().templates.toList()
                    if (searchQuery.isEmpty()) {
                        filtered.clear(); filtered.addAll(allItems)
                        notifyItemMoved(oldPos, newPos)
                    } else {
                        filter(searchQuery)
                    }
                }

                b.tvMoveUp.setOnClickListener { moveTemplate(-1) }
                b.tvMoveDown.setOnClickListener { moveTemplate(+1) }

                b.tvCopy.setOnClickListener {
                    val ctx = requireContext()
                    val cbLayout = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(48, 16, 48, 8)
                    }
                    val cb = android.widget.CheckBox(ctx).apply {
                        text = getString(R.string.templates_copy_apps)
                        isChecked = true
                    }
                    cbLayout.addView(cb)
                    val warn = TextView(ctx).apply {
                        text = getString(R.string.templates_copy_warn_dup)
                        setTextColor(0xFFB8860B.toInt()) // dark goldenrod
                        textSize = 12f
                        setPadding(0, 8, 0, 0)
                    }
                    cbLayout.addView(warn)
                    AlertDialog.Builder(ctx)
                        .setTitle(getString(R.string.templates_copy_title))
                        .setMessage(getString(R.string.templates_copy_msg, t.name))
                        .setView(cbLayout)
                        .setPositiveButton(getString(R.string.dialog_confirm_ok)) { _, _ ->
                            mutateConfig { cfg ->
                                // Auto-name: "a" → "a-2", "a-2" → "a-3", etc.
                                var copyName = t.name
                                val m = Regex("^(.*)-(\\d+)$", RegexOption.IGNORE_CASE).find(copyName)
                                val base = m?.groupValues?.get(1) ?: copyName
                                val pattern = Regex("^" + Regex.escape(base) + "-(\\d+)$", RegexOption.IGNORE_CASE)
                                val maxN = cfg.templates
                                    .mapNotNull { pattern.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
                                    .maxOrNull() ?: 1
                                copyName = "$base-${maxN + 1}"
                                val copy = KeyTemplate(copyName)
                                copy.enabled = t.enabled
                                copy.overrides.putAll(t.overrides.mapValues {
                                    PerKeyOverride().apply {
                                        overrideMode = it.value.overrideMode
                                        switchState = it.value.switchState
                                    }
                                })
                                if (cb.isChecked) copy.packages.addAll(t.packages)
                                cfg.templates.add(copy) // append to end
                            }
                            adapter.refresh()
                        }
                        .setNegativeButton(getString(R.string.dialog_confirm_cancel), null)
                        .show()
                }

                b.tvDelete.setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.templates_delete_title))
                        .setMessage(getString(R.string.templates_delete_msg, t.name))
                        .setPositiveButton(getString(R.string.templates_btn_delete_confirm)) { _, _ ->
                            mutateConfig { cfg -> cfg.templates.removeAt(pos) }
                            adapter.refresh()
                        }
                        .setNegativeButton(getString(R.string.dialog_confirm_cancel), null)
                        .show()
                }
            }
        }
    }

    // ── 对话框 ──

    private fun showNameDialog(current: String, onOk: (String) -> Unit) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(current)
            setSingleLine()
            selectAll()
            if (current.isEmpty()) {
                setHint(getString(R.string.templates_new_template_hint))
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.templates_name_dialog_title))
            .setView(input)
            .setPositiveButton(getString(R.string.dialog_confirm_ok)) { _, _ ->
                val rawName = input.text.toString().trim()
                if (rawName.isEmpty()) return@setPositiveButton
                // Auto-suffix duplicate names: a → a-2, a-3, …
                val cfg = Config.load()
                var name = rawName
                val exists: (String) -> Boolean = { n ->
                    cfg.templates.any { it.name.equals(n, ignoreCase = true) && it.name != current }
                }
                if (exists(name)) {
                    // Extract base name: "a-2" → base="a", "a" → base="a"
                    val m = Regex("^(.*)-(\\d+)$", RegexOption.IGNORE_CASE).find(name)
                    val base = m?.groupValues?.get(1) ?: name
                    val pattern = Regex("^" + Regex.escape(base) + "-(\\d+)$", RegexOption.IGNORE_CASE)
                    val maxN = cfg.templates
                        .mapNotNull { pattern.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
                        .maxOrNull() ?: 1
                    name = "$base-${maxN + 1}"
                }
                if (name != rawName) {
                    android.widget.Toast.makeText(requireContext(),
                        getString(R.string.templates_name_duplicate, rawName, name),
                        android.widget.Toast.LENGTH_SHORT).show()
                }
                onOk(name)
            }
            .setNegativeButton(getString(R.string.dialog_confirm_cancel), null)
            .show()
    }

}
