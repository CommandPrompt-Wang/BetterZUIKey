package moe.lovefirefly.betterzuikey.ime

import android.os.SystemClock
import android.view.KeyEvent

/**
 * 内置通用适配器 — 注入 Ctrl+Space。
 *
 * 这是最通用的输入法内部切换方式：
 *   - GBoard / 三星键盘 / SwiftKey 等 ROW 输入法普遍认 Ctrl+Space
 *   - 中文输入法（搜狗、百度）大部分也认 Ctrl+Shift 或 Shift，
 *     但 Ctrl+Space 不冲突，作为安全 fallback
 *
 * 行为等同于 [Ctrl+Shift remap(ctrlShiftRemapEnabled)],
 * 但以适配器形式提供，用户可扩展。
 */
class GenericIMEAdapter : IMEAdapter {

    override val imePackageName: String = "*"

    override val displayName: String = "Generic (Ctrl+Space)"

    override fun onShortcutTriggered(): List<KeyEvent> {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_SPACE,
            0,
            KeyEvent.META_CTRL_MASK,
            0, 0, 0
        )
        val up = KeyEvent(
            now, now + 50,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_SPACE,
            0,
            KeyEvent.META_CTRL_MASK,
            0, 0, 0
        )
        return listOf(down, up)
    }
}
