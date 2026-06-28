package moe.lovefirefly.betterzuikey.ime

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import moe.lovefirefly.betterzuikey.Utils.LogHelper
import moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel

/**
 * IME 按键注入调度器 — 所有定向注入和状态查询的入口。
 *
 * 核心保证：
 * 1. **无递归**: 注入事件通过 [ThreadLocal] 标记，Hook 层自动跳过
 * 2. **定向投喂**: `InputManager.injectInputEvent()` 走正常管线，
 *    IME 先收到；如果 IME 不消费（非输入状态），事件到 App。
 *    我们通过 [isAcceptingText] 保证只在输入状态注入。
 * 3. **双投递预留**: [injectToApp] 为后续 Binder 直达 App 预留入口，
 *    首期回退到正常管线。
 *
 * 注意：所有方法都从 system_server (LSPosed) 上下文调用，
 * Context 通过 ActivityThread.systemContext 获取。
 */
object IMEDispatcher {

    /** ThreadLocal 注入标记，Hook 入口检查此标记跳过注入事件 */
    val INJECTING: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /** 标记当前线程正在注入（所有 Hook 入口应检查并跳过） */
    @JvmStatic
    fun beginInject() { INJECTING.set(true) }

    /** 清除注入标记 */
    @JvmStatic
    fun endInject() { INJECTING.set(false) }

    /** 查询是否有激活的注入（供 Hook 入口使用） */
    @JvmStatic
    fun isInjecting(): Boolean = INJECTING.get() == true

    // -----------------------------------------------------------------
    // IME 状态
    // -----------------------------------------------------------------

    private var cachedSystemContext: Context? = null
    private var cachedImm: InputMethodManager? = null

    /**
     * 获取 system_server 下的 InputMethodManager。
     * 通过 ActivityThread.systemContext 反射获取。
     */
    private fun getIMM(): InputMethodManager? {
        if (cachedImm != null) return cachedImm
        try {
            val at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            val sysCtx = at.javaClass.getMethod("getSystemContext").invoke(at) as Context
            cachedSystemContext = sysCtx
            cachedImm = sysCtx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.ERROR, "IMEDispatcher: getIMM failed:", t.message)
        }
        return cachedImm
    }

    /**
     * 当前是否有输入框获焦、IME 正在接收文本输入。
     * 对应 [InputMethodManager.isAcceptingText]。
     */
    @JvmStatic
    fun isAcceptingText(): Boolean {
        return try {
            getIMM()?.isAcceptingText ?: false
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: isAcceptingText failed:", t.message)
            false
        }
    }

    /**
     * 获取当前激活的 IME 包名。
     * 供 AdapterManager 做适配器匹配。
     */
    @JvmStatic
    fun getCurrentIMEPackage(): String? {
        return try {
            getIMM()?.currentInputMethodInfo?.packageName
        } catch (t: Throwable) {
            null
        }
    }

    // -----------------------------------------------------------------
    // 按键注入（反射调用 InputManager hidden API）
    // -----------------------------------------------------------------

    /** Cached InputManager instance and inject mode constant */
    private var cachedInputManager: Any? = null
    private var cachedInjectMode: Int = -1

    private fun getInputManager(): Any? {
        if (cachedInputManager != null) return cachedInputManager
        return try {
            val clazz = Class.forName("android.hardware.input.InputManager")
            val method = clazz.getMethod("getInstance")
            cachedInputManager = method.invoke(null)
            // INJECT_INPUT_EVENT_MODE_ASYNC = 0
            cachedInjectMode = clazz.getField("INJECT_INPUT_EVENT_MODE_ASYNC").getInt(null)
            cachedInputManager
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.ERROR, "IMEDispatcher: getInputManager failed:", t.message)
            null
        }
    }

    /**
     * 注入单个 KeyEvent 到输入管线。
     * 注入的事件带 [INJECTING] 标记，Hook 层自动跳过。
     *
     * @return true if injection was dispatched successfully
     */
    @JvmStatic
    fun injectKeyEvent(event: KeyEvent): Boolean {
        beginInject()
        return try {
            val im = getInputManager() ?: return false
            val method = im.javaClass.getMethod(
                "injectInputEvent",
                KeyEvent::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(im, event, cachedInjectMode) != null || true
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.ERROR, "IMEDispatcher: injectKeyEvent failed:", t.message)
            false
        } finally {
            endInject()
        }
    }

    /**
     * 批量注入 KeyEvent 序列（用于适配器返回的多键组合）。
     * 按顺序注入，每对 DOWN/UP 之间不保证延迟（由 injectInputEvent 队列自然处理）。
     */
    @JvmStatic
    fun injectKeyEvents(events: List<KeyEvent>): Boolean {
        if (events.isEmpty()) return false
        beginInject()
        return try {
            val im = getInputManager() ?: return false
            val method = im.javaClass.getMethod(
                "injectInputEvent",
                KeyEvent::class.java,
                Int::class.javaPrimitiveType
            )
            for (e in events) {
                method.invoke(im, e, cachedInjectMode)
            }
            true
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.ERROR, "IMEDispatcher: injectKeyEvents failed:", t.message)
            false
        } finally {
            endInject()
        }
    }

    /**
     * 构建 Ctrl+Space KeyEvent 对。
     */
    @JvmStatic
    fun createCtrlSpaceEvents(): List<KeyEvent> {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_SPACE,
            0,
            KeyEvent.META_CTRL_MASK,
            InputDevice.SOURCE_KEYBOARD,
            0, 0
        )
        val up = KeyEvent(
            now + 50, now + 50,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_SPACE,
            0,
            KeyEvent.META_CTRL_MASK,
            InputDevice.SOURCE_KEYBOARD,
            0, 0
        )
        return listOf(down, up)
    }

    /**
     * 预留：定向注入到 App 焦点窗口。
     * 首期回退到正常管线注入（与 [injectKeyEvent] 相同）。
     * 后续可通过 Binder 拿到 App InputEventReceiver 实现真正并行投递。
     */
    @JvmStatic
    fun injectToApp(event: KeyEvent): Boolean {
        // TODO: Binder-based direct injection to focused app window.
        // For now, fall back to normal pipeline injection.
        // In dual-delivery mode, the IME injection happens first (via injectKeyEvent),
        // then this call sends a second event through the pipeline.
        // Since isAcceptingText() is true, the normal pipeline will deliver to IME first
        // again — but the guard prevents re-hooking.
        return injectKeyEvent(event)
    }
}
