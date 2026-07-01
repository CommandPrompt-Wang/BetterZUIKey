package moe.lovefirefly.betterzuikey.ime

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * IME 适配器配置文件（JSON profile）。
 *
 * 每个输入法一个 JSON 文件，放在 `adapters/` 目录下。
 * 三种策略：[Strategy.framework]、[Strategy.hook]、[Strategy.keyremap]。
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

    /** hook 策略配置（仅 strategy=hook 时有效） */
    val hook: HookConfig? = null,

    /** keyremap 目标组合键，如 "Ctrl+Shift", "Ctrl+Space", "Shift"（仅 strategy=keyremap 时有效） */
    @SerializedName("remap-to")
    val remapTo: String? = null
) {
    companion object {
        /** 内置默认配置（不可删除，按 UUID 识别） */
        val BUILTIN_DEFAULTS = listOf(
            IMEProfile(
                ime = "com.google.android.inputmethod.latin",
                strategy = Strategy.framework,
                name = "GBoard",
                uuid = "bzuikey-builtin-gboard-0001"
            ),
            IMEProfile(
                ime = "com.sohu.inputmethod.sogou.oem",
                strategy = Strategy.hook,
                name = "Sogou OEM",
                uuid = "bzuikey-builtin-sogou-oem-0002",
                hook = HookConfig(
                    clazz = "defpackage.C2224gua",
                    method = "a",
                    instanceof = "defpackage.InterfaceC1447_ra"
                )
            ),
            IMEProfile(
                ime = "com.sohu.inputmethod.sogou",
                strategy = Strategy.keyremap,
                name = "Sogou (Public)",
                uuid = "bzuikey-builtin-sogou-pub-0003",
                remapTo = "Ctrl+Shift"
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
    @SerializedName("hook") hook,
    @SerializedName("keyremap") keyremap
}

data class HookConfig(
    @SerializedName("class") val clazz: String,
    val method: String,
    val params: List<String> = emptyList(),
    val instanceof: String? = null,
    @SerializedName("static") val isStatic: Boolean = false
)

/** 校验结果 */
data class ProfileValidationError(
    val profile: IMEProfile,
    val problems: List<String>,
    val rawJson: String
) {
    val isBuiltin get() = IMEProfile.isBuiltin(profile.uuid)
}
