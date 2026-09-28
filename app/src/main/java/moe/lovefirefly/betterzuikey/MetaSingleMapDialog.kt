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
import moe.lovefirefly.betterzuikey.Config.KeyTemplate
import moe.lovefirefly.betterzuikey.Config.PerKeyOverride
import moe.lovefirefly.betterzuikey.Utils.LogHelper

/**
 * Win 单按「映射到…」的录制窗口。
 *
 * <h3>录制期间模块全权接管</h3>
 * 弹窗一出现就通过 ContentProvider 通知 system_server：这段时间的按键**全部消费**，
 * 只上报给弹窗。这样做的原因有两个：
 * <ul>
 *   <li>亮度 / 音量 / toggle 这类键按下去本身就有副作用（真的会改亮度、改音量、切窗口），
 *       录制时不该发生；</li>
 *   <li>不消费的话，已经被模块占用的组合（Ctrl+Shift+T 之类）也录不到。</li>
 * </ul>
 * 代价是应用窗口收不到按键，所以这里完全靠模块上报重建：只报「第一次按下」和「抬起」，
 * 靠这两条区分短按与长按。窗口那条 [AlertDialog.setOnKeyListener] 只作兜底
 * （万一某个键漏过模块的消费）。
 *
 * <p>「全权接管」是**短租约**：弹窗每隔几秒续一次，进程一旦被杀/崩溃，
 * 最迟一个租约（8 秒）后模块就恢复处理快捷键，不会把键盘锁死。
 */
object MetaSingleMapDialog {

    /** 录制接管标志的租约时长；弹窗开着时每 [HEARTBEAT_MS] 续一次。 */
    private const val RECORD_LEASE_MS = 8_000L
    private const val HEARTBEAT_MS = 3_000L

    /** 轮询模块上报队列的间隔。 */
    private const val RECORD_POLL_MS = 50L

    private const val ACTION_DOWN = 0
    private const val ACTION_UP = 1

    /**
     * @param cfg 调用方（设置页）正在使用的那一份 Config 实例。
     *
     * <p>**必须传进来，不能在这里自己 [Config.load]**：卡片的下拉文案读的就是调用方
     * 缓存的那一份，另开一份实例去写盘会出现两个问题 ——
     * 界面上「映射到…」永远显示旧档位；之后调用方任何一次 `cfg.save()` 都会用
     * 它那份没更新的 `metaSingleMap` 把刚录好的映射覆盖掉。
     */
    /** 录制结果的写入目标：全局卡片，或模板里的一张卡。 */
    interface Store {
        fun load(): MetaKeyMap
        fun save(map: MetaKeyMap)

        /** 空值确定：全局 → 清掉映射并落到「关闭」；模板 → 解除这张卡的覆写 */
        fun clear()
    }

    /** 全局卡片：写 Config、落盘并同步（语义与改动前一致）。 */
    class GlobalStore(private val context: Context, private val cfg: Config) : Store {
        override fun load(): MetaKeyMap = ShortcutMeta.getMetaSingleMap(cfg)

        override fun save(map: MetaKeyMap) {
            ShortcutMeta.setMetaSingleMap(cfg, map)
            ShortcutMeta.setMetaSingleUiMode(cfg, MetaSingleUiMode.MAP)
            cfg.save()
            Config.syncToSharedPrefs(context, cfg)
        }

        override fun clear() {
            ShortcutMeta.setMetaSingleMap(cfg, MetaKeyMap.UNSET)
            ShortcutMeta.setMetaSingleUiMode(cfg, MetaSingleUiMode.OFF)
            cfg.save()
            Config.syncToSharedPrefs(context, cfg)
        }
    }

