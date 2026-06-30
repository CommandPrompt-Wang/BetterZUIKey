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
 * IME 适配器配置管理器 — 加载 `adapters/` 下 JSON profile，按 IME 包名匹配并执行策略。
 *
 * 替代旧的 DexClassLoader 方案。三种策略：
 * - [Strategy.framework]: 反射 `InputMethodManager.switchToNextInputMethodSubtype()`
 * - [Strategy.hook]: 返回 [HookConfig] 供 MainHook 在 IME 进程中安装 Xposed hook
 * - [Strategy.keyremap]: 注入 `remap-to` 指定的组合键到 IME
 *
 * ## 生命周期
 *
 * 1. [init] — 设置 profile 目录并加载所有 JSON
 * 2. [getProfileForIME] — 根据当前 IME 包名查找匹配的 profile
 * 3. [executeStrategy] — 执行匹配到的 profile 的切换策略
 */
object IMEProfileManager {

    private const val TAG = "IMEProfileManager"

    /** 已加载的 profile 列表（按 ime 索引） */
    private val profiles = mutableMapOf<String, IMEProfile>()

    /** profile 目录 */
    private var adaptersDir: File? = null

    private val gson = Gson()

    /** 适配器目录 */
    @JvmStatic
    fun getAdaptersDir(): File? = adaptersDir

    // -----------------------------------------------------------------
    // 初始化 & 加载
    // -----------------------------------------------------------------

    @JvmStatic
    fun init(dir: File?) {
        adaptersDir = dir
        if (dir != null && !dir.exists()) dir.mkdirs()
        reload()
        LogHelper.log(VerboseLevel.INFO, "$TAG: init dir=${dir?.absolutePath}, loaded ${profiles.size} profile(s)")
    }

    @JvmStatic
    fun reload() {
        profiles.clear()
        val dir = adaptersDir ?: return
        if (!dir.isDirectory) return
        val jsonFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        for (file in jsonFiles) {
            try {
                val raw = file.readText()
                val profile = gson.fromJson(raw, IMEProfile::class.java)
                val ime = profile?.ime
                if (profile != null && ime != null && ime.isNotBlank()) {
                    profiles[ime] = profile
                }
            } catch (t: Throwable) {
                LogHelper.log(VerboseLevel.WARNING, "$TAG: parse fail ${file.name}: ${t.message}")
            }
        }
    }

    // -----------------------------------------------------------------
    // 查找
    // -----------------------------------------------------------------

    /**
     * 根据 IME 包名查找匹配的 profile。
     *
     * @param imePackage 当前 IME 包名（如 "com.google.android.inputmethod.latin"）
     * @return 匹配的 profile，未找到返回 null
     */
    @JvmStatic
    fun getProfileForIME(imePackage: String?): IMEProfile? {
        if (imePackage == null) return null
        return profiles[imePackage]
    }

    /** 获取所有需要 hook 策略的 profile（供 MainHook 在 onPackageReady 中使用） */
    @JvmStatic
    fun getHookProfiles(): List<IMEProfile> {
        return profiles.values.filter { it.strategy == Strategy.hook }
    }

    /** 获取已加载的 profile 数量 */
    @JvmStatic
    fun getProfileCount(): Int = profiles.size

    // -----------------------------------------------------------------
    // 策略执行
    // -----------------------------------------------------------------

    /**
     * 为指定 IME 执行切换策略。
     *
     * @param imePackage 当前 IME 包名
     * @return true 如果成功执行了策略
     */
    @JvmStatic
    fun executeForIME(imePackage: String?): Boolean {
        val profile = getProfileForIME(imePackage) ?: return false
        return executeStrategy(profile)
    }

    /**
     * 执行指定 profile 的切换策略。
     * hook 策略在此处不执行（需要 Xposed 上下文），由 MainHook 处理。
     */
    @JvmStatic
    fun executeStrategy(profile: IMEProfile): Boolean {
        return when (profile.strategy) {
            Strategy.framework -> executeFrameworkStrategy()
            Strategy.keyremap -> {
                val remapTo = profile.remapTo ?: return false
                executeKeyRemapStrategy(remapTo)
            }
            Strategy.hook -> {
                // hook 策略由 MainHook.onPackageReady 安装，此处不执行
                LogHelper.log(VerboseLevel.DEBUG,
                    "$TAG: hook strategy for '${profile.ime}' — handled by MainHook")
                false
            }
            null -> false
        }
    }

    // -----------------------------------------------------------------
    // framework 策略
    // -----------------------------------------------------------------

