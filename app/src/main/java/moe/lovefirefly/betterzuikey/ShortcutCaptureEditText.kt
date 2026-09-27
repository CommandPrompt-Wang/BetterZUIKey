package moe.lovefirefly.betterzuikey

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatEditText

/**
 * 只用来「录键」的输入框：不接收文本，把硬件按键原样交给 [captureListener]。
 *
 * <p>为什么要专门派生一个 View 而不是用 {@code setOnKeyListener}：
 * 对话框里的焦点可能在按钮上、软键盘也可能插一脚，{@code onKeyDown} 是
 * 硬件按键最可靠的一层，且能顺手把**修饰键自身**也吞掉（否则 Shift 会被
 * 输入法/选区逻辑当成文本选择）。
 */
class ShortcutCaptureEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {

    /** 返回 true = 该键已被认领（不再往下传）。 */
    var captureListener: ((event: KeyEvent) -> Boolean)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isCursorVisible = false
        isSingleLine = true
        // 放最后：setKeyListener(null) 会把 inputType 置为 TYPE_NULL，
        // 必须在这些属性设置之后执行，否则可能被重新装回键盘监听。
        setKeyListener(null)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        captureListener?.let { if (it(event)) return true }
        // 修饰键自己不算键：吞掉，别让它触发文本选择之类
        if (captureListener != null && MetaKeyMap.isModifierKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        captureListener?.let { if (it(event)) return true }
        if (captureListener != null && MetaKeyMap.isModifierKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }
}
