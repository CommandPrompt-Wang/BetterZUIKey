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
        val dir = java.io.File(filesDir, "adapters")
        // Async scan for problems
        Thread {
            val errors = IMEProfileManager.scanForProblems(dir)
            runOnUiThread {
                if (errors.isNotEmpty()) {
                    showProblemChain(dir, errors)
                } else {
                    adapter.refresh(dir)
                }
            }
        }.start()
    }

    private fun showProblemChain(dir: File, errors: List<ProfileValidationError>) {
        val err = errors.first()
        val p = err.profile
        val rawName = p.name ?: p.ime ?: ""
        val name = rawName.ifBlank { "unknown" }
        val probText = err.problems.joinToString("\n") { "  • ${IMEProfileManager.localizeError(it, "", this@IMEProfileManageActivity)}" }
        val msg = getString(R.string.ime_problem_msg,
            name, p.uuid ?: "?", probText, err.rawJson)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ime_problem_title, name))
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ime_manage_delete)) { _, _ ->
                val file = File(dir, "${(p.ime ?: "unknown").replace('.', '_')}.json")
                file.delete()
                Toast.makeText(this,
                    getString(R.string.ime_manage_deleted, name), Toast.LENGTH_SHORT).show()
                val remaining = errors.drop(1)
                if (remaining.isNotEmpty()) showProblemChain(dir, remaining)
                else adapter.refresh(dir)
            }
            .show()
    }

    inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {

        private var allItems = listOf<Pair<File, IMEProfile>>()
        private var items = listOf<Pair<File, IMEProfile>>()
        private var query = ""

        fun refresh(dir: File) {
            val files = if (dir.isDirectory) dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?: emptyArray() else emptyArray()
            allItems = files.mapNotNull { f ->
                try {
                    val raw = f.readText()
                    val p = gson.fromJson(raw, IMEProfile::class.java)
                    if (p != null && p.ime?.isNotBlank() == true) f to p else null
                } catch (_: Exception) { null }
            }
            filter(query)
        }

        fun filter(q: String) {
            query = q
            items = if (q.isBlank()) allItems else allItems.filter { (_, p) ->
                (p.name ?: "").contains(q, true) ||
                        (p.ime ?: "").contains(q, true) ||
                        (p.strategy?.name ?: "").contains(q, true)
            }
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTemplateRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(private val b: ItemTemplateRowBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(entry: Pair<File, IMEProfile>) {
                val (_, p) = entry
                val isBuiltin = IMEProfile.isBuiltin(p.uuid)

                b.tvTemplateName.text = p.name ?: p.ime
                b.swEnabled.visibility = View.GONE
                b.tvRename.text = p.ime
                b.tvRename.isClickable = false

                val strategyTag = p.strategy?.name ?: "?"
                val summary = buildString {
                    appendLine("${getString(R.string.ime_manage_strategy)}: $strategyTag")
                    if (p.uuid != null) appendLine("UUID: ${p.uuid}")
                    if (isBuiltin) appendLine("🔒 ${getString(R.string.profile_builtin_label)}")
                }
                b.tvApps.text = summary
                b.tvApps.isClickable = false

                b.tvDelete.text = if (isBuiltin) getString(R.string.profile_builtin_label)
                    else getString(R.string.ime_manage_delete)
                b.tvDelete.isClickable = !isBuiltin
                if (isBuiltin) b.tvDelete.alpha = 0.4f

                // Hide unused template features
                b.tvCopy.visibility = View.GONE
                (b.tvMoveUp.parent as View).visibility = View.GONE
                (b.tvMoveDown.parent as View).visibility = View.GONE

                b.root.setOnClickListener {
                    showViewDialog(p)
                }

                b.tvDelete.setOnClickListener {
                    if (isBuiltin) return@setOnClickListener
                    AlertDialog.Builder(this@IMEProfileManageActivity)
                        .setTitle(p.name ?: p.ime)
                        .setMessage(getString(R.string.ime_manage_delete_msg, p.name ?: p.ime))
                        .setPositiveButton(getString(R.string.ime_manage_delete)) { _, _ ->
                            entry.first.delete()
                            val dir = java.io.File(filesDir, "adapters")
                            refresh(dir)
                            Toast.makeText(this@IMEProfileManageActivity,
                                getString(R.string.ime_manage_deleted, p.name ?: p.ime),
                                Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
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
