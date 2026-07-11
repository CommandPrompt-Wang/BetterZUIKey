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
 * Win 长按卡片 UI 四档模式。
 */
enum class WinLongPressUiMode {
    FOLLOW_SYSTEM, ZUI, BLOCK, CUSTOM;

    fun displayName(context: Context): String = when (this) {
        FOLLOW_SYSTEM -> context.getString(R.string.mode_follow_system)
        ZUI -> context.getString(R.string.mode_zui)
        BLOCK -> context.getString(R.string.mode_block)
        CUSTOM -> context.getString(R.string.app_key_mode_custom)
    }
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
 * @param showOffOption  Whether to show OFF (passthrough) in the dropdown. metaSingle excludes it.
 * @param showSwitch      Whether to show the switch control.
 * @param cardClick       卡片点击行为。默认 AUTO
 * @param onSpinSelectedNonDefault Spinner 选中非默认项时的附加行为。
 */
data class ShortcutMeta(
    val key: String,
    @param:StringRes val displayResId: Int,
    @param:StringRes val descResId: Int = 0,
    val groupKeys: List<String> = emptyList(),
    val hasZui: Boolean = false,
    val hasAosp: Boolean = false,
    val hasSystemSwitch: Boolean = true,
    /** Whether to show AOSP as a selectable option in the dropdown. Default: hasAosp. */
    val showAospOption: Boolean = hasAosp,
    /** Whether to show OFF (passthrough) in the dropdown. Default: true. */
    val showOffOption: Boolean = true,
    /** Whether to show the switch control. Defaults to hasSystemSwitch value. */
    val showSwitch: Boolean = hasSystemSwitch,
    /** 卡片点击行为。默认 AUTO：优先 ExpandSpin → Switch → None */
    val cardClick: CardClickBehavior = CardClickBehavior.AUTO,
    /** Spinner 选中非默认项时的附加行为。默认 SWITCH_ON */
    val onSpinSelectedNonDefault: OnSpinSelectedNonDefault = OnSpinSelectedNonDefault.SWITCH_ON,
    /** Custom labels for OverrideMode spinner options. Map of OverrideMode → stringRes. */
    val overrideModeLabels: Map<Config.OverrideMode, Int>? = null,
) {
    companion object {
        val ALL: List<ShortcutMeta> = listOf(
            // ═══ Win/Meta + 字母 ═══
            ShortcutMeta("winD",        R.string.shortcut_winD,        R.string.shortcut_winD_desc,        hasZui = false, hasAosp = true),
            ShortcutMeta("winE",        R.string.shortcut_winE,        R.string.shortcut_winE_desc,        hasZui = true),
            ShortcutMeta("winS",        R.string.shortcut_winS,        R.string.shortcut_winS_desc,        hasZui = true),
            ShortcutMeta("winA",        R.string.shortcut_winA,        R.string.shortcut_winA_desc,        hasZui = true),
            ShortcutMeta("winW",        R.string.shortcut_winW,        R.string.shortcut_winW_desc,        hasZui = true),
            ShortcutMeta("winI",        R.string.shortcut_winI,        R.string.shortcut_winI_desc,        hasZui = false, hasAosp = true),
            ShortcutMeta("winL",        R.string.shortcut_winL,        R.string.shortcut_winL_desc,        hasZui = true),
            ShortcutMeta("winM",        R.string.shortcut_winM,        R.string.shortcut_winM_desc,        hasZui = false, hasAosp = true),
            ShortcutMeta("winN",        R.string.shortcut_winN,        R.string.shortcut_winN_desc,        hasZui = false, hasAosp = true),
            ShortcutMeta("winP",        R.string.shortcut_winP,        R.string.shortcut_winP_desc,        hasZui = true),
            ShortcutMeta("winNumber",   R.string.shortcut_winNumber,   R.string.shortcut_winNumber_desc,   hasZui = true),
            ShortcutMeta("winTab",      R.string.shortcut_winTab,      R.string.shortcut_winTab_desc,      hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("winBack",     R.string.shortcut_winBack,     R.string.shortcut_winBack_desc,     hasZui = true),

            // ═══ Win+方向键 ═══
            ShortcutMeta("winUp",       R.string.shortcut_winUp,       R.string.shortcut_winUp_desc,       hasZui = false, hasAosp = true,
                groupKeys = listOf("winDown")),
            ShortcutMeta("winLeft",     R.string.shortcut_winLeft,     R.string.shortcut_winLeft_desc,     hasZui = true,
                groupKeys = listOf("winRight")),

            // ═══ Win (Meta) 单键 ═══
            ShortcutMeta("metaSingle",       R.string.shortcut_metaSingle,       R.string.shortcut_metaSingle_desc,       hasZui = true,  hasAosp = true, hasSystemSwitch = false, showOffOption = false),
            ShortcutMeta("winLongPress",     R.string.shortcut_winLongPress,     R.string.shortcut_winLongPress_desc,     hasZui = true,  hasSystemSwitch = false,
                showOffOption = false, showAospOption = false,
                onSpinSelectedNonDefault = OnSpinSelectedNonDefault.NOTHING),

            // ═══ Ctrl ═══
            ShortcutMeta("ctrlCard",    R.string.shortcut_ctrlCard,    R.string.shortcut_ctrlCard_desc,
                         hasZui = false,  hasAosp = false, hasSystemSwitch = false, showSwitch = true,
                         onSpinSelectedNonDefault = OnSpinSelectedNonDefault.NOTHING),
            ShortcutMeta("ctrlShiftT",  R.string.shortcut_ctrlShiftT,  R.string.shortcut_ctrlShiftT_desc,  hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("ctrlEnter",   R.string.shortcut_ctrlEnter,   R.string.shortcut_ctrlEnter_desc,   hasZui = true,
                overrideModeLabels = mapOf(
                    Config.OverrideMode.FOLLOW_SYSTEM to R.string.mode_ctrl_enter_follow_system,
                    Config.OverrideMode.ZUI to R.string.mode_ctrl_enter_newline,
                    Config.OverrideMode.OFF to R.string.mode_ctrl_enter_passthrough,
                    Config.OverrideMode.BLOCK to R.string.mode_ctrl_enter_block,
                )),

            // ═══ Alt ═══
            ShortcutMeta("altTab",      R.string.shortcut_altTab,      R.string.shortcut_altTab_desc,      hasZui = true,  hasAosp = true),

            // ═══ 智能键 (507/508) ═══
            ShortcutMeta("keyApp1",         R.string.shortcut_keyApp1,         R.string.shortcut_keyApp1_desc,         hasZui = true,  hasSystemSwitch = false,
                showOffOption = false, showAospOption = false,
                onSpinSelectedNonDefault = OnSpinSelectedNonDefault.NOTHING),
            ShortcutMeta("keyApp2",         R.string.shortcut_keyApp2,         R.string.shortcut_keyApp2_desc,         hasZui = true,  hasSystemSwitch = false,
                showOffOption = false, showAospOption = false,
                onSpinSelectedNonDefault = OnSpinSelectedNonDefault.NOTHING),

            // ═══ AOSP 辅助键 ═══
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

        fun isAppKey(key: String): Boolean = key == "keyApp1" || key == "keyApp2"

        /** 智能键：三档 Spin（跟随系统 / 忽略 / 执行命令…） */
        fun usesAppKeyMode(key: String): Boolean = isAppKey(key)

        fun getWinLongPressUiMode(cfg: Config): WinLongPressUiMode = when {
            cfg.winLongUseCommand -> WinLongPressUiMode.CUSTOM
            cfg.overrideWinLongPress == Config.OverrideMode.BLOCK -> WinLongPressUiMode.BLOCK
            cfg.overrideWinLongPress == Config.OverrideMode.ZUI -> WinLongPressUiMode.ZUI
            else -> WinLongPressUiMode.FOLLOW_SYSTEM
        }

        fun setWinLongPressUiMode(cfg: Config, mode: WinLongPressUiMode) {
            when (mode) {
                WinLongPressUiMode.CUSTOM -> {
                    cfg.winLongUseCommand = true
                }
                WinLongPressUiMode.BLOCK -> {
                    cfg.winLongUseCommand = false
                    cfg.overrideWinLongPress = Config.OverrideMode.BLOCK
                }
                WinLongPressUiMode.ZUI -> {
                    cfg.winLongUseCommand = false
                    cfg.overrideWinLongPress = Config.OverrideMode.ZUI
                }
                WinLongPressUiMode.FOLLOW_SYSTEM -> {
                    cfg.winLongUseCommand = false
                    cfg.overrideWinLongPress = Config.OverrideMode.FOLLOW_SYSTEM
                }
            }
        }

        fun getAppKeyMode(cfg: Config, key: String): Config.AppKeyMode = when (key) {
            "keyApp1" -> cfg.app1Mode
            "keyApp2" -> cfg.app2Mode
            else -> Config.AppKeyMode.FOLLOW_SYSTEM
        }

        fun setAppKeyMode(cfg: Config, key: String, value: Config.AppKeyMode) {
            when (key) {
                "keyApp1" -> cfg.app1Mode = value
                "keyApp2" -> cfg.app2Mode = value
            }
        }

        fun getAppKeyCommand(cfg: Config, key: String): String = when (key) {
            "keyApp1" -> cfg.app1Command ?: ""
            "keyApp2" -> cfg.app2Command ?: ""
            "winLongPress" -> cfg.winLongCommand ?: ""
            else -> ""
        }

        fun setAppKeyCommand(cfg: Config, key: String, command: String) {
            when (key) {
                "keyApp1" -> cfg.app1Command = command
                "keyApp2" -> cfg.app2Command = command
                "winLongPress" -> cfg.winLongCommand = command
            }
        }

        fun getAppKeyCommandRoot(cfg: Config, key: String): Boolean = when (key) {
            "keyApp1" -> cfg.app1CommandRoot
            "keyApp2" -> cfg.app2CommandRoot
            "winLongPress" -> cfg.winLongCommandRoot
            else -> false
        }

        fun setAppKeyCommandRoot(cfg: Config, key: String, root: Boolean) {
            when (key) {
                "keyApp1" -> cfg.app1CommandRoot = root
                "keyApp2" -> cfg.app2CommandRoot = root
                "winLongPress" -> cfg.winLongCommandRoot = root
            }
        }

        fun getAppKeyCommandSingleton(cfg: Config, key: String): Boolean = when (key) {
            "keyApp1" -> cfg.app1CommandSingleton
            "keyApp2" -> cfg.app2CommandSingleton
            "winLongPress" -> cfg.winLongCommandSingleton
            else -> true
        }

        fun setAppKeyCommandSingleton(cfg: Config, key: String, singleton: Boolean) {
            when (key) {
                "keyApp1" -> cfg.app1CommandSingleton = singleton
                "keyApp2" -> cfg.app2CommandSingleton = singleton
                "winLongPress" -> cfg.winLongCommandSingleton = singleton
            }
        }

        fun getAppKeyCommandTimeoutMin(cfg: Config, key: String): Int = when (key) {
            "keyApp1" -> cfg.app1CommandTimeoutMin
            "keyApp2" -> cfg.app2CommandTimeoutMin
            "winLongPress" -> cfg.winLongCommandTimeoutMin
            else -> 1
        }

        fun setAppKeyCommandTimeoutMin(cfg: Config, key: String, minutes: Int) {
            val value = when (minutes) {
                5, 10 -> minutes
                else -> 1
            }
            when (key) {
                "keyApp1" -> cfg.app1CommandTimeoutMin = value
                "keyApp2" -> cfg.app2CommandTimeoutMin = value
                "winLongPress" -> cfg.winLongCommandTimeoutMin = value
            }
        }

        /** 从 Config 读取 SwitchState（直接字段访问，不使用反射） */
        fun getSwitch(cfg: Config, key: String): Config.SwitchState = when (key) {
            "winD" -> cfg.switchWinD; "winS" -> cfg.switchWinS; "winA" -> cfg.switchWinA
            "winBack" -> cfg.switchWinBack; "winE" -> cfg.switchWinE; "winI" -> cfg.switchWinI
            "winL" -> cfg.switchWinL; "winM" -> cfg.switchWinM; "winN" -> cfg.switchWinN
            "winP" -> cfg.switchWinP; "winW" -> cfg.switchWinW; "winNumber" -> cfg.switchWinNumber
            "winTab" -> cfg.switchWinTab; "winUp" -> cfg.switchWinUp
            "winLeft" -> cfg.switchWinLeft
            "ctrlLongPress" -> cfg.switchCtrlLongPress
            "ctrlSpace" -> cfg.switchCtrlSpace
            "ctrlShiftT" -> cfg.switchCtrlShiftT; "ctrlEnter" -> cfg.switchCtrlEnter
            "altTab" -> cfg.switchAltTab
            "keyMute" -> cfg.switchKeyMute; "keyTouchpad" -> cfg.switchKeyTouchpad
            "keySplitScreen" -> cfg.switchKeySplitScreen; "keySuperConnect" -> cfg.switchKeySuperConnect
            "keyApp1" -> cfg.switchKeyApp1; "keyApp2" -> cfg.switchKeyApp2
            "keySearch" -> cfg.switchKeySearch; "keySettings" -> cfg.switchKeySettings
            "keyFnLock" -> cfg.switchKeyFnLock; "keyBacklight" -> cfg.switchKeyBacklight
            "keyTpUp" -> cfg.switchKeyTpUp; "keyScreenLock" -> cfg.switchKeyScreenLock
            "printScreenShort" -> cfg.switchPrintScreenShort; "printScreenLong" -> cfg.switchPrintScreenLong
            "winLongPress" -> Config.SwitchState.ON  // no system switch, always active
            "metaSingle" -> Config.SwitchState.ON     // no system switch, always active
            "keyKeyboardRestore" -> cfg.switchKeyKeyboardRestore
            "keyKeyboardReverse" -> cfg.switchKeyKeyboardReverse
            "aospBounceKeys" -> cfg.switchAospBounceKeys; "aospMouseKeys" -> cfg.switchAospMouseKeys
            "aospStickyKeys" -> cfg.switchAospStickyKeys; "aospSlowKeys" -> cfg.switchAospSlowKeys
            else -> Config.SwitchState.OFF // fallback: unknown keys default to off
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
                "ctrlSpace" -> cfg.switchCtrlSpace = value
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
                "winLongPress" -> { /* no switch field */ }
                "metaSingle" -> { /* no switch field */ }
                "keyKeyboardRestore" -> cfg.switchKeyKeyboardRestore = value
                "keyKeyboardReverse" -> cfg.switchKeyKeyboardReverse = value
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
            "ctrlShiftT" -> cfg.overrideCtrlShiftT
            "ctrlEnter" -> cfg.overrideCtrlEnter; "altTab" -> cfg.overrideAltTab
            "ctrlSpace" -> cfg.overrideCtrlSpace
            // ⚠ 以下字段 Config.java 命名不带 "Key" 前缀（反射会失败）
            "keyMute" -> cfg.overrideMute; "keyTouchpad" -> cfg.overrideTouchpad
            "keySplitScreen" -> cfg.overrideSplitScreen
            "keySuperConnect" -> cfg.overrideSuperConnect
            "keyApp1" -> when (cfg.app1Mode) {
                Config.AppKeyMode.FOLLOW_SYSTEM -> Config.OverrideMode.FOLLOW_SYSTEM
                Config.AppKeyMode.BLOCK -> Config.OverrideMode.BLOCK
                Config.AppKeyMode.CUSTOM -> Config.OverrideMode.ZUI
            }
            "keyApp2" -> when (cfg.app2Mode) {
                Config.AppKeyMode.FOLLOW_SYSTEM -> Config.OverrideMode.FOLLOW_SYSTEM
                Config.AppKeyMode.BLOCK -> Config.OverrideMode.BLOCK
                Config.AppKeyMode.CUSTOM -> Config.OverrideMode.ZUI
            }
            "keySearch" -> cfg.overrideSearch; "keySettings" -> cfg.overrideSettings
            "keyFnLock" -> cfg.overrideFnLock; "keyBacklight" -> cfg.overrideBacklight
            "keyTpUp" -> cfg.overrideTpUp; "keyScreenLock" -> cfg.overrideScreenLock
            "printScreenShort" -> cfg.overridePrintScreenShort
            "printScreenLong" -> cfg.overridePrintScreenLong
            "metaSingle" -> {
                val v = cfg.overrideMetaSingle
                if (v == Config.OverrideMode.OFF) Config.OverrideMode.BLOCK else v
            }
            "winLongPress" -> if (cfg.winLongUseCommand) {
                Config.OverrideMode.ZUI
            } else {
                cfg.overrideWinLongPress
            }
            "keyKeyboardRestore" -> cfg.overrideKeyboardRestore
            "keyKeyboardReverse" -> cfg.overrideKeyboardReverse
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
                "ctrlShiftT" -> cfg.overrideCtrlShiftT = value
                "ctrlEnter" -> cfg.overrideCtrlEnter = value; "altTab" -> cfg.overrideAltTab = value
                "ctrlSpace" -> cfg.overrideCtrlSpace = value
                "keyMute" -> cfg.overrideMute = value; "keyTouchpad" -> cfg.overrideTouchpad = value
                "keySplitScreen" -> cfg.overrideSplitScreen = value
                "keySuperConnect" -> cfg.overrideSuperConnect = value
                "keyApp1" -> cfg.app1Mode = @Suppress("REDUNDANT_ELSE") when (value) {
                    Config.OverrideMode.BLOCK, Config.OverrideMode.OFF -> Config.AppKeyMode.BLOCK
                    Config.OverrideMode.FOLLOW_SYSTEM, Config.OverrideMode.ZUI,
                    Config.OverrideMode.AOSP -> Config.AppKeyMode.FOLLOW_SYSTEM
                    else -> Config.AppKeyMode.CUSTOM
                }
                "keyApp2" -> cfg.app2Mode = @Suppress("REDUNDANT_ELSE") when (value) {
                    Config.OverrideMode.BLOCK, Config.OverrideMode.OFF -> Config.AppKeyMode.BLOCK
                    Config.OverrideMode.FOLLOW_SYSTEM, Config.OverrideMode.ZUI,
                    Config.OverrideMode.AOSP -> Config.AppKeyMode.FOLLOW_SYSTEM
                    else -> Config.AppKeyMode.CUSTOM
                }
                "keySearch" -> cfg.overrideSearch = value; "keySettings" -> cfg.overrideSettings = value
                "keyFnLock" -> cfg.overrideFnLock = value; "keyBacklight" -> cfg.overrideBacklight = value
                "keyTpUp" -> cfg.overrideTpUp = value; "keyScreenLock" -> cfg.overrideScreenLock = value
                "printScreenShort" -> cfg.overridePrintScreenShort = value
                "printScreenLong" -> cfg.overridePrintScreenLong = value
                "metaSingle" -> cfg.overrideMetaSingle =
                    if (value == Config.OverrideMode.OFF) Config.OverrideMode.BLOCK else value
                "winLongPress" -> @Suppress("REDUNDANT_ELSE") when (value) {
                    Config.OverrideMode.BLOCK, Config.OverrideMode.OFF -> {
                        cfg.winLongUseCommand = false
                        cfg.overrideWinLongPress = Config.OverrideMode.BLOCK
                    }
                    Config.OverrideMode.ZUI, Config.OverrideMode.AOSP -> {
                        cfg.winLongUseCommand = false
                        cfg.overrideWinLongPress = Config.OverrideMode.ZUI
                    }
                    Config.OverrideMode.FOLLOW_SYSTEM -> {
                        cfg.winLongUseCommand = false
                        cfg.overrideWinLongPress = Config.OverrideMode.FOLLOW_SYSTEM
                    }
                    else -> cfg.winLongUseCommand = true
                }
                "keyKeyboardRestore" -> cfg.overrideKeyboardRestore = value
                "keyKeyboardReverse" -> cfg.overrideKeyboardReverse = value
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
    val cfg = Config.load()
    val metaLabel = cfg.metaKeyLabel
    val name = context.getString(displayResId, metaLabel)
    val badges = mutableListOf<String>()
    if (hasZui) badges.add(context.getString(R.string.badge_zui))
    if (hasAosp) badges.add(context.getString(R.string.badge_aosp))
    return if (badges.isNotEmpty()) context.getString(R.string.shortcut_badge_format, name, badges.joinToString("/")) else name
}

/**
 * 从资源解析快捷方式功能描述。⚠ 开头的行自动渲染为黄色。
 */
fun ShortcutMeta.displayDesc(context: Context): CharSequence {
    if (descResId == 0) return ""
    val raw = context.getString(descResId)
    val idx = raw.indexOf("\n⚠")
    if (idx < 0) return raw
    val sp = android.text.SpannableString(raw)
    sp.setSpan(android.text.style.ForegroundColorSpan(0xFFCC8800.toInt()),
        idx, raw.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    return sp
}

fun Config.AppKeyMode.displayName(context: Context): String = when (this) {
    Config.AppKeyMode.FOLLOW_SYSTEM -> context.getString(R.string.app_key_mode_follow_system)
    Config.AppKeyMode.BLOCK -> context.getString(R.string.app_key_mode_block)
    Config.AppKeyMode.CUSTOM -> context.getString(R.string.app_key_mode_custom)
}

fun Config.AppKeyMode.displayName(): String = when (this) {
    Config.AppKeyMode.FOLLOW_SYSTEM -> "Follow system"
    Config.AppKeyMode.BLOCK -> "Block"
    Config.AppKeyMode.CUSTOM -> "Run command…"
}

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
    Config.OverrideMode.ZUI           -> "Use ZUX"
    Config.OverrideMode.AOSP          -> "Use AOSP"
    Config.OverrideMode.OFF           -> "Off"
    Config.OverrideMode.BLOCK         -> "Block"
}

fun Config.OverrideMode.isAvailable(meta: ShortcutMeta): Boolean = when (this) {
    Config.OverrideMode.FOLLOW_SYSTEM -> true
    Config.OverrideMode.ZUI           -> meta.hasZui
    Config.OverrideMode.AOSP          -> meta.hasAosp
    Config.OverrideMode.OFF           -> meta.showOffOption
    Config.OverrideMode.BLOCK         -> true
}
