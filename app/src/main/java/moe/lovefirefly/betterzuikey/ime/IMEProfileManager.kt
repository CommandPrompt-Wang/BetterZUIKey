package moe.lovefirefly.betterzuikey.ime

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import com.google.gson.Gson
import moe.lovefirefly.betterzuikey.R
import moe.lovefirefly.betterzuikey.Utils.LogHelper
import moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel
import java.io.File

/**
 * IME 适配器配置管理器 — 纯内存 + SharedPreferences 存储。
 *
 * 三种策略：
 * - [Strategy.framework]: 反射 `InputMethodManager.switchToNextInputMethodSubtype()`
 * - [Strategy.keyremap]: 注入 `remap-to` 指定的组合键到 IME
 *
 * ## 生命周期
 *
 * 1. [initDefaults] — 加载内置 BUILTIN_DEFAULTS + 用户配置（来自 Config.imeProfilesJson）
 * 2. [getProfileForIME] — 根据当前 IME 包名查找匹配的 profile
 * 3. [executeStrategy] — 执行匹配到的 profile 的切换策略
 *
 * ## system_server 同步
 *
 * Profiles 序列化到 [Config.imeProfilesJson]，随 Config 通过 ContentProvider IPC 同步。
 * 变更时调用 [saveToConfig] 写入 SharedPreferences → 下次按键 system_server 自动热加载。
 */
object IMEProfileManager {

    private const val TAG = "IMEProfileManager"

    /**
     * 已加载的 profile 列表，按 **[imeKey]（包名|策略）** 索引。
     *
     * 同一输入法要能同时存在两条：framework（框架接管）与 keyremap（重映射快捷键），
     * 界面把两者放在不同段里。所以 key 不能只用包名 —— 只用包名时第二条会覆盖第一条。
     */
    private val profiles = mutableMapOf<String, IMEProfile>()

    /** 内部索引 key：包名 + 策略。 */
    private fun imeKey(ime: String?, strategy: Strategy?): String =
        ime?.trim() + "|" + (strategy?.name ?: "null")

    /** 是否已从 SP 加载过一次 —— 避免在未加载时用空列表覆盖远端配置。 */
    @Volatile
    private var loadedOnce = false

    private val gson = Gson()

    // -----------------------------------------------------------------
    // 初始化 & 加载
    // -----------------------------------------------------------------

    /** 当前存储 key（v2）。 */
    private const val SP_PROFILES_KEY = "ime_profiles_v2"

    /** 旧存储 key。<b>只用于一次性迁移读取</b>，迁移完就删掉。 */
    private const val SP_PROFILES_KEY_V1 = "ime_profiles"

    private const val PREF_FILE = "betterzuikey_config"

