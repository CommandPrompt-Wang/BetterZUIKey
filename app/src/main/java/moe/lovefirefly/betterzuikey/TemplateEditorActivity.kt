package moe.lovefirefly.betterzuikey

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
        binding.recycler.adapter = EditorAdapter()
    }

    override fun onPause() {
        super.onPause()
        globalCfg.save()
    }

    // ── Adapter ──

    inner class EditorAdapter : RecyclerView.Adapter<EditorAdapter.VH>() {
        override fun getItemCount() = ShortcutMeta.ALL.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemShortcutRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(ShortcutMeta.ALL[position])
        }

        inner class VH(private val b: ItemShortcutRowBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(meta: ShortcutMeta) {
                val key = meta.key
                val keyCap = key.replaceFirstChar { it.uppercase() }
                val ov = template.get(key)

                // 全局默认值
                val gSwitch: Config.SwitchState = try {
                    globalCfg.javaClass.getField("switch$keyCap").get(globalCfg) as Config.SwitchState
                } catch (_: Exception) { Config.SwitchState.ON }
                val gAction: Config.OverrideMode = try {
                    globalCfg.javaClass.getField("override$keyCap").get(globalCfg) as Config.OverrideMode
                } catch (_: Exception) { Config.OverrideMode.FOLLOW_SYSTEM }

                // 生效值
                val effSwitch = ov?.switchState ?: gSwitch
                val effOverride = ov?.overrideMode ?: gAction
                val isOverride = ov != null && !ov.isInherit()

                // 名称（覆写时加标记）
                b.tvName.text = if (isOverride) "✦ ${meta.display}" else meta.display
                b.tvName.setTextColor(
                    if (isOverride) 0xFF_1976D2.toInt()
                    else b.tvName.currentTextColor
                )

                b.swEnabled.isChecked = effSwitch.isEnabled
                b.swEnabled.isEnabled = true // 模板中都可切换

                val actions = Config.OverrideMode.entries
                    .filter { it.isAvailable(meta) }
                    .toMutableList()
                val actionLabels = actions.map { it.displayName() }.toMutableList()
                // 加 "继承全局" 选项
                actionLabels.add(0, "继承全局")
                b.spAction.isFocusable = true
                b.spAction.isFocusableInTouchMode = true
                b.spAction.setAdapter(ArrayAdapter(
                    this@TemplateEditorActivity,
                    R.layout.dropdown_item_wrap,
                    actionLabels
                ))

                // 选中值：覆写则选原值，否则选 "继承全局"
                b.spAction.setText(
                    if (isOverride) effOverride.displayName() else "继承全局",
                    false
                )
                b.spAction.setOnClickListener { b.spAction.showDropDown() }

                b.swEnabled.setOnCheckedChangeListener { _, checked ->
                    val ns = if (checked) Config.SwitchState.ON else Config.SwitchState.OFF
                    ensureOverride(key).switchState = ns
                    globalCfg.save()
                    // 刷新名称颜色
                    notifyItemChanged(adapterPosition)
                }

                b.spAction.setOnItemClickListener { _, _, pos, _ ->
                    if (pos == 0) {
                        // "继承全局" — 清除覆写
                        template.overrides.remove(key)
                    } else {
                        val act = Config.OverrideMode.entries.filter { it.isAvailable(meta) }[pos - 1]
                        ensureOverride(key).overrideMode = act
                    }
                    globalCfg.save()
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
