package moe.lovefirefly.betterzuikey

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Config.KeyTemplate
import moe.lovefirefly.betterzuikey.Config.PerKeyOverride
import moe.lovefirefly.betterzuikey.databinding.ActivityTemplateEditorBinding
import moe.lovefirefly.betterzuikey.databinding.ItemShortcutRowBinding

/**
 * 模板编辑器 — 显示全部 48 条快捷键，覆写或继承全局。
 */
class TemplateEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTemplateEditorBinding
    private var templateIndex = -1
    private lateinit var template: KeyTemplate
    private lateinit var globalCfg: Config
    private lateinit var adapter: EditorAdapter
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityTemplateEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        templateIndex = intent.getIntExtra("template_index", -1)
        globalCfg = Config.load()
        if (templateIndex < 0 || templateIndex >= globalCfg.templates.size) {
            finish()
            return
        }
        template = globalCfg.templates[templateIndex]

        binding.toolbar.title = template.name
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        adapter = EditorAdapter()
        binding.recycler.adapter = adapter

        binding.searchView.setOnQueryTextListener(object : android.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean { adapter.applyFilters(q ?: ""); return true }
            override fun onQueryTextChange(q: String?): Boolean { adapter.applyFilters(q ?: ""); return true }
        })

        binding.btnFilter.setOnClickListener { showFilterDialog() }
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
    private var filterModeInherit = true

    private fun showFilterDialog() {
        val items = arrayOf(
            "系统开关关", "系统开关开", "无系统开关",
            "继承全局", "默认", "AOSP", "ZUI", "关闭", "忽略"
        )
        val checked = booleanArrayOf(
            filterSwitchOff, filterSwitchOn, filterNoSwitch,
            filterModeInherit, filterModeDefault, filterModeAOSP, filterModeZUI, filterModeOFF, filterModeBLOCK
        )
        AlertDialog.Builder(this)
            .setTitle("筛选快捷键")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> filterSwitchOff = isChecked
                    1 -> filterSwitchOn = isChecked
                    2 -> filterNoSwitch = isChecked
                    3 -> filterModeInherit = isChecked
                    4 -> filterModeDefault = isChecked
                    5 -> filterModeAOSP = isChecked
                    6 -> filterModeZUI = isChecked
                    7 -> filterModeOFF = isChecked
                    8 -> filterModeBLOCK = isChecked
                }
            }
            .setPositiveButton("确定") { _, _ -> adapter.applyFilters(binding.searchView.query?.toString() ?: "") }
            .setNegativeButton("重置") { _, _ ->
                filterSwitchOff = true; filterSwitchOn = true; filterNoSwitch = true
                filterModeInherit = true; filterModeDefault = true; filterModeAOSP = true
                filterModeZUI = true; filterModeOFF = true; filterModeBLOCK = true
                adapter.applyFilters(binding.searchView.query?.toString() ?: "")
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (dirty) {
            globalCfg.save()
            Config.syncToSharedPrefs(this, globalCfg)
            dirty = false
        }
    }

    // ── Adapter ──

    inner class EditorAdapter : RecyclerView.Adapter<EditorAdapter.VH>() {
        /** 当前展开的 Spinner 所在 position，-1 表示无 */
        private var openSpinnerPos = -1
        /** Spinner 统一固定最小宽度（px） */
        private var spinnerFixedMinWidth: Int = 0
        /** 搜索过滤后的列表 */
        private var filtered: List<ShortcutMeta> = ShortcutMeta.ALL

        fun applyFilters(query: String) {
            val q = query.trim()
            filtered = ShortcutMeta.ALL.filter { meta ->
                // ── Text search (AND tokens) ──
                val tokens = q.split("+", " ", "\t").map { it.trim() }.filter { it.isNotEmpty() }
                val text = meta.displayName(this@TemplateEditorActivity) +
                    (if (meta.descResId != 0) " " + this@TemplateEditorActivity.getString(meta.descResId) else "")
                val textOk = tokens.isEmpty() || tokens.all { token -> text.contains(token, ignoreCase = true) }
                if (!textOk) return@filter false

                // ── Switch state filter ──
                val hasSwitch = meta.showSwitch || meta.hasSystemSwitch
                val switchOn = hasSwitch && ShortcutMeta.getSwitch(globalCfg, meta.key).isEnabled
                val switchOk = (filterSwitchOn && hasSwitch && switchOn) ||
                               (filterSwitchOff && hasSwitch && !switchOn) ||
                               (filterNoSwitch && !hasSwitch)
                if (!switchOk) return@filter false

                // ── Override mode filter (template override or global fallback) ──
                val ov = template.get(meta.key)
                val isInherit = ov == null || ov.isInherit()
                val useMap = ov?.useMap == true
                val mode = ov?.overrideMode ?: ShortcutMeta.getOverride(globalCfg, meta.key)
                // 「映射到…」与全局页同规矩，归到「非默认(ZUI)」那一档
                val modeOk = (filterModeInherit && isInherit) ||
                             (filterModeDefault && !isInherit && !useMap && (mode == Config.OverrideMode.FOLLOW_SYSTEM || mode == Config.OverrideMode.ZUI)) ||
                             (filterModeAOSP && !isInherit && !useMap && mode == Config.OverrideMode.AOSP) ||
                             (filterModeZUI && !isInherit && (mode == Config.OverrideMode.ZUI || useMap)) ||
                             (filterModeOFF && !isInherit && !useMap && mode == Config.OverrideMode.OFF) ||
                             (filterModeBLOCK && !isInherit && !useMap && mode == Config.OverrideMode.BLOCK)
                modeOk
            }
            openSpinnerPos = -1
            notifyDataSetChanged()
        }

        override fun getItemCount() = filtered.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemShortcutRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            if (spinnerFixedMinWidth == 0) {
                val ctx = parent.context
                val paint = b.spAction.paint
                // 所有卡片可能出现的标签（通用五档 + 三张专属卡的档位 + 映射目标）
                // 映射目标的文案里带着录到的组合键（可能很长，还有 Ctrl(L) 这种侧别），
                // 所以把本模板已录的全部目标也算进来，避免新录的键被截断
                val mapLabels = (listOf(ShortcutMeta.getMetaSingleMap(globalCfg).optionLabel(ctx)) +
                        template.overrides.values
                            .mapNotNull { it.mapTarget }
                            .filter { it.isNotBlank() }
                            .map { MetaKeyMap.parse(it).optionLabel(ctx) })
                val allLabels = Config.OverrideMode.entries.map { it.displayName(ctx) } +
                        Config.AppKeyMode.entries.map { it.displayName(ctx) } +
                        WinLongPressUiMode.entries.map { it.displayName(ctx) } +
                        MetaSingleUiMode.entries.map { it.displayName(ctx) } +
                        mapLabels
                val modeMaxW = allLabels.maxOf { paint.measureText(it).toInt() }
                val inheritMaxW = allLabels.maxOf {
                    paint.measureText(ctx.getString(R.string.editor_inherit_global, it)).toInt()
                }
                // 额外留出 dropdown 图标 + 内边距
                spinnerFixedMinWidth = maxOf(modeMaxW, inheritMaxW) +
                    b.spAction.paddingLeft + b.spAction.paddingRight + 48
            }
            b.spAction.minWidth = spinnerFixedMinWidth
            b.spAction.maxWidth = spinnerFixedMinWidth
            b.spAction.dropDownWidth = spinnerFixedMinWidth
            b.spAction.layoutParams?.let { lp ->
                lp.width = spinnerFixedMinWidth
                b.spAction.layoutParams = lp
            }
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

        /** 与主界面同规矩：哪些档位对这张卡可用（AOSP 还要看 showAospOption）。 */
        private fun availableModes(meta: ShortcutMeta): List<Config.OverrideMode> =
            Config.OverrideMode.entries.filter {
                it.isAvailable(meta)
                    && (it != Config.OverrideMode.AOSP || meta.showAospOption)
            }

        /** 档位文案：卡片自定义标签优先（Ctrl+Enter 的「换行 / 透传」等，与主界面一致）。 */
        private fun modeLabel(meta: ShortcutMeta, mode: Config.OverrideMode): String =
            meta.overrideModeLabels?.get(mode)
                ?.let { this@TemplateEditorActivity.getString(it) }
                ?: mode.displayName(this@TemplateEditorActivity)

        inner class VH(private val b: ItemShortcutRowBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(meta: ShortcutMeta, pos: Int) {
                // ctrlCard 与主界面同规矩：下拉写的是 ctrlSlash（Ctrl 长按那个开关在模板里不出现）
                val key = if (meta.key == "ctrlCard") "ctrlSlash" else meta.key
                val ov = template.get(key)

                // 全局默认值（使用 ShortcutMeta 直接字段访问，避免反射）
                val gAction = ShortcutMeta.getOverride(globalCfg, key)
                val effOverride = ov?.overrideMode ?: gAction
                val isOverride = ov != null && !ov.isInherit()

                // 名称
                b.tvName.text = meta.displayName(this@TemplateEditorActivity)

                // 覆写项显示星标（固定宽度列，不影响名称/Spinner 对齐）
                b.tvStar.visibility = if (isOverride) View.VISIBLE else View.INVISIBLE

                // 描述文字（与主界面统一使用 ShortcutMeta.displayDesc）
                b.tvDesc.text = meta.displayDesc(this@TemplateEditorActivity)
                b.tvDesc.visibility = if (meta.descResId != 0) View.VISIBLE else View.GONE

                // 隐藏 Switch — 系统开关由全局控制
                b.swEnabled.visibility = View.GONE

                // 卡片专属档位：与全局页同构，但多一项「继承全局」，且写进模板覆写
                when {
                    meta.key == "winLongPress" -> { bindWinLongSpin(key, b, pos); return }
                    ShortcutMeta.usesMetaSingleMode(meta.key) -> { bindMetaSingleSpin(key, b, pos); return }
                    ShortcutMeta.usesAppKeyMode(meta.key) -> { bindAppKeySpin(key, b, pos); return }
                }

                val ctx = this@TemplateEditorActivity
                val actions = availableModes(meta)
                val actionLabels = actions.map { modeLabel(meta, it) }.toMutableList()
                // 加 "继承全局（实际值）" 选项
                val inheritLabel = ctx.getString(R.string.editor_inherit_global, modeLabel(meta, gAction))
                actionLabels.add(0, inheritLabel)
                setupSpin(b, pos, actionLabels,
                    if (isOverride) modeLabel(meta, effOverride) else inheritLabel)

                // 卡片点击行为（模板编辑器无 Switch，由 meta.cardClick 统一控制）
                val resolvedClick = meta.cardClick.resolve(hasSpinner = true, hasSwitch = false)
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
                        CardClickBehavior.NONE -> { /* 仅水波纹 */ }
                        else -> {}
                    }
                }

                b.spAction.setOnItemClickListener { _, _, itemPos, _ ->
                    openSpinnerPos = -1
                    if (itemPos == 0) {
                        template.overrides.remove(key)
                    } else {
                        val o = ensureOverride(key)
                        // 走通用档位时清掉专属字段，避免残留（专属卡不会走到这里）
                        o.useMap = null
                        o.mapTarget = null
                        o.useCommand = null
                        o.appKeyMode = null
                        o.overrideMode = actions[itemPos - 1]
                    }
                    dirty = true
                    notifyItemChanged(adapterPosition)
                }
            }

            /** 通用：装配下拉（首项固定是「继承全局」） */
            private fun setupSpin(
                b: ItemShortcutRowBinding,
                pos: Int,
                labels: List<String>,
                selectedText: String,
            ) {
                b.spAction.isFocusable = true
                b.spAction.isFocusableInTouchMode = true
                b.spAction.setAdapter(null)
                b.spAction.setAdapter(ArrayAdapter(
                    this@TemplateEditorActivity, R.layout.dropdown_item_wrap, labels))
                b.spAction.threshold = Int.MAX_VALUE
                b.spAction.setText(selectedText, false)
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
            }

            private fun editCommand(key: String, pos: Int) {
                AppKeyCommandDialog.show(
                    this@TemplateEditorActivity,
                    AppKeyCommandDialog.TemplateStore(this@TemplateEditorActivity, template, key),
                ) {
                    dirty = true
                    notifyItemChanged(pos)
                }
            }

            /** Win 长按：继承 + 四档（含「执行命令…」，命令写进模板） */
            private fun bindWinLongSpin(key: String, b: ItemShortcutRowBinding, pos: Int) {
                val ctx = this@TemplateEditorActivity
                val ov = template.get(key)
                val modes = WinLongPressUiMode.entries
                val globalMode = ShortcutMeta.getWinLongPressUiMode(globalCfg)
                val current = when {
                    ov?.useCommand == true -> WinLongPressUiMode.CUSTOM
                    ov?.overrideMode == Config.OverrideMode.BLOCK -> WinLongPressUiMode.BLOCK
                    ov?.overrideMode == Config.OverrideMode.ZUI -> WinLongPressUiMode.ZUI
                    ov?.overrideMode == Config.OverrideMode.FOLLOW_SYSTEM -> WinLongPressUiMode.FOLLOW_SYSTEM
                    else -> null
                }
                val labels = listOf(ctx.getString(R.string.editor_inherit_global, globalMode.displayName(ctx))) +
                        modes.map { it.displayName(ctx) }
                setupSpin(b, pos, labels, current?.displayName(ctx) ?: labels[0])
                b.root.setOnLongClickListener {
                    if (current == WinLongPressUiMode.CUSTOM) editCommand(key, pos)
                    true
                }
                b.spAction.setOnItemClickListener { _, _, itemPos, _ ->
                    openSpinnerPos = -1
                    if (itemPos == 0) {
                        template.overrides.remove(key)
                    } else {
                        val sel = modes[itemPos - 1]
                        val o = ensureOverride(key)
                        o.appKeyMode = null
                        when (sel) {
                            WinLongPressUiMode.CUSTOM -> {
                                o.useCommand = true
                                o.overrideMode = null
                            }
                            WinLongPressUiMode.BLOCK -> {
                                o.useCommand = false
                                o.overrideMode = Config.OverrideMode.BLOCK
                            }
                            WinLongPressUiMode.ZUI -> {
                                o.useCommand = false
                                o.overrideMode = Config.OverrideMode.ZUI
                            }
                            WinLongPressUiMode.FOLLOW_SYSTEM -> {
                                o.useCommand = false
                                o.overrideMode = Config.OverrideMode.FOLLOW_SYSTEM
                            }
                        }
                        if (sel == WinLongPressUiMode.CUSTOM) editCommand(key, pos)
                    }
                    dirty = true
                    notifyItemChanged(pos)
                }
            }

            /** 智能键：继承 + 三档（跟随系统 / 忽略 / 执行命令…），命令写进模板 */
            private fun bindAppKeySpin(key: String, b: ItemShortcutRowBinding, pos: Int) {
                val ctx = this@TemplateEditorActivity
                val ov = template.get(key)
                val modes = Config.AppKeyMode.entries
                val globalMode = ShortcutMeta.getAppKeyMode(globalCfg, key)
                val current = when {
                    ov?.appKeyMode != null -> ov.appKeyMode
                    ov?.overrideMode == Config.OverrideMode.BLOCK
                            || ov?.overrideMode == Config.OverrideMode.OFF -> Config.AppKeyMode.BLOCK
                    ov?.overrideMode != null -> Config.AppKeyMode.FOLLOW_SYSTEM
                    else -> null
                }
                val labels = listOf(ctx.getString(R.string.editor_inherit_global, globalMode.displayName(ctx))) +
                        modes.map { it.displayName(ctx) }
                setupSpin(b, pos, labels, current?.displayName(ctx) ?: labels[0])
                // 长按与主界面一致：打开系统里的智能键设置
                b.root.setOnLongClickListener {
                    AppKeySystemSettingsLauncher.open(ctx, key)
                    true
                }
                b.spAction.setOnItemClickListener { _, _, itemPos, _ ->
                    openSpinnerPos = -1
                    if (itemPos == 0) {
                        template.overrides.remove(key)
                    } else {
                        val sel = modes[itemPos - 1]
                        val o = ensureOverride(key)
                        o.appKeyMode = sel
                        o.overrideMode = null
                        o.useCommand = null
                        if (sel == Config.AppKeyMode.CUSTOM) editCommand(key, pos)
                    }
                    dirty = true
                    notifyItemChanged(pos)
                }
            }

            /** Win 单按：继承 + 六档（含「映射到…」，目标**模板独立**） */
            private fun bindMetaSingleSpin(key: String, b: ItemShortcutRowBinding, pos: Int) {
                val ctx = this@TemplateEditorActivity
                val ov = template.get(key)
                val modes = MetaSingleUiMode.entries
                val globalMode = ShortcutMeta.getMetaSingleUiMode(globalCfg)
                val current = when {
                    ov?.useMap == true -> MetaSingleUiMode.MAP
                    ov?.overrideMode == Config.OverrideMode.BLOCK -> MetaSingleUiMode.BLOCK
                    ov?.overrideMode == Config.OverrideMode.OFF -> MetaSingleUiMode.OFF
                    ov?.overrideMode == Config.OverrideMode.AOSP -> MetaSingleUiMode.AOSP
                    ov?.overrideMode == Config.OverrideMode.ZUI -> MetaSingleUiMode.ZUI
                    ov?.overrideMode == Config.OverrideMode.FOLLOW_SYSTEM -> MetaSingleUiMode.FOLLOW_SYSTEM
                    else -> null
                }
                // 「映射到…」的两种文案与主界面一致：
                //   展开的下拉 = 「映射到…（Ctrl+A）」（挑的时候看得明白）
                //   收起的框   = 只写快捷键本身，不写「映射到…」那层壳
                fun optionLabelOf(mode: MetaSingleUiMode, target: String?): String = when (mode) {
                    MetaSingleUiMode.MAP ->
                        MetaKeyMap.parse(target ?: globalCfg.metaSingleMap).optionLabel(ctx)
                    else -> mode.displayName(ctx)
                }
                fun fieldLabelOf(mode: MetaSingleUiMode, target: String?): String = when (mode) {
                    MetaSingleUiMode.MAP -> MetaKeyMap.parse(target ?: globalCfg.metaSingleMap)
                        .displayName().ifEmpty { mode.displayName(ctx) }
                    else -> mode.displayName(ctx)
                }
                val labels = listOf(ctx.getString(R.string.editor_inherit_global,
                        optionLabelOf(globalMode, globalCfg.metaSingleMap))) +
                        modes.map { optionLabelOf(it, ov?.mapTarget) }
                val selectedText = when {
                    current == null -> labels[0]
                    else -> fieldLabelOf(current, ov?.mapTarget)
                }
                setupSpin(b, pos, labels, selectedText)
                b.root.setOnLongClickListener {
                    if (current == MetaSingleUiMode.MAP) editMap(key, pos)
                    true
                }
                b.spAction.setOnItemClickListener { _, _, itemPos, _ ->
                    openSpinnerPos = -1
                    if (itemPos == 0) {
                        template.overrides.remove(key)
                    } else {
                        val sel = modes[itemPos - 1]
                        if (sel == MetaSingleUiMode.MAP) {
                            editMap(key, pos)
                        } else {
                            val o = ensureOverride(key)
                            o.useMap = null
                            o.mapTarget = null
                            o.overrideMode = when (sel) {
                                MetaSingleUiMode.BLOCK -> Config.OverrideMode.BLOCK
                                MetaSingleUiMode.OFF -> Config.OverrideMode.OFF
                                MetaSingleUiMode.AOSP -> Config.OverrideMode.AOSP
                                MetaSingleUiMode.ZUI -> Config.OverrideMode.ZUI
                                else -> Config.OverrideMode.FOLLOW_SYSTEM
                            }
                        }
                    }
                    dirty = true
                    notifyItemChanged(pos)
                }
            }

            private fun editMap(key: String, pos: Int) {
                MetaSingleMapDialog.show(
                    this@TemplateEditorActivity,
                    MetaSingleMapDialog.TemplateStore(template, key),
                    onCancelled = { notifyItemChanged(pos) },
                    onChanged = {
                        dirty = true
                        // 刚录的目标可能比之前的长（带上侧别后更长），宽度按最新标签重算
                        spinnerFixedMinWidth = 0
                        notifyDataSetChanged()
                    },
                )
            }

            private fun ensureOverride(key: String): PerKeyOverride {
                var ov = template.get(key)
                if (ov == null) {
                    ov = PerKeyOverride()
                    template.put(key, ov)
                }
                return ov
            }
        }
    }
}
