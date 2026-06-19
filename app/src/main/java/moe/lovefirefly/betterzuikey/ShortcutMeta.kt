package moe.lovefirefly.betterzuikey

import android.content.Context
import androidx.annotation.StringRes
import moe.lovefirefly.betterzuikey.Config.Config

/**
 * 卡片点击行为。
 */
enum class CardClickBehavior {
    /** 仅播放水波纹动画，无操作 */
    NONE,
    /** 切换开关 */
    SWITCH,
    /** 展开 Spinner 下拉菜单 */
    EXPAND_SPIN,
    /** 自动：按 ExpandSpin → Switch → None 优先级选择 */
    AUTO;

    fun resolve(hasSpinner: Boolean, hasSwitch: Boolean): CardClickBehavior {
        if (this != AUTO) return this
        if (hasSpinner) return EXPAND_SPIN
        if (hasSwitch) return SWITCH
        return NONE
    }
}

/**
 * 当 Spinner 选中非默认项时的附加行为。
 */
enum class OnSpinSelectedNonDefault {
    /** 不执行额外操作 */
    NOTHING,
    /** 打开开关（如果有） */
    SWITCH_ON,
    /** 关闭开关（如果有） */
    SWITCH_OFF
}

/**
 * 快捷方式元数据 — UI 渲染所需信息。
 *
 * @param key             主 Config 字段 key（如 "winUp"）
 * @param displayResId    显示名称的字符串资源 ID（如 R.string.shortcut_winD）
 * @param descResId       功能描述的字符串资源 ID；0 = 无描述
 * @param groupKeys       与此项共享同一个 Settings.System 开关的其他 key
 * @param hasZui          有 ZUI 专用实现
 * @param hasAosp         有 AOSP 原生实现
 * @param hasSystemSwitch 系统设置有 GUI 开关
 * @param showAospOption  Whether to show AOSP as a selectable option in the dropdown.
 * @param showSwitch      Whether to show the switch control.
 * @param cardClick       卡片点击行为。默认 AUTO
 * @param onSpinSelectedNonDefault Spinner 选中非默认项时的附加行为。
 */
