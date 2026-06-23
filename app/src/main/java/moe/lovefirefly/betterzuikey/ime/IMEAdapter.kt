package moe.lovefirefly.betterzuikey.ime

import android.view.KeyEvent

/**
 * IME 内部语言切换适配器接口。
 *
 * 外部 .dex/.jar 适配器实现此接口，通过 [AdapterManager] 加载。
 * 默认实现 [GenericIMEAdapter] 注入 Ctrl+Space（通用于 GBoard 等主流输入法）。
 *
 * ## 为什么要适配器？
 *
 * Android InputMethodSubtype 体系设计上支持输入法内语言切换，
 * 但实际各输入法实现不统一 — GBoard 认 Ctrl+Space，搜狗认 Ctrl+Shift，
 * 百度认 Shift，SwiftKey 用手势 — 没有一个通用 API。
 * 适配器把"特定输入法的切换方式"封装成一个实现。
 *
 * ## 约定
 *
 * 1. 类必须有公开无参构造函数
 * 2. 类名必须与文件名一致
 * 3. 必须直接实现 [IMEAdapter] 接口（不能通过父类间接实现）
 *    因为 [AdapterManager] 通过 [Class.isAssignableFrom] 检测
 */
interface IMEAdapter {
    /** 目标输入法包名，如 "com.google.android.inputmethod.latin" */
    val imePackageName: String

    /** 用户可读名称，如 "GBoard Adapter v1.0" */
    val displayName: String

    /**
     * 当绑定的快捷键触发且 IME 处于接受文本输入状态时调用。
     *
     * @return 要注入到 IME 的 KeyEvent 列表（DOWN/UP 成对），
     *         例如 [Ctrl+Space DOWN, Ctrl+Space UP]。
     *         返回空列表表示不执行任何操作。
     */
    fun onShortcutTriggered(): List<KeyEvent>
}
