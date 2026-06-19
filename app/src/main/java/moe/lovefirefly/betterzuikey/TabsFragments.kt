package moe.lovefirefly.betterzuikey

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import moe.lovefirefly.betterzuikey.Utils.LogHelper
import moe.lovefirefly.betterzuikey.databinding.FragmentRecyclerBinding
import moe.lovefirefly.betterzuikey.databinding.ItemShortcutRowBinding
import moe.lovefirefly.betterzuikey.databinding.FragmentTemplatesBinding
import moe.lovefirefly.betterzuikey.databinding.ItemTemplateRowBinding

/**
 * 全局配置 Tab — 显示所有快捷键的系统开关。
 * 支持下拉刷新和 su 自授权。
 */
class GlobalFragment : Fragment(R.layout.fragment_recycler) {

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
    private var cachedConfig: Config? = null
    /** 首次初始化标志 — 只设一次 LayoutManager/Adapter/SwipeRefresh/PermissionCheck */
    private var firstInitDone = false

    private fun reloadConfig(): Config {
        val cfg = Config.load()
        cfg.syncSwitchesFromSystem(requireContext())
        cfg.save()
        Config.syncToSharedPrefs(requireContext(), cfg)
        cachedConfig = cfg
        return cfg
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentRecyclerBinding.bind(view)
        val recycler = binding.recycler

        if (!firstInitDone) {
            firstInitDone = true
            recycler.layoutManager = LinearLayoutManager(requireContext())
            adapter = ShortcutAdapter()
            recycler.adapter = adapter

            binding.swipeRefresh.setOnRefreshListener {
                canWriteSystemSettings = null
                checkWritePermission(view)
                reloadConfig()
                adapter.notifyDataSetChanged()
                if (canWriteSystemSettings == true) {
                    view.findViewById<CardView>(R.id.cardWriteWarning)?.visibility = View.GONE
                }
                binding.swipeRefresh.isRefreshing = false
            }

            checkWritePermission(view)
        }

        reloadConfig()
        adapter.notifyDataSetChanged()
    }