data class ShortcutMeta(
    val key: String,
    @StringRes val displayResId: Int,
    @StringRes val descResId: Int = 0,
    val groupKeys: List<String> = emptyList(),
    val hasZui: Boolean = false,
    val hasAosp: Boolean = false,
    val hasSystemSwitch: Boolean = true,
    /** Whether to show AOSP as a selectable option in the dropdown. Default: hasAosp. */
    val showAospOption: Boolean = hasAosp,
    /** Whether to show the switch control. Defaults to hasSystemSwitch value. */
    val showSwitch: Boolean = hasSystemSwitch,
    /** 卡片点击行为。默认 AUTO：优先 ExpandSpin → Switch → None */
    val cardClick: CardClickBehavior = CardClickBehavior.AUTO,
    /** Spinner 选中非默认项时的附加行为。默认 SWITCH_ON */
    val onSpinSelectedNonDefault: OnSpinSelectedNonDefault = OnSpinSelectedNonDefault.SWITCH_ON,
) {
    companion object {
        val ALL: List<ShortcutMeta> = listOf(
            // 一、Win/Meta + 字母
            ShortcutMeta("winD",        R.string.shortcut_winD,        R.string.shortcut_winD_desc,        hasZui = false, hasAosp = true),
            ShortcutMeta("winS",        R.string.shortcut_winS,        R.string.shortcut_winS_desc,        hasZui = true),
            ShortcutMeta("winA",        R.string.shortcut_winA,        R.string.shortcut_winA_desc,        hasZui = true),
            ShortcutMeta("winBack",     R.string.shortcut_winBack,     R.string.shortcut_winBack_desc,     hasZui = true),
            ShortcutMeta("winE",        R.string.shortcut_winE,        R.string.shortcut_winE_desc,        hasZui = true),
            ShortcutMeta("winI",        R.string.shortcut_winI,        R.string.shortcut_winI_desc,        hasZui = false, hasAosp = true),
            ShortcutMeta("winL",        R.string.shortcut_winL,        R.string.shortcut_winL_desc,        hasZui = true),
            ShortcutMeta("winM",        R.string.shortcut_winM,        R.string.shortcut_winM_desc,        hasZui = false, hasAosp = true),
            ShortcutMeta("winN",        R.string.shortcut_winN,        R.string.shortcut_winN_desc,        hasZui = false, hasAosp = true),
            ShortcutMeta("winP",        R.string.shortcut_winP,        R.string.shortcut_winP_desc,        hasZui = true),
            ShortcutMeta("winW",        R.string.shortcut_winW,        R.string.shortcut_winW_desc,        hasZui = true),
            ShortcutMeta("winNumber",   R.string.shortcut_winNumber,   R.string.shortcut_winNumber_desc,   hasZui = true),
            ShortcutMeta("winTab",      R.string.shortcut_winTab,      R.string.shortcut_winTab_desc,      hasZui = true,  hasSystemSwitch = false),
            // 二、Win+功能键（↑↓ 共用 keyboard_combo_ud_arrow，←→ 共用 keyboard_combo_lr_arrow）
            ShortcutMeta("winUp",       R.string.shortcut_winUp,       R.string.shortcut_winUp_desc,       hasZui = false, hasAosp = true,
                groupKeys = listOf("winDown")),
            ShortcutMeta("winLeft",     R.string.shortcut_winLeft,     R.string.shortcut_winLeft_desc,     hasZui = true,
                groupKeys = listOf("winRight")),
            // 三、Ctrl/Alt/Shift
            ShortcutMeta("ctrlCard",    R.string.shortcut_ctrlCard,    R.string.shortcut_ctrlCard_desc,
                         hasZui = false,  hasAosp = false, hasSystemSwitch = false, showSwitch = true,
                         onSpinSelectedNonDefault = OnSpinSelectedNonDefault.SWITCH_OFF),
            ShortcutMeta("ctrlShift",   R.string.shortcut_ctrlShift,   R.string.shortcut_ctrlShift_desc,   hasZui = true),
            ShortcutMeta("altShift",    R.string.shortcut_altShift,    R.string.shortcut_altShift_desc,    hasZui = true),
            ShortcutMeta("ctrlShiftT",  R.string.shortcut_ctrlShiftT,  R.string.shortcut_ctrlShiftT_desc,  hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("ctrlEnter",   R.string.shortcut_ctrlEnter,   R.string.shortcut_ctrlEnter_desc,   hasZui = true),
            ShortcutMeta("altTab",      R.string.shortcut_altTab,      R.string.shortcut_altTab_desc,      hasZui = true,  hasAosp = true),
            // 四、ZUI 物理键（无 Settings.System 开关，系统强制开启）
            ShortcutMeta("keyMute",         R.string.shortcut_keyMute,         R.string.shortcut_keyMute_desc,         hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyTouchpad",     R.string.shortcut_keyTouchpad,     R.string.shortcut_keyTouchpad_desc,     hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keySplitScreen",  R.string.shortcut_keySplitScreen,  R.string.shortcut_keySplitScreen_desc,  hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keySuperConnect", R.string.shortcut_keySuperConnect, R.string.shortcut_keySuperConnect_desc, hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyApp1",         R.string.shortcut_keyApp1,         R.string.shortcut_keyApp1_desc,         hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyApp2",         R.string.shortcut_keyApp2,         R.string.shortcut_keyApp2_desc,         hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keySearch",       R.string.shortcut_keySearch,       R.string.shortcut_keySearch_desc,       hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keySettings",     R.string.shortcut_keySettings,     R.string.shortcut_keySettings_desc,     hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyFnLock",       R.string.shortcut_keyFnLock,       R.string.shortcut_keyFnLock_desc,       hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyBacklight",    R.string.shortcut_keyBacklight,    R.string.shortcut_keyBacklight_desc,    hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyTpUp",         R.string.shortcut_keyTpUp,         R.string.shortcut_keyTpUp_desc,         hasZui = true,  hasAosp = true, hasSystemSwitch = false),
            ShortcutMeta("keyScreenLock",   R.string.shortcut_keyScreenLock,   R.string.shortcut_keyScreenLock_desc,   hasZui = true,  hasSystemSwitch = false),
            // 五、截图/特殊键（无 Settings.System 开关）
            ShortcutMeta("printScreenShort", R.string.shortcut_printScreenShort, R.string.shortcut_printScreenShort_desc, hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("printScreenLong",  R.string.shortcut_printScreenLong,  R.string.shortcut_printScreenLong_desc,  hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("metaSingle",       R.string.shortcut_metaSingle,       R.string.shortcut_metaSingle_desc,       hasZui = true,  hasAosp = true, hasSystemSwitch = false),
            ShortcutMeta("metaShortRow",     R.string.shortcut_metaShortRow,     R.string.shortcut_metaShortRow_desc,     hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("metaLongRow",      R.string.shortcut_metaLongRow,      R.string.shortcut_metaLongRow_desc,      hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("metaHoldNonRow",   R.string.shortcut_metaHoldNonRow,   R.string.shortcut_metaHoldNonRow_desc,   hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyKeyboardRestore",  R.string.shortcut_keyKeyboardRestore,  R.string.shortcut_keyKeyboardRestore_desc,  hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyKeyboardReverse",  R.string.shortcut_keyKeyboardReverse,  R.string.shortcut_keyKeyboardReverse_desc,  hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("altRightKR",          R.string.shortcut_altRightKR,          R.string.shortcut_altRightKR_desc,          hasZui = true,  hasSystemSwitch = false),
            // 六、AOSP 辅助键（Settings.Secure 控制，非 Settings.System）
            ShortcutMeta("aospBounceKeys",  R.string.shortcut_aospBounceKeys,  R.string.shortcut_aospBounceKeys_desc,  hasZui = false, hasAosp = true, hasSystemSwitch = false,
                onSpinSelectedNonDefault = OnSpinSelectedNonDefault.NOTHING),
            ShortcutMeta("aospMouseKeys",   R.string.shortcut_aospMouseKeys,   R.string.shortcut_aospMouseKeys_desc,   hasZui = false, hasAosp = true, hasSystemSwitch = false,
                onSpinSelectedNonDefault = OnSpinSelectedNonDefault.NOTHING),
            ShortcutMeta("aospStickyKeys",  R.string.shortcut_aospStickyKeys,  R.string.shortcut_aospStickyKeys_desc,  hasZui = false, hasAosp = true, hasSystemSwitch = false,
                onSpinSelectedNonDefault = OnSpinSelectedNonDefault.NOTHING),
            ShortcutMeta("aospSlowKeys",    R.string.shortcut_aospSlowKeys,    R.string.shortcut_aospSlowKeys_desc,    hasZui = false, hasAosp = true, hasSystemSwitch = false,
                onSpinSelectedNonDefault = OnSpinSelectedNonDefault.NOTHING),
        )

        // ── 直接字段访问：替代不可靠的反射（Config.java 字段命名不一致导致反射失败）──

        /** 从 Config 读取 SwitchState（直接字段访问，不使用反射） */
        fun getSwitch(cfg: Config, key: String): Config.SwitchState = when (key) {
            "winD" -> cfg.switchWinD; "winS" -> cfg.switchWinS; "winA" -> cfg.switchWinA
            "winBack" -> cfg.switchWinBack; "winE" -> cfg.switchWinE; "winI" -> cfg.switchWinI
            "winL" -> cfg.switchWinL; "winM" -> cfg.switchWinM; "winN" -> cfg.switchWinN
            "winP" -> cfg.switchWinP; "winW" -> cfg.switchWinW; "winNumber" -> cfg.switchWinNumber
            "winTab" -> cfg.switchWinTab; "winUp" -> cfg.switchWinUp
            "winLeft" -> cfg.switchWinLeft
            "ctrlShift" -> cfg.switchCtrlShift; "altShift" -> cfg.switchAltShift
            "ctrlShiftT" -> cfg.switchCtrlShiftT; "ctrlEnter" -> cfg.switchCtrlEnter
            "altTab" -> cfg.switchAltTab
            "keyMute" -> cfg.switchKeyMute; "keyTouchpad" -> cfg.switchKeyTouchpad
            "keySplitScreen" -> cfg.switchKeySplitScreen; "keySuperConnect" -> cfg.switchKeySuperConnect
            "keyApp1" -> cfg.switchKeyApp1; "keyApp2" -> cfg.switchKeyApp2
            "keySearch" -> cfg.switchKeySearch; "keySettings" -> cfg.switchKeySettings
            "keyFnLock" -> cfg.switchKeyFnLock; "keyBacklight" -> cfg.switchKeyBacklight
            "keyTpUp" -> cfg.switchKeyTpUp; "keyScreenLock" -> cfg.switchKeyScreenLock
            "printScreenShort" -> cfg.switchPrintScreenShort; "printScreenLong" -> cfg.switchPrintScreenLong
            "metaSingle" -> cfg.switchMetaSingle; "metaShortRow" -> cfg.switchMetaShortRow
            "metaLongRow" -> cfg.switchMetaLongRow; "metaHoldNonRow" -> cfg.switchMetaHoldNonRow
            "keyKeyboardRestore" -> cfg.switchKeyKeyboardRestore
            "keyKeyboardReverse" -> cfg.switchKeyKeyboardReverse
            "altRightKR" -> cfg.switchAltRightKR
            "aospBounceKeys" -> cfg.switchAospBounceKeys; "aospMouseKeys" -> cfg.switchAospMouseKeys
            "aospStickyKeys" -> cfg.switchAospStickyKeys; "aospSlowKeys" -> cfg.switchAospSlowKeys
            else -> Config.SwitchState.ON // fallback
        }

        /** 向 Config 写入 SwitchState */
        fun setSwitch(cfg: Config, key: String, value: Config.SwitchState) {
            when (key) {
                "winD" -> cfg.switchWinD = value; "winS" -> cfg.switchWinS = value
                "winA" -> cfg.switchWinA = value; "winBack" -> cfg.switchWinBack = value
                "winE" -> cfg.switchWinE = value; "winI" -> cfg.switchWinI = value
                "winL" -> cfg.switchWinL = value; "winM" -> cfg.switchWinM = value
                "winN" -> cfg.switchWinN = value; "winP" -> cfg.switchWinP = value
                "winW" -> cfg.switchWinW = value; "winNumber" -> cfg.switchWinNumber = value
                "winTab" -> cfg.switchWinTab = value; "winUp" -> cfg.switchWinUp = value
                "winLeft" -> cfg.switchWinLeft = value
                "ctrlLongPress" -> cfg.switchCtrlLongPress = value
                "ctrlShift" -> cfg.switchCtrlShift = value; "altShift" -> cfg.switchAltShift = value
                "ctrlShiftT" -> cfg.switchCtrlShiftT = value; "ctrlEnter" -> cfg.switchCtrlEnter = value
                "altTab" -> cfg.switchAltTab = value
                "keyMute" -> cfg.switchKeyMute = value; "keyTouchpad" -> cfg.switchKeyTouchpad = value
                "keySplitScreen" -> cfg.switchKeySplitScreen = value
                "keySuperConnect" -> cfg.switchKeySuperConnect = value
                "keyApp1" -> cfg.switchKeyApp1 = value; "keyApp2" -> cfg.switchKeyApp2 = value
                "keySearch" -> cfg.switchKeySearch = value; "keySettings" -> cfg.switchKeySettings = value
                "keyFnLock" -> cfg.switchKeyFnLock = value; "keyBacklight" -> cfg.switchKeyBacklight = value
                "keyTpUp" -> cfg.switchKeyTpUp = value; "keyScreenLock" -> cfg.switchKeyScreenLock = value
                "printScreenShort" -> cfg.switchPrintScreenShort = value
                "printScreenLong" -> cfg.switchPrintScreenLong = value
                "metaSingle" -> cfg.switchMetaSingle = value; "metaShortRow" -> cfg.switchMetaShortRow = value
                "metaLongRow" -> cfg.switchMetaLongRow = value
                "metaHoldNonRow" -> cfg.switchMetaHoldNonRow = value
                "keyKeyboardRestore" -> cfg.switchKeyKeyboardRestore = value
                "keyKeyboardReverse" -> cfg.switchKeyKeyboardReverse = value
                "altRightKR" -> cfg.switchAltRightKR = value
                "aospBounceKeys" -> cfg.switchAospBounceKeys = value
                "aospMouseKeys" -> cfg.switchAospMouseKeys = value
                "aospStickyKeys" -> cfg.switchAospStickyKeys = value
                "aospSlowKeys" -> cfg.switchAospSlowKeys = value
            }
        }

        /** 从 Config 读取 OverrideMode（直接字段访问） */
        fun getOverride(cfg: Config, key: String): Config.OverrideMode = when (key) {
            "winD" -> cfg.overrideWinD; "winS" -> cfg.overrideWinS; "winA" -> cfg.overrideWinA
            "winBack" -> cfg.overrideWinBack; "winE" -> cfg.overrideWinE; "winI" -> cfg.overrideWinI
            "winL" -> cfg.overrideWinL; "winM" -> cfg.overrideWinM; "winN" -> cfg.overrideWinN
            "winP" -> cfg.overrideWinP; "winW" -> cfg.overrideWinW
            "winNumber" -> cfg.overrideWinNumber; "winTab" -> cfg.overrideWinTab
            "winUp" -> cfg.overrideWinUp
            "winLeft" -> cfg.overrideWinLeft
            "ctrlSlash" -> cfg.overrideCtrlSlash
            "ctrlShift" -> cfg.overrideCtrlShift; "altShift" -> cfg.overrideAltShift
            "ctrlShiftT" -> cfg.overrideCtrlShiftT
            "ctrlEnter" -> cfg.overrideCtrlEnter; "altTab" -> cfg.overrideAltTab
            // ⚠ 以下字段 Config.java 命名不带 "Key" 前缀（反射会失败）
            "keyMute" -> cfg.overrideMute; "keyTouchpad" -> cfg.overrideTouchpad
            "keySplitScreen" -> cfg.overrideSplitScreen
            "keySuperConnect" -> cfg.overrideSuperConnect
            "keyApp1" -> cfg.app1LongPressOverride; "keyApp2" -> cfg.app2LongPressOverride
            "keySearch" -> cfg.overrideSearch; "keySettings" -> cfg.overrideSettings
            "keyFnLock" -> cfg.overrideFnLock; "keyBacklight" -> cfg.overrideBacklight
            "keyTpUp" -> cfg.overrideTpUp; "keyScreenLock" -> cfg.overrideScreenLock
            "printScreenShort" -> cfg.overridePrintScreenShort
            "printScreenLong" -> cfg.overridePrintScreenLong
            "metaSingle" -> cfg.overrideMetaSingle; "metaShortRow" -> cfg.overrideMetaShortRow
            "metaLongRow" -> cfg.overrideMetaLongRow; "metaHoldNonRow" -> cfg.overrideMetaHoldNonRow
            "keyKeyboardRestore" -> cfg.overrideKeyboardRestore
            "keyKeyboardReverse" -> cfg.overrideKeyboardReverse
            "altRightKR" -> cfg.overrideAltRightKR
            "aospBounceKeys" -> cfg.overrideAospBounceKeys
            "aospMouseKeys" -> cfg.overrideAospMouseKeys
            "aospStickyKeys" -> cfg.overrideAospStickyKeys
            "aospSlowKeys" -> cfg.overrideAospSlowKeys
            else -> Config.OverrideMode.FOLLOW_SYSTEM
        }

        /** 向 Config 写入 OverrideMode */
        fun setOverride(cfg: Config, key: String, value: Config.OverrideMode) {
            when (key) {
                "winD" -> cfg.overrideWinD = value; "winS" -> cfg.overrideWinS = value
                "winA" -> cfg.overrideWinA = value; "winBack" -> cfg.overrideWinBack = value
                "winE" -> cfg.overrideWinE = value; "winI" -> cfg.overrideWinI = value
                "winL" -> cfg.overrideWinL = value; "winM" -> cfg.overrideWinM = value
                "winN" -> cfg.overrideWinN = value; "winP" -> cfg.overrideWinP = value
                "winW" -> cfg.overrideWinW = value; "winNumber" -> cfg.overrideWinNumber = value
                "winTab" -> cfg.overrideWinTab = value; "winUp" -> cfg.overrideWinUp = value
                "winLeft" -> cfg.overrideWinLeft = value
                "ctrlSlash" -> cfg.overrideCtrlSlash = value
                "ctrlShift" -> cfg.overrideCtrlShift = value; "altShift" -> cfg.overrideAltShift = value
                "ctrlShiftT" -> cfg.overrideCtrlShiftT = value
                "ctrlEnter" -> cfg.overrideCtrlEnter = value; "altTab" -> cfg.overrideAltTab = value
                "keyMute" -> cfg.overrideMute = value; "keyTouchpad" -> cfg.overrideTouchpad = value
                "keySplitScreen" -> cfg.overrideSplitScreen = value
                "keySuperConnect" -> cfg.overrideSuperConnect = value
                "keyApp1" -> cfg.app1LongPressOverride = value
                "keyApp2" -> cfg.app2LongPressOverride = value
                "keySearch" -> cfg.overrideSearch = value; "keySettings" -> cfg.overrideSettings = value
                "keyFnLock" -> cfg.overrideFnLock = value; "keyBacklight" -> cfg.overrideBacklight = value
                "keyTpUp" -> cfg.overrideTpUp = value; "keyScreenLock" -> cfg.overrideScreenLock = value
                "printScreenShort" -> cfg.overridePrintScreenShort = value
                "printScreenLong" -> cfg.overridePrintScreenLong = value
                "metaSingle" -> cfg.overrideMetaSingle = value
                "metaShortRow" -> cfg.overrideMetaShortRow = value
                "metaLongRow" -> cfg.overrideMetaLongRow = value
                "metaHoldNonRow" -> cfg.overrideMetaHoldNonRow = value
                "keyKeyboardRestore" -> cfg.overrideKeyboardRestore = value
                "keyKeyboardReverse" -> cfg.overrideKeyboardReverse = value
                "altRightKR" -> cfg.overrideAltRightKR = value
                "aospBounceKeys" -> cfg.overrideAospBounceKeys = value
                "aospMouseKeys" -> cfg.overrideAospMouseKeys = value
                "aospStickyKeys" -> cfg.overrideAospStickyKeys = value
                "aospSlowKeys" -> cfg.overrideAospSlowKeys = value
            }
        }
    }
}

/**
 * 从资源解析快捷方式显示名称（含 ZUI/AOSP 标签）。
 */
fun ShortcutMeta.displayName(context: Context): String {
    val name = context.getString(displayResId)
    val badges = mutableListOf<String>()
    if (hasZui) badges.add(context.getString(R.string.badge_zui))
    if (hasAosp) badges.add(context.getString(R.string.badge_aosp))
    return if (badges.isNotEmpty()) context.getString(R.string.shortcut_badge_format, name, badges.joinToString("/")) else name
}

/**
 * 从资源解析快捷方式功能描述。
 */
fun ShortcutMeta.displayDesc(context: Context): String =
    if (descResId != 0) context.getString(descResId) else ""

/**
 * OverrideMode 显示名称映射（可从资源加载）。
 */
fun Config.OverrideMode.displayName(context: Context): String = when (this) {
    Config.OverrideMode.FOLLOW_SYSTEM -> context.getString(R.string.mode_follow_system)
    Config.OverrideMode.ZUI           -> context.getString(R.string.mode_zui)
    Config.OverrideMode.AOSP          -> context.getString(R.string.mode_aosp)
    Config.OverrideMode.OFF           -> context.getString(R.string.mode_off)
    Config.OverrideMode.BLOCK         -> context.getString(R.string.mode_block)
}

/**
 * OverrideMode 显示名称（无 Context 版本，用于宽度计算等无 Context 场景）。
 * 默认返回英文（与 values/strings.xml 回退语言一致）。
 */
fun Config.OverrideMode.displayName(): String = when (this) {
    Config.OverrideMode.FOLLOW_SYSTEM -> "Keep Default"
    Config.OverrideMode.ZUI           -> "Use ZUI"
    Config.OverrideMode.AOSP          -> "Use AOSP"
    Config.OverrideMode.OFF           -> "Off"
    Config.OverrideMode.BLOCK         -> "Block"
}

fun Config.OverrideMode.displayDesc(): String = when (this) {
    Config.OverrideMode.FOLLOW_SYSTEM -> "Follow system switch (on = ZUI, off = pass-through)"
    Config.OverrideMode.ZUI           -> "Force ZUI shortcut behavior"
    Config.OverrideMode.AOSP          -> "Disable ZUI, use Android native handling"
    Config.OverrideMode.OFF           -> "Disable ZUI and AOSP, pass event to app"
    Config.OverrideMode.BLOCK         -> "Consume event; neither system nor app receives it"
}

fun Config.OverrideMode.isAvailable(meta: ShortcutMeta): Boolean = when (this) {
    Config.OverrideMode.FOLLOW_SYSTEM -> true
    Config.OverrideMode.ZUI           -> meta.hasZui
    Config.OverrideMode.AOSP          -> meta.hasAosp
    Config.OverrideMode.OFF           -> true
    Config.OverrideMode.BLOCK         -> true
}
