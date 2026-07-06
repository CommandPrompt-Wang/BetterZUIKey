package moe.lovefirefly.betterzuikey

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityImeImportBinding
import moe.lovefirefly.betterzuikey.ime.IMEProfile
import moe.lovefirefly.betterzuikey.ime.IMEProfileManager

class IMEImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImeImportBinding

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return@registerForActivityResult
            doImport(json)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.import_toast_read_fail, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityImeImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rgMode.setOnCheckedChangeListener { _, id ->
            binding.etJson.visibility = if (id == binding.rbText.id)
                android.view.View.VISIBLE else android.view.View.GONE
        }

        binding.btnSubmit.setOnClickListener {
            if (binding.rbFile.isChecked) {
                filePicker.launch(arrayOf("application/json", "*/*"))
            } else {
                val text = binding.etJson.text.toString().trim()
                if (text.isBlank()) {
                    Toast.makeText(this, getString(R.string.import_toast_input_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                doImport(text)
            }
        }
    }

    private fun doImport(json: String) {
        val (profile, error) = IMEProfileManager.validateAndFix(json)
        if (error.isNotEmpty()) {
            showImportErrorDialog(json, error.joinToString("|"))
            return
        }
        val p = profile ?: run {
            showImportErrorDialog(json, "err_json_null|")
            return
        }
        IMEProfileManager.loadFromSP(this)
        val existing = IMEProfileManager.getProfileForIME(p.ime)
        if (existing != null) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.import_overwrite_title))
                .setMessage(getString(R.string.import_overwrite_msg, existing.name ?: existing.ime))
                .setPositiveButton(getString(R.string.import_overwrite_btn)) { _, _ -> doImportConfirmed(p) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        doImportConfirmed(p)
    }

    private fun doImportConfirmed(p: IMEProfile) {
        IMEProfileManager.putProfile(p)
        IMEProfileManager.appendChange(this, "new", p)
        IMEProfileManager.saveToConfig(this)
        val name = p.name ?: "?"
        Toast.makeText(this,
            getString(R.string.ime_import_success, name), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showImportErrorDialog(raw: String, error: String) {
        val codes = error.split("|")
        val localized = codes.map { c ->
            val parts = c.split(" ", limit = 2)
            IMEProfileManager.localizeError(parts[0], parts.getOrElse(1) { "" }, this)
        }.joinToString("\n")

        val formatted = try {
            com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                com.google.gson.JsonParser.parseString(raw))
        } catch (_: Exception) { raw }

        val msg = buildString {
            appendLine(localized)
            appendLine()
            appendLine(getString(R.string.ime_import_raw))
            appendLine(formatted)
        }

        val scroll = android.widget.ScrollView(this)
        val tv = android.widget.TextView(this).apply {
            text = msg
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(32, 16, 32, 16)
        }
        scroll.addView(tv)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.ime_import_fail_title))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
