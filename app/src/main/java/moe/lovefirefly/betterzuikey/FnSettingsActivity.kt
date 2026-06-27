package moe.lovefirefly.betterzuikey

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityFnSettingsBinding

class FnSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFnSettingsBinding
    private var spinnerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityFnSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val cfg = Config.load()

        binding.swEnabled.isChecked = cfg.fnMasterEnabled
        binding.swEnabled.setOnCheckedChangeListener { _, checked ->
            cfg.fnMasterEnabled = checked
            cfg.save()
            Config.syncToSharedPrefs(this@FnSettingsActivity, cfg)
        }

        binding.swFnToast.isChecked = cfg.fnToastEnabled
        binding.swFnToast.setOnCheckedChangeListener { _, checked ->
            cfg.fnToastEnabled = checked
            cfg.save()
            Config.syncToSharedPrefs(this@FnSettingsActivity, cfg)
        }

        binding.spProfile.setOnItemClickListener { _, _, pos, _ ->
            spinnerOpen = false
            if (spinnerUpdating) return@setOnItemClickListener
            val keys = KeyboardProfiles.getProfileNames(this@FnSettingsActivity).map { it.first }
            cfg.fnProfileKey = if (pos == 0) "" else keys.getOrElse(pos - 1) { "" }
            cfg.save()
            Config.syncToSharedPrefs(this@FnSettingsActivity, cfg)
            refreshSummary()
            refreshEscRemapWarning()
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

        // ESC→BACK warning buttons
        binding.btnGoCreateProfile.setOnClickListener {
            startActivity(Intent(this, KeyboardDetectActivity::class.java))
        }
        binding.btnGoModifierKeys.setOnClickListener {
            try {
                // Launch the physical keyboard page (closest deep-link available)
                startActivity(Intent("android.settings.HARD_KEYBOARD_SETTINGS"))
            } catch (e: Exception) {
                Toast.makeText(this,
                    getString(R.string.fn_modifier_keys_failed), Toast.LENGTH_SHORT).show()
            }
        }

        // 点整行切换开关 / 展开下拉
        binding.rowFnEnabled.setOnClickListener { binding.swEnabled.toggle() }
        binding.rowFnToast.setOnClickListener { binding.swFnToast.toggle() }
        binding.rowProfile.setOnClickListener {
            if (spinnerOpen) {
                spinnerOpen = false
                binding.spProfile.dismissDropDown()
            } else {
                spinnerOpen = true
                binding.spProfile.requestFocus()
                binding.spProfile.post { binding.spProfile.showDropDown() }
            }
        }

        refreshSpinner()
        refreshSummary()
        checkEscToBackRemap()
    }

    // ---- ESC→BACK remap detection ----

    private fun checkEscToBackRemap() {
        Thread {
            val detected = detectEscToBack()
            runOnUiThread {
                escToBackDetected = detected
                refreshEscRemapWarning()
            }
        }.start()
    }

    private var escToBackDetected = false

    /**
     * Run su -c abx2xml to decode /data/system/input-manager-state.xml
     * and check for ESC(111)→BACK(4) remapping.
     * Returns true if the remapping exists.
     */
    private fun detectEscToBack(): Boolean {
        try {
            // Step 1: decode ABX → plain XML
            val pb = ProcessBuilder("su", "-c",
                "abx2xml /data/system/input-manager-state.xml /data/local/tmp/bzuikey_esc_check.xml")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.waitFor()

            if (proc.exitValue() != 0) return false

            // Step 2: read decoded XML
            val pb2 = ProcessBuilder("su", "-c",
                "cat /data/local/tmp/bzuikey_esc_check.xml")
            pb2.redirectErrorStream(true)
            val proc2 = pb2.start()
            val output = proc2.inputStream.bufferedReader().readText()
            proc2.waitFor()

            // Step 3: check for ESC(111)→BACK(4)
            return output.contains("from-key=\"111\"") && output.contains("to-key=\"4\"")
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Show the ESC→BACK warning only when:
     * - ESC→BACK remapping is detected
     * - User is NOT using a custom profile (i.e. on auto-detect or built-in default)
     *   Custom profile = user explicitly selected a non-empty profile key
     */
    private fun refreshEscRemapWarning() {
        val cfg = Config.load()
        val hasCustomProfile = cfg.fnProfileKey.isNotEmpty()
        val show = escToBackDetected && !hasCustomProfile
        binding.cardEscRemapWarning.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---- existing ----

    private var spinnerUpdating = false

    private fun refreshSpinner() {
        spinnerUpdating = true
        val profiles = KeyboardProfiles.getProfileNames(this)
        val detectedKey = KeyboardProfiles.detectProfileKey(this)
        val allProfiles = KeyboardProfiles.all(this)
        val autoLabel = if (detectedKey != null) {
            val friendly = allProfiles[detectedKey]?.friendlyName ?: detectedKey
            getString(R.string.fn_auto_detect_label, friendly)
        } else {
            getString(R.string.fn_auto_detect_failed)
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
                sb.appendLine(getString(R.string.fn_auto_detect_line, detectedKey))
            } else {
                sb.appendLine(getString(R.string.fn_auto_detect_failed_detail))
            }
            sb.appendLine()
        }

        if (p == null) {
            if (!isAuto) sb.appendLine(getString(R.string.fn_no_profile_matched))
            sb.append(getString(R.string.fn_available_count, all.size))
            binding.tvSummary.text = sb.toString()
            return
        }
        sb.appendLine("${p.friendlyName} (${p.name})")
        if (p.isCustom) sb.appendLine(getString(R.string.fn_custom_tag))
        sb.appendLine()
        for (i in 1..12) {
            val entry = p.keys["F$i"]
            if (entry != null && entry.isValid) {
                if (entry.scan > 0) {
                    sb.appendLine(getString(R.string.fn_key_scan_format, i, Integer.toHexString(entry.scan), entry.scan))
                } else {
                    sb.appendLine(getString(R.string.fn_key_keycode_format, i, entry.keyCode))
                }
            } else {
                sb.appendLine(getString(R.string.fn_key_unmapped_format, i))
            }
        }
        binding.tvSummary.text = sb.toString()
    }
}
