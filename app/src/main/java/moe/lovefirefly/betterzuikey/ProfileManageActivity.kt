package moe.lovefirefly.betterzuikey

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityProfileManageBinding
import moe.lovefirefly.betterzuikey.databinding.ItemTemplateRowBinding

class ProfileManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileManageBinding
    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityProfileManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ProfileAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        adapter.refresh()
    }

    inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {

        private var items = listOf<Pair<String, KeyboardProfiles.Profile>>()

        fun refresh() {
            items = KeyboardProfiles.all(this@ProfileManageActivity).toList()
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTemplateRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], position)
        }

        inner class VH(private val b: ItemTemplateRowBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(entry: Pair<String, KeyboardProfiles.Profile>, pos: Int) {
                val (key, p) = entry
                b.tvTemplateName.text = p.friendlyName
                b.swEnabled.visibility = View.GONE
                b.tvRename.text = key
                b.tvRename.isClickable = false

                // 摘要
                val summary = buildString {
                    appendLine(p.name)
                    for (i in 1..12) {
                        appendLine("  F$i → ${formatKeyEntry(p.keys["F$i"])}")
                    }
                }
                b.tvApps.text = summary
                b.tvApps.isClickable = false

                b.tvDelete.text = if (p.isCustom) "删除" else "内置(不可删)"
                b.tvDelete.isClickable = p.isCustom
                if (!p.isCustom) b.tvDelete.alpha = 0.4f

                b.root.setOnClickListener {
                    // 点击查看详情（可编辑）
                    if (p.isCustom) {
                        showEditDialog(key, p)
                    } else {
                        showViewDialog(p)
                    }
                }

                b.tvDelete.setOnClickListener {
                    if (!p.isCustom) return@setOnClickListener
                    AlertDialog.Builder(this@ProfileManageActivity)
                        .setTitle("删除配置")
                        .setMessage("确定删除「${p.friendlyName}」？")
                        .setPositiveButton("删除") { _, _ ->
                            KeyboardProfiles.deleteProfile(this@ProfileManageActivity, key)
                            refresh()
                            Toast.makeText(this@ProfileManageActivity, "已删除", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    private fun formatKeyEntry(entry: KeyboardProfiles.KeyEntry?): String {
        if (entry == null || !entry.isValid) return "(未映射)"
        return if (entry.scan > 0) "scan:0x${Integer.toHexString(entry.scan)}"
        else "keyCode:${entry.keyCode}"
    }

    private fun showViewDialog(p: KeyboardProfiles.Profile) {
        val summary = buildString {
            appendLine("${p.friendlyName}")
            appendLine(p.name)
            appendLine()
            for (i in 1..12) {
                appendLine("  F$i → ${formatKeyEntry(p.keys["F$i"])}")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(p.friendlyName)
            .setMessage(summary)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showEditDialog(key: String, p: KeyboardProfiles.Profile) {
        val summary = buildString {
            appendLine("${p.friendlyName}")
            appendLine(p.name)
            appendLine()
            for (i in 1..12) {
                appendLine("  F$i → ${formatKeyEntry(p.keys["F$i"])}")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("编辑: ${p.friendlyName}")
            .setMessage(summary)
            .setPositiveButton("编辑键值", null) // TODO: 打开编辑器
            .setNegativeButton("关闭", null)
            .show()
    }
}
