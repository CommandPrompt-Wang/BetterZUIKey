package moe.lovefirefly.betterzuikey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityProfileMakerBinding
import moe.lovefirefly.betterzuikey.ime.Strategy

class ProfileMakerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileMakerBinding
    private var selectedImePackage: String? = null
    private var selectedImeName: String? = null
    private var selectedStrategy: Strategy? = null
    private var remapTo: String = ""
    private var capturingShortcut = false
    private var capturedShortcut = ""
    private var shortcutDisplay: android.widget.TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityProfileMakerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Strategy dropdown
        val strategyItems = listOf(
            getString(R.string.profile_maker_strategy_placeholder),
            getString(R.string.profile_maker_framework_label),
            getString(R.string.profile_maker_keyremap_label_short)
        )
        binding.spStrategy.setAdapter(ArrayAdapter(this, R.layout.dropdown_item_wrap, strategyItems))
        binding.spStrategy.threshold = Int.MAX_VALUE
        binding.spStrategy.setText(strategyItems[0], false)
        binding.spStrategy.setOnItemClickListener { _, _, pos, _ ->
            selectedStrategy = when (pos) {
                1 -> Strategy.framework
                2 -> Strategy.keyremap
                else -> null
            }
            onStrategyChanged()
            updatePreview()
        }

        // IME picker — both row and button work
        binding.rowSelectIme.setOnClickListener { openImePicker() }
        binding.btnSelectIme.setOnClickListener { openImePicker() }

        // Shortcut setter
        binding.btnSetShortcut.setOnClickListener { openShortcutDialog() }

        // Preview click to copy
        binding.tvPreview.setOnClickListener {
            if (isComplete()) {
                val json = buildJsonString()
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("IME Profile", json))
                Toast.makeText(this, getString(R.string.profile_maker_copied), Toast.LENGTH_SHORT).show()
            }
        }

        updatePreview()
    }

    private fun openImePicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val imeList = imm.enabledInputMethodList
        if (imeList.isEmpty()) {
            Toast.makeText(this, getString(R.string.profile_maker_no_ime), Toast.LENGTH_SHORT).show()
            return
        }
        val names = imeList.map { "${it.loadLabel(packageManager)} (${it.packageName})" }
        val packages = imeList.map { it.packageName }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.profile_maker_select_ime))
            .setItems(names.toTypedArray()) { _, which ->
                selectedImePackage = packages[which]
                selectedImeName = imeList[which].loadLabel(packageManager).toString()
                binding.tvImeValue.text = selectedImeName
                updatePreview()
            }
            .show()
    }

    private fun openShortcutDialog() {
        capturedShortcut = remapTo

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        shortcutDisplay = android.widget.TextView(this).apply {
            text = capturedShortcut.ifBlank { getString(R.string.profile_maker_shortcut_hint) }
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(16, 48, 16, 48)
            setBackgroundColor(0x11000000)
        }
        container.addView(shortcutDisplay)

        // Hidden EditText to grab hardware key focus, TYPE_NULL blocks IME
        val keyGrabber = android.widget.EditText(this).apply {
            setRawInputType(android.text.InputType.TYPE_NULL)
            showSoftInputOnFocus = false
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (capturingShortcut && event.action == KeyEvent.ACTION_DOWN) {
                    val isModifier = keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT
                        || keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
                        || keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT
                        || keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT
                    val parts = mutableListOf<String>()
                    if (event.isCtrlPressed || keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT)
                        parts.add("Ctrl")
                    if (event.isAltPressed || keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT)
                        parts.add("Alt")
                    if (event.isShiftPressed || keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT)
                        parts.add("Shift")
                    if (event.isMetaPressed || keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT)
                        parts.add("Win")
                    if (!isModifier) {
                        val keyName = keyCodeToShortcutName(keyCode)
                        if (keyName != null) parts.add(keyName)
                    }
                    if (parts.isNotEmpty()) {
                        capturedShortcut = parts.joinToString("+")
                        shortcutDisplay?.text = capturedShortcut
                    }
                    true
                } else false
            }
        }
        container.addView(keyGrabber)

        capturingShortcut = true
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.profile_maker_dialog_title))
            .setMessage(getString(R.string.profile_maker_dialog_msg))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                capturingShortcut = false
                shortcutDisplay = null
                remapTo = capturedShortcut
                binding.tvKeyremapValue.text = remapTo.ifBlank { getString(R.string.profile_maker_keyremap_placeholder) }
                updatePreview()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                capturingShortcut = false
                shortcutDisplay = null
            }
            .setOnCancelListener {
                capturingShortcut = false
                shortcutDisplay = null
            }
            .create()

        dialog.setOnShowListener {
            keyGrabber.requestFocus()
        }
        dialog.show()
    }

    private fun keyCodeToShortcutName(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_ENTER -> "Enter"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_ESCAPE -> "Esc"
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
        else -> null
    }

    private fun onStrategyChanged() {
        binding.rowKeyremap.visibility =
            if (selectedStrategy == Strategy.keyremap) android.view.View.VISIBLE
            else android.view.View.GONE
    }

    private fun isComplete(): Boolean =
        selectedImePackage != null && selectedStrategy != null &&
        (selectedStrategy != Strategy.keyremap || remapTo.isNotBlank())

    private fun updatePreview() {
        val complete = isComplete()
        binding.tvPreviewLabel.text = if (complete)
            getString(R.string.profile_maker_preview_complete)
        else
            getString(R.string.profile_maker_preview_incomplete)
        binding.tvPreview.text = buildJsonString()
    }

    private fun buildJsonString(): String {
        val sb = StringBuilder("{\n")
        var first = true

        fun append(key: String, value: String) {
            if (!first) sb.append(",\n")
            first = false
            sb.append("  \"$key\": \"$value\"")
        }

        // Auto-generate name matching import behavior
        if (selectedImePackage != null) {
            append("ime", selectedImePackage!!)
            val autoName = selectedImeName ?: selectedImePackage!!.let { pkg ->
                pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
            }
            append("name", "$autoName 的配置")
        }
        selectedStrategy?.let { append("strategy", it.name) }
        if (selectedStrategy == Strategy.keyremap && remapTo.isNotBlank()) {
            append("remap-to", remapTo)
        }

        sb.append("\n}")
        return sb.toString()
    }
}
