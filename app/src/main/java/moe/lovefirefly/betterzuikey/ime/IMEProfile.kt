package moe.lovefirefly.betterzuikey.ime

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * IME 适配器配置文件（JSON profile）。
 *
 * 每个输入法一个 JSON 文件，放在 `adapters/` 目录下。
 * 两种策略：[Strategy.framework]、[Strategy.keyremap]。
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

    /**
     * 该条配置是否启用（界面上的勾选框）；`null` 表示 JSON 里没有这个字段。
     *
     * <p>**不要直接读这个字段**，用 [enabled]（缺省即启用）。做成可空是因为：
     * Gson 用 Unsafe 反射直接写字段，缺字段时置成 JVM 默认的 `false`，而 Kotlin
     * 构造器的"默认值合并"救不回来（Gson 传 null 被当成"未指定"，于是照写 false）。
     * 用可空类型 + 派生属性才能真正区分"没这个字段"和"显式 false"。
     */
    @SerializedName("enabled")
    val enabledRaw: Boolean? = null
) {
    /** 是否启用。**JSON 里没有 `enabled` 字段一律视为启用**（老配置、老导出文件都如此）。 */
    val enabled: Boolean
        get() = enabledRaw ?: true

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
                name = "搜狗输入法",
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
                enabledRaw = false
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
    @SerializedName("keyremap") keyremap
}

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
