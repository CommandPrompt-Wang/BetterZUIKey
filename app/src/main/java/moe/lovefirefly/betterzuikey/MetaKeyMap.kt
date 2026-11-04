package moe.lovefirefly.betterzuikey

import android.content.Context
import android.view.KeyEvent

/**
 * 一个修饰键按在**哪一侧**。
 *
 * <p>`ANY` 只用于兼容旧配置：老格式用 0/1 表示修饰位，1 只知道「按了」，
 * 分不清左右。显示上不编造侧别，注入时按历史行为用左侧。
 */
enum class KeySide(val left: Boolean, val right: Boolean, val known: Boolean) {
    NONE(false, false, true),
    LEFT(true, false, true),
    RIGHT(false, true, true),
    /** 两侧同时按住（物理上同时压住左右 Shift 之类）。 */
    BOTH(true, true, true),
    /** 旧配置遗留：知道按了、不知道哪一侧。 */
    ANY(false, false, false);

    /** 是否按下了（`NONE` 之外都算）。 */
    val isOn: Boolean get() = this != NONE

    /** 序列化用的记号（见 [MetaKeyMap.serialize]）。 */
    internal val token: String
        get() = when (this) {
            NONE -> "0"
            LEFT -> "L"
            RIGHT -> "R"
            BOTH -> "B"
            ANY -> "1"
        }

    companion object {
        /** 解析 [token]；也接受旧式 `0`/`1` 与中间格式的数字写法。 */
        @JvmStatic
        fun parse(raw: String?): KeySide = when (raw?.trim()?.uppercase()) {
            null, "", "0", "N", "NONE" -> NONE
            "L", "LEFT" -> LEFT
            "R", "RIGHT", "2" -> RIGHT
            "B", "BOTH", "LR", "3" -> BOTH
            "1", "ANY" -> ANY
            else -> ANY   // 认得「按了」但读不懂侧别时，宁可当旧值处理
        }

        /**
         * 从 metaState 里取出某个修饰键的按下情况。
         *
         * <p>注意 Android 的位分三层：`META_CTRL_ON`（按了）与 `META_CTRL_LEFT_ON` /
         * `META_CTRL_RIGHT_ON`（哪一侧）是**各自独立**的位。真机事件通常三层都有；
         * 只有 ON 位没有侧位时（旧配置、合成事件）只知道按了，记作 [ANY]，
         * 不编造左右。
         */
        @JvmStatic
        fun of(metaState: Int, onBit: Int, leftBit: Int, rightBit: Int): KeySide {
            val l = (metaState and leftBit) != 0
            val r = (metaState and rightBit) != 0
            return when {
                l && r -> BOTH
                l -> LEFT
                r -> RIGHT
                (metaState and onBit) != 0 -> ANY
                else -> NONE
            }
        }
    }
}

/**
 * Win 单按的「映射到…」目标 —— 一个按键加上可选的 Ctrl / Shift / Alt 修饰。
 *
 * <p>与输入法增强的 `remapTo`（形如 `"Ctrl+Space"` 的字符串）不同，这里要支持**任意**
 * 按键（F1~F12、方向键、多媒体键…），名字化的字符串会有歧义、也无法完整往返，
 * 所以直接用键码存：`"keyCode:shift:ctrl:alt:scanCode"`。
 *
 * <p>修饰键保留**左右**（`Alt(R)+Shift(R)+X`）：同一组组合键在不少应用里左右含义不同，
 * 而且右侧修饰键的键码与 metaState 位都不一样，压成布尔就再也还原不回去了。
 *
 * <p>不支持把 Meta 自己作为修饰键：它就是触发键，再映射回 Win 类组合既无意义、
 * 又可能被模块自己的 Meta 分支重新拦截。
 */
