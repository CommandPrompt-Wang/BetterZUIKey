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
 * - [Strategy.hook]: 返回 [HookConfig] 供 MainHook 在 IME 进程中安装 Xposed hook
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

    /** 已加载的 profile 列表（按 ime 索引） */
    private val profiles = mutableMapOf<String, IMEProfile>()

    private val gson = Gson()

    // -----------------------------------------------------------------
    // 初始化 & 加载
    // -----------------------------------------------------------------

    private const val SP_PROFILES_KEY = "ime_profiles"

    /** 从 SP 加载所有 profile 到内存。 */
    @JvmStatic
    fun loadFromSP(context: android.content.Context) {
        profiles.clear()
        val sp = context.getSharedPreferences(
            "betterzuikey_config", android.content.Context.MODE_PRIVATE)
        try {
            val json = sp.getString(SP_PROFILES_KEY, "[]") ?: "[]"
            if (json != "[]") loadFromJsonArray(json)
        } catch (_: Throwable) {}
        // Fix any null strategies (from old hook-strategy configs)
        for ((ime, p) in profiles) {
            if (p.strategy == null) {
                profiles[ime] = p.copy(strategy = Strategy.keyremap)
            }
        }
        LogHelper.log(VerboseLevel.INFO, "$TAG: loadFromSP — ${profiles.size} profile(s)")
        // Push full reload signal to system_server so it picks up existing profiles
        // (covers boot sync race: SP has data but no delta changes were made)
        if (profiles.isNotEmpty()) {
            appendChange(context, "reload", null)
        }
    }

    /** 首次启动时 seed 内置配置（仅当 SP 为空时）。 */
    @JvmStatic
    fun seedBuiltinsIfEmpty(context: android.content.Context) {
        loadFromSP(context)
        if (profiles.isEmpty()) {
            for (p in IMEProfile.BUILTIN_DEFAULTS) {
                val ime = p.ime?.trim() ?: continue
                profiles[ime] = p
            }
            saveToConfig(context)
            LogHelper.log(VerboseLevel.INFO, "$TAG: seeded ${profiles.size} builtin(s)")
        }
    }

    /** "还原内置配置" — upsert BUILTIN_DEFAULTS 到现有列表。 */
    @JvmStatic
    fun restoreBuiltins(context: android.content.Context) {
        loadFromSP(context)
        for (p in IMEProfile.BUILTIN_DEFAULTS) {
            val ime = p.ime?.trim() ?: continue
            profiles[ime] = p
        }
        saveToConfig(context)
        LogHelper.log(VerboseLevel.INFO, "$TAG: restoreBuiltins — ${profiles.size} profile(s)")
    }

    /**
     * App 进程：将内存 profiles 写入独立 SP key `ime_profiles`，
     * 并通过 delta 队列通知 system_server。与 Config 完全解耦。
     */
    @JvmStatic
    fun saveToConfig(context: android.content.Context) {
        try {
            val json = toJsonArray()
            context.getSharedPreferences("betterzuikey_config", android.content.Context.MODE_PRIVATE)
                .edit()?.putString(SP_PROFILES_KEY, json)?.commit()
            // Signal system_server via delta queue
            appendChange(context, "reload", null)
            LogHelper.log(VerboseLevel.INFO, "$TAG: saveToConfig — ${profiles.size} profile(s)")
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.WARNING, "$TAG: saveToConfig failed: ${t.message}")
        }
    }

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
                    profiles[ime.trim()] = p
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
                        profiles[ime] = p
                        LogHelper.log(VerboseLevel.INFO, "$TAG: applyChanges new: $ime")
                    }
                    "del" -> {
                        val ime = ch.content?.ime?.trim() ?: continue
                        profiles.remove(ime)
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
        profiles[ime] = profile
    }

    /** 重命名内存中一个 profile（不自动 saveToConfig） */
    @JvmStatic
    fun renameProfile(ime: String?, newName: String) {
        val key = ime?.trim() ?: return
        profiles[key]?.let { profiles[key] = it.copy(name = newName.ifBlank { null }) }
    }

    /** 从内存中移除一个 profile（不自动 saveToConfig） */
    @JvmStatic
    fun removeProfile(ime: String?) {
        if (ime == null) return
        profiles.remove(ime.trim())
    }

    // -----------------------------------------------------------------
    // 查找
    // -----------------------------------------------------------------

    @JvmStatic
    fun getProfileForIME(imePackage: String?): IMEProfile? {
        if (imePackage == null) return null
        return profiles[imePackage.trim()]
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
                "$TAG: framework strategy → switchToNextInputMethodSubtype() OK")
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

    // -----------------------------------------------------------------
    // 校验
    // -----------------------------------------------------------------

    @JvmStatic
    fun validateAndFix(rawJson: String): Pair<IMEProfile?, List<String>> {
        val problems = mutableListOf<String>()

        val profile = try {
            gson.fromJson(rawJson, IMEProfile::class.java)
        } catch (t: Throwable) {
            return null to listOf("err_json_parse", t.message ?: "")
        }
        if (profile == null) return null to listOf("err_json_null", "")

        if (profile.ime == null || profile.ime.isBlank()) problems += "err_missing_ime"

        val stratField = Regex("\"strategy\"\\s*:").containsMatchIn(rawJson)
        if (!stratField) problems += "err_missing_strategy"

        when (profile.strategy) {
            Strategy.keyremap -> {
                if (profile.remapTo.isNullOrBlank() && !Regex("\"remap-to\"\\s*:").containsMatchIn(rawJson))
                    problems += "err_keyremap_missing_target"
            }
            Strategy.framework -> { /* no extra required */ }
            null -> problems += "err_unknown_strategy"
        }

        val hasName = profile.name != null && Regex("\"name\"\\s*:").containsMatchIn(rawJson)
        val hasUuid = profile.uuid != null && Regex("\"uuid\"\\s*:").containsMatchIn(rawJson)

        val fixedName = if (hasName) profile.name else "${profile.ime ?: "unknown"} 的配置"
        val fixedUuid = if (hasUuid) profile.uuid else IMEProfile.generateUUID()

        val fixed = if (hasName && hasUuid) profile
        else profile.copy(name = fixedName, uuid = fixedUuid)

        return fixed to problems
    }

    @JvmStatic
    fun localizeError(code: String, detail: String, ctx: android.content.Context): String {
        if (code.startsWith("err_json_")) {
            val reason = ctx.getString(R.string.ime_err_parse)
            return "$reason\n${if (detail.isNotBlank()) detail else ctx.getString(R.string.ime_err_unknown)}"
        }
        return when (code) {
            "err_missing_ime" -> ctx.getString(R.string.ime_err_missing_ime)
            "err_missing_strategy" -> ctx.getString(R.string.ime_err_missing_strategy)
            "err_keyremap_missing_target" -> ctx.getString(R.string.ime_err_keyremap_missing_target)
            "err_unknown_strategy" -> ctx.getString(R.string.ime_err_unknown_strategy)
            else -> code
        }
    }

    /** 清除所有缓存的 profile */
    @JvmStatic
    fun clear() {
        profiles.clear()
    }
}
