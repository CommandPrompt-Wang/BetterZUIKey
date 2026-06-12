package moe.lovefirefly.betterzuikey

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityHelpBinding

class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.tvHelp.text = buildHelpText()
    }

    private fun buildHelpText(): String {
        return """
BetterZUIKey 使用帮助
====================

概述
----
BetterZUIKey 是一个 Xposed 模块，用于接管和自定义 ZUI/ZUXOS 系统中的
键盘快捷键行为。通过本模块，你可以：
  • 禁用不想要的快捷键
  • 将快捷键行为改为 AOSP 原生方式
  • 为特定应用创建自定义快捷键模板
  • 虚拟 Fn 键（Win 模拟 Fn → F1~F12）
  • 区域差异化行为覆写（ROW/中国/韩国）
  • AOSP 辅助功能开关（防抖/鼠标/粘滞/慢速键）


行为类型说明
-----------

ZUI 默认
  保持 ZUI 系统的原始行为，不做任何修改。

忽略 (Block)
  消费该快捷键事件但不执行任何操作。按键被"吞掉"，
  系统和前台应用都不会收到。

关闭 (Pass Through)
  完全禁用该快捷键拦截，事件直接透传给前台应用处理。
  适合需要应用自身响应该组合键的场景。

AOSP 原生
  将快捷键行为委托给 Android 原生处理。注意：并非所有
  快捷键都有对应的 AOSP 实现，若无对应处理则等同于透传。

自定义 (Custom)
  执行用户自定义的操作（由模板配置）。

打开设置
  触发该快捷键时打开系统或应用设置页面。


快捷键参考
----------

Win + D          回到桌面
Win + S          全局搜索
Win + A          隐藏/显示任务栏
Win + E          打开文件管理器
Win + I          打开设置
Win + L          锁屏
Win + M          最小化窗口
Win + N          通知面板
Win + P          切换 PC 模式
Win + W          关闭前台应用
Win + 1~8        打开 Dock 栏对应应用
Win + Tab        最近任务
Win + ↑          窗口最大化
Win + ↓          窗口还原/退回桌面
Win + ←          分屏到左侧
Win + →          分屏到右侧

Ctrl + /         弹出快捷键菜单
Ctrl + Shift      切换输入法（仅 ROW）
Alt + Shift       切换语言（仅 ROW）
Alt + Tab         最近任务切换

Print Screen 短按  区域截图
Print Screen 长按  全屏截图
Meta 单按          开始菜单


物理按键（仅 ZUI 键盘）
---------------------
501 静音键        静音切换
502 触控板键      触控板开关
504 分屏键        分屏开关
505 超级互联      启动超级互联
507 App1          自定义按键1
508 App2          自定义按键2
509 搜索键        全局搜索
510 设置键        打开设置
511 Fn 锁         Fn 锁定切换
512 背光键        键盘背光循环
514 触控板上移    通知面板
515 锁屏键        锁屏


设置说明
--------

模块总开关
  全局总开关，关闭后整个模块的所有功能失效，
  所有快捷键恢复 ZUI 原始行为，等同于未安装模块。
  注意：切换后需要重启系统界面或重启设备才能完全生效。

虚拟 Fn 键
  对于没有物理 Fn 键的键盘，本模块通过软件模拟 Fn 键功能，
  将键盘顶行按键映射为 F1~F12。

  两种触发模式：
    FnLock 模式：按下 Win + `（Tab 上方重音符键）切换 Fn 锁定状态。
      锁定后，单按顶行键（不需要按 Win）直接输出 F1~F12。
      Win+` 被完全拦截，不会触发其他功能。
      再次按下 Win+` 可关闭 Fn 锁定。

    临时 Fn 模式：FnLock 未开启时，
      按住 Win 再按顶行键，临时输出 F1~F12，
      松开 Win 后顶行键恢复原始功能。

  例如（Lenovo Yoga Keyboard 顶行默认映射）：
    ESC       → F1
    静音       → F2
    音量-      → F3
    音量+      → F4
    闭麦       → F5
    亮度-      → F6
    亮度+      → F7
    截图       → F8
    最大化     → F9
    分屏       → F10
    星星(自定义)→ F11
    删除       → F12

  FnLock（Fn 锁定）：
    按下 Win + `（重音符键）切换 Fn 锁定状态。
    • 锁定开启：顶行键直接输出 F1~F12
    • 锁定关闭：顶行键恢复原始功能（仍可通过 Win 临时触发）
    Win+` 被完全拦截，不会产生任何副作用。

  键盘配置文件：
    不同键盘的物理按键扫描码不同，模块内置了默认的
    Lenovo Yoga Keyboard 映射表。对于其他键盘型号，
    可以通过导入 JSON 配置文件来定义自定义的扫描码映射。

  如何配置：
    1. 进入"虚拟 Fn 键"设置页面
    2. 选择或导入匹配的键盘配置文件
    3. 可通过"配置文件管理"查看、导入、删除配置

OneVision 特性
  联想 OneVision（超级互联）是一套跨屏协同解决方案。
  此开关控制 ZUI 中与 OneVision 相关的快捷键行为是否被模块
  接管和覆写。关闭后这些快捷键恢复 ZUI 原生处理。


匹配系统主题色
  开启时，App 自动跟随系统的深色模式设置切换主题；
  关闭时强制使用亮色主题，不受系统深色模式影响。
  更改后需重启 App 生效。

辅助功能开关
  管理 Win+Alt+3~6 四个 AOSP 原生辅助功能的启用/禁用：
    3 = 防抖键 (Bounce Keys)
        忽略过快的连续击键，防止误触重复输入
    4 = 鼠标键 (Mouse Keys)
        用数字小键盘区域控制鼠标指针移动和点击
    5 = 粘滞键 (Sticky Keys)
        组合键（如 Ctrl+C）可以逐一按下而非同时按住
    6 = 慢速键 (Slow Keys)
        按键必须按住一定时间后才被识别，过滤意外轻触
  注意：这些是 Android 系统级辅助功能，模块仅控制其在
  ZUI 下的启用状态，具体行为由系统设置决定。

日志级别
  控制 Xposed 模块运行时日志输出的详细程度：
    INFO   — 仅输出关键事件（启动、配置变更、错误）
    DEBUG  — 包含详细调试信息（推荐排查问题时使用）
    ERROR  — 仅输出错误信息（最安静）
  日志可通过 LSPosed 管理器或 logcat 查看。

区域覆写
  ZUI 系统根据两个属性决定快捷键的差异化行为：
    ro.config.lgsi.region      — 判定中国/海外 (ROW)
    ro.config.lgsi.countrycode — 判定韩国 (KR)
  模块将区域和国家/地区拆分为两个独立设置：

  区域 (region)
    默认      — 不干预，使用设备原始值
    中国(CN)  — 强制中国区，Meta 按住连续切语言
    国际(ROW) — 强制海外区，Ctrl+Shift/Alt+Shift 切输入法
    自定义    — 输入任意区域代码（如 "TW"、"JP"）

  国家/地区 (countrycode)
    默认      — 不干预，使用设备原始值
    韩国(KR)  — 强制韩国区，启用 Alt_RIGHT 切韩语
    自定义    — 输入任意国家代码

  注意：修改区域可能导致部分快捷键行为异常，请确认理解
  各区域差异后再修改。


模板说明
--------

应用模板允许你为特定前台应用覆写快捷键行为。
模板中未列出的快捷键将继承全局"快捷键"Tab 中的设置。

使用场景举例：
  • 在游戏中禁用 Win 键，防止误触跳出
  • 在视频播放器中屏蔽 Print Screen 截屏快捷键
  • 在特定应用中自定义 App1/App2 按键行为

创建模板后可以：
  1. 绑定目标应用（支持多选，可匹配多个应用）
  2. 覆写任意快捷键的行为（开关+动作选择）
  3. 重命名模板
  4. 删除不再需要的模板


⚠ 注意事项
----------

1. 本模块需要 Xposed 框架（LSPosed 等）激活才能生效，
   请在 LSPosed 管理器中勾选本模块并选择「系统框架」作用域。
   （注意：是"系统框架"而非"Android 系统"）

2. 修改快捷键行为可能导致系统功能异常或快捷键冲突，
   请务必阅读完本文档后再进行修改。

3. 部分快捷键在锁屏状态或特定场景下无效，
   这是 ZUI 系统的底层限制，非模块问题。

4. 区域覆写仅对 ZUI/ZUXOS 系统有效。

5. 虚拟 Fn 键通过 Win+` 切换 Fn 锁定；锁定后顶行键直接
   输出 F1~F12（无需按 Win）。FnLock 关闭时也可通过 Win
   临时触发。该功能不需要键盘硬件支持物理 Fn 键。

6. OneVision 特性需要设备硬件和系统版本支持，
   部分旧款设备或海外版可能不支持。

7. 如果修改后键盘行为异常，请先将模块总开关关闭，
   重启设备后恢复原始行为，再逐项排查问题配置。


版本: BetterZUIKey v1.0
项目地址: https://github.com/CommandPrompt-Wang/BetterZUIKey


附录 A — 编写虚拟 Fn 键盘配置文件
================================

配置文件的 JSON 结构如下：

{
  "profiles": {
    "<vendor:product>": {
      "name": "键盘名称（内部标识）",
      "friendlyName": "显示名称（用户可见）",
      "keys": {
        "F1":  { "scan": <扫描码> },
        "F2":  { "keyCode": <Android keyCode> },
        ...
        "F12": { "scan": <扫描码> }
      }
    }
  }
}

示例（联想小新Pad Pro GT 磁吸键盘，17ef:6271）：

{
  "profiles": {
    "17ef:6271": {
      "name": "Lenovo Keyboard Pack For Yoga Keyboard",
      "friendlyName": "联想小新平板Pro GT 磁吸键盘",
      "keys": {
        "F1":  { "keyCode": 1 },
        "F2":  { "keyCode": 501 },
        "F3":  { "keyCode": 25 },
        "F4":  { "keyCode": 24 },
        "F5":  { "keyCode": 91 },
        "F6":  { "keyCode": 220 },
        "F7":  { "keyCode": 221 },
        "F8":  { "keyCode": 120 },
        "F9":  { "keyCode": 500 },
        "F10": { "keyCode": 504 },
        "F11": { "keyCode": 507 },
        "F12": { "keyCode": 112 }
      }
    }
  }
}

字段说明：
  <vendor:product> — 键盘的 USB VID:PID（十六进制小写），
    可通过"虚拟 Fn 键 → 获取键盘VID:PID"自动检测。

  keys — F1~F12 对应的物理按键标识，使用 "keyCode"：
    值为 Android KeyEvent keyCode（非 Linux 键码）。
    键盘检测页面显示的「按键代码」即为正确值。

  当 Fn 映射触发时，模块会注入对应的 F-key
  （F1=131, F2=132, ..., F12=142）。

附录 B — 获取键盘 VID:PID
=======================

使用「键盘检测」页面（虚拟 Fn 键 → 获取键盘VID:PID）：
  1. 按下键盘任意按键，自动检测 VID:PID 和设备名称
  2. 页面同时提供：
       • 「复制 VID:PID」— 复制厂商:产品 ID
       • 「复制配置模板」— 复制含 F1~F12 占位符的 JSON 模板
       • 实时显示当前按键的 Android keyCode + 扫描码
       • 「复制 keyCode」— 一键复制当前按键的映射值
  3. 逐一按下顶行 12 个按键，记录每个的 keyCode
  4. 将 keyCode 填入配置文件模板对应位置


附录 C — 获取按键的 Android keyCode
=====================================

推荐方式：App 内置键盘检测页面
  1. 进入「虚拟 Fn 键 → 获取键盘VID:PID」
  2. 按下键盘任意按键，App 自动检测 VID:PID
  3. 每次按键时，页面实时显示：
       按键代码: 501 (KEYCODE_LENOVO_VOLUME_MUTE)
       扫描码: 0x71
     「按键代码」就是填入配置文件的 keyCode 值
  4. 点击「复制 keyCode」一键复制
  5. 逐一按下顶行 12 个按键，记录每个的 keyCode 值
  6. 填入 JSON 配置文件对应位置

  此方式获取的是 Android 框架实际看到的 keyCode，
  已包含 ZUI 厂商 .kl 布局文件的重映射。

备选方式：LSPosed 日志
  如果键盘检测页面不可用，可通过 LSPosed 日志获取：
  1. 确保模块已激活且日志级别为 INFO
  2. 打开 LSPosed 管理器 → BetterZUIKey → 日志
  3. 按下目标按键，查找类似输出：
       L0: ALL KEYS keyCode= 501  scanCode= 113
     keyCode= 后的数字即为 Android keyCode


附录 D — 区域覆写的系统属性说明
==============================

模块通过 Hook SystemProperties.get() 覆写以下属性：

  ro.config.lgsi.region      — 区域代码
  ro.config.lgsi.countrycode — 国家代码

ZUI 的判定逻辑：
  • isRowProduct:  !isEmpty(value) && !value.equals("CN")
    非空且不等于 "CN" → ROW（海外）模式
  • isChinaProduct: value 为空 或 等于 "CN"
    空或 "CN" → 中国模式
  • isKrProduct: ro.config.lgsi.countrycode.toUpperCase() = "KR"
    等于 "KR" → 韩国模式

不同区域的行为差异详见"区域覆写"设置说明。
        """.trimIndent()
    }
}
