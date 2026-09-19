package moe.lovefirefly.betterzuikey

import android.app.Activity
import android.os.Handler
import android.os.Looper

/**
 * 启动期模态对话框的串行闸门。
 *
 * 场景：冷启动时可能有多个模态弹窗同时要弹（协议 / 求投喂 / 发现新版本），会叠在一起。
 * 优先级：协议 > 投喂 > 更新；前一个关闭后再等 [GAP_MS] 才弹下一个。
 * 本质是一个支持插队的 FIFO 队列。
 *
 * 用法：
 *  - 展示方：`ModalDialogGate.enqueue { show(...) }`，并在对话框 dismiss 时调用 [release]；
 *  - 需要优先展示（求投喂优先于更新）：[enqueueFirst]；
 *  - 若因 Activity 已销毁等原因没能真正弹出，也必须调 [release]，否则队列会永久卡住。
 *
 * 线程：全部切到主线程执行，可从任意线程调用。
 */
object ModalDialogGate {

    /** 前后两个对话框之间的间隔 */
    const val GAP_MS = 500L

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<() -> Unit>()

    /**
     * true 表示「有对话框正在显示」或「正处于两个对话框之间的间隔期」。
     * 间隔期内保持 true，这样间隔期间新来的请求会继续排队，不会被立刻弹出。
     */
    private var busy = false

    /** 排队展示；当前空闲则立即展示 */
    fun enqueue(block: () -> Unit) = enqueue(block, front = false)

    /** 插到队首，下一个展示（求投喂优先于更新） */
    fun enqueueFirst(block: () -> Unit) = enqueue(block, front = true)

    private fun enqueue(block: () -> Unit, front: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (busy) {
                if (front) queue.addFirst(block) else queue.addLast(block)
            } else {
                busy = true
                block()
            }
        } else {
            handler.post { enqueue(block, front) }
        }
    }

    /** 当前对话框已关闭：等 [GAP_MS] 后放行下一个；没有下一个则回到空闲 */
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { release() }
            return
        }
        val next = queue.removeFirstOrNull()
        if (next == null) {
            busy = false
        } else {
            // busy 保持 true，间隔期内新来的请求继续排队
            handler.postDelayed({ next() }, GAP_MS)
        }
    }

    /** 调试：清空队列与状态 */
    fun reset() {
        handler.post {
            queue.clear()
            busy = false
        }
    }

    /** Activity 已销毁时不该再弹窗；顺带保证闸门被释放 */
    fun isGone(ctx: Any?): Boolean {
        val activity = ctx as? Activity ?: return false
        return activity.isFinishing || activity.isDestroyed
    }
}
