package moe.lovefirefly.betterzuikey

import moe.lovefirefly.betterzuikey.Config.Config

/**
 * 快捷方式元数据 — UI 渲染所需信息。
 *
 * @param key             主 Config 字段 key（如 "winUp"）
 * @param groupKeys       与此项共享同一个 Settings.System 开关的其他 key
 *                        切换开关时，这些 key 对应的 Config 字段也会同步更新
 * @param hasZui          有 ZUI 专用实现
 * @param hasAosp         有 AOSP 原生实现
 * @param hasSystemSwitch 系统设置有 GUI 开关
 */
data class ShortcutMeta(
    val key: String,
    val display: String,
    val desc: String = "",
    val groupKeys: List<String> = emptyList(),
    val hasZui: Boolean = false,
    val hasAosp: Boolean = false,
    val hasSystemSwitch: Boolean = true
) {
    // ── 预计算的显示文本（一次性计算，不在每次 bind 中动态拼接）──
    /** 标题：快捷方式名 + 实现标注 */
    val displayName: String by lazy {
        val badges = mutableListOf<String>()
        if (hasZui) badges.add("ZUI")
        if (hasAosp) badges.add("AOSP")
        if (badges.isNotEmpty()) "$display  [${badges.joinToString("/")}]" else display
    }
    /** 描述：仅功能说明 */
    val displayDesc: String by lazy { desc }

    companion object {
        val ALL: List<ShortcutMeta> = listOf(
            // 一、Win/Meta + 字母
            ShortcutMeta("winD",        "Win + D",           "回到桌面",                              hasZui = false, hasAosp = true),
            ShortcutMeta("winS",        "Win + S",           "全局搜索",                              hasZui = true),
            ShortcutMeta("winA",        "Win + A",           "隐藏/显示任务栏",                        hasZui = true),
            ShortcutMeta("winBack",     "Win + Back",        "发送 ESC / 返回\n设置-实体键盘-辅助键 可改 ESC 功能", hasZui = true),
            ShortcutMeta("winE",        "Win + E",           "打开文件管理器",                          hasZui = true),
            ShortcutMeta("winI",        "Win + I",           "打开设置",                               hasZui = false, hasAosp = true),
            ShortcutMeta("winL",        "Win + L",           "锁屏",                                   hasZui = true),
            ShortcutMeta("winM",        "Win + M",           "最小化窗口",                             hasZui = false, hasAosp = true),
            ShortcutMeta("winN",        "Win + N",           "通知面板",                               hasZui = false, hasAosp = true),
            ShortcutMeta("winP",        "Win + P",           "切换 PC 模式",                           hasZui = true),
            ShortcutMeta("winW",        "Win + W",           "关闭前台应用（保护5个系统进程）",            hasZui = true),
            ShortcutMeta("winNumber",   "Win + 1~8",         "打开 Dock 栏对应位置应用",                  hasZui = true),
            ShortcutMeta("winTab",      "Win + Tab",         "最近任务",                               hasZui = true),
            // 二、Win+功能键（↑↓ 共用 keyboard_combo_ud_arrow，←→ 共用 keyboard_combo_lr_arrow）
            ShortcutMeta("winUp",       "Win + ↑ / Win + ↓",   "窗口最大化 / 窗口还原\n系统开关 ↑↓ 共用",    hasZui = false, hasAosp = true,
                groupKeys = listOf("winDown")),
            ShortcutMeta("winLeft",     "Win + ← / Win + →",   "分屏到左侧 / 分屏到右侧\n系统开关 ←→ 共用",    hasZui = true,
                groupKeys = listOf("winRight")),
            // 三、Ctrl/Alt/Shift
            ShortcutMeta("ctrlSlash",       "Ctrl + /",          "弹出快捷键菜单（系统无独立开关",              hasZui = true, hasAosp = true,  hasSystemSwitch = false),
            ShortcutMeta("ctrlLongPress",   "Ctrl 长按",         "长按≥3s 弹出快捷键菜单（同上系统开关）",       hasZui = true,  hasSystemSwitch = true),
            ShortcutMeta("ctrlShift",       "Ctrl + Shift",      "切换输入法（仅 ROW 生效）",            hasZui = true),
            ShortcutMeta("altShift",        "Alt + Shift",       "切换语言（仅 ROW 生效）",              hasZui = true),
            ShortcutMeta("ctrlShiftT",      "Ctrl + Shift + T",  "切换触控板开关",                       hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("ctrlEnter",       "Ctrl + Enter",      "QQ 前台时放行给应用",                  hasZui = true),
            ShortcutMeta("altTab",          "Alt + Tab",         "最近任务切换",                          hasZui = true,  hasAosp = true),
            // 四、ZUI 物理键（无 Settings.System 开关，系统强制开启）
            ShortcutMeta("keyMute",         "501 静音键",        "静音切换",                              hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyTouchpad",     "502 触控板",        "触控板开关",                            hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keySplitScreen",  "504 分屏",          "分屏开关",                              hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keySuperConnect", "505 超级互联",      "启动超级互联",                           hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyApp1",         "507 App1",          "自定义按键（短按行为 / 长按设置）",         hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyApp2",         "508 App2",          "自定义按键（短按行为 / 长按设置）",         hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keySearch",       "509 搜索",          "全局搜索",                              hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keySettings",     "510 设置",          "⚠ 解锁时无效（ZUI Bug）",               hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyFnLock",       "511 Fn 锁",         "Fn 锁定切换 + LED",                     hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyBacklight",    "512 背光",          "键盘背光循环",                           hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyTpUp",         "514 触控板上移",    "打开通知面板",                           hasZui = true,  hasAosp = true, hasSystemSwitch = false),
            ShortcutMeta("keyScreenLock",   "515 锁屏",          "锁屏",                                  hasZui = true,  hasSystemSwitch = false),
            // 五、截图/特殊键（无 Settings.System 开关）
            ShortcutMeta("printScreenShort", "Print Screen 短按", "区域截图",                             hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("printScreenLong",  "Print Screen 长按", "全屏截图",                             hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("metaSingle",       "Meta 单按",         "开始菜单",                             hasZui = true,  hasAosp = true, hasSystemSwitch = false),
            ShortcutMeta("metaShortRow",     "Meta ROW 短按",     "切换语言（需 ROW+特殊键盘）",             hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("metaLongRow",      "Meta ROW 长按",     "语音助手（需 ROW+特殊键盘）",             hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("metaHoldNonRow",   "Meta 非ROW 按住",   "连续切语言（需非ROW+特殊键盘）",          hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyKeyboardRestore",  "520 键盘恢复",   "禁用物理键盘",                           hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("keyKeyboardReverse",  "521 键盘翻转",   "启用物理键盘",                           hasZui = true,  hasSystemSwitch = false),
            ShortcutMeta("altRightKR",          "Alt_RIGHT KR",   "韩国版切语言",                           hasZui = true,  hasSystemSwitch = false),
            // 六、AOSP 辅助键（Settings.Secure 控制，非 Settings.System）
            ShortcutMeta("aospBounceKeys",  "Win+Alt+3 防抖", "AOSP 原生防抖键",                         hasZui = false, hasAosp = true, hasSystemSwitch = false),
            ShortcutMeta("aospMouseKeys",   "Win+Alt+4 鼠标", "AOSP 原生鼠标键",                         hasZui = false, hasAosp = true, hasSystemSwitch = false),
            ShortcutMeta("aospStickyKeys",  "Win+Alt+5 粘滞", "AOSP 原生粘滞键",                         hasZui = false, hasAosp = true, hasSystemSwitch = false),
            ShortcutMeta("aospSlowKeys",    "Win+Alt+6 慢速", "AOSP 原生慢速键",                         hasZui = false, hasAosp = true, hasSystemSwitch = false),
        )

        // ── 直接字段访问：替代不可靠的反射（Config.java 字段命名不一致导致反射失败）──

        /** 从 Config 读取 SwitchState（直接字段访问，不使用反射） */
        fun getSwitch(cfg: Config, key: String): Config.SwitchState = when (key) {
            "winD" -> cfg.switchWinD; "winS" -> cfg.switchWinS; "winA" -> cfg.switchWinA
            "winBack" -> cfg.switchWinBack; "winE" -> cfg.switchWinE; "winI" -> cfg.switchWinI
            "winL" -> cfg.switchWinL; "winM" -> cfg.switchWinM; "winN" -> cfg.switchWinN
            "winP" -> cfg.switchWinP; "winW" -> cfg.switchWinW; "winNumber" -> cfg.switchWinNumber
            "winTab" -> cfg.switchWinTab; "winUp" -> cfg.switchWinUp; "winDown" -> cfg.switchWinDown
            "winLeft" -> cfg.switchWinLeft; "winRight" -> cfg.switchWinRight
            "ctrlSlash" -> cfg.switchCtrlSlash; "ctrlLongPress" -> cfg.switchCtrlLongPress
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
                "winDown" -> cfg.switchWinDown = value
                "winLeft" -> cfg.switchWinLeft = value; "winRight" -> cfg.switchWinRight = value
                "ctrlSlash" -> cfg.switchCtrlSlash = value
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
            "winUp" -> cfg.overrideWinUp; "winDown" -> cfg.overrideWinDown
            "winLeft" -> cfg.overrideWinLeft; "winRight" -> cfg.overrideWinRight
            "ctrlSlash" -> cfg.overrideCtrlSlash; "ctrlLongPress" -> cfg.overrideCtrlLongPress
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
                "winDown" -> cfg.overrideWinDown = value
                "winLeft" -> cfg.overrideWinLeft = value; "winRight" -> cfg.overrideWinRight = value
                "ctrlSlash" -> cfg.overrideCtrlSlash = value
                "ctrlLongPress" -> cfg.overrideCtrlLongPress = value
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
 * OverrideMode 显示名称映射
 */
fun Config.OverrideMode.displayName(): String = when (this) {
    Config.OverrideMode.FOLLOW_SYSTEM -> "保持默认"
    Config.OverrideMode.ZUI           -> "启用 ZUI 实现"
    Config.OverrideMode.AOSP          -> "启用 AOSP 实现"
    Config.OverrideMode.OFF           -> "关闭"
    Config.OverrideMode.BLOCK         -> "忽略"
}

fun Config.OverrideMode.displayDesc(): String = when (this) {
    Config.OverrideMode.FOLLOW_SYSTEM -> "跟随系统开关（开启则用 ZUI，关闭则透传）"
    Config.OverrideMode.ZUI           -> "强制启用 ZUI 快捷键行为"
    Config.OverrideMode.AOSP          -> "禁用 ZUI，启用 Android 原生处理"
    Config.OverrideMode.OFF           -> "禁用 ZUI 和 AOSP，事件透传给应用"
    Config.OverrideMode.BLOCK         -> "消费事件，系统和应用都收不到"
}

fun Config.OverrideMode.isAvailable(meta: ShortcutMeta): Boolean = when (this) {
    Config.OverrideMode.FOLLOW_SYSTEM -> true
    Config.OverrideMode.ZUI           -> meta.hasZui
    Config.OverrideMode.AOSP          -> meta.hasAosp
    Config.OverrideMode.OFF           -> true
    Config.OverrideMode.BLOCK         -> true
}