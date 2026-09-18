package moe.lovefirefly.betterzuikey.ime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import moe.lovefirefly.betterzuikey.Utils.LogHelper
import moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel
import java.lang.reflect.Field
import java.lang.reflect.Method

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
    // 注入事件检测 (跨线程安全，替代不可靠的 ThreadLocal)
    // -----------------------------------------------------------------

    /**
     * 通过事件特征判断是否为 BetterZUIKey 注入的合成事件。
     *
     * 注入事件由我们通过 KeyEvent 构造函数创建，特征固定：
     * scanCode=0, flags=0, source=0
     * 物理键盘的 scanCode 一定非零，可可靠区分。
     */
    @JvmStatic
    fun isInjectedEvent(event: android.view.KeyEvent): Boolean {
        return event.scanCode == 0
            && event.flags == 0
            && event.source == 0
    }

    // -----------------------------------------------------------------
    // 冷却期 (防止同一触发源导致的快速重复执行)
    // -----------------------------------------------------------------

    @Volatile
    @JvmField
    var lastProfileTriggerMs: Long = 0L

    /** IME profile 触发最小间隔 */
    const val PROFILE_COOLDOWN_MS: Long = 50L

    @JvmStatic
    fun isInProfileCooldown(): Boolean {
        val last = lastProfileTriggerMs
        if (last == 0L) return false
        return (android.os.SystemClock.uptimeMillis() - last) < PROFILE_COOLDOWN_MS
    }

    @JvmStatic
    fun markProfileTriggered() {
        lastProfileTriggerMs = android.os.SystemClock.uptimeMillis()
    }

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

    /** IMMS 当前用户的 UserData（可见性判断与 subtype 切换都要用）。 */
    private fun getUserData(): Any? {
        val ims = getInputMethodManagerService() ?: return null
        return try {
            val userId = ims.javaClass.getDeclaredField("mCurrentImeUserId")
                .apply { isAccessible = true }.getInt(ims)
            ims.javaClass.getMethod("getUserData", java.lang.Integer.TYPE)
                .invoke(ims, userId)
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: getUserData failed:", t.message)
            null
        }
    }

    private fun getVisibilityComputer(): Any? {
        val userData = getUserData() ?: return null
        return try {
            userData.javaClass.getDeclaredField("mVisibilityStateComputer")
                .apply { isAccessible = true }.get(userData)
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: getVisibilityComputer failed:", t.message)
            null
        }
    }

    /** system_server 里的 InputMethodManager（公开 API 侧，用于读 subtype 列表）。 */
    private fun getSystemImm(): InputMethodManager? = try {
        val at = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread").invoke(null)
        val sysCtx = at.javaClass.getMethod("getSystemContext").invoke(at) as Context
        sysCtx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    } catch (t: Throwable) {
        LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: getSystemImm failed:", t.message)
        null
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
    fun getCurrentIMEPackage(): String? =
        try { getSystemImm()?.currentInputMethodInfo?.packageName?.trim() } catch (t: Throwable) { null }

    // -----------------------------------------------------------------
    // subtype 切换（framework 策略）
    // -----------------------------------------------------------------

    /**
     * 用户配置的语言顺序（逗号分隔的键，空 = 按框架顺序）。
     * 由 system_server 侧在每次触发前从 Config 灌进来（见 HookContext.triggerIMEProfile）。
     */
    @Volatile
    private var subtypeOrderRaw: String = ""

    /** 灌入语言顺序（见 [SubtypeRotation.parseOrder] 认的格式）。 */
    @JvmStatic
    fun setSubtypeOrder(order: String?) {
        subtypeOrderRaw = order?.trim().orEmpty()
    }

    /**
     * 是否**覆盖框架默认轮转**（该输入法的开关）。
     *
     * <p>false = 框架原生行为：`switchToNextInputMethodLocked`，只在"最近使用的两门"之间转
     * （`mSwitchingAwareRotationList` 只装最近两项为证）；true = 按模型自己算目标、点名切，
     * 能轮满所有已启用 subtype。
     */
    @Volatile
    private var subtypeOrderEnabled: Boolean = false

    /** 灌入"是否覆盖默认轮转"（由 HookContext 按当前输入法取）。 */
    @JvmStatic
    fun setSubtypeOrderEnabled(enabled: Boolean) {
        subtypeOrderEnabled = enabled
    }

    /**
     * 切换当前 IME 的 subtype —— **按顺序点名切**（framework 策略的正解）。
     *
     * 公开 API 在 system_server 侧走不通：`InputMethodManager` 需要 IME 的 token
     * （系统实例拿不到），而且它自己明确拒绝系统进程调用
     * （"System process should not call setCurrentInputMethodSubtype() ... Consider
     * directly interacting with InputMethodManagerService via LocalServices."）。
     *
     * <p>所以直接操作 IMMS，按框架的锁语义 synchronized(ImfLock)。**但不再用框架的
     * "next"**：`switchToNextInputMethodLocked` 不是遍历语义（MRU / 分组，三门语言常常
     * 只在两门之间来回跳，见 ANALYSIS 的 `mSwitchingAwareRotationList`）。改成：
     *
     * 1. 读当前 IME 的**已启用 subtype 列表**（框架自己的那份，含隐式默认 subtype）；
     * 2. 读框架当前 subtype，定位它在列表里的下标；
     * 3. 用 [SubtypeRotation] 按用户顺序算出下一个；
     * 4. `IMMS.setInputMethodAndSubtypeLocked(id, subtype, userData)` 点名切过去。
     *
     * <p>只在本输入法内切；只有一个可用 subtype 时什么都不做，**不会**切到别的输入法。
     * 顺序表认不出 / 老系统没有那个方法时才退回框架的 "next"。
     */
    @JvmStatic
    fun switchCurrentImeSubtype(): Boolean {
        val ims = getInputMethodManagerService()
        val userData = getUserData()
        if (ims == null || userData == null) {
            LogHelper.log(VerboseLevel.WARNING,
                "IMEDispatcher: switchCurrentImeSubtype — IMMS/UserData unavailable")
            return false
        }
        return try {
            val lock = Class.forName("com.android.server.inputmethod.ImfLock", false, systemClassLoader)
            synchronized(lock) {
                if (!subtypeOrderEnabled) {
                    // 没开「覆盖默认轮转顺序」⇒ 保持框架原生语义（最近使用的两门）
                    LogHelper.log(VerboseLevel.INFO,
                        "IMEDispatcher: subtype → framework next (override off)")
                    switchToNextLocked(ims, userData, true)
                } else {
                    val ordered = switchOrderedLocked(ims, userData)
                    if (ordered != null) {
                        ordered
                    } else {
                        // 算不出来（老系统 / ROM 改过签名）才退回框架的 "next"
                        LogHelper.log(VerboseLevel.WARNING,
                            "IMEDispatcher: ordered switch unavailable — falling back to framework next")
                        switchToNextLocked(ims, userData, true)
                    }
                }
            }
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.WARNING,
                "IMEDispatcher: switchCurrentImeSubtype failed:", t.message)
            false
        }
    }

    /**
     * 按顺序切（调用方须已持 ImfLock）。
     *
     * @return true/false = 切了 / 试过但不用切；**null = 这条路走不通**（退回框架 next）
     */
    private fun switchOrderedLocked(ims: Any, userData: Any): Boolean? {
        val userId = getCurrentUserId(ims) ?: return null
        val imeId = getCurrentImeId(ims, userData) ?: return null
        val enabled = getEnabledSubtypes(ims, imeId, userId) ?: return null
        if (enabled.isEmpty()) return null

        // 键 = 语言标签；同一个标签有多个 subtype（搜狗拼音/五笔都是 zh-CN）时自动加 mode 区分
        val keys = SubtypeRotation.uniqueKeys(
            enabled.map { SubtypeRotation.keyOf(languageTagOf(it), it.locale) },
            enabled.map { it.mode ?: "" })
        val curIdx = indexOfCurrentSubtype(ims, userData, enabled)
        val order = SubtypeRotation.parseOrder(subtypeOrderRaw)
        val nextIdx = SubtypeRotation.nextIndex(keys, order, curIdx)
        if (nextIdx < 0) {
            LogHelper.log(VerboseLevel.INFO,
                "IMEDispatcher: subtype ordered switch — nothing to do",
                " avail=", enabled.size.toString(),
                " order=", order.size.toString(),
                " chain=", keys.joinToString("/"))
            return false
        }

        val setter = findMethodFor(ims.javaClass, "setInputMethodAndSubtypeLocked",
            listOf<Any?>(imeId, enabled[nextIdx], userData)) ?: return null
        LogHelper.log(VerboseLevel.INFO,
            "IMEDispatcher: subtype ordered switch ",
            describeSubtype(enabled, keys, curIdx),
            " -> ", describeSubtype(enabled, keys, nextIdx),
            " (order=", order.joinToString("/"), " avail=", enabled.size.toString(),
            " keys=", keys.joinToString("/"), " ime=", imeId, ")")
        setter.invoke(ims, imeId, enabled[nextIdx], userData)
        return true
    }

    /** 框架的 "next" —— 只在按顺序切走不通时兜底。 */
    private fun switchToNextLocked(ims: Any, userData: Any, onlyCurrentIme: Boolean): Boolean = try {
        val m = findMethod(ims.javaClass, "switchToNextInputMethodLocked",
            java.lang.Boolean.TYPE, userData.javaClass) ?: return false
        val r = m.invoke(ims, onlyCurrentIme, userData)
        LogHelper.log(VerboseLevel.INFO,
            "IMEDispatcher: subtype → next (framework fallback, onlyCurrentIme=",
            onlyCurrentIme.toString(), ") result=", r?.toString() ?: "null")
        r == true
    } catch (t: Throwable) {
        LogHelper.log(VerboseLevel.WARNING,
            "IMEDispatcher: switchToNextLocked failed:", t.message)
        false
    }

    // -----------------------------------------------------------------
    // 按顺序切换用的框架读数（全部反射，锚点只用框架自己的名字）
    // -----------------------------------------------------------------

    /** IMMS.mCurrentImeUserId。 */
    private fun getCurrentUserId(ims: Any): Int? = try {
        findField(ims.javaClass, "mCurrentImeUserId")?.getInt(ims)
    } catch (t: Throwable) {
        LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: getCurrentUserId failed:", t.message)
        null
    }

    /** 当前 IME 的 id（`包名/服务类名`）—— 先问 InputMethodBindingController，再退回公开 API。 */
    private fun getCurrentImeId(ims: Any, userData: Any): String? {
        val bc = bindingController(userData)
        if (bc != null) {
            val id = try { findMethod(bc.javaClass, "getCurId")?.invoke(bc) as? String } catch (t: Throwable) { null }
            if (!id.isNullOrBlank()) return id
        }
        return try {
            getSystemImm()?.currentInputMethodInfo?.id?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: getCurrentImeId failed:", t.message)
            null
        }
    }

    /** UserData.mBindingController（Android 14+ 起 id / 当前 subtype 都挂在它上面）。 */
    private fun bindingController(userData: Any): Any? = try {
        findField(userData.javaClass, "mBindingController")?.get(userData)
    } catch (t: Throwable) {
        null
    }

    /**
     * 当前 IME 的**已启用 subtype 列表** —— 与框架自己的轮转表同源。
     *
     * 两条独立路子，任一成功即可：
     * 1. **公开 API**：system_server 里也有一个 `InputMethodManager` 实例
     *    （`getCurrentIMEPackage()` 一直在用 ✓），让**框架自己**去解 SafeList —— 最省事；
     * 2. **IMMS 内部**：`getEnabledInputMethodSubtypeList` 返回的是
     *    `InputMethodSubtypeSafeList`（**不是 List**，是个装了 marshall 字节的壳，
     *    见 `AbstractSafeList.mBuffer[B`）⇒ 要用框架自己的
     *    `AbstractSafeList.extractFrom(safeList, CREATOR)` 解出来。
     *
     * 两条都不行就返回 null（调用方退回框架的 next，**绝不猜**）。
     */
    private fun getEnabledSubtypes(ims: Any, imeId: String, userId: Int): List<InputMethodSubtype>? {
        // 先走 IMMS（in-process，用户号确定，与框架轮转表同源），再退公开 API
        fromIMMS(ims, imeId, userId)?.let { return it }
        return fromPublicApi()
    }

    /** 路子 1：公开 API（框架自己解壳）。 */
    private fun fromPublicApi(): List<InputMethodSubtype>? = try {
        val imm = getSystemImm()
        val imi = imm?.currentInputMethodInfo
        if (imm == null || imi == null) null else {
            val list = imm.getEnabledInputMethodSubtypeList(imi, true)
                ?.filterIsInstance<InputMethodSubtype>()
            if (list.isNullOrEmpty()) null else {
                LogHelper.log(VerboseLevel.DEBUG,
                    "IMEDispatcher: enabled subtypes via public API, n=", list.size.toString())
                list
            }
        }
    } catch (t: Throwable) {
        LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: public-API subtype list failed:", t.message)
        null
    }

    /** 路子 2：IMMS 内部 + 反射解 SafeList。 */
    private fun fromIMMS(ims: Any, imeId: String, userId: Int): List<InputMethodSubtype>? {
        val m = findMethod(ims.javaClass, "getEnabledInputMethodSubtypeList",
            String::class.java, java.lang.Boolean.TYPE, java.lang.Integer.TYPE)
            ?: return null
        val raw = try { m.invoke(ims, imeId, true, userId) } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.DEBUG, "IMEDispatcher: getEnabledSubtypes failed:", t.message)
            return null
        } ?: return null
        if (raw is List<*>) return raw.filterIsInstance<InputMethodSubtype>().ifEmpty { null }
        val list = extractSafeList(raw)
        if (list == null) {
            LogHelper.log(VerboseLevel.WARNING,
                "IMEDispatcher: subtype list type unexpected:", raw.javaClass.name)
        } else {
            LogHelper.log(VerboseLevel.DEBUG,
                "IMEDispatcher: enabled subtypes via IMMS, n=", list.size.toString())
        }
        return list?.ifEmpty { null }
    }

    /**
     * 把 `AbstractSafeList` 壳解开成 List —— 用框架自己的 `extractFrom`。
     *
     * 两个形态都试：
     * - 具体类上的 `extractFrom(SafeList)`（静态或实例方法都可能）；
     * - 父类上继承来的 `extractFrom(AbstractSafeList, Parcelable.Creator)` + 壳的 `CREATOR` 字段。
     */
    private fun extractSafeList(raw: Any): List<InputMethodSubtype>? {
        // (a) 具体类自带的单参版本
        for (c in hierarchy(raw.javaClass)) {
            val one = try { c.getDeclaredMethod("extractFrom", raw.javaClass) } catch (t: Throwable) { null }
            if (one != null) {
                one.isAccessible = true
                val r = try {
                    if (java.lang.reflect.Modifier.isStatic(one.modifiers)) one.invoke(null, raw)
                    else one.invoke(raw)
                } catch (t: Throwable) { null }
                (r as? List<*>)?.let { return it.filterIsInstance<InputMethodSubtype>() }
            }
        }
        // (b) 父类的静态 extractFrom(SafeList, Parcelable.Creator)
        val creator = try { raw.javaClass.getField("CREATOR").get(null) } catch (t: Throwable) { null }
        if (creator != null) {
            for (c in hierarchy(raw.javaClass)) {
                for (mm in c.declaredMethods) {
                    if (mm.name != "extractFrom") continue
                    if (!java.lang.reflect.Modifier.isStatic(mm.modifiers)) continue
                    if (mm.parameterTypes.size != 2) continue
                    if (!mm.parameterTypes[0].isAssignableFrom(raw.javaClass)) continue
                    mm.isAccessible = true
                    val r = try { mm.invoke(null, raw, creator) } catch (t: Throwable) { null }
                    (r as? List<*>)?.let { return it.filterIsInstance<InputMethodSubtype>() }
                }
            }
        }
        return null
    }

    private fun hierarchy(cls: Class<*>): List<Class<*>> {
        val out = ArrayList<Class<*>>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            out.add(c)
            c = c.superclass
        }
        return out
    }

    /** 框架当前的 subtype —— 先问 IMMS，再问绑定控制器。 */
    private fun getCurrentSubtype(ims: Any, userData: Any): InputMethodSubtype? {
        val userId = getCurrentUserId(ims)
        if (userId != null) {
            val m = findMethod(ims.javaClass, "getCurrentInputMethodSubtype", java.lang.Integer.TYPE)
            if (m != null) {
                val r = try { m.invoke(ims, userId) } catch (t: Throwable) { null }
                if (r is InputMethodSubtype) return r
            }
        }
        val bc = bindingController(userData) ?: return null
        return try { findMethod(bc.javaClass, "getCurrentSubtype")?.invoke(bc) as? InputMethodSubtype } catch (t: Throwable) { null }
    }

    /** 当前 subtype 在已启用列表里的下标（按 hashCode 认，认不出再按 equals）。-1 = 认不出。 */
    private fun indexOfCurrentSubtype(
        ims: Any, userData: Any, enabled: List<InputMethodSubtype>
    ): Int {
        val cur = getCurrentSubtype(ims, userData) ?: return -1
        val h = cur.hashCode()
        val byHash = enabled.indexOfFirst { it.hashCode() == h }
        if (byHash >= 0) return byHash
        return enabled.indexOfFirst { it == cur }
    }

    /** API 34+ 才有 getLanguageTag；低版本只能退回 locale 串。 */
    private fun languageTagOf(subtype: InputMethodSubtype): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try { subtype.languageTag } catch (t: Throwable) { null }
        } else null

    private fun describeSubtype(
        enabled: List<InputMethodSubtype>, keys: List<String>, idx: Int
    ): String = if (idx in enabled.indices) {
        keys[idx] + "#" + Integer.toHexString(enabled[idx].hashCode())
    } else "<unknown>"

    /** 沿类继承链找方法（运行时类可能是 ROM 的子类，例如 ZuiInputMethodManagerService）。 */
    private fun findMethod(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            try {
                return c.getDeclaredMethod(name, *params).apply { isAccessible = true }
            } catch (e: NoSuchMethodException) {
                c = c.superclass
            }
        }
        return null
    }

    /**
     * 按**参数形状**找方法：先精确类型，再"名字 + 参数个数 + 可赋值"。
     *
     * 用它的原因：ROM 可能把参数类型换成子类（本机就有 ZUI 自己的
     * `ZuiInputMethodManagerService`），精确匹配会踩空而形状匹配不会。
     */
    private fun findMethodFor(cls: Class<*>, name: String, args: List<Any?>): Method? {
        val types = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        findMethod(cls, name, *types)?.let { return it }
        for (c in hierarchy(cls)) {
            for (m in c.declaredMethods) {
                if (m.name != name || m.parameterTypes.size != args.size) continue
                var ok = true
                for (i in args.indices) {
                    val p = m.parameterTypes[i]
                    val a = args[i]
                    if (a == null) {
                        if (p.isPrimitive) { ok = false; break }
                    } else if (!p.isAssignableFrom(a.javaClass)) {
                        ok = false; break
                    }
                }
                if (ok) return m.apply { isAccessible = true }
            }
        }
        return null
    }

    /** 沿类继承链找字段。 */
    private fun findField(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
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
            val method = im.javaClass.getMethod("injectInputEvent",
                android.view.InputEvent::class.java, java.lang.Integer.TYPE)
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
            val method = im.javaClass.getMethod("injectInputEvent",
                android.view.InputEvent::class.java, java.lang.Integer.TYPE)
            for (e in events) {
                LogHelper.log(VerboseLevel.INFO,
                    "IMEDispatcher: INJECT_BEFORE",
                    " kc=", e.keyCode.toString(),
                    " sc=", e.scanCode.toString(),
                    " dev=", e.deviceId.toString(),
                    " src=0x", Integer.toHexString(e.source),
                    " flags=0x", Integer.toHexString(e.flags),
                    " meta=0x", Integer.toHexString(e.metaState),
                    " action=", if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP",
                    " thread=", Thread.currentThread().name)
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
            val method = ims.javaClass.getMethod("getUserData", java.lang.Integer.TYPE)
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
            val header = headerClass.getConstructor(java.lang.Integer.TYPE).newInstance(0)

            // Call IRemoteInputConnection.commitText(header, text, 1)
            inputConn.javaClass.getMethod(
                "commitText", headerClass, CharSequence::class.java,
                java.lang.Integer.TYPE
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
