package moe.lovefirefly.betterzuikey

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityAppPickerBinding

/**
 * App 选择器 — 独立页面，支持搜索、多选、跨模板冲突检测。
 * 返回: Intent 带 ArrayList<String> "packages"。
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private var templateIndex = -1
    private val selected = mutableSetOf<String>()
    private var allApps = listOf<AppInfo>()

    data class AppInfo(val packageName: String, val label: String) {
        fun display() = "$label ($packageName)"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        templateIndex = intent.getIntExtra("template_index", -1)
        val preSelected = intent.getStringArrayListExtra("preselected") ?: arrayListOf()
        selected.addAll(preSelected)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // 加载所有已安装应用
        val pm = packageManager
        allApps = pm.getInstalledApplications(0)
            .filter { it.packageName !in setOf(
                "android", "com.android.systemui", "com.android.settings",
                "com.android.launcher3", "com.zui.launcher"
            )}
            .map { AppInfo(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }

        val adapter = AppAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(q: String?): Boolean {
                adapter.filter(q ?: "")
                return true
            }
        })

        binding.btnDone.setOnClickListener {
            val result = Intent()
            result.putStringArrayListExtra("packages", ArrayList(selected))
            setResult(Activity.RESULT_OK, result)
            finish()
        }

        updateCount()
    }

    private fun updateCount() {
        binding.tvSelectedCount.text = getString(R.string.app_picker_selected_count, selected.size)
    }

    // ── Adapter ──

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {

        private var filtered = allApps

        fun filter(query: String) {
            filtered = if (query.isBlank()) allApps
            else allApps.filter {
                it.label.contains(query, true) || it.packageName.contains(query, true)
            }
            notifyDataSetChanged()
        }

        override fun getItemCount() = filtered.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(filtered[position])
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cb: CheckBox = itemView.findViewById(R.id.cb_selected)
            private val tv: TextView = itemView.findViewById(R.id.tv_app_name)
            private var current: AppInfo? = null

            fun bind(app: AppInfo) {
                current = app
                tv.text = app.display()
                cb.isChecked = selected.contains(app.packageName)

                itemView.setOnClickListener {
                    val pkg = app.packageName
                    if (cb.isChecked) {
                        selected.remove(pkg)
                        cb.isChecked = false
                    } else {
                        // 跨模板冲突检测
                        val conflict = findConflictTemplate(pkg)
                        if (conflict != null) {
                            Toast.makeText(
                                this@AppPickerActivity,
                                getString(R.string.app_picker_conflict_toast, conflict),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        selected.add(pkg)
                        cb.isChecked = true
                    }
                    updateCount()
                }
            }
        }
    }

    /** 检查此包名是否已被其他模板使用，返回冲突模板名 */
    private fun findConflictTemplate(packageName: String): String? {
        val cfg = Config.load()
        for (i in cfg.templates.indices) {
            if (i == templateIndex) continue
            if (cfg.templates[i].packages.contains(packageName)) {
                return cfg.templates[i].name
            }
        }
        return null
    }
}
