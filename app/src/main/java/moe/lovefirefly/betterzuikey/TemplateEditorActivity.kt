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
                val mode = ov?.overrideMode ?: ShortcutMeta.getOverride(globalCfg, meta.key)
                val modeOk = (filterModeInherit && isInherit) ||
                             (filterModeDefault && !isInherit && (mode == Config.OverrideMode.FOLLOW_SYSTEM || mode == Config.OverrideMode.ZUI)) ||
                             (filterModeAOSP && !isInherit && mode == Config.OverrideMode.AOSP) ||
                             (filterModeZUI && !isInherit && mode == Config.OverrideMode.ZUI) ||
                             (filterModeOFF && !isInherit && mode == Config.OverrideMode.OFF) ||
                             (filterModeBLOCK && !isInherit && mode == Config.OverrideMode.BLOCK)
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
                val modeMaxW = Config.OverrideMode.entries.maxOf {
                    paint.measureText(it.displayName(ctx)).toInt()
                }
                val inheritMaxW = Config.OverrideMode.entries.maxOf {
                    paint.measureText(ctx.getString(R.string.editor_inherit_global, it.displayName(ctx))).toInt()
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

        inner class VH(private val b: ItemShortcutRowBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(meta: ShortcutMeta, pos: Int) {
                val key = meta.key
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

                val actions = Config.OverrideMode.entries
                    .filter { it.isAvailable(meta) }
                    .toMutableList()
                val ctx = this@TemplateEditorActivity
                val actionLabels = actions.map { it.displayName(ctx) }.toMutableList()
                // 加 "继承全局（实际值）" 选项
                actionLabels.add(0, ctx.getString(R.string.editor_inherit_global, gAction.displayName(ctx)))
                b.spAction.isFocusable = true
                b.spAction.isFocusableInTouchMode = true
                b.spAction.setAdapter(ArrayAdapter(
                    this@TemplateEditorActivity,
                    R.layout.dropdown_item_wrap,
                    actionLabels
                ))

                // 选中值：覆写则选原值，否则显示继承的全局值
                b.spAction.setText(
                    if (isOverride) effOverride.displayName(ctx)
                    else ctx.getString(R.string.editor_inherit_global, gAction.displayName(ctx)),
                    false
                )

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
                        // "继承全局" — 清除覆写
                        template.overrides.remove(key)
                    } else {
                        val act = Config.OverrideMode.entries.filter { it.isAvailable(meta) }[itemPos - 1]
                        ensureOverride(key).overrideMode = act
                    }
                    dirty = true
                    notifyItemChanged(adapterPosition)
                }
            }

            private fun ensureOverride(key: String): PerKeyOverride {
                var ov = template.overrides[key]
                if (ov == null) {
                    ov = PerKeyOverride()
                    template.overrides[key] = ov
                }
                return ov
            }
        }
    }
}