    /**
     * 模板里的一张卡：映射目标**模板独立**（写进覆写的 mapTarget），落盘交给模板编辑器。
     * 「清除」= 解除该卡覆写，而不是像全局那样把档位落到「关闭」。
     */
    class TemplateStore(private val template: KeyTemplate, private val key: String) : Store {
        override fun load(): MetaKeyMap {
            val ov = template.get(key)
            val raw = ov?.mapTarget?.takeIf { it.isNotBlank() } ?: Config.load().metaSingleMap
            return MetaKeyMap.parse(raw)
        }

        override fun save(map: MetaKeyMap) {
            // 先填字段再入 map：覆写对象一旦离开原地就会被丢弃，顺序不能反
            val ov = template.get(key) ?: PerKeyOverride()
            ov.useMap = true
            ov.mapTarget = map.serialize()
            ov.overrideMode = null
            template.put(key, ov)
        }

        override fun clear() {
            template.overrides.remove(key)
        }
    }

    /** 全局卡片（保持原有调用方式）。 */
    fun show(
        context: Context,
        cfg: Config,
        onCancelled: () -> Unit = {},
        onChanged: () -> Unit = {},
    ) = show(context, GlobalStore(context, cfg), onCancelled, onChanged)

    fun show(
        context: Context,
        store: Store,
        onCancelled: () -> Unit = {},
        onChanged: () -> Unit = {},
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_meta_single_map, null)
        val field = view.findViewById<TextView>(R.id.tv_meta_map_capture)
        val btnClear = view.findViewById<MaterialButton>(R.id.btn_meta_map_clear)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_meta_map_cancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btn_meta_map_save)

        var captured = store.load()
        var saved = false

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        fun refresh() {
            field.text = if (captured.isSet) captured.displayName()
            else context.getString(R.string.dialog_meta_single_map_waiting)
        }
        refresh()

        val handler = Handler(Looper.getMainLooper())
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val prefs = context.getSharedPreferences(
            ConfigSyncProvider.PREF_FILE, Context.MODE_PRIVATE)

        // ── Esc / 退格：短按录键，长按 取消 / 清除 ──
        var pendingKey = 0
        var pendingMeta = 0
        var pendingScan = 0
        var longPressFired = false
        var longPressRunnable: Runnable? = null

        fun stopLongPress() {
            longPressRunnable?.let(handler::removeCallbacks)
            longPressRunnable = null
            pendingKey = 0
            longPressFired = false
        }

        fun record(keyCode: Int, metaState: Int, scanCode: Int) {
            captured = MetaKeyMap.of(keyCode, metaState, scanCode)
            refresh()
        }

        /** 一个按键事件。模块上报和窗口兜底都走这里。 */
        fun onKey(keyCode: Int, scanCode: Int, metaState: Int, action: Int) {
            // Win 本身不作为映射目标 —— 它是触发键，映射成自己没有意义
            if (keyCode == KeyEvent.KEYCODE_META_LEFT
                || keyCode == KeyEvent.KEYCODE_META_RIGHT) return
            when {
                keyCode == KeyEvent.KEYCODE_BACK -> if (action == ACTION_DOWN) dialog.dismiss()

                keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_DEL -> {
                    if (action == ACTION_DOWN) {
                        pendingKey = keyCode
                        pendingMeta = metaState
                        pendingScan = scanCode
                        longPressFired = false
                        val r = Runnable {
                            longPressFired = true
                            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                                dialog.dismiss()             // 长按 Esc = 取消
                            } else {
                                captured = MetaKeyMap.UNSET  // 长按退格 = 清除（留在窗内）
                                refresh()
                            }
                        }
                        longPressRunnable = r
                        handler.postDelayed(r, longPressTimeout)
                    } else if (keyCode == pendingKey) {
                        val fired = longPressFired
                        val meta = pendingMeta
                        val scan = pendingScan
                        stopLongPress()
                        if (!fired) record(keyCode, meta, scan)   // 只有短按才录
                    }
                }

                action == ACTION_DOWN -> {
                    stopLongPress()
                    record(keyCode, metaState, scanCode)
                }
            }
        }

        // ── 模块上报队列（`keyCode,scanCode,metaState,action` 每行一条）──
        fun drainReportedKeys() {
            val raw = prefs.getString(ConfigSyncProvider.PREF_RECORDED_QUEUE, "") ?: ""
            if (raw.isEmpty()) return
            prefs.edit().remove(ConfigSyncProvider.PREF_RECORDED_QUEUE).apply()
            for (line in raw.split('\n')) {
                if (line.isEmpty()) continue
                val p = line.split(',')
                val kc = p.getOrNull(0)?.toIntOrNull() ?: continue
                val scan = p.getOrNull(1)?.toIntOrNull() ?: 0
                val meta = p.getOrNull(2)?.toIntOrNull() ?: 0
                val act = p.getOrNull(3)?.toIntOrNull() ?: ACTION_DOWN
                onKey(kc, scan, meta, act)
            }
        }

        val pollRunnable = object : Runnable {
            override fun run() {
                drainReportedKeys()
                handler.postDelayed(this, RECORD_POLL_MS)
            }
        }
        val heartbeatRunnable = object : Runnable {
            override fun run() {
                setRecording(context, true)
                handler.postDelayed(this, HEARTBEAT_MS)
            }
        }

        // 兜底：万一某个键漏过了模块的消费，窗口这条也能录到（同值重复无害）
        dialog.setOnKeyListener { _, _, event ->
            onKey(
                event.keyCode, event.scanCode, event.metaState,
                if (event.action == KeyEvent.ACTION_DOWN) ACTION_DOWN else ACTION_UP,
            )
            true
        }

        btnClear.setOnClickListener {
            captured = MetaKeyMap.UNSET
            refresh()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            drainReportedKeys()   // 最后一个键可能还没被轮询到
            if (!captured.isSet) {
                // 空值保存 = 把映射清掉（全局落到「关闭」；模板解除覆写）。
                // 也不该停在「映射到…（未设置）」这种看着生效、实际什么都不做的档位。
                store.clear()
                LogHelper.log(LogHelper.VerboseLevel.INFO,
                    "MetaSingleMap: empty save → clear")
                saved = true
                dialog.dismiss()
                return@setOnClickListener
            }
            store.save(captured)
            LogHelper.log(LogHelper.VerboseLevel.INFO,
                "MetaSingleMap: saved ", captured.serialize())
            saved = true
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            handler.removeCallbacks(pollRunnable)
            handler.removeCallbacks(heartbeatRunnable)
            stopLongPress()
            prefs.edit().remove(ConfigSyncProvider.PREF_RECORDED_QUEUE).apply()
            setRecording(context, false)
            // 没录到目标就不算数：回到进来之前那一档，避免出现
            // 「映射到…（未设置）」这种看着生效、实际什么都不做的状态。
            if (saved) onChanged() else onCancelled()
        }

        prefs.edit().remove(ConfigSyncProvider.PREF_RECORDED_QUEUE).apply()   // 清掉上次残留
        dialog.show()
        setRecording(context, true)
        handler.post(pollRunnable)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
    }

    /**
     * 告知模块：这段时间是否由录制弹窗接管按键。
     *
     * <p>传的是**租约截止时刻**（[SystemClock.elapsedRealtime]）：弹窗开着时靠心跳续期，
     * 进程被杀/崩溃后租约会自己过期，模块不会永久性地停止处理快捷键。
     */
    private fun setRecording(context: Context, active: Boolean) {
        try {
            context.contentResolver.call(
                ConfigSyncProvider.RELOAD_URI,
                ConfigSyncProvider.METHOD_SET_SHORTCUT_RECORDING,
                null,
                Bundle().apply {
                    putLong(
                        ConfigSyncProvider.KEY_SHORTCUT_RECORDING,
                        if (active) SystemClock.elapsedRealtime() + RECORD_LEASE_MS else 0L,
                    )
                },
            )
        } catch (e: Exception) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                "MetaSingleMap: setRecording IPC failed:", e.message)
        }
    }
}
