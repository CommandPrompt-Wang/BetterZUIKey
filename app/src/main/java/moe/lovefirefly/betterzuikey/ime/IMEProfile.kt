package moe.lovefirefly.betterzuikey.ime

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * IME 适配器配置文件（JSON profile）。
 *
 * 每个输入法一个 JSON 文件，放在 `adapters/` 目录下。
 * 三种策略：[Strategy.framework]、[Strategy.keyremap]、[Strategy.hook]。
 */
data class IMEProfile(
    /** 目标输入法包名，如 "com.google.android.inputmethod.latin" */
    val ime: String?,

    /** 切换策略 */
    val strategy: Strategy?,

    /** 友好名称（缺省自动生成："%s的配置" / "Config for %s"） */
    val name: String? = null,

    /** 唯一标识（缺省自动生成 UUID） */
    val uuid: String? = null,

    /** keyremap 目标组合键，如 "Ctrl+Shift", "Ctrl+Space", "Shift"（仅 strategy=keyremap 时有效） */
    @SerializedName("remap-to")
    val remapTo: String? = null,

    /** hook 锚点配置（仅 strategy=hook 时有效） */
    val hook: HookConfig? = null,

    /**
     * 该条配置是否启用（界面上的勾选框）。
     *
     * 缺省 true：老的 JSON 里没有这个字段，读出来应当是"启用"，
     * 免得升级后所有已有配置被静默关掉。
     */
    val enabled: Boolean = true
) {
    companion object {
        /** 内置默认配置（不可删除，按 UUID 识别） */
        @JvmField
        val BUILTIN_DEFAULTS = listOf(
            IMEProfile(
                ime = "com.google.android.inputmethod.latin",
                strategy = Strategy.keyremap,
                name = "GBoard",
                uuid = "bzuikey-builtin-gboard-0001",
                remapTo = "Ctrl+Space"
            ),
            IMEProfile(
                ime = "com.sohu.inputmethod.sogou.oem",
                strategy = Strategy.keyremap,
                name = "Sogou OEM",
                uuid = "bzuikey-builtin-sogou-oem-0002",
                remapTo = "Ctrl+Shift"
            ),
            IMEProfile(
                ime = "com.sohu.inputmethod.sogou",
                strategy = Strategy.keyremap,
                name = "Sogou (Public)",
                uuid = "bzuikey-builtin-sogou-pub-0003",
                remapTo = "Ctrl+Shift"
            ),
            // 系统输入法框架模式：默认存在但**默认关闭**（用户按需勾选）。
            // 装的模块（如搜狗增强）会自己把 subtype 补出来，勾上即由框架接管语言。
            IMEProfile(
                ime = "com.sohu.inputmethod.sogou.oem",
                strategy = Strategy.framework,
                name = "Sogou OEM",
                uuid = "bzuikey-builtin-framework-sogou-oem-0101",
                enabled = false
            )
        )

        private const val BUILTIN_UUID_PREFIX = "bzuikey-builtin-"

        fun isBuiltin(uuid: String?) = uuid != null && uuid.startsWith(BUILTIN_UUID_PREFIX)

        /** 生成 UUID */
        fun generateUUID() = UUID.randomUUID().toString()
    }
}

/** IME 切换策略枚举 */
enum class Strategy {
    @SerializedName("framework") framework,
    @SerializedName("keyremap") keyremap,

    /**
     * 在输入法进程里用 DexKit 按稳定锚点定位目标类后安装 hook。
     *
     * 与旧 hook 实现的区别：配置里只保存「锚点」（父类 / 框架 override 名），
     * 不保存混淆类名与方法名，所以输入法小版本更新通常不需要改配置。
     */
    @SerializedName("hook") hook
}

/**
 * hook 策略配置 —— matcher 形态，只描述「怎么找」与「挂哪个入口」。
 *
 * 支持的锚点预设见 [Companion.FIND_IME_SERVICE]。
 */
data class HookConfig(
    /**
     * 锚点预设：
     * - `ime-service`：按 `android.inputmethodservice.InputMethodService` 父类定位 IME 服务类
     */
    @SerializedName("find") val find: String? = FIND_IME_SERVICE,

    /**
     * 找到类后 hook 的入口方法名。必须是框架 override（混淆器改不了）：
     * 默认 `onKeyDown(int, KeyEvent)`，`onKeyUp` / `onKeyLongPress` 同理可用。
     */
    @SerializedName("entry") val entry: String? = "onKeyDown",

    /**
     * 预留：要在捕获到的 IME 实例上主动调用的内部方法名（空 = 只观测不调用）。
     * 可用「hook 候选方法 + 用户点一次中/英」的方式确定后再固化。
     */
    @SerializedName("invoke") val invoke: String? = null
) {
    companion object {
        /** 按 InputMethodService 父类定位 IME 服务类。 */
        const val FIND_IME_SERVICE = "ime-service"
    }
}

/** 增量变更操作（前端计算，后端 apply） */
data class ProfileChange(
    val op: String,          // "new" | "del" | "reload"
    val content: IMEProfile? = null
)

/** 校验结果 */
data class ProfileValidationError(
    val profile: IMEProfile,
    val problems: List<String>,
    val rawJson: String
) {
    val isBuiltin get() = IMEProfile.isBuiltin(profile.uuid)
}
