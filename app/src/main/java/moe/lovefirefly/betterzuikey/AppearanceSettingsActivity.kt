package moe.lovefirefly.betterzuikey

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityAppearanceSettingsBinding

class AppearanceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceSettingsBinding
    private var spinnerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityAppearanceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val cfg = Config.load()
        val nightModes = arrayOf(
            getString(R.string.appearance_night_auto),
            getString(R.string.appearance_night_on),
            getString(R.string.appearance_night_off)
        )
        val adapter = ArrayAdapter(this, R.layout.dropdown_item_wrap, nightModes)
        binding.spNightMode.setAdapter(adapter)
        binding.spNightMode.threshold = Int.MAX_VALUE
        binding.spNightMode.setText(nightModes[cfg.nightMode], false)
        binding.spNightMode.setOnItemClickListener { _, _, pos, _ ->
            spinnerOpen = false
            if (pos == cfg.nightMode) return@setOnItemClickListener
            cfg.nightMode = pos
            cfg.save()
            Config.syncToSharedPrefs(this, cfg)
            val nightMode = when (pos) {
                1 -> AppCompatDelegate.MODE_NIGHT_YES
                2 -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
            delegate.applyDayNight()
            // Re-set text without filtering after selection
            binding.spNightMode.setText(nightModes[pos], false)
        }

        binding.swDynamicColor.isChecked = cfg.dynamicColorEnabled
        binding.swDynamicColor.setOnCheckedChangeListener { _, checked ->
            cfg.dynamicColorEnabled = checked
            cfg.save()
            Config.syncToSharedPrefs(this@AppearanceSettingsActivity, cfg)
            // 动态调色板需要重建 Activity 才能完全刷新
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.getDefaultNightMode()
            )
            recreate()
        }

        // 点整行展开/收回下拉
        binding.rowNightMode.setOnClickListener {
            if (spinnerOpen) {
                spinnerOpen = false
                binding.spNightMode.dismissDropDown()
            } else {
                spinnerOpen = true
                binding.spNightMode.requestFocus()
                binding.spNightMode.post { binding.spNightMode.showDropDown() }
            }
        }
        binding.rowDynamicColor.setOnClickListener { binding.swDynamicColor.toggle() }
    }
}
