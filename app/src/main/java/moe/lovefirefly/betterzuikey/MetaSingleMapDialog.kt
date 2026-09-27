package moe.lovefirefly.betterzuikey

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Utils.LogHelper

/**
 * Win 单按「映射到…」的录制窗口。
 *
 * <p>UI 参考 WeTypeExt 的快捷键窗口（标题 + 提示 + 一个只用来显示的框 + 取消/确定），
 * 但限制宽松得多：**任何单键和组合键都收**，不像那边要求必须带修饰键。
 *
 * <p>录不到已经被模块占用的组合（比如 Ctrl+Shift+T）是个真问题，所以弹窗一出现就会
 * 通过 ContentProvider 通知 system_server「先别处理快捷键」，关掉再恢复。
 *
 * <h3>按键拦截</h3>
 * 挂在 [AlertDialog.setOnKeyListener] 上，并且**一律返回 true 全吞**。这是
 * `Dialog.dispatchKeyEvent` 问的第一站 —— 比挂在输入框的 `onKeyDown` 彻底：
 * 按键不会再被按钮焦点、弹窗自身的返回逻辑（`Dialog.onKeyDown` → `onBackPressed`）
 * 或焦点导航吃掉，也不会漏给系统。与键盘检测页
 * `KeyboardDetectActivity.dispatchKeyEvent` 里那句 `return true` 是同一个思路。
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
        val field = view.findViewById<TextView>(R.id.tv_meta_map_capture)
        val btnClear = view.findViewById<MaterialButton>(R.id.btn_meta_map_clear)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_meta_map_cancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btn_meta_map_save)

        var captured = ShortcutMeta.getMetaSingleMap(cfg)
        /** 进窗时原本就有映射 —— 空值保存时用它决定「撤回」还是落到「关闭」。 */
        val hadMapping = captured.isSet
        var saved = false

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        fun refresh() {
            field.text = if (captured.isSet) captured.displayName()
            else context.getString(R.string.dialog_meta_single_map_waiting)
        }
        refresh()

        // ── 录键 ────────────────────────────────────────────────────────────
        // Esc / 退格 既要能「录成映射目标」，又要保留「取消 / 清除」——
        // 只能靠长按区分：短按录键，长按才做那两件事。
        val handler = Handler(Looper.getMainLooper())
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var pendingKey = 0
        var pendingMeta = 0
        var longPressFired = false
        var longPressRunnable: Runnable? = null

        fun stopLongPressTimer() {
            longPressRunnable?.let(handler::removeCallbacks)
            longPressRunnable = null
            pendingKey = 0
            longPressFired = false
        }

        fun record(keyCode: Int, metaState: Int) {
            captured = MetaKeyMap(
                keyCode = keyCode,
                shift = (metaState and KeyEvent.META_SHIFT_ON) != 0,
                ctrl = (metaState and KeyEvent.META_CTRL_ON) != 0,
                alt = (metaState and KeyEvent.META_ALT_ON) != 0,
            )
            refresh()
        }

        fun handleKey(event: KeyEvent) {
            val kc = event.keyCode
            val isDown = event.action == KeyEvent.ACTION_DOWN
            when (kc) {
                // 返回键：始终立即取消（不作为映射目标）
                KeyEvent.KEYCODE_BACK -> if (isDown) dialog.dismiss()

                // Esc / 退格：短按录成该键本身，长按分别是 取消 / 清除
                KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL -> when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                        // 记住按下那一刻的修饰位：抬起时再读 metaState 可能已经松开修饰键了
                        pendingKey = kc
                        pendingMeta = event.metaState
                        longPressFired = false
                        val r = Runnable {
                            longPressFired = true
                            if (kc == KeyEvent.KEYCODE_ESCAPE) {
                                dialog.dismiss()             // 长按 Esc = 取消
                            } else {
                                captured = MetaKeyMap.UNSET  // 长按退格 = 清除（留在窗内）
                                refresh()
                            }
                        }
                        longPressRunnable = r
                        handler.postDelayed(r, longPressTimeout)
                    }
                    KeyEvent.ACTION_UP -> if (kc == pendingKey) {
                        val fired = longPressFired
                        val meta = pendingMeta
                        stopLongPressTimer()
                        if (!fired) record(kc, meta)   // 只有短按才录
                    }
                }

                else -> if (isDown && event.repeatCount == 0 && !MetaKeyMap.isModifierKey(kc)) {
                    stopLongPressTimer()
                    record(kc, event.metaState)
                }
            }
        }

        dialog.setOnKeyListener { _, _, event ->
            handleKey(event)
            true   // 全吞：一个按键都不漏给弹窗自身或系统
        }

        btnClear.setOnClickListener {
            captured = MetaKeyMap.UNSET
            refresh()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            if (!captured.isSet) {
                // 空值也要有确定的行为，不能"点了没反应"：
                //  · 本来就有映射 → 撤回（cfg 一动不动，旧映射保留，等价于取消）
                //  · 本来就没映射 → 落到「关闭」，别停在「映射到…（未设置）」
                //    这种看着生效、实际什么都不做的档位
                if (hadMapping) {
                    LogHelper.log(LogHelper.VerboseLevel.INFO,
                        "MetaSingleMap: empty save → keep previous mapping")
                    dialog.dismiss()   // saved 保持 false ⇒ onCancelled ⇒ 重绑回旧值
                    return@setOnClickListener
                }
                ShortcutMeta.setMetaSingleMap(cfg, MetaKeyMap.UNSET)
                ShortcutMeta.setMetaSingleUiMode(cfg, MetaSingleUiMode.OFF)
                cfg.save()
                Config.syncToSharedPrefs(context, cfg)
                LogHelper.log(LogHelper.VerboseLevel.INFO,
                    "MetaSingleMap: empty save → fallback to OFF")
                saved = true
                dialog.dismiss()
                return@setOnClickListener
            }
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
            stopLongPressTimer()   // 别让挂起的计时器在关窗后再动 cfg
            setRecording(context, false)
            // 没录到目标就不算数：回到进来之前那一档，避免出现
            // 「映射到…（未设置）」这种看着生效、实际什么都不做的状态。
            if (saved) onChanged() else onCancelled()
        }

        dialog.show()
        setRecording(context, true)
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
