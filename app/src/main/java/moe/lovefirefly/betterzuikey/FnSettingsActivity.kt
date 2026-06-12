package moe.lovefirefly.betterzuikey

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityFnSettingsBinding

class FnSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFnSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityFnSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val cfg = Config.load()

        binding.swEnabled.isChecked = cfg.fnKeyEnabled
        binding.swEnabled.setOnCheckedChangeListener { _, checked ->
            cfg.fnKeyEnabled = checked
            cfg.save()
        }

        binding.swFnToast.isChecked = cfg.fnToastEnabled
        binding.swFnToast.setOnCheckedChangeListener { _, checked ->
            cfg.fnToastEnabled = checked
            cfg.save()
        }

        binding.spProfile.setOnItemClickListener { _, _, pos, _ ->
            if (spinnerUpdating) return@setOnItemClickListener
            val keys = KeyboardProfiles.getProfileNames(this@FnSettingsActivity).map { it.first }
            cfg.fnProfileKey = if (pos == 0) "" else keys.getOrElse(pos - 1) { "" }
            cfg.save()
            refreshSummary()
        }

        binding.tvImport.setOnClickListener {
            startActivity(Intent(this, ImportProfileActivity::class.java))
        }
        binding.tvDetect.setOnClickListener {
            startActivity(Intent(this, KeyboardDetectActivity::class.java))
        }
        binding.tvManage.setOnClickListener {
            startActivity(Intent(this, ProfileManageActivity::class.java))
        }

        // 点整行切换开关 / 展开下拉
        binding.rowFnEnabled.setOnClickListener { binding.swEnabled.toggle() }
        binding.rowFnToast.setOnClickListener { binding.swFnToast.toggle() }
        binding.rowProfile.setOnClickListener {
            binding.spProfile.requestFocus()
            binding.spProfile.post { binding.spProfile.showDropDown() }
        }

        refreshSpinner()
        refreshSummary()
    }

    private var spinnerUpdating = false

    private fun refreshSpinner() {
        spinnerUpdating = true
        val profiles = KeyboardProfiles.getProfileNames(this)
        val detectedKey = KeyboardProfiles.detectProfileKey(this)
        val allProfiles = KeyboardProfiles.all(this)
        val autoLabel = if (detectedKey != null) {
            val friendly = allProfiles[detectedKey]?.friendlyName ?: detectedKey
            "自动检测 ($friendly)"
        } else {
            "自动检测 (失败)"
        }
        val names = mutableListOf(autoLabel)
        names.addAll(profiles.map { it.second })
        val adapter = ArrayAdapter(this, R.layout.dropdown_item_wrap, names)
        binding.spProfile.setAdapter(adapter)
        binding.spProfile.threshold = Int.MAX_VALUE

        val cfg = Config.load()
        val profileKeys = profiles.map { it.first }
        val currentIdx = if (cfg.fnProfileKey.isEmpty()) 0
            else profileKeys.indexOf(cfg.fnProfileKey).let { if (it < 0) 0 else it + 1 }
        binding.spProfile.setText(names[currentIdx], false)
        spinnerUpdating = false
    }

    private fun refreshSummary() {
        val cfg = Config.load()
        val isAuto = cfg.fnProfileKey.isEmpty()
        val detectedKey = if (isAuto) KeyboardProfiles.detectProfileKey(this) else null
        val key = if (isAuto) detectedKey ?: "" else cfg.fnProfileKey
        val all = KeyboardProfiles.all(this)
        val p = all[key]

        val sb = StringBuilder()

        if (isAuto) {
            if (detectedKey != null) {
                sb.appendLine("自动检测: $detectedKey")
            } else {
                sb.appendLine("自动检测: 失败（未找到匹配的键盘）")
            }
            sb.appendLine()
        }

        if (p == null) {
            if (!isAuto) sb.appendLine("未匹配到键盘配置")
            sb.append("可用配置: ${all.size} 个 (内置 + 导入)")
            binding.tvSummary.text = sb.toString()
            return
        }
        sb.appendLine("${p.friendlyName} (${p.name})")
        if (p.isCustom) sb.appendLine("[自定义配置]")
        sb.appendLine()
        for (i in 1..12) {
            val entry = p.keys["F$i"]
            if (entry != null && entry.isValid) {
                if (entry.scan > 0) {
                    sb.appendLine("  F$i → scan:0x${Integer.toHexString(entry.scan)} (${entry.scan})")
                } else {
                    sb.appendLine("  F$i → keyCode:${entry.keyCode}")
                }
            } else {
                sb.appendLine("  F$i → (未映射)")
            }
        }
        binding.tvSummary.text = sb.toString()
    }
}