    /** 从 SP 加载所有 profile 到内存；v2 不存在时先做一次性迁移。 */
    @JvmStatic
    fun loadFromSP(context: android.content.Context) {
        profiles.clear()
        val sp = context.getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
        try {
            val v2 = sp.getString(SP_PROFILES_KEY, null)
            if (v2 != null) {
                // 已经迁移过：只认 v2，老 key 即便又冒出来也忽略
                if (v2 != "[]") loadFromJsonArray(v2)
            } else {
                migrateFromV1(sp)
            }
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.DEBUG, "$TAG: loadFromSP failed:", t.message)
        }
        // 旧 hook 策略的条目（枚举已移除 ⇒ strategy 解析成 null）：**直接丢弃**。
        // 别再"修"成 keyremap —— 那会让一条死配置突然开始注入按键。
        val strays = profiles.values.filter { it.strategy == null }
        for (p in strays) {
            profiles.remove(imeKey(p.ime, null))
            LogHelper.log(VerboseLevel.INFO, "$TAG: dropped legacy profile (hook strategy removed): ",
                p.ime ?: "?")
        }
        LogHelper.log(VerboseLevel.INFO, "$TAG: loadFromSP — ${profiles.size} profile(s)")
        loadedOnce = true
        // 启动期的 reload 信号由 ConfigSyncProvider.onCreate 负责（它只在队列为空时塞一条）。
        //
        // 这里**不能**再无条件 append：loadFromSP 在一个进程里会被调用多次
        // （ModuleApp.onCreate / ConfigSyncProvider.onCreate / seedBuiltinsIfEmpty…），
        // 每调一次就多一条 "reload"，而 appendProfileChange 是"重写整个数组"实现的，
        // 队列于是变成 1+2+3+… 的平方级增长。实测踩过：5583 条 reload / 162KB，
        // 最终把 shared_prefs 的 XML 写坏（截断在数组中间，整个文件解析失败）。
    }

    /**
     * 从旧 key（`ime_profiles`）一次性迁移到 v2。
     *
     * <p>规则：**旧内置全部丢弃** —— 它们是本模块自己写进去的默认值，没有用户意图，
     * 而且新版内置集已经不同（多了 framework 条目、索引改成了包名|策略）。
     * 只保留用户自建的那些（uuid 不带 `bzuikey-builtin-` 前缀）。
     *
     * <p>迁移完立刻删掉旧 key：它既是幂等标记，也避免两份数据互相矛盾。
     */
    private fun migrateFromV1(sp: android.content.SharedPreferences) {
        val legacy = sp.getString(SP_PROFILES_KEY_V1, null)
        var kept = 0
        var dropped = 0
        if (!legacy.isNullOrBlank() && legacy != "[]") {
            try {
                val arr = gson.fromJson(legacy, Array<IMEProfile>::class.java) ?: emptyArray()
                for (p in arr) {
                    if (p.ime?.trim().isNullOrEmpty()) continue
                    if (IMEProfile.isBuiltin(p.uuid)) {
                        dropped++
                        continue
                    }
                    profiles[imeKey(p.ime, p.strategy)] = p
                    kept++
                }
            } catch (t: Throwable) {
                // 旧数据坏了也不能卡住升级：按空处理，后面会 seed 新内置
                LogHelper.log(VerboseLevel.WARNING,
                    "$TAG: migrateFromV1 parse failed, treating as empty: ${t.message}")
            }
        }
        LogHelper.log(VerboseLevel.INFO,
            "$TAG: migrated v1 -> v2 (kept user=$kept, dropped builtin=$dropped)")

        // 写 v2 并删除旧 key（即使这次没读到东西也要写，标记"已迁移"）
        sp.edit()
            .putString(SP_PROFILES_KEY, toJsonArray())
            .remove(SP_PROFILES_KEY_V1)
            .commit()
    }

    /**
     * 没有内置项时 seed 全部内置。
     *
     * <p>判据是"**是否存在内置项**"而不是"列表是否为空"：v1 迁移会把旧内置整体丢弃，
     * 若用户自建的条目还在，列表就非空 —— 用 isEmpty 判断会让新内置永远补不上。
     */
    @JvmStatic
    fun seedBuiltinsIfEmpty(context: android.content.Context) {
        loadFromSP(context)
        if (profiles.values.any { IMEProfile.isBuiltin(it.uuid) }) return
        for (p in IMEProfile.BUILTIN_DEFAULTS) {
            if (p.ime?.trim().isNullOrEmpty()) continue
            profiles[imeKey(p.ime, p.strategy)] = p
        }
        saveToConfig(context)
        LogHelper.log(VerboseLevel.INFO, "$TAG: seeded ${profiles.size} builtin(s)")
    }

    /** "还原内置配置" — upsert BUILTIN_DEFAULTS 到现有列表。 */
    @JvmStatic
    fun restoreBuiltins(context: android.content.Context) {
        loadFromSP(context)
        for (p in IMEProfile.BUILTIN_DEFAULTS) {
            if (p.ime?.trim().isNullOrEmpty()) continue
            val key = imeKey(p.ime, p.strategy)
            val old = profiles[key]
            // 保留用户原有的「启用/停用」选择：否则新增一条内置会把已勾选的同款条目关掉
            profiles[key] = if (old != null) p.copy(enabledRaw = old.enabledRaw) else p
        }
        saveToConfig(context)
        LogHelper.log(VerboseLevel.INFO, "$TAG: restoreBuiltins — ${profiles.size} profile(s)")
    }

    /**
     * App 进程：将内存 profiles 写入独立 SP key `ime_profiles_v2`，
     * 并通过 delta 队列通知 system_server。与 Config 完全解耦。
     */
    @JvmStatic
    fun saveToConfig(context: android.content.Context) {
        try {
            val json = toJsonArray()
            context.getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
                .edit()?.putString(SP_PROFILES_KEY, json)?.commit()
            // Signal system_server via delta queue
            appendChange(context, "reload", null)
            // 同步到框架远端配置，供输入法进程读取
            LogHelper.log(VerboseLevel.INFO, "$TAG: saveToConfig — ${profiles.size} profile(s)")
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.WARNING, "$TAG: saveToConfig failed: ${t.message}")
        }
    }

    // -----------------------------------------------------------------

    /**
     * 从 JSON 数组字符串加载 profiles（供 system_server 通过 Config IPC 获取）。
     * 与 [initDefaults] 不同，此方法不加载内置配置 — 内置配置已由 App 进程写入 Config。
     */
    @JvmStatic
    fun loadFromJsonArray(json: String) {
        if (json.isBlank() || json == "[]") {
            LogHelper.log(VerboseLevel.INFO, "$TAG: loadFromJsonArray — empty, nothing to load")
            return
        }
        try {
            val arr = gson.fromJson(json, Array<IMEProfile>::class.java) ?: return
            for (p in arr) {
                val ime = p.ime
                if (ime != null && ime.isNotBlank()) {
                    profiles[imeKey(ime, p.strategy)] = p
                    LogHelper.log(VerboseLevel.INFO, "$TAG: loaded profile ime=${ime.trim()} strategy=${p.strategy}")
                }
            }
            LogHelper.log(VerboseLevel.INFO, "$TAG: loadFromJsonArray — loaded ${arr.size} profile(s)")
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.WARNING, "$TAG: loadFromJsonArray parse failed: ${t.message}")
        }
    }

    /** 将内存中的 profiles 序列化为 JSON 数组字符串。 */
    @JvmStatic
    fun toJsonArray(): String {
        return gson.toJson(profiles.values.toList())
    }

    // ---- Delta-based SP sync (frontend computes deltas, backend applies) ----

    /** system_server: apply delta operations from the frontend's ime_changes queue. */
    @JvmStatic
    fun applyChanges(changesJson: String) {
        if (changesJson.isBlank() || changesJson == "[]") return
        try {
            val arr = gson.fromJson(changesJson, Array<ProfileChange>::class.java) ?: return
            for (ch in arr) {
                when (ch.op) {
                    "reload" -> {
                        // Full reload from SP needed — handled by caller re-pulling getProfiles
                        LogHelper.log(VerboseLevel.INFO, "$TAG: applyChanges — reload signal")
                    }
                    "new" -> {
                        val p = ch.content ?: continue
                        val ime = p.ime?.trim() ?: continue
                        profiles[imeKey(ime, p.strategy)] = p
                        LogHelper.log(VerboseLevel.INFO, "$TAG: applyChanges new: $ime|${p.strategy}")
                    }
                    "del" -> {
                        val ime = ch.content?.ime?.trim() ?: continue
                        profiles.remove(imeKey(ime, ch.content?.strategy))
                        LogHelper.log(VerboseLevel.INFO, "$TAG: applyChanges del: $ime")
                    }
                }
            }
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.WARNING, "$TAG: applyChanges failed: ${t.message}")
        }
    }

    /** App 进程: append a single delta operation to the ime_changes queue. */
    @JvmStatic
    fun appendChange(context: android.content.Context, op: String, profile: IMEProfile?) {
        try {
            val contentJson = if (profile != null) gson.toJson(profile) else "{}"
            val changeJson = """{"op":"$op","content":$contentJson}"""
            context.contentResolver.call(
                moe.lovefirefly.betterzuikey.ConfigSyncProvider.RELOAD_URI,
                "appendProfileChange", null,
                android.os.Bundle().apply { putString("change", changeJson) })
            LogHelper.log(VerboseLevel.INFO, "$TAG: appendChange $op for ${profile?.ime}")
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.WARNING, "$TAG: appendChange failed: ${t.message}")
        }
    }


    // -----------------------------------------------------------------
    // 增删
    // -----------------------------------------------------------------

    /** 添加或覆盖一个 profile 到内存（不自动 saveToConfig） */
    @JvmStatic
    fun putProfile(profile: IMEProfile) {
        val ime = profile.ime?.trim() ?: return
        profiles[imeKey(ime, profile.strategy)] = profile
    }

    /** 重命名内存中一个 profile（不自动 saveToConfig） */
    @JvmStatic
    fun renameProfile(ime: String?, newName: String) {
        val target = ime?.trim() ?: return
        for ((k, v) in profiles.entries.toList()) {
            if (v.ime?.trim() == target) {
                profiles[k] = v.copy(name = newName.ifBlank { null })
            }
        }
    }

    /** 从内存中移除一个 profile（不自动 saveToConfig） */
    @JvmStatic
    fun removeProfile(ime: String?) {
        val target = ime?.trim() ?: return
        profiles.entries.removeAll { it.value.ime?.trim() == target }
    }

    /** 按「包名 + 策略」精确移除（界面上两段各自删除时用）。 */
    @JvmStatic
    fun removeProfile(ime: String?, strategy: Strategy?) {
        val target = ime?.trim() ?: return
        profiles.remove(imeKey(target, strategy))
    }

    // -----------------------------------------------------------------
    // 查找
    // -----------------------------------------------------------------

    /**
     * 取当前输入法该用哪条配置。
     *
     * 同一输入法可能同时有 framework 与 keyremap 两条，**framework 优先**；
     * 各自策略内部优先取启用的那条（界面上的勾选框）。
     */
    @JvmStatic
    fun getProfileForIME(imePackage: String?): IMEProfile? {
        val target = imePackage?.trim() ?: return null
        val mine = profiles.values.filter { it.ime?.trim() == target }
        if (mine.isEmpty()) return null
        return mine.firstOrNull { it.strategy == Strategy.framework && it.enabled }
            ?: mine.firstOrNull { it.strategy == Strategy.framework }
            ?: mine.firstOrNull { it.enabled }
            ?: mine.first()
    }

    /** 取某输入法某策略下的那一条（界面上按段精确操作）。 */
    @JvmStatic
    fun getProfile(imePackage: String?, strategy: Strategy?): IMEProfile? {
        val target = imePackage?.trim() ?: return null
        return profiles[imeKey(target, strategy)]
    }

    @JvmStatic
    fun getProfileCount(): Int = profiles.size

    /** 获取所有已加载的 profile */
    @JvmStatic
    fun getAllProfiles(): List<IMEProfile> = profiles.values.toList()

    // -----------------------------------------------------------------
    // 策略执行
    // -----------------------------------------------------------------

    @JvmStatic
    fun executeForIME(imePackage: String?): Boolean {
        val profile = getProfileForIME(imePackage) ?: return false
        return executeStrategy(profile)
    }

    @JvmStatic
    fun executeStrategy(profile: IMEProfile): Boolean {
        return when (profile.strategy) {
            Strategy.framework -> executeFrameworkStrategy()
            Strategy.keyremap -> {
                val remapTo = profile.remapTo ?: return false
                executeKeyRemapStrategy(remapTo)
            }
            null -> false
        }
    }

    // -----------------------------------------------------------------
    // framework 策略
    // -----------------------------------------------------------------

    @JvmStatic
    fun executeFrameworkStrategy(): Boolean {
        // 正解：system_server 内直接操作 IMMS（公开 API 拿不到 IME token，
        // 且明确拒绝系统进程调用）。
        if (IMEDispatcher.switchCurrentImeSubtype()) {
            LogHelper.log(VerboseLevel.INFO,
                "$TAG: framework strategy → IMMS subtype switch OK")
            return true
        }
        LogHelper.log(VerboseLevel.WARNING,
            "$TAG: framework strategy — IMMS path unavailable, falling back to IMM reflection")
        return executeFrameworkStrategyLegacy()
    }

    /** 旧路径：通过 InputMethodManager + 反射读 mToken。多数设备上 token 为空，等于空转。 */
    private fun executeFrameworkStrategyLegacy(): Boolean {
        return try {
            val at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            val sysCtx = at.javaClass.getMethod("getSystemContext").invoke(at)
                    as android.content.Context
            val imm = sysCtx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager

            val token = try {
                val field = imm.javaClass.getDeclaredField("mToken")
                field.isAccessible = true
                field.get(imm)
            } catch (e: Throwable) { null }

            // On newer Android (15+), the method is switchToNextInputMethod (no "Subtype")
            val method = try {
                imm.javaClass.getMethod("switchToNextInputMethodSubtype",
                    android.os.IBinder::class.java, Boolean::class.javaPrimitiveType)
            } catch (e: NoSuchMethodException) {
                imm.javaClass.getMethod("switchToNextInputMethod",
                    android.os.IBinder::class.java, Boolean::class.javaPrimitiveType)
            }
            method.invoke(imm, token as? android.os.IBinder, true)

            LogHelper.log(VerboseLevel.INFO,
                "$TAG: framework strategy → switchToNextInputMethodSubtype() OK (legacy path)")
            true
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.ERROR,
                "$TAG: framework strategy failed: ${t.message}")
            false
        }
    }

    // -----------------------------------------------------------------
    // keyremap 策略
    // -----------------------------------------------------------------

    @JvmStatic
    fun executeKeyRemapStrategy(remapTo: String): Boolean {
        val events = parseRemapTo(remapTo) ?: return false
        LogHelper.log(VerboseLevel.INFO,
            "$TAG: keyremap strategy '$remapTo' → ${events.size} event(s)")
        return IMEDispatcher.injectKeyEvents(events)
    }

    private fun parseRemapTo(remapTo: String): List<KeyEvent>? {
        val parts = remapTo.split("\\+".toRegex()).map { it.trim().lowercase() }
        val modifiers = mutableListOf<Int>()
        var keyCode = -1

        for (part in parts) {
            when (part) {
                "ctrl" -> modifiers.add(KeyEvent.KEYCODE_CTRL_LEFT)
                "alt" -> modifiers.add(KeyEvent.KEYCODE_ALT_LEFT)
                "shift" -> modifiers.add(KeyEvent.KEYCODE_SHIFT_LEFT)
                "meta", "win" -> modifiers.add(KeyEvent.KEYCODE_META_LEFT)
                "space" -> keyCode = KeyEvent.KEYCODE_SPACE
                "tab" -> keyCode = KeyEvent.KEYCODE_TAB
                "enter" -> keyCode = KeyEvent.KEYCODE_ENTER
                "esc", "escape" -> keyCode = KeyEvent.KEYCODE_ESCAPE
                "backspace" -> keyCode = KeyEvent.KEYCODE_DEL
                else -> {
                    if (part.length == 1 && part[0] in 'a'..'z') {
                        keyCode = KeyEvent.KEYCODE_A + (part[0] - 'a')
                    } else {
                        LogHelper.log(VerboseLevel.WARNING,
                            "$TAG: unknown key in remap-to: '$part'")
                        return null
                    }
                }
            }
        }

        val now = SystemClock.uptimeMillis()
        val events = mutableListOf<KeyEvent>()

        if (keyCode >= 0) {
            // Standard: modifiers + key
            var meta = 0
            for (m in modifiers) {
                meta = meta or modifierKeyToMeta(m)
            }
            events.add(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode,
                0, meta, InputDevice.SOURCE_KEYBOARD, 0, 0))
            events.add(KeyEvent(now + 50, now + 50, KeyEvent.ACTION_UP, keyCode,
                0, meta, InputDevice.SOURCE_KEYBOARD, 0, 0))
        } else if (modifiers.isNotEmpty()) {
            // Modifier-only: inject sequential DOWN then reverse UP
            var t = now
            for (m in modifiers) {
                events.add(KeyEvent(t, t, KeyEvent.ACTION_DOWN, m,
                    0, 0, InputDevice.SOURCE_KEYBOARD, 0, 0))
                t += 20
            }
            for (m in modifiers.reversed()) {
                events.add(KeyEvent(t, t, KeyEvent.ACTION_UP, m,
                    0, 0, InputDevice.SOURCE_KEYBOARD, 0, 0))
                t += 20
            }
        } else {
            LogHelper.log(VerboseLevel.WARNING,
                "$TAG: no key or modifier in remap-to: '$remapTo'")
            return null
        }

        return events
    }

    private fun modifierKeyToMeta(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_MASK
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_MASK
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_MASK
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_MASK
        else -> 0
    }

    /** 清除所有缓存的 profile */
    @JvmStatic
    fun clear() {
        profiles.clear()
    }
}