    /** 切回此 Tab 时统一刷新（开关状态可能已通过系统或其他途径变化） */
    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            reloadConfig()
            adapter.notifyDataSetChanged()
        }
    }

    private fun checkWritePermission(view: View) {
        if (canWriteSystemSettings != null) return
        val err = Config.writeSystemSwitch(requireContext(), "winD", true)
        canWriteSystemSettings = (err == null)
        if (err != null) showWriteWarning(view, err)
    }

    private fun showWriteWarning(view: View, errorMsg: String? = null) {
        val card = view.findViewById<CardView>(R.id.cardWriteWarning) ?: return
        card.visibility = View.VISIBLE

        // 显示具体错误原因
        if (!errorMsg.isNullOrBlank()) {
            val tvError = card.findViewById<TextView>(R.id.tvWriteErrorDetail)
            tvError?.text = "原因: $errorMsg"
            tvError?.visibility = View.VISIBLE
        }

        // 方法一：前往系统授权页
        view.findViewById<Button>(R.id.btnGrantSettings)?.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                })
            } catch (_: Exception) { }
        }

        // 方法二：su -c 自授权（appops）
        view.findViewById<Button>(R.id.btnSuGrant)?.setOnClickListener {
            trySuGrant(view)
        }
    }

    /** 通过 su -c appops 自授权 WRITE_SETTINGS */
    private fun trySuGrant(view: View) {
        val pkg = requireContext().packageName
        val cmd = "appops set $pkg WRITE_SETTINGS allow"

        val thread = Thread {
            var success = false
            var output = ""
            try {
                val su = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                // 读取 stdout
                val reader = java.io.BufferedReader(java.io.InputStreamReader(su.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line).append("\n")
                reader.close()
                output = sb.toString().trim()
                su.waitFor()
                success = (su.exitValue() == 0)
                // 读取 stderr
                val errReader = java.io.BufferedReader(java.io.InputStreamReader(su.errorStream))
                val errSb = StringBuilder()
                while (errReader.readLine().also { line = it } != null) errSb.append(line).append("\n")
                errReader.close()
                val errOutput = errSb.toString().trim()
                if (errOutput.isNotEmpty()) output = if (output.isEmpty()) errOutput else "$output\n$errOutput"
            } catch (e: Exception) {
                output = e.message ?: "未知错误"
            }

            val finalSuccess = success
            val finalOutput = output

            requireActivity().runOnUiThread {
                if (finalSuccess) {
                    // 重新验证权限
                    canWriteSystemSettings = null
                    checkWritePermission(view)
                    if (canWriteSystemSettings == true) {
                        view.findViewById<CardView>(R.id.cardWriteWarning)?.visibility = View.GONE
                        AlertDialog.Builder(requireContext())
                            .setTitle("授权成功")
                            .setMessage("系统设置写入权限已通过 su 授予，开关可正常同步到系统。")
                            .setPositiveButton("确定", null)
                            .show()
                    } else {
                        AlertDialog.Builder(requireContext())
                            .setTitle("授权可能未生效")
                            .setMessage("su 执行成功但权限验证仍失败。\n\n输出: $finalOutput")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle("su 授权失败")
                        .setMessage("无法获取 Root 权限。\n请确保已安装 Magisk/KSU 并授权。\n\n" +
                            "命令: su -c \"$cmd\"\n" +
                            (if (finalOutput.isNotEmpty()) "输出: $finalOutput" else ""))
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    /** RecyclerView Adapter — 48 条静态数据，直接字段访问，不使用反射 */
    inner class ShortcutAdapter : RecyclerView.Adapter<ShortcutAdapter.VH>() {

        /** 所有 Spinner 统一固定最小宽度（px），按全部可能文本的最宽值一次性计算 */
        private var spinnerFixedMinWidth: Int = 0

        override fun getItemCount() = ShortcutMeta.ALL.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemShortcutRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            // 首次创建时，根据所有 OverrideMode 显示名称计算最大文本宽度，统一设置
            if (spinnerFixedMinWidth == 0) {
                val paint = b.spAction.paint
                val maxTextW = Config.OverrideMode.entries.maxOf {
                    paint.measureText(it.displayName()).toInt()
                }
                // 额外留出 dropdown 图标 + 内边距空间
                spinnerFixedMinWidth = maxTextW + b.spAction.paddingLeft +
                    b.spAction.paddingRight + 48
            }
            // 固定宽度（不仅是 minWidth），防止 setText 不同文本时宽度抖动
            b.spAction.minWidth = spinnerFixedMinWidth
            b.spAction.maxWidth = spinnerFixedMinWidth
            b.spAction.dropDownWidth = spinnerFixedMinWidth
            // 同时约束外层 TextInputLayout
            b.tilAction.minWidth = spinnerFixedMinWidth
            b.tilAction.layoutParams?.let { lp ->
                lp.width = spinnerFixedMinWidth
                b.tilAction.layoutParams = lp
            }
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(ShortcutMeta.ALL[position])
        }

        inner class VH(private val b: ItemShortcutRowBinding) : RecyclerView.ViewHolder(b.root) {

            fun bind(meta: ShortcutMeta) {
                val cfg = cachedConfig ?: return
                // ctrlCard: switch → ctrlLongPress, spinner → ctrlSlash
                val isCtrlCard = meta.key == "ctrlCard"
                val switchKey = if (isCtrlCard) "ctrlLongPress" else meta.key
                val overrideKey = if (isCtrlCard) "ctrlSlash" else meta.key
                val switchState = ShortcutMeta.getSwitch(cfg, switchKey)
                val overrideMode = ShortcutMeta.getOverride(cfg, overrideKey)

                // ── 始终先清空所有视图状态，防止 RecyclerView 复用残留旧数据 ──
                b.tvName.text = meta.displayName
                b.tvDesc.text = meta.displayDesc
                b.tvDesc.visibility = View.VISIBLE
                b.swEnabled.setOnCheckedChangeListener(null)
                b.spAction.setOnItemClickListener(null)
                b.spAction.setOnClickListener(null)
                b.root.setOnClickListener(null)

                // ── 开关（系统开关 或 ctrlCard 的 Ctrl 长按开关）──
                val isAospSecure = meta.key in AOSP_SECURE_KEY_MAP
                if (meta.showSwitch || isAospSecure) {
                    b.swEnabled.visibility = View.VISIBLE
                    if (isAospSecure) {
                        // AOSP 辅助键：直接读写 Settings.Secure
                        val secureKey = AOSP_SECURE_KEY_MAP[meta.key]!!
                        b.swEnabled.isEnabled = true
                        b.swEnabled.isChecked = try {
                            Settings.Secure.getInt(requireContext().contentResolver, secureKey) == 1
                        } catch (_: Exception) { false }
                        b.swEnabled.setOnCheckedChangeListener { _, isChecked ->
                            try {
                                Settings.Secure.putInt(requireContext().contentResolver, secureKey, if (isChecked) 1 else 0)
                            } catch (_: SecurityException) {
                                try {
                                    Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put secure $secureKey ${if (isChecked) 1 else 0}"))
                                } catch (_: Exception) { }
                            }
                        }
                    } else {
                        b.swEnabled.isEnabled = switchState.isUserToggleable
                        b.swEnabled.isChecked = switchState.isEnabled
                        b.swEnabled.setOnCheckedChangeListener { _, isChecked ->
                            val newState = if (isChecked) Config.SwitchState.ON else Config.SwitchState.OFF
                            ShortcutMeta.setSwitch(cfg, switchKey, newState)
                            // 组内同步
                            for (gk in meta.groupKeys) {
                                ShortcutMeta.setSwitch(cfg, gk, newState)
                            }
                            val err = Config.writeSystemSwitch(requireContext(), switchKey, isChecked)
                            if (err != null) {
                                LogHelper.log(LogHelper.VerboseLevel.WARNING,
                                    "Failed to sync switch to system:", err)
                                canWriteSystemSettings = false
                                showWriteWarning(b.root.rootView, err)
                            }
                            cfg.syncSwitchesFromSystem(requireContext())
                            cfg.save()
                            Config.syncToSharedPrefs(requireContext(), cfg)
                            // 不主动刷新列表 — 切回此 Tab 时 onResume 会统一刷新
                        }
                    }
                } else {
                    b.swEnabled.visibility = View.GONE
                }

                // ── 行为覆写下拉 ──
                val modes = Config.OverrideMode.entries
                    .filter { it.isAvailable(meta) && (it != Config.OverrideMode.AOSP || meta.showAospOption) }
                    .map { it.displayName() }
                // Clear old adapter first to force visual refresh on rebind
                b.spAction.setAdapter(null)
                b.spAction.setAdapter(android.widget.ArrayAdapter(
                    requireContext(), R.layout.dropdown_item_wrap, modes))
                b.spAction.threshold = Int.MAX_VALUE
                b.spAction.setText(overrideMode.displayName(), false)

                b.spAction.setOnItemClickListener { _, _, pos, _ ->
                    val nm = Config.OverrideMode.entries.filter { it.isAvailable(meta) && (it != Config.OverrideMode.AOSP || meta.showAospOption) }[pos]
                    ShortcutMeta.setOverride(cfg, overrideKey, nm)
                    cfg.save()
                    // Push full JSON via SharedPreferences → XSharedPreferences reads in system_server
                    Config.syncToSharedPrefs(requireContext(), cfg)
                    LogHelper.log(LogHelper.VerboseLevel.INFO,
                        "Notify config change: override ", overrideKey, " → ", nm.name)
                    // 操作优化：修改覆写模式后，若开关存在、可操作且未开启，则自动打开
                    // 由 ShortcutMeta.autoSwitchOnIfPossible 控制是否启用此行为
                    // - false 的场景：AOSP 辅助键 (Win+Alt+3,4,5,6) 开关直读 Settings.Secure 不走 Config
                    // - false 的场景：Ctrl Card 的 Ctrl 长按开关，switch 和 spinner 分别控制两个不同的功能，应当互不影响
                    if (meta.autoSwitchOnIfPossible
                            && b.swEnabled.visibility == View.VISIBLE
                            && b.swEnabled.isEnabled && !b.swEnabled.isChecked) {
                        b.swEnabled.isChecked = true
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
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = SettingsAdapter(this)
    }

    class SettingsAdapter(private val host: Fragment) : RecyclerView.Adapter<SettingsAdapter.VH>() {

        // ── 卡片类型：Tap=点击跳转, Switch=开关, Combo=下拉多选 ──
        sealed class SettingItem(val label: String, val desc: String = "") {
            class Tap(label: String, desc: String = "", val onClick: (VH) -> Unit) : SettingItem(label, desc)
            class Switch(label: String, desc: String = "", val getChecked: () -> Boolean, val onChanged: (Boolean) -> Unit) : SettingItem(label, desc)
            class Combo(label: String, desc: String = "", val getOptions: () -> List<String>, val getCurrentText: () -> String, val onSelected: (Int) -> Unit) : SettingItem(label, desc)
        }

        private fun showRegionCustomDialog() {
            val ctx = host.requireContext()
            val cfg = Config.load()
            val input = android.widget.EditText(ctx)
            input.hint = "例如: TW, JP, US"
            input.setText(cfg.regionCustomValue)

            AlertDialog.Builder(ctx)
                .setTitle("自定义区域值")
                .setMessage("输入一个区域代码，将覆写 ro.config.lgsi.region 属性")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val v = input.text.toString().trim()
                    if (v.isNotEmpty()) {
                        cfg.regionCustomValue = v
                        cfg.regionOverride = moe.lovefirefly.betterzuikey.Region.RegionProfile.CUSTOM
                        cfg.save()
                        Config.syncToSharedPrefs(host.requireContext(), cfg)
                    }
                    // 无论输入与否都刷新，空输入会回退到上一个值
                    notifyDataSetChanged()
                }
                .setOnCancelListener {
                    // 取消时回退到上一个值
                    notifyDataSetChanged()
                }
                .setNegativeButton("取消") { _, _ ->
                    notifyDataSetChanged()
                }
                .show()
        }

        private fun showCountryCustomDialog() {
            val ctx = host.requireContext()
            val cfg = Config.load()
            val input = android.widget.EditText(ctx)
            input.hint = "例如: KR, JP, US"
            input.setText(cfg.countryOverride)

            AlertDialog.Builder(ctx)
                .setTitle("自定义国家/地区值")
                .setMessage("输入一个国家代码，将覆写 ro.config.lgsi.countrycode 属性")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val v = input.text.toString().trim().uppercase()
                    cfg.countryOverride = v
                    cfg.save()
                    Config.syncToSharedPrefs(host.requireContext(), cfg)
                    notifyDataSetChanged()
                }
                .setOnCancelListener { notifyDataSetChanged() }
                .setNegativeButton("取消") { _, _ -> notifyDataSetChanged() }
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
            val cfg = Config.load()
            listOf(
                SettingItem.Switch("模块功能开关", "关闭后所有 Hook 失效，快捷键恢复 ZUI 原始行为",
                    getChecked = { cfg.zuxKeyboardFuncEnabled },
                    onChanged = { cfg.zuxKeyboardFuncEnabled = it; cfg.save(); Config.syncToSharedPrefs(host.requireContext(), cfg) }
                ),
                SettingItem.Tap("虚拟 Fn 键", "管理 Fn 组合键配置文件与键值映射",
                    onClick = {
                        host.startActivity(Intent(host.requireContext(), FnSettingsActivity::class.java))
                    }
                ),
                SettingItem.Switch("OneVision 特性", "启用联想 OneVision 跨屏协同相关快捷键行为",
                    getChecked = { cfg.oneVisionFeatureEnabled },
                    onChanged = { cfg.oneVisionFeatureEnabled = it; cfg.save(); Config.syncToSharedPrefs(host.requireContext(), cfg) }
                ),
                SettingItem.Tap("外观设置", "夜间模式与 Material You 主题",
                    onClick = {
                        host.startActivity(Intent(host.requireContext(), AppearanceSettingsActivity::class.java))
                    }
                ),
                SettingItem.Combo("日志级别", "控制 Xposed 模块日志输出详细程度",
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
                SettingItem.Combo("区域", "控制 ro.config.lgsi.region 系统属性（区域判定）",
                    getOptions = {
                        val real = getSysProp("ro.config.lgsi.region", "?")
                        val v = cfg.regionCustomValue.ifBlank { "未配置" }
                        listOf("默认 ($real)", "中国 (CN)", "国际 (ROW)", "自定义 ($v)")
                    },
                    getCurrentText = {
                        val real = getSysProp("ro.config.lgsi.region", "?")
                        when (cfg.regionOverride) {
                            moe.lovefirefly.betterzuikey.Region.RegionProfile.CHINA -> "中国 (CN)"
                            moe.lovefirefly.betterzuikey.Region.RegionProfile.ROW -> "国际 (ROW)"
                            moe.lovefirefly.betterzuikey.Region.RegionProfile.CUSTOM -> {
                                val v = cfg.regionCustomValue.ifBlank { "未配置" }
                                "自定义 ($v)"
                            }
                            else -> "默认 ($real)"
                        }
                    },
                    onSelected = { idx ->
                        when (idx) {
                            0 -> { cfg.regionOverride = moe.lovefirefly.betterzuikey.Region.RegionProfile.DEFAULT; cfg.save(); Config.syncToSharedPrefs(host.requireContext(), cfg) }
                            1 -> { cfg.regionOverride = moe.lovefirefly.betterzuikey.Region.RegionProfile.CHINA;  cfg.save(); Config.syncToSharedPrefs(host.requireContext(), cfg) }
                            2 -> { cfg.regionOverride = moe.lovefirefly.betterzuikey.Region.RegionProfile.ROW;    cfg.save(); Config.syncToSharedPrefs(host.requireContext(), cfg) }
                            3 -> showRegionCustomDialog()
                        }
                    }
                ),
                SettingItem.Combo("国家/地区", "控制 ro.config.lgsi.countrycode 系统属性（国家判定）",
                    getOptions = {
                        val real = getSysProp("ro.config.lgsi.countrycode", "?")
                        val v = cfg.countryOverride.ifBlank { "未配置" }
                        listOf("默认 ($real)", "韩国 (KR)", "自定义 ($v)")
                    },
                    getCurrentText = {
                        val real = getSysProp("ro.config.lgsi.countrycode", "?")
                        when {
                            cfg.countryOverride == "KR" -> "韩国 (KR)"
                            cfg.countryOverride.isNotEmpty() -> "自定义 (${cfg.countryOverride})"
                            else -> "默认 ($real)"
                        }
                    },
                    onSelected = { idx ->
                        when (idx) {
                            0 -> { cfg.countryOverride = ""; cfg.save(); Config.syncToSharedPrefs(host.requireContext(), cfg) }
                            1 -> { cfg.countryOverride = "KR"; cfg.save(); Config.syncToSharedPrefs(host.requireContext(), cfg) }
                            2 -> showCountryCustomDialog()
                        }
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
                        // ── Combo 型：右侧显示下拉菜单，点击卡片展开 ──
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
                            item.onSelected(pos)
                            b.spAction.setText(options[pos], false)
                        }
                        b.root.setOnClickListener {
                            b.spAction.requestFocus()
                            b.spAction.post { b.spAction.showDropDown() }
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

    private lateinit var adapter: TemplateAdapter
    private var pendingPickerPos = -1

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val pkgs = result.data?.getStringArrayListExtra("packages") ?: return@registerForActivityResult
            if (pendingPickerPos < 0) return@registerForActivityResult
            val cfg = Config.load()
            if (pendingPickerPos < cfg.templates.size) {
                cfg.templates[pendingPickerPos].packages.clear()
                cfg.templates[pendingPickerPos].packages.addAll(pkgs)
                cfg.save()
                adapter.refresh()
            }
            pendingPickerPos = -1
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // TODO: 应用模板 - 暂时禁用
        disableAllChildren(view)
        showComingSoonOverlay(view)
        
        val binding = moe.lovefirefly.betterzuikey.databinding.FragmentTemplatesBinding.bind(view)

        adapter = TemplateAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.fabAddTemplate.setOnClickListener {
            showNameDialog("新建模板") { name ->
                val cfg = Config.load()
                cfg.templates.add(KeyTemplate(name))
                cfg.save()
                adapter.refresh()
            }
        }
    }
    
    /** 递归禁用所有子 View 的交互 */
    private fun disableAllChildren(view: View) {
        view.isEnabled = false
        view.isClickable = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableAllChildren(view.getChildAt(i))
            }
        }
    }
    
    /** 叠加"敬请期待"遮罩 */
    private fun showComingSoonOverlay(view: View) {
        val overlay = TextView(requireContext()).apply {
            text = "敬请期待"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0x88000000.toInt())  // 半透明黑色背景
            isClickable = true   // 拦截所有触摸事件
            isFocusable = true
        }
        (view as? ViewGroup)?.addView(
            overlay,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // ── TemplateAdapter ──

    inner class TemplateAdapter : RecyclerView.Adapter<TemplateAdapter.VH>() {

        private var items: List<KeyTemplate> = emptyList()

        init { refresh() }

        fun refresh() {
            items = Config.load().templates.toList()
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = moe.lovefirefly.betterzuikey.databinding.ItemTemplateRowBinding
                .inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], position)
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
                    t.enabled = checked
                    Config.load().save()
                }

                b.tvRename.setOnClickListener {
                    showNameDialog(t.name) { newName ->
                        t.name = newName
                        Config.load().save()
                        adapter.refresh()
                    }
                }

                val count = t.packages.size
                b.tvApps.text = "选择应用（已选择 $count 个）"
                b.tvApps.setOnClickListener {
                    pendingPickerPos = pos
                    val intent = Intent(requireContext(), AppPickerActivity::class.java)
                    intent.putExtra("template_index", pos)
                    intent.putStringArrayListExtra("preselected", ArrayList(t.packages))
                    appPickerLauncher.launch(intent)
                }

                b.tvDelete.setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("删除模板")
                        .setMessage("确定删除「${t.name}」？")
                        .setPositiveButton("删除") { _, _ ->
                            val cfg = Config.load()
                            cfg.templates.removeAt(pos)
                            cfg.save()
                            adapter.refresh()
                        }
                        .setNegativeButton("取消", null)
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
        }
        AlertDialog.Builder(requireContext())
            .setTitle("模板名称")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAppPicker(template: KeyTemplate) {
        val pm = requireContext().packageManager
        val allApps = pm.getInstalledApplications(0)
            .filter { it.packageName !in setOf(
                "android", "com.android.systemui", "com.android.settings",
                "com.android.launcher3", "com.zui.launcher"
            )}
            .sortedBy { it.loadLabel(pm).toString() }

        val names = allApps.map { it.loadLabel(pm).toString() }.toTypedArray()
        val checked = BooleanArray(allApps.size) { i ->
            template.packages.contains(allApps[i].packageName)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("选择应用")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("确定") { _, _ ->
                template.packages.clear()
                allApps.forEachIndexed { i, app ->
                    if (checked[i]) template.packages.add(app.packageName)
                }
                Config.load().save()
                adapter.refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
