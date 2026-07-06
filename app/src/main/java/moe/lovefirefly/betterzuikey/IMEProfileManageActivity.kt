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
import com.google.gson.Gson
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityImeProfileManageBinding
import moe.lovefirefly.betterzuikey.databinding.ItemTemplateRowBinding
import moe.lovefirefly.betterzuikey.ime.IMEProfile
import moe.lovefirefly.betterzuikey.ime.IMEProfileManager
import moe.lovefirefly.betterzuikey.ime.Strategy
import moe.lovefirefly.betterzuikey.ime.ProfileValidationError
import java.io.File

class IMEProfileManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImeProfileManageBinding
    private lateinit var adapter: ProfileAdapter
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityImeProfileManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ProfileAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnRestoreBuiltins.setOnClickListener {
            IMEProfileManager.restoreBuiltins(this)
            adapter.refresh(IMEProfileManager.getAllProfiles())
            Toast.makeText(this, getString(R.string.restore_done), Toast.LENGTH_SHORT).show()
        }

        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(q: String?): Boolean {
                adapter.filter(q ?: "")
                return true
            }
        })
    }

    override fun onResume() {
        super.onResume()
        IMEProfileManager.loadFromSP(this)
        adapter.refresh(IMEProfileManager.getAllProfiles())
    }

    inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {

        private var allItems = listOf<IMEProfile>()
        private var items = listOf<IMEProfile>()
        private var query = ""

        fun refresh(list: List<IMEProfile>) {
            allItems = list
            filter(query)
        }

        fun filter(q: String) {
            query = q
            items = if (q.isBlank()) allItems else allItems.filter { p ->
                (p.name ?: "").contains(q, true) ||
                        (p.ime ?: "").contains(q, true) ||
                        (p.strategy?.name ?: "").contains(q, true)
            }
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_profile_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName = itemView.findViewById<TextView>(R.id.tv_name)
            private val tvPackage = itemView.findViewById<TextView>(R.id.tv_package)
            private val tvStrategy = itemView.findViewById<TextView>(R.id.tv_strategy)
            private val tvRenameBtn = itemView.findViewById<TextView>(R.id.tv_rename_btn)
            private val tvDeleteBtn = itemView.findViewById<TextView>(R.id.tv_delete_btn)

            fun bind(p: IMEProfile) {
                tvName.text = p.name ?: "（未设置）"
                tvPackage.text = p.ime
                tvStrategy.text = when (p.strategy) {
                    Strategy.framework -> getString(R.string.strategy_framework_label)
                    Strategy.keyremap -> getString(R.string.strategy_keyremap_label, p.remapTo ?: "?")
                    null -> "?"
                }
                tvRenameBtn.setOnClickListener { showRenameDialog(p) }
                tvDeleteBtn.setOnClickListener { showDeleteConfirm(p) }
                itemView.setOnClickListener { showViewDialog(p) }
            }
        }
    }

    private fun showDeleteConfirm(p: IMEProfile) {
        AlertDialog.Builder(this)
            .setTitle(p.name ?: p.ime)
            .setMessage(getString(R.string.ime_manage_delete_msg, p.name ?: p.ime))
            .setPositiveButton(getString(R.string.ime_manage_delete)) { _, _ ->
                IMEProfileManager.removeProfile(p.ime)
                IMEProfileManager.saveToConfig(this)
                adapter.refresh(IMEProfileManager.getAllProfiles())
                Toast.makeText(this,
                    getString(R.string.ime_manage_deleted, p.name ?: p.ime),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(p: IMEProfile) {
        val input = android.widget.EditText(this).apply {
            setText(p.name ?: "")
            selectAll()
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                IMEProfileManager.renameProfile(p.ime, newName)
                IMEProfileManager.saveToConfig(this)
                adapter.refresh(IMEProfileManager.getAllProfiles())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showViewDialog(p: IMEProfile) {
        val raw = try {
            gson.toJson(p)
        } catch (_: Exception) { "{}" }

        AlertDialog.Builder(this)
            .setTitle(p.name ?: p.ime)
            .setMessage(raw)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
