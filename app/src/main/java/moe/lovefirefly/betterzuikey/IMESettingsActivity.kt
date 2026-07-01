package moe.lovefirefly.betterzuikey

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Config.Config.IMEBinding
import moe.lovefirefly.betterzuikey.databinding.ActivityImeSettingsBinding
import moe.lovefirefly.betterzuikey.ime.IMEProfile
import java.io.File

class IMESettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImeSettingsBinding
    private var spinnerOpen = false

    private val allBindings = IMEBinding.values().toList()
    private val exclusiveBindings = allBindings.filter {
        it != IMEBinding.FOLLOW_SYSTEM && it != IMEBinding.OFF && it != IMEBinding.BLOCK
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityImeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val cfg = Config.load()

        // Master switch
        binding.swEnabled.isChecked = cfg.imeMasterEnabled
        binding.swEnabled.setOnCheckedChangeListener { _, checked ->
            cfg.imeMasterEnabled = checked
            cfg.save()
            Config.syncToSharedPrefs(this, cfg)
        }

        // Toast switch
        binding.swToast.isChecked = cfg.imeToastEnabled
        binding.swToast.setOnCheckedChangeListener { _, checked ->
            cfg.imeToastEnabled = checked
            cfg.save()
            Config.syncToSharedPrefs(this, cfg)
        }

        // Dropdown listeners
        binding.spImeSwitch.setOnItemClickListener { _, _, pos, _ ->
            spinnerOpen = false
            val selected = allBindings[pos]
            onImeSwitchSelected(cfg, selected)
        }
        binding.spImeLanguage.setOnItemClickListener { _, _, pos, _ ->
            spinnerOpen = false
            val selected = allBindings[pos]
            onLanguageSwitchSelected(cfg, selected)
        }

        // Row click: toggle switch / expand dropdown
        binding.rowImeEnabled.setOnClickListener { binding.swEnabled.toggle() }
        binding.rowImeToast.setOnClickListener { binding.swToast.toggle() }
        binding.rowImeSwitch.setOnClickListener { toggleSpinner(binding.spImeSwitch) }
        binding.rowImeLanguage.setOnClickListener { toggleSpinner(binding.spImeLanguage) }

        // Import IME Profile
        binding.tvImport.setOnClickListener {
            startActivity(android.content.Intent(this, IMEImportActivity::class.java))
        }

        // Manage IME Profiles
        binding.tvManage.setOnClickListener {
            startActivity(Intent(this, IMEProfileManageActivity::class.java))
        }

        // Seed built-in defaults (app-side: system_server can't write app's filesDir)
        seedBuiltinProfiles()

        refreshDropdowns(cfg)
    }

    private fun seedBuiltinProfiles() {
        val dir = java.io.File(filesDir, "adapters")
        if (!dir.exists()) dir.mkdirs()
        val gson = com.google.gson.Gson()
        for (builtin in IMEProfile.BUILTIN_DEFAULTS) {
            val file = java.io.File(dir, "${builtin.ime?.replace('.', '_') ?: "unknown"}.json")
            if (!file.exists()) {
                try { file.writeText(gson.toJson(builtin)) } catch (_: Exception) { }
            }
        }
    }

    private fun toggleSpinner(spinner: com.google.android.material.textfield.MaterialAutoCompleteTextView) {
        if (spinnerOpen) {
            spinnerOpen = false
            spinner.dismissDropDown()
        } else {
            spinnerOpen = true
            spinner.requestFocus()
            spinner.post { spinner.showDropDown() }
        }
    }

    // ---- Mutual exclusion logic ----

    private fun onImeSwitchSelected(cfg: Config, selected: IMEBinding) {
        if (selected == IMEBinding.FOLLOW_SYSTEM) {
            // All sync to FOLLOW_SYSTEM
            cfg.imeSwitchBinding = IMEBinding.FOLLOW_SYSTEM
            cfg.languageSwitchBinding = IMEBinding.FOLLOW_SYSTEM
        } else if (exclusiveBindings.contains(selected)) {
            // If another spin holds this combo, reset it to OFF
            if (cfg.languageSwitchBinding == selected) {
                cfg.languageSwitchBinding = IMEBinding.OFF
                Toast.makeText(this,
                    getString(R.string.ime_held_released, getBindingName(selected)),
                    Toast.LENGTH_SHORT).show()
            }
            // Push FOLLOW_SYSTEM → OFF
            if (cfg.languageSwitchBinding == IMEBinding.FOLLOW_SYSTEM) {
                cfg.languageSwitchBinding = IMEBinding.OFF
            }
            cfg.imeSwitchBinding = selected
        } else {
            cfg.imeSwitchBinding = selected
        }
        cfg.save()
        Config.syncToSharedPrefs(this, cfg)
        refreshDropdowns(cfg)
    }

    private fun onLanguageSwitchSelected(cfg: Config, selected: IMEBinding) {
        if (selected == IMEBinding.FOLLOW_SYSTEM) {
            cfg.imeSwitchBinding = IMEBinding.FOLLOW_SYSTEM
            cfg.languageSwitchBinding = IMEBinding.FOLLOW_SYSTEM
        } else if (exclusiveBindings.contains(selected)) {
            if (cfg.imeSwitchBinding == selected) {
                cfg.imeSwitchBinding = IMEBinding.OFF
                Toast.makeText(this,
                    getString(R.string.ime_held_released, getBindingName(selected)),
                    Toast.LENGTH_SHORT).show()
            }
            if (cfg.imeSwitchBinding == IMEBinding.FOLLOW_SYSTEM) {
                cfg.imeSwitchBinding = IMEBinding.OFF
            }
            cfg.languageSwitchBinding = selected
        } else {
            cfg.languageSwitchBinding = selected
        }
        cfg.save()
        Config.syncToSharedPrefs(this, cfg)
        refreshDropdowns(cfg)
    }

    // ---- Dropdown refresh ----

    private fun refreshDropdowns(cfg: Config) {
        refreshOneSpinner(cfg, cfg.imeSwitchBinding, cfg.languageSwitchBinding,
            binding.spImeSwitch, binding.tvImeSwitchDesc,
            getString(R.string.ime_switch_desc))
        refreshOneSpinner(cfg, cfg.languageSwitchBinding, cfg.imeSwitchBinding,
            binding.spImeLanguage, binding.tvImeLanguageDesc,
            getString(R.string.ime_language_desc))
    }

    private fun refreshOneSpinner(
        cfg: Config,
        current: IMEBinding,
        other: IMEBinding,
        spinner: com.google.android.material.textfield.MaterialAutoCompleteTextView,
        descView: android.widget.TextView,
        baseDesc: String
    ) {
        val names = allBindings.map { getBindingName(it) }
        val adapter = object : ArrayAdapter<String>(this, R.layout.dropdown_item_wrap, names) {
            override fun isEnabled(position: Int): Boolean {
                val binding = allBindings[position]
                if (exclusiveBindings.contains(binding) && other == binding) return false
                return true
            }
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getDropDownView(position, convertView, parent)
                if (!isEnabled(position)) {
                    (v as? android.widget.TextView)?.let {
                        it.paintFlags = it.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    }
                }
                return v
            }
        }
        spinner.setAdapter(adapter)
        spinner.threshold = Int.MAX_VALUE
        val idx = allBindings.indexOf(current).coerceAtLeast(0)
        spinner.setText(names[idx], false)
        descView.text = baseDesc
    }

    // ---- Display helpers ----

    private fun getBindingName(binding: IMEBinding): String = when (binding) {
        IMEBinding.FOLLOW_SYSTEM -> getString(R.string.ime_binding_follow_system)
        IMEBinding.CTRL_SHIFT -> getString(R.string.ime_binding_ctrl_shift)
        IMEBinding.CTRL_SPACE -> getString(R.string.ime_binding_ctrl_space)
        IMEBinding.ALT_SHIFT -> getString(R.string.ime_binding_alt_shift)
        IMEBinding.RIGHT_ALT -> getString(R.string.ime_binding_right_alt)
        IMEBinding.WIN -> getString(R.string.ime_binding_win, Config.load().metaKeyLabel)
        IMEBinding.OFF -> getString(R.string.ime_binding_off)
        IMEBinding.BLOCK -> getString(R.string.ime_binding_block)
    }

    companion object {
        fun getBindingNameStatic(ctx: Context, binding: IMEBinding): String = when (binding) {
            IMEBinding.FOLLOW_SYSTEM -> ctx.getString(R.string.ime_binding_follow_system)
            IMEBinding.CTRL_SHIFT -> ctx.getString(R.string.ime_binding_ctrl_shift)
            IMEBinding.CTRL_SPACE -> ctx.getString(R.string.ime_binding_ctrl_space)
            IMEBinding.ALT_SHIFT -> ctx.getString(R.string.ime_binding_alt_shift)
            IMEBinding.RIGHT_ALT -> ctx.getString(R.string.ime_binding_right_alt)
            IMEBinding.WIN -> ctx.getString(R.string.ime_binding_win, Config.load().metaKeyLabel)
            IMEBinding.OFF -> ctx.getString(R.string.ime_binding_off)
            IMEBinding.BLOCK -> ctx.getString(R.string.ime_binding_block)
        }
    }
}
