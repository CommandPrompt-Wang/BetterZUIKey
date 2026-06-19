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

                b.tvDelete.text = if (p.isCustom) getString(R.string.profile_btn_delete) else getString(R.string.profile_builtin_label)
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
                        .setTitle(getString(R.string.profile_delete_title))
                        .setMessage(getString(R.string.profile_delete_msg, p.friendlyName))
                        .setPositiveButton(getString(R.string.profile_delete_confirm)) { _, _ ->
                            KeyboardProfiles.deleteProfile(this@ProfileManageActivity, key)
                            refresh()
                            Toast.makeText(this@ProfileManageActivity, getString(R.string.profile_deleted_toast), Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(getString(R.string.dialog_confirm_cancel), null)
                        .show()
                }
            }
        }
    }

    private fun formatKeyEntry(entry: KeyboardProfiles.KeyEntry?): String {
        if (entry == null || !entry.isValid) return getString(R.string.profile_key_unmapped)
        return if (entry.scan > 0) getString(R.string.profile_key_scan_fmt, Integer.toHexString(entry.scan))
        else getString(R.string.profile_key_keycode_fmt, entry.keyCode)
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
            .setTitle(getString(R.string.profile_view_title, p.friendlyName))
            .setMessage(summary)
            .setPositiveButton(getString(R.string.profile_btn_close), null)
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
            .setTitle(getString(R.string.profile_edit_title, p.friendlyName))
            .setMessage(summary)
            .setPositiveButton(getString(R.string.profile_btn_edit_keys), null) // TODO: 打开编辑器
            .setNegativeButton(getString(R.string.profile_btn_close), null)
            .show()
    }
}
