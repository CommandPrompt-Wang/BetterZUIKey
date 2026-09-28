package moe.lovefirefly.betterzuikey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Config.KeyTemplate
import moe.lovefirefly.betterzuikey.Utils.LogHelper

object AppKeyCommandDialog {

    const val EXTRA_OPEN_APP_KEY = "open_app_key_editor"

    /** 编辑器读写的一份命令配置。 */
    data class Spec(
        val command: String,
        val root: Boolean,
        val singleton: Boolean,
        val timeoutMin: Int,
    )

    /**
     * 编辑器的写入目标：全局的某张卡，或**模板里的一张卡**（模板可独立配命令）。
     */
    interface Store {
        val title: String
        fun load(): Spec
        fun save(spec: Spec)

        /** 关闭时脚本为空：全局 → 清空并落到「忽略」；模板 → 解除这张卡的覆写 */
        fun clear()
    }

    /** 全局卡片：写 Config、落盘并同步。语义与改动前完全一致。 */
    class GlobalStore(private val context: Context, private val appKey: String) : Store {
        override val title: String
            get() = context.getString(
                R.string.dialog_app_key_command_window_title,
                ShortcutMeta.ALL.find { it.key == appKey }?.displayName(context) ?: appKey,
            )

        override fun load(): Spec {
            val cfg = Config.load()
            return Spec(
                ShortcutMeta.getAppKeyCommand(cfg, appKey),
                ShortcutMeta.getAppKeyCommandRoot(cfg, appKey),
                ShortcutMeta.getAppKeyCommandSingleton(cfg, appKey),
                ShortcutMeta.getAppKeyCommandTimeoutMin(cfg, appKey),
            )
        }

        override fun save(spec: Spec) {
            val cfg = Config.load()
            ShortcutMeta.setAppKeyCommand(cfg, appKey, spec.command)
            ShortcutMeta.setAppKeyCommandRoot(cfg, appKey, spec.root)
            ShortcutMeta.setAppKeyCommandSingleton(cfg, appKey, spec.singleton)
            ShortcutMeta.setAppKeyCommandTimeoutMin(cfg, appKey, spec.timeoutMin)
            cfg.save()
            Config.syncToSharedPrefs(context, cfg)
            LogHelper.log(LogHelper.VerboseLevel.INFO,
                "App key command saved: ", appKey, " len=", spec.command.length.toString())
        }

        override fun clear() {
            val cfg = Config.load()
            ShortcutMeta.setAppKeyCommand(cfg, appKey, "")
            ShortcutMeta.setAppKeyMode(cfg, appKey, Config.AppKeyMode.BLOCK)
            cfg.save()
            Config.syncToSharedPrefs(context, cfg)
        }
    }

    /**
     * 模板里的一张卡：只改这份覆写，落盘/同步交给模板编辑器。
     * 「清除」= 解除该卡覆写（回到继承全局），而不是像全局那样把档位落到「忽略」。
     */
    class TemplateStore(
        private val context: Context,
        private val template: KeyTemplate,
        private val key: String,
    ) : Store {
        override val title: String
            get() = context.getString(
                R.string.dialog_app_key_command_window_title,
                ShortcutMeta.ALL.find { it.key == key }?.displayName(context) ?: key,
            )

        private fun ensure(): moe.lovefirefly.betterzuikey.Config.PerKeyOverride =
            template.get(key) ?: moe.lovefirefly.betterzuikey.Config.PerKeyOverride()

        override fun load(): Spec {
            val ov = template.get(key)
            // 模板没表态的字段以全局当前值为起点，方便在此基础上改
            val cfg = Config.load()
            return Spec(
                ov?.command ?: ShortcutMeta.getAppKeyCommand(cfg, key),
                ov?.commandRoot ?: ShortcutMeta.getAppKeyCommandRoot(cfg, key),
                ov?.commandSingleton ?: ShortcutMeta.getAppKeyCommandSingleton(cfg, key),
                ov?.commandTimeoutMin ?: ShortcutMeta.getAppKeyCommandTimeoutMin(cfg, key),
            )
        }

        override fun save(spec: Spec) {
            // 先填字段再入 map：覆写对象一旦离开原地就会被丢弃，顺序不能反
            val ov = ensure()
            ov.useCommand = true
            ov.command = spec.command
            ov.commandRoot = spec.root
            ov.commandSingleton = spec.singleton
            ov.commandTimeoutMin = spec.timeoutMin
            template.put(key, ov)
        }

        override fun clear() {
            template.overrides.remove(key)
        }
    }

    /** 全局卡片（保持原有调用方式）。 */
    fun show(context: Context, appKey: String, onChanged: () -> Unit = {}) {
        if (appKey != "keyApp1" && appKey != "keyApp2" && appKey != "winLongPress") {
            LogHelper.log(LogHelper.VerboseLevel.WARNING, "AppKeyEditor: dialog invalid key=", appKey)
            return
        }
        show(context, GlobalStore(context, appKey), onChanged)
    }

    fun show(context: Context, store: Store, onChanged: () -> Unit = {}) {
        LogHelper.log(LogHelper.VerboseLevel.INFO, "AppKeyEditor: show dialog title=", store.title,
            " ctx=", context.javaClass.simpleName)
        val initial = store.load()
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
        val tvCrlfWarning = view.findViewById<TextView>(R.id.tv_command_crlf_warning)
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
        spTimeout.setSelection(timeoutMinutes.indexOf(initial.timeoutMin).coerceAtLeast(0))

        etScript.setText(initial.command)
        cbRoot.isChecked = initial.root
        cbSingleton.isChecked = initial.singleton

        fun updateCrLfWarning() {
            val script = etScript.text?.toString() ?: ""
            tvCrlfWarning.visibility =
                if (AppKeyCommandExecutor.containsCrLineEndings(script)) View.VISIBLE else View.GONE
        }
        etScript.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCrLfWarning()
            }
        })
        updateCrLfWarning()

        fun readTimeoutMin(): Int =
            timeoutMinutes[spTimeout.selectedItemPosition.coerceIn(0, timeoutMinutes.lastIndex)]

        val dialog = AlertDialog.Builder(context)
            .setTitle(store.title)
            .setView(view)
            .create()

        view.findViewById<MaterialButton>(R.id.btn_termux_permission).setOnClickListener {
            TermuxPermissionDialog.show(context)
        }
        view.findViewById<MaterialButton>(R.id.btn_apply_template).setOnClickListener {
            val index = spTemplate.selectedItemPosition.coerceIn(0, templates.lastIndex)
            etScript.setText(readRawTemplate(context, templates[index].rawRes))
            updateCrLfWarning()
        }
        view.findViewById<MaterialButton>(R.id.btn_paste_clipboard).setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrEmpty()) {
                etScript.setText(text)
                updateCrLfWarning()
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
            store.save(
                Spec(
                    etScript.text?.toString()?.trim() ?: "",
                    cbRoot.isChecked,
                    cbSingleton.isChecked,
                    readTimeoutMin(),
                )
            )
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            // 关窗时脚本为空 → 交给目标处理（全局落到「忽略」、模板解除覆写）
            if ((etScript.text?.toString()?.trim() ?: "").isEmpty()) {
                store.clear()
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
