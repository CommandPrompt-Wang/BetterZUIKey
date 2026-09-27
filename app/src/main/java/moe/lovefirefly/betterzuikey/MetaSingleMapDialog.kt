package moe.lovefirefly.betterzuikey

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Utils.LogHelper

/**
 * Win 单按「映射到…」的录制窗口。
 *
 * <p>UI 参考 WeTypeExt 的快捷键窗口（标题 + 提示 + 一个只用来捕获按键的框 + 取消/确定），
 * 但限制宽松得多：**任何单键和组合键都收**，不像那边要求必须带修饰键。
 *
 * <p>录不到已经被模块占用的组合（比如 Ctrl+Shift+T）是个真问题，所以弹窗一出现就会
 * 通过 ContentProvider 通知 system_server「先别处理快捷键」，关掉再恢复。
 */
object MetaSingleMapDialog {

    /**
     * 录制标志的有效期。存的是截止时刻而不是布尔值：App 若在录制中被杀/崩溃，
     * 标志会自己过期，不会让模块永久性地停止响应快捷键。
     */
    private const val RECORDING_TTL_MS = 10 * 60 * 1000L

    /**
     * @param cfg 调用方（设置页）正在使用的那一份 Config 实例。
     *
     * <p>**必须传进来，不能在这里自己 [Config.load]**：卡片的下拉文案读的就是调用方
     * 缓存的那一份，另开一份实例去写盘会出现两个问题 ——
     * 界面上「映射到…」永远显示旧档位；之后调用方任何一次 `cfg.save()` 都会用
     * 它那份没更新的 `metaSingleMap` 把刚录好的映射覆盖掉。
     */
    fun show(
        context: Context,
        cfg: Config,
        onCancelled: () -> Unit = {},
        onChanged: () -> Unit = {},
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_meta_single_map, null)
        val field = view.findViewById<ShortcutCaptureEditText>(R.id.et_meta_map_capture)
        val btnClear = view.findViewById<MaterialButton>(R.id.btn_meta_map_clear)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_meta_map_cancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btn_meta_map_save)

        var captured = ShortcutMeta.getMetaSingleMap(cfg)
        var saved = false

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        fun refresh() {
            field.setText(
                if (captured.isSet) captured.displayName()
                else context.getString(R.string.dialog_meta_single_map_waiting)
            )
            btnSave.isEnabled = captured.isSet
        }
        refresh()

        field.captureListener = { event ->
            when {
                event.keyCode == KeyEvent.KEYCODE_ESCAPE
                        || event.keyCode == KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN) dialog.dismiss()
                    true
                }
                event.keyCode == KeyEvent.KEYCODE_DEL -> {
                    // Backspace = 清除；只认第一次 DOWN，免得长按连清
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        captured = MetaKeyMap.UNSET
                        refresh()
                    }
                    true
                }
                else -> {
                    if (event.action == KeyEvent.ACTION_DOWN
                        && event.repeatCount == 0
                        && !MetaKeyMap.isModifierKey(event.keyCode)
                    ) {
                        val meta = event.metaState
                        captured = MetaKeyMap(
                            keyCode = event.keyCode,
                            shift = (meta and KeyEvent.META_SHIFT_ON) != 0,
                            ctrl = (meta and KeyEvent.META_CTRL_ON) != 0,
                            alt = (meta and KeyEvent.META_ALT_ON) != 0,
                        )
                        refresh()
                    }
                    true
                }
            }
        }

        btnClear.setOnClickListener {
            captured = MetaKeyMap.UNSET
            refresh()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            if (!captured.isSet) return@setOnClickListener
            // 直接改写调用方那一份实例：界面下次 bind 读到的就是新值。
            ShortcutMeta.setMetaSingleMap(cfg, captured)
            ShortcutMeta.setMetaSingleUiMode(cfg, MetaSingleUiMode.MAP)
            cfg.save()
            Config.syncToSharedPrefs(context, cfg)
            LogHelper.log(LogHelper.VerboseLevel.INFO,
                "MetaSingleMap: saved ", captured.serialize())
            saved = true
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            setRecording(context, false)
            // 没录到目标就不算数：回到进来之前那一档，避免出现
            // 「映射到…（未设置）」这种看着生效、实际什么都不做的状态。
            if (saved) onChanged() else onCancelled()
        }

        dialog.show()
        setRecording(context, true)
        field.requestFocus()
    }

    /** 告知模块：录制期间对任何按键都撒手（见 {@code HookContext.isShortcutRecording}）。 */
    private fun setRecording(context: Context, active: Boolean) {
        try {
            context.contentResolver.call(
                ConfigSyncProvider.RELOAD_URI,
                ConfigSyncProvider.METHOD_SET_SHORTCUT_RECORDING,
                null,
                Bundle().apply {
                    putLong(
                        ConfigSyncProvider.KEY_SHORTCUT_RECORDING,
                        if (active) SystemClock.elapsedRealtime() + RECORDING_TTL_MS else 0L,
                    )
                },
            )
        } catch (e: Exception) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                "MetaSingleMap: setRecording IPC failed:", e.message)
        }
    }
}
