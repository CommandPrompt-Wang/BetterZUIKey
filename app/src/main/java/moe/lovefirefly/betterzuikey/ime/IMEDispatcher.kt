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
    // IME 状态 (system_server ClassLoader required for com.android.server.*)
    // -----------------------------------------------------------------

    @Volatile
    @JvmField
    var systemClassLoader: ClassLoader? = null

    /** Called from MainHook with system_server ClassLoader before any IME state queries. */
    @JvmStatic
    fun initClassLoader(classLoader: ClassLoader) {
        systemClassLoader = classLoader
        cachedIms = null
    }

    @Volatile
    private var cachedIms: Any? = null

    private fun getInputMethodManagerService(): Any? {
        cachedIms?.let { return it }
        val cl = systemClassLoader ?: return null
        return try {
            val immInternal = Class.forName(
                "com.android.server.inputmethod.InputMethodManagerInternal", false, cl)
            val svc = immInternal.getMethod("get").invoke(null)
                ?: return null
            if (!svc.javaClass.name.endsWith("LocalServiceImpl")) {
                LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: IMMInternal.get() not LocalServiceImpl")
                return null
            }
            val ims = svc.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(svc)
            cachedIms = ims
            ims
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: getIMS failed:", t.message)
            null
        }
    }

    private fun getVisibilityComputer(): Any? {
        val ims = getInputMethodManagerService() ?: return null
        return try {
            val userId = ims.javaClass.getDeclaredField("mCurrentImeUserId")
                .apply { isAccessible = true }.getInt(ims)
            val userData = ims.javaClass.getMethod("getUserData", Int::class.javaPrimitiveType)
                .invoke(ims, userId) ?: return null
            userData.javaClass.getDeclaredField("mVisibilityStateComputer")
                .apply { isAccessible = true }.get(userData)
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: getVisibilityComputer failed:", t.message)
            null
        }
    }

    /**
     * IME 是否正在显示（键盘展开）。
     * 走 InputMethodManagerInternal → IMS.getUserData → ImeVisibilityStateComputer.isInputShown()。
     */
    @JvmStatic
    fun isAcceptingText(): Boolean {
        try {
            val vc = getVisibilityComputer()
            if (vc == null) {
                LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: isAcceptingText=false (vc=null)")
                return false
            }
            val shown = vc.javaClass.getMethod("isInputShown").invoke(vc) as Boolean
            LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: isAcceptingText=", shown.toString(), " (isInputShown)")
            return shown
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: isAcceptingText=false (err:", t.message, ")")
            return false
        }
    }

    /**
     * 获取当前激活的 IME 包名。
     * 供 AdapterManager 做适配器匹配。
     */
    @JvmStatic
    fun getCurrentIMEPackage(): String? {
        try {
            val at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            val sysCtx = at.javaClass.getMethod("getSystemContext").invoke(at) as Context
            val imm = sysCtx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            return imm.currentInputMethodInfo?.packageName
        } catch (t: Throwable) { return null }
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

    /**
     * 通过 Binder 直接向当前 App 的 InputConnection 提交文本。
     * 链路：IMS → UserData.mCurClient → mFallbackInputConnection → commitText()
     * 仅在 IME 已激活（isAcceptingText）时有效。
     *
     * @return true if the text was committed successfully
     */
    @JvmStatic
    fun commitTextToInputConnection(text: CharSequence): Boolean {
        try {
            val ims = getInputMethodManagerService() ?: return false
            val cl = systemClassLoader ?: return false

            // Get current IME user ID
            val userId = ims.javaClass.getDeclaredField("mCurrentImeUserId")
                .apply { isAccessible = true }.getInt(ims)

            // Get UserData
            val method = ims.javaClass.getMethod("getUserData", Int::class.javaPrimitiveType)
            val userData = method.invoke(ims, userId) ?: return false

            // Get mCurInputConnection from UserData (the active IRemoteInputConnection)
            // Note: NOT mCurClient.mFallbackInputConnection — that's stale;
            // UserData.mCurInputConnection is set by startInputUncheckedLocked.
            val inputConn = try {
                userData.javaClass.getDeclaredField("mCurInputConnection")
                    .apply { isAccessible = true }.get(userData)
            } catch (e: NoSuchFieldException) { null }
                ?: return false

            // Create InputConnectionCommandHeader(sessionId=0)
            val headerClass = Class.forName(
                "com.android.internal.inputmethod.InputConnectionCommandHeader",
                false, cl)
            val header = headerClass.getConstructor(Int::class.javaPrimitiveType).newInstance(0)

            // Call IRemoteInputConnection.commitText(header, text, 1)
            inputConn.javaClass.getMethod(
                "commitText", headerClass, CharSequence::class.java,
                Int::class.javaPrimitiveType
            ).invoke(inputConn, header, text, 1)

            LogHelper.log(VerboseLevel.INFO,
                "commitText: OK len=", text.length.toString())
            return true
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.ERROR,
                "commitTextToInputConnection failed:", t.message)
            return false
        }
    }
}