    /**
     * 反射调用 InputMethodManager.switchToNextInputMethodSubtype()。
     *
     * 这是 GBoard 等声明了 subtype 的输入法的标准切换方式。
     * 对应隐藏 API `InputMethodManager.switchToNextInputMethodSubtype()`。
     */
    @JvmStatic
    fun executeFrameworkStrategy(): Boolean {
        return try {
            val at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            val sysCtx = at.javaClass.getMethod("getSystemContext").invoke(at)
                    as android.content.Context
            val imm = sysCtx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager

            // switchToNextInputMethodSubtype() 是 @hide 方法，通过反射调用
            val token = try {
                // 尝试获取 IME token（隐藏 API）
                val field = imm.javaClass.getDeclaredField("mToken")
                field.isAccessible = true
                field.get(imm)
            } catch (e: Throwable) {
                null
            }

            val method = imm.javaClass.getMethod(
                "switchToNextInputMethodSubtype",
                android.os.IBinder::class.java,
                Boolean::class.javaPrimitiveType
            )
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

    /**
     * 解析 `remap-to` 字符串为组合键并注入。
     *
     * 支持的格式（大小写不敏感）：
     * - 单键: "Space", "Shift"
     * - 双修饰: "Ctrl+Space", "Alt+Shift", "Ctrl+Shift"
     * - 三修饰: "Ctrl+Alt+Space"
     *
     * @param remapTo 组合键描述字符串
     * @return true 如果注入成功
     */
    @JvmStatic
    fun executeKeyRemapStrategy(remapTo: String): Boolean {
        val events = parseRemapTo(remapTo) ?: return false
        LogHelper.log(VerboseLevel.INFO,
            "$TAG: keyremap strategy '$remapTo' → ${events.size} event(s)")
        return IMEDispatcher.injectKeyEvents(events)
    }

    /**
     * 解析 remap-to 字符串为 KeyEvent DOWN/UP 对。
     */
    private fun parseRemapTo(remapTo: String): List<KeyEvent>? {
        val parts = remapTo.split("\\+".toRegex()).map { it.trim().lowercase() }
        var metaState = 0
        var keyCode = -1

        for (part in parts) {
            when (part) {
                "ctrl" -> metaState = metaState or KeyEvent.META_CTRL_MASK
                "alt" -> metaState = metaState or KeyEvent.META_ALT_MASK
                "shift" -> metaState = metaState or KeyEvent.META_SHIFT_MASK
                "meta", "win" -> metaState = metaState or KeyEvent.META_META_MASK
                "space" -> keyCode = KeyEvent.KEYCODE_SPACE
                "tab" -> keyCode = KeyEvent.KEYCODE_TAB
                "enter" -> keyCode = KeyEvent.KEYCODE_ENTER
                "esc", "escape" -> keyCode = KeyEvent.KEYCODE_ESCAPE
                "backspace" -> keyCode = KeyEvent.KEYCODE_DEL
                else -> {
                    // 尝试单字母键
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

        if (keyCode == -1) {
            LogHelper.log(VerboseLevel.WARNING,
                "$TAG: no key code found in remap-to: '$remapTo'")
            return null
        }

        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            metaState,
            InputDevice.SOURCE_KEYBOARD,
            0, 0
        )
        val up = KeyEvent(
            now + 50, now + 50,
            KeyEvent.ACTION_UP,
            keyCode,
            0,
            metaState,
            InputDevice.SOURCE_KEYBOARD,
            0, 0
        )

        return listOf(down, up)
    }

    // -----------------------------------------------------------------
    // 清理
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // 校验
    // -----------------------------------------------------------------

    /** 校验并补全 profile。返回 Pair(profile, 错误码列表)。空列表 = 通过。
     *  错误码在 UI 层通过 [localizeError] 翻译。 */
    @JvmStatic
    fun validateAndFix(rawJson: String): Pair<IMEProfile?, List<String>> {
        val problems = mutableListOf<String>()

        // 1. JSON 解析
        val profile = try {
            gson.fromJson(rawJson, IMEProfile::class.java)
        } catch (t: Throwable) {
            return null to listOf("err_json_parse", t.message ?: "")
        }
        if (profile == null) return null to listOf("err_json_null", "")

        // 2. 必须有 ime
        if (profile.ime == null || profile.ime.isBlank()) problems += "err_missing_ime"

        // 3. 必须有 strategy
        val stratField = Regex("\"strategy\"\\s*:").containsMatchIn(rawJson)
        if (!stratField) problems += "err_missing_strategy"

        // 4. 策略特定必填字段
        when (profile.strategy) {
            Strategy.hook -> {
                val hook = profile.hook
                if (hook == null) problems += "err_hook_missing_object"
                else {
                    if (!Regex("\"class\"\\s*:").containsMatchIn(rawJson))
                        problems += "err_hook_missing_class"
                    if (!Regex("\"method\"\\s*:").containsMatchIn(rawJson))
                        problems += "err_hook_missing_method"
                }
            }
            Strategy.keyremap -> {
                if (profile.remapTo.isNullOrBlank() && !Regex("\"remap-to\"\\s*:").containsMatchIn(rawJson))
                    problems += "err_keyremap_missing_target"
            }
            Strategy.framework -> { /* no extra required */ }
            null -> problems += "err_unknown_strategy"
        }

        // 5. 自动补全 name / uuid
        val hasName = profile.name != null && Regex("\"name\"\\s*:").containsMatchIn(rawJson)
        val hasUuid = profile.uuid != null && Regex("\"uuid\"\\s*:").containsMatchIn(rawJson)

        val fixedName = if (hasName) profile.name else "${profile.ime ?: "unknown"} 的配置"
        val fixedUuid = if (hasUuid) profile.uuid else IMEProfile.generateUUID()

        val fixed = if (hasName && hasUuid) profile
        else profile.copy(name = fixedName, uuid = fixedUuid)

        return fixed to problems
    }

    /** 将错误码翻译为本地化消息。错误码含 "err_" 前缀 = 校验问题；含 "err_json_" = 解析异常 */
    @JvmStatic
    fun localizeError(code: String, detail: String, ctx: android.content.Context): String {
        if (code.startsWith("err_json_")) {
            val reason = ctx.getString(R.string.ime_err_parse)
            return "$reason\n${if (detail.isNotBlank()) detail else ctx.getString(R.string.ime_err_unknown)}"
        }
        return when (code) {
            "err_missing_ime" -> ctx.getString(R.string.ime_err_missing_ime)
            "err_missing_strategy" -> ctx.getString(R.string.ime_err_missing_strategy)
            "err_hook_missing_object" -> ctx.getString(R.string.ime_err_hook_missing_object)
            "err_hook_missing_class" -> ctx.getString(R.string.ime_err_hook_missing_class)
            "err_hook_missing_method" -> ctx.getString(R.string.ime_err_hook_missing_method)
            "err_keyremap_missing_target" -> ctx.getString(R.string.ime_err_keyremap_missing_target)
            "err_unknown_strategy" -> ctx.getString(R.string.ime_err_unknown_strategy)
            else -> code
        }
    }

    // -----------------------------------------------------------------
    // 扫描问题
    // -----------------------------------------------------------------

    /** 异步扫描 adapters/ 目录，返回有问题的 profile 列表 */
    @JvmStatic
    fun scanForProblems(dir: File): List<ProfileValidationError> {
        val errors = mutableListOf<ProfileValidationError>()
        if (!dir.isDirectory) return errors
        val jsonFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return errors
        for (file in jsonFiles) {
            try {
                val raw = file.readText()
                val (profile, problems) = validateAndFix(raw)
                if (problems.isNotEmpty() && profile != null) {
                    errors.add(ProfileValidationError(profile, problems, raw))
                }
            } catch (_: Throwable) { }
        }
        return errors
    }

    // -----------------------------------------------------------------
    // 导入辅助
    // -----------------------------------------------------------------

    /** 导入 JSON 到 adapters/。返回 Pair(profile, error) — error 为 null 即成功 */
    @JvmStatic
    fun importProfile(json: String, dir: File): Pair<IMEProfile?, String?> {
        val (profile, problems) = validateAndFix(json)
        if (problems.isNotEmpty()) {
            return profile to problems.joinToString("|")
        }
        val p = profile ?: return null to "err_json_null|"
        val fileName = "${(p.ime ?: "unknown").replace('.', '_')}.json"
        val outFile = File(dir, fileName)
        val jsonToWrite = gson.toJson(p)
        try {
            outFile.writeText(jsonToWrite)
            return p to null
        } catch (e: Exception) {
            return null to "Write failed: ${e.message}"
        }
    }

    /** 为导入的 profile 补齐 name/uuid 并写回文件 */
    @JvmStatic
    fun fixAndRewrite(file: File) {
        try {
            val raw = file.readText()
            val (fixed, problems) = validateAndFix(raw)
            if (fixed != null) {
                file.writeText(gson.toJson(fixed))
                LogHelper.log(VerboseLevel.INFO, "$TAG: fixed ${file.name}")
            }
        } catch (_: Throwable) { }
    }

    /** 清除所有缓存的 profile */
    @JvmStatic
    fun clear() {
        profiles.clear()
    }
}
