package moe.lovefirefly.betterzuikey

import android.content.Context
import android.view.KeyEvent

/**
 * Win 单按的「映射到…」目标 —— 一个按键加上可选的 Ctrl / Shift / Alt 修饰。
 *
 * <p>与输入法增强的 `remapTo`（形如 `"Ctrl+Space"` 的字符串）不同，这里要支持**任意**
 * 按键（F1~F12、方向键、多媒体键…），名字化的字符串会有歧义、也无法完整往返，
 * 所以直接用键码存：`"keyCode:shift:ctrl:alt"`。
 *
 * <p>不支持把 Meta 自己作为修饰键：它就是触发键，再映射回 Win 类组合既无意义、
 * 又可能被模块自己的 Meta 分支重新拦截。
 */
data class MetaKeyMap(
    val keyCode: Int,
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
) {
    /** keyCode == 0 表示「未设置」。 */
    val isSet: Boolean get() = keyCode > 0

    val hasModifier: Boolean get() = shift || ctrl || alt

    /** 序列化成 Config.metaSingleMap。 */
    fun serialize(): String = if (!isSet) "" else buildString {
        append(keyCode)
        append(':').append(if (shift) 1 else 0)
        append(':').append(if (ctrl) 1 else 0)
        append(':').append(if (alt) 1 else 0)
    }

    /** 人类可读，如 `Ctrl+Shift+A`、`PageDown`。 */
    fun displayName(): String {
        if (!isSet) return ""
        return buildString {
            if (ctrl) append("Ctrl+")
            if (shift) append("Shift+")
            if (alt) append("Alt+")
            append(keyName(keyCode))
        }
    }

    /**
     * 下拉里那一项的文案：`映射到…（Ctrl+Shift+A）`；
     * 未设置时为 `映射到…（未设置）`。
     */
    fun optionLabel(context: Context): String = if (isSet) {
        context.getString(R.string.mode_meta_single_map_set, displayName())
    } else {
        context.getString(R.string.mode_meta_single_map_unset)
    }

    companion object {
        val UNSET = MetaKeyMap(0)

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
                    shift = f[1].trim() != "0",
                    ctrl = f.size > 2 && f[2].trim() != "0",
                    alt = f.size > 3 && f[3].trim() != "0",
                )
            } catch (t: Throwable) {
                UNSET
            }
        }

        /** 修饰键本身不能单独作为映射目标。 */
        @JvmStatic
        fun isModifierKey(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_CAPS_LOCK,
            KeyEvent.KEYCODE_SCROLL_LOCK, KeyEvent.KEYCODE_FUNCTION,
            -> true
            else -> false
        }

        /**
         * 友好的键名：**能打出字符的键就显示那个字符**（`.` 而不是 `PERIOD`），
         * 其余用 Android 的键码名去掉 `KEYCODE_` 前缀并转成 `SomeName`。
         */
        fun keyName(keyCode: Int): String {
            when (keyCode) {
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