data class MetaKeyMap(
    val keyCode: Int,
    val shift: KeySide = KeySide.NONE,
    val ctrl: KeySide = KeySide.NONE,
    val alt: KeySide = KeySide.NONE,
    /**
     * 原始扫描码；普通按键为 0（不参与回放）。
     *
     * <p>ZUI 的顶行虚拟键（500/501/503/504/507…）重新注入时必须带上它自己那套
     * scanCode，否则 ZUI 认不出来 —— 见 FnKeyManager 里那句
     * “re-injected event loses scanCode → ZUI custom keys break”。
     */
    val scanCode: Int = 0,
) {
    /** keyCode == 0 表示「未设置」。 */
    val isSet: Boolean get() = keyCode > 0

    val hasModifier: Boolean get() = shift.isOn || ctrl.isOn || alt.isOn

    /** 序列化成 Config.metaSingleMap。 */
    fun serialize(): String = if (!isSet) "" else buildString {
        append(keyCode)
        append(':').append(shift.token)
        append(':').append(ctrl.token)
        append(':').append(alt.token)
        append(':').append(scanCode)
    }

    /** 人类可读，如 `Ctrl(L)+Shift(R)+A`、`PageDown`。 */
    fun displayName(): String {
        if (!isSet) return ""
        return buildString {
            if (ctrl.isOn) append(modifierName("Ctrl", ctrl))
            if (shift.isOn) append(modifierName("Shift", shift))
            if (alt.isOn) append(modifierName("Alt", alt))
            append(keyName(keyCode))
        }
    }

    /**
     * 下拉里那一项的文案：`映射到…（Ctrl(L)+Shift(R)+A）`；
     * 未设置时为 `映射到…（未设置）`。
     */
    fun optionLabel(context: Context): String = if (isSet) {
        context.getString(R.string.mode_meta_single_map_set, displayName())
    } else {
        context.getString(R.string.mode_meta_single_map_unset)
    }

    /**
     * 展开成待注入的 `(keyCode, metaState 位)` 序列。
     *
     * <p>顺序固定为 **Ctrl → Shift → Alt，每个先左后右**；注入方按下正序、抬起逆序。
     * 放在这里而不是注入端，是因为「哪一侧该发哪个键码、置哪一位」属于这张映射
     * 自己的语义，也便于纯逻辑测试。
     *
     * <p>[KeySide.BOTH] 展开成左右两个键（物理上确实是两个键一起按着）；
     * [KeySide.ANY]（旧配置，侧别未知）按历史行为当左侧。
     */
    fun modifierKeySequence(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(6)
        appendModifier(out, ctrl,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.META_CTRL_LEFT_ON, KeyEvent.META_CTRL_RIGHT_ON)
        appendModifier(out, shift,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.META_SHIFT_LEFT_ON, KeyEvent.META_SHIFT_RIGHT_ON)
        appendModifier(out, alt,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.META_ALT_LEFT_ON, KeyEvent.META_ALT_RIGHT_ON)
        return out
    }

    companion object {
        val UNSET = MetaKeyMap(0)

        private fun appendModifier(
            out: MutableList<Pair<Int, Int>>,
            side: KeySide,
            leftKeyCode: Int, rightKeyCode: Int,
            leftBit: Int, rightBit: Int,
        ) {
            if (side.left || side == KeySide.ANY) out.add(leftKeyCode to leftBit)
            if (side.right) out.add(rightKeyCode to rightBit)
        }

        /** `Ctrl(L)+` / `Ctrl(R)+` / `Ctrl(LR)+`；旧值（侧别未知）只写 `Ctrl+`。 */
        private fun modifierName(name: String, side: KeySide): String = when (side) {
            KeySide.LEFT -> "$name(L)+"
            KeySide.RIGHT -> "$name(R)+"
            KeySide.BOTH -> "$name(LR)+"
            else -> "$name+"
        }

        /**
         * 从一次按键事件构造映射目标。
         *
         * <p>[keyCode] 自己若是修饰键，要把**它自己那两位**从 [metaState] 里剔掉：
         * 单按 Ctrl 时事件的 metaState 就带着 Ctrl 自己那一位（Ctrl 键把自己那位置上了），
         * 不剔就会变成「Ctrl+Ctrl」这种荒唐显示，语义上也不对 ——
         * 它自己现在是主键，不再是修饰键。
         */
        @JvmStatic
        fun of(keyCode: Int, metaState: Int, scanCode: Int = 0): MetaKeyMap {
            val selfBits = when (keyCode) {
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_MASK
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_MASK
                KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_MASK
                else -> 0
            }
            val meta = metaState and selfBits.inv()
            return MetaKeyMap(
                keyCode = keyCode,
                shift = KeySide.of(meta, KeyEvent.META_SHIFT_ON,
                    KeyEvent.META_SHIFT_LEFT_ON, KeyEvent.META_SHIFT_RIGHT_ON),
                ctrl = KeySide.of(meta, KeyEvent.META_CTRL_ON,
                    KeyEvent.META_CTRL_LEFT_ON, KeyEvent.META_CTRL_RIGHT_ON),
                alt = KeySide.of(meta, KeyEvent.META_ALT_ON,
                    KeyEvent.META_ALT_LEFT_ON, KeyEvent.META_ALT_RIGHT_ON),
                scanCode = scanCode.coerceAtLeast(0),
            )
        }

        /** 解析 `serialize()` 的产物；任何异常/残缺一律回落到「未设置」。 */
        @JvmStatic
        fun parse(raw: String?): MetaKeyMap {
            if (raw.isNullOrBlank()) return UNSET
            val f = raw.split(':')
            if (f.size < 2) return UNSET
            return try {
                val kc = f[0].trim().toInt()
                if (kc <= 0) return UNSET
                MetaKeyMap(
                    keyCode = kc,
                    shift = KeySide.parse(f[1]),
                    ctrl = if (f.size > 2) KeySide.parse(f[2]) else KeySide.NONE,
                    alt = if (f.size > 3) KeySide.parse(f[3]) else KeySide.NONE,
                    // 第 5 段是 scanCode；老格式只有 4 段，缺省 0
                    scanCode = if (f.size > 4) f[4].trim().toIntOrNull() ?: 0 else 0,
                )
            } catch (t: Throwable) {
                UNSET
            }
        }

        /**
         * 友好的键名：**能打出字符的键就显示那个字符**（`.` 而不是 `PERIOD`），
         * 其余用 Android 的键码名去掉 `KEYCODE_` 前缀并转成 `SomeName`。
         */
        fun keyName(keyCode: Int): String {
            when (keyCode) {
                // 修饰键：单独映射成它们时别显示成 CtrlLeft / MetaRight
                KeyEvent.KEYCODE_CTRL_LEFT -> return "Ctrl(L)"
                KeyEvent.KEYCODE_CTRL_RIGHT -> return "Ctrl(R)"
                KeyEvent.KEYCODE_SHIFT_LEFT -> return "Shift(L)"
                KeyEvent.KEYCODE_SHIFT_RIGHT -> return "Shift(R)"
                KeyEvent.KEYCODE_ALT_LEFT -> return "Alt(L)"
                KeyEvent.KEYCODE_ALT_RIGHT -> return "Alt(R)"
                KeyEvent.KEYCODE_META_LEFT -> return "Win(L)"
                KeyEvent.KEYCODE_META_RIGHT -> return "Win(R)"
                KeyEvent.KEYCODE_BRIGHTNESS_UP -> return "Brightness+"
                KeyEvent.KEYCODE_BRIGHTNESS_DOWN -> return "Brightness-"
                KeyEvent.KEYCODE_SPACE -> return "Space"
                KeyEvent.KEYCODE_PERIOD -> return "."
                KeyEvent.KEYCODE_COMMA -> return ","
                KeyEvent.KEYCODE_SEMICOLON -> return ";"
                KeyEvent.KEYCODE_APOSTROPHE -> return "'"
                KeyEvent.KEYCODE_SLASH -> return "/"
                KeyEvent.KEYCODE_BACKSLASH -> return "\\"
                KeyEvent.KEYCODE_LEFT_BRACKET -> return "["
                KeyEvent.KEYCODE_RIGHT_BRACKET -> return "]"
                KeyEvent.KEYCODE_MINUS -> return "-"
                KeyEvent.KEYCODE_EQUALS -> return "="
                KeyEvent.KEYCODE_GRAVE -> return "`"
                KeyEvent.KEYCODE_ENTER -> return "Enter"
                KeyEvent.KEYCODE_TAB -> return "Tab"
                KeyEvent.KEYCODE_ESCAPE -> return "Esc"
                KeyEvent.KEYCODE_DEL -> return "Backspace"
                KeyEvent.KEYCODE_FORWARD_DEL -> return "Delete"
                KeyEvent.KEYCODE_DPAD_LEFT -> return "←"
                KeyEvent.KEYCODE_DPAD_RIGHT -> return "→"
                KeyEvent.KEYCODE_DPAD_UP -> return "↑"
                KeyEvent.KEYCODE_DPAD_DOWN -> return "↓"
                KeyEvent.KEYCODE_PAGE_UP -> return "PageUp"
                KeyEvent.KEYCODE_PAGE_DOWN -> return "PageDown"
                KeyEvent.KEYCODE_MOVE_HOME -> return "Home"
                KeyEvent.KEYCODE_MOVE_END -> return "End"
                KeyEvent.KEYCODE_INSERT -> return "Insert"
                KeyEvent.KEYCODE_BACK -> return "Back"
                KeyEvent.KEYCODE_MENU -> return "Menu"
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> return "Play/Pause"
                KeyEvent.KEYCODE_MEDIA_NEXT -> return "Next"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return "Previous"
                KeyEvent.KEYCODE_VOLUME_UP -> return "VolumeUp"
                KeyEvent.KEYCODE_VOLUME_DOWN -> return "VolumeDown"
                KeyEvent.KEYCODE_VOLUME_MUTE -> return "Mute"
                else -> Unit
            }
            if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                return (keyCode - KeyEvent.KEYCODE_0).toString()
            }
            return try {
                var n = KeyEvent.keyCodeToString(keyCode)
                if (n.startsWith("KEYCODE_")) n = n.substring("KEYCODE_".length)
                val sb = StringBuilder()
                for (part in n.split('_')) {
                    if (part.isEmpty()) continue
                    sb.append(part[0].uppercaseChar()).append(part.substring(1).lowercase())
                }
                if (sb.isEmpty()) "Key$keyCode" else sb.toString()
            } catch (t: Throwable) {
                "Key$keyCode"
            }
        }
    }
}
