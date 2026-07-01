package moe.lovefirefly.betterzuikey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Utils.LogHelper

object AppKeyCommandDialog {

    const val EXTRA_OPEN_APP_KEY = "open_app_key_editor"

    fun show(context: Context, appKey: String, onChanged: () -> Unit = {}) {
        if (appKey != "keyApp1" && appKey != "keyApp2" && appKey != "winLongPress") {
            LogHelper.log(LogHelper.VerboseLevel.WARNING, "AppKeyEditor: dialog invalid key=", appKey)
            return
        }
        LogHelper.log(LogHelper.VerboseLevel.INFO, "AppKeyEditor: show dialog key=", appKey,
            " ctx=", context.javaClass.simpleName)
        val meta = ShortcutMeta.ALL.find { it.key == appKey } ?: run {
            LogHelper.log(LogHelper.VerboseLevel.WARNING, "AppKeyEditor: meta not found key=", appKey)
            return
        }
        val cfg = Config.load()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_app_key_command, null)
        val tvDesc = view.findViewById<TextView>(R.id.tv_command_editor_desc)
        TermuxPermissionDialog.bindDescriptionLink(tvDesc, context)

        val templates = listOf(
            AppKeyCommandTemplate(R.string.app_key_template_termux_full, R.raw.app_key_template_termux_full),
            AppKeyCommandTemplate(R.string.app_key_template_termux_short, R.raw.app_key_template_termux_short),
            AppKeyCommandTemplate(R.string.app_key_template_launchapp_short, R.raw.app_key_template_launchapp_short),
            AppKeyCommandTemplate(R.string.app_key_template_launchapp_full, R.raw.app_key_template_launchapp_full),
        )
        val spTemplate = view.findViewById<Spinner>(R.id.sp_command_template)
        spTemplate.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            templates.map { context.getString(it.labelRes) },
        )

        val etScript = view.findViewById<TextInputEditText>(R.id.et_command_script)
        val etStdout = view.findViewById<TextInputEditText>(R.id.et_command_stdout)
        val btnTest = view.findViewById<MaterialButton>(R.id.btn_command_test)
        val cbRoot = view.findViewById<MaterialCheckBox>(R.id.cb_command_root)
        val cbSingleton = view.findViewById<MaterialCheckBox>(R.id.cb_command_singleton)
        val spTimeout = view.findViewById<Spinner>(R.id.sp_command_timeout)

        val timeoutMinutes = AppKeyCommandTimeoutOptions.minutes
        spTimeout.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            AppKeyCommandTimeoutOptions.labels(context),
        )
        val savedTimeout = ShortcutMeta.getAppKeyCommandTimeoutMin(cfg, appKey)
        spTimeout.setSelection(timeoutMinutes.indexOf(savedTimeout).coerceAtLeast(0))

        etScript.setText(ShortcutMeta.getAppKeyCommand(cfg, appKey))
        cbRoot.isChecked = ShortcutMeta.getAppKeyCommandRoot(cfg, appKey)
        cbSingleton.isChecked = ShortcutMeta.getAppKeyCommandSingleton(cfg, appKey)

        fun readTimeoutMin(): Int =
            timeoutMinutes[spTimeout.selectedItemPosition.coerceIn(0, timeoutMinutes.lastIndex)]

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_app_key_command_window_title, meta.displayName(context)))
            .setView(view)
            .create()

        view.findViewById<MaterialButton>(R.id.btn_termux_permission).setOnClickListener {
            TermuxPermissionDialog.show(context)
        }
        view.findViewById<MaterialButton>(R.id.btn_apply_template).setOnClickListener {
            val index = spTemplate.selectedItemPosition.coerceIn(0, templates.lastIndex)
            etScript.setText(readRawTemplate(context, templates[index].rawRes))
        }
        view.findViewById<MaterialButton>(R.id.btn_paste_clipboard).setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrEmpty()) {
                etScript.setText(text)
            }
        }
        btnTest.setOnClickListener {
            val script = etScript.text?.toString() ?: ""
            val timeoutMin = readTimeoutMin()
            btnTest.isEnabled = false
            etStdout.setText(context.getString(R.string.dialog_app_key_command_running))
            Thread({
                val result = AppKeyCommandExecutor.execute(
                    context,
                    script,
                    cbRoot.isChecked,
                    cbSingleton.isChecked,
                    timeoutMin,
                )
                val text = AppKeyCommandExecutor.formatForDisplay(context, result, timeoutMin)
                view.post {
                    if (!dialog.isShowing) return@post
                    etStdout.setText(text)
                    btnTest.isEnabled = true
                }
            }, "AppKeyCommandTest").start()
        }
        view.findViewById<MaterialButton>(R.id.btn_command_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_command_save).setOnClickListener {
            val latest = Config.load()
            ShortcutMeta.setAppKeyCommand(latest, appKey, etScript.text?.toString()?.trim() ?: "")
            ShortcutMeta.setAppKeyCommandRoot(latest, appKey, cbRoot.isChecked)
            ShortcutMeta.setAppKeyCommandSingleton(latest, appKey, cbSingleton.isChecked)
            ShortcutMeta.setAppKeyCommandTimeoutMin(latest, appKey, readTimeoutMin())
            latest.save()
            Config.syncToSharedPrefs(context, latest)
            LogHelper.log(
                LogHelper.VerboseLevel.INFO,
                "App key command saved: ",
                appKey,
                " len=",
                ShortcutMeta.getAppKeyCommand(latest, appKey).length.toString(),
            )
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            val script = etScript.text?.toString()?.trim() ?: ""
            if (script.isEmpty()) {
                val latest = Config.load()
                ShortcutMeta.setAppKeyCommand(latest, appKey, "")
                ShortcutMeta.setAppKeyMode(latest, appKey, Config.AppKeyMode.BLOCK)
                latest.save()
                Config.syncToSharedPrefs(context, latest)
            }
            onChanged()
        }

        dialog.show()
    }

    private fun readRawTemplate(context: Context, rawRes: Int): String =
        context.resources.openRawResource(rawRes).bufferedReader().use { it.readText() }
}

private data class AppKeyCommandTemplate(val labelRes: Int, val rawRes: Int)

private object AppKeyCommandTimeoutOptions {
    val minutes = listOf(1, 5, 10)

    fun labels(context: Context): List<String> = listOf(
        context.getString(R.string.dialog_app_key_command_timeout_1m),
        context.getString(R.string.dialog_app_key_command_timeout_5m),
        context.getString(R.string.dialog_app_key_command_timeout_10m),
    )
}
