# BetterZUIKey

> 让联想平板的键盘快捷键听你的，而不是听 ZUI 的。
>
> *Customize Lenovo tablet keyboard shortcuts at the system level.*

[![Android](https://img.shields.io/badge/API-34%2B-green)](https://developer.android.com/about/versions/15) [![Xposed](https://img.shields.io/badge/Xposed-LSPosed-blue)](https://github.com/LSPosed/LSPosed) [![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7f52ff)](https://kotlinlang.org) [![License](https://img.shields.io/badge/License-GPL--3.0-orange)](LICENSE)

---

## 🤔 Why?

联想平板的 ZUI 系统有大量内置键盘快捷键——Win+D 回桌面、Win+Tab 最近任务、Win+P 切换 PC 模式……但 ZUI 不给用户任何自定义选项。有些快捷键你根本不想用，有些你想换成 AOSP 原生行为，有些你想彻底禁用。

BetterZUIKey 是一个 [LSPosed](https://github.com/LSPosed/LSPosed) 模块，直接 hook 进 ZUI 的键盘快捷键处理链，让你在每条快捷键上独立选择行为。本质上就是把 `KeyboardShortcutController` 的 L0~L4 分发架构暴露出来给你控制。

Ciallo～(∠・ω< )⌒★

## ✨ Features

- **50+ 快捷键独立控制** — Win+字母、Win+功能键、Ctrl/Alt/Shift 组合、ZUI 物理键、AOSP 辅助键
- **5 种覆写模式** — 保持默认 / 强制 ZUI / 强制 AOSP / 关闭（透传给应用） / 屏蔽（彻底吞掉）
- **应用模板** — 不同 app 前台时自动切换快捷键配置（比如 QQ 里 Ctrl+Enter 换行而不是发消息）
- **虚拟 Fn 键** — 用多媒体键模拟 F1~F12，支持键盘 profile 导入导出
- **键盘检测工具** — 内置 scanCode 探测器，帮你映射物理键盘的 Fn 区
- **区域适配** — ROW/CN/KR 区域差异行为的独立覆写
- **AOSP 辅助键** — Win+Alt+3~6 的防抖键/鼠标键/粘滞键/慢速键（Settings.Secure 直读，不走系统 UI）
- **OneVision 开关** — 联想跨屏协作快捷键行为控制
- **模块自检** —`ModuleStatus.isLoaded()`，LSPosed 是否真的加载了模块一目了然
- **中英文双语** — 应用内语言切换，配置变更即时生效

## 📐 Architecture

ZUI 的键盘快捷键分发有五层（L0~L4），BetterZUIKey 在每一层都插入了拦截点：

| Layer | Hook Point | Scope |
|-------|-----------|-------|
| **L0** | `KeyboardShortcutController.interceptKeyBeforeQueueing()` | Win+Tab, Win+L, Win+P, Win+Back, Ctrl+Space, Ctrl+Enter, Ctrl+/, Ctrl+Shift+T, FnLock, Win+Alt+3~6 |
| **L1** | `KeyboardShortcutController.interceptKeyBeforeDispatching()` | Win+字母 (S/A/D/I/E/N/M/W/1~8/↑↓), Ctrl+Shift, Alt+Shift |
| **L3** | `PhoneWindowManager.interceptKeyGestureEvent()` | AOSP 原生 gesture（type=1/7/8/12/52/53/201） |
| **L4** | `KeyboardShortcutController.handleKeyGestureEvent()` | ZUI 专属 gesture（type=300/302/305/306/307/308/309/310/311/312） |

每层的分发逻辑：**override mode（右侧 spinner）决定行为，switch（左侧开关）是系统状态的只读投射**——switch 不 gate hook 行为，override mode 才是唯一决策者。

```
Config (SharedPreferences)
    ↕ ContentProvider IPC
system_server (MainHook)
    ├── L0 → L1 → ZUI dispatch → L4
    │                ↕ (PhoneWindowManager)
    │              L3 (AOSP native)
    └── FnKeyManager (virtual Fn + FnLock)
```

## 📦 Installation

0. **前置条件**：已 root + 安装 [LSPosed](https://github.com/LSPosed/LSPosed)、Android 14+ (ZUI 16)
1. 在 [Releases](https://github.com/CommandPrompt-Wang/BetterZUIKey/releases) 下载最新 APK
2. 安装后在 LSPosed Manager 中激活模块（勾选 `system_server` 和 `android`）
3. 重启 system_server（LSPosed 内提供软重启，无需重启设备）
4. 打开应用，Home 页显示 `✅ Active` 即成功

## 🔧 Building

```bash
git clone https://github.com/CommandPrompt-Wang/BetterZUIKey.git
cd BetterZUIKey
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

需要 Android Studio + JDK 17 + Android SDK 34+。模块本身不依赖 LSPosed API 编译——通过反射调用 Xposed 框架，因此依赖项极简。

## 📖 Usage

1. **Shortcuts Tab** — 每条快捷键有一张 card
   - 左侧 switch：系统开关的投射（Settings.System / Settings.Secure）
   - 右侧 spinner：覆写模式
   - 点 card 展开下拉菜单
2. **Templates Tab** — 创建针对特定应用的快捷键模板
3. **Settings Tab** — 总开关、虚拟 Fn、OneVision、外观、日志级别、区域覆写、语言
4. **Keyboard Detect** (Virtual Fn → Create Profile) — 接上键盘，按一个键，自动检测 keyCode/scanCode

### 覆写模式速查

| Mode | 效果 |
|------|------|
| **Keep Default** | 跟随 ZUI 系统开关（开→ZUI，关→透传） |
| **Use ZUI** | 强制执行 ZUI 快捷键行为 |
| **Use AOSP** | 拦截 ZUI，交由 AOSP 原生实现 |
| **Off** | 不拦截，按键直达前台 app |
| **Block** | 吞掉按键，系统和 app 都收不到 |

## ⚠️ 免责

这是一个 Xposed 模块，直接 hook 系统键盘输入处理链。使用前请：
- **完整阅读 Help 文档**（应用内可查看）
- 理解每个选项的含义再操作
- 不当配置可能导致部分快捷键行为异常

开发者不承担因使用本模块造成的系统故障、数据丢失或设备异常的任何责任。Ciallo～(∠・ω< )⌒★

## 🧪 Tech Stack

Java 处理 hook 层（反射密集、Xposed API），Kotlin 处理 UI 层（data binding + RecyclerView + ContentProvider）。

- **Hook 框架**：LSPosed / Xposed (Reflection-based, no compile-time dependency)
- **IPC**：ContentProvider (Binder) — system_server ↔ app 双向通信，绕过 SELinux 文件权限限制
- **配置持久化**：Gson JSON + SharedPreferences + Settings.System 双向同步
- **精确修饰键匹配**：`KeyInjector.modifiersMatch(event, meta, shift, ctrl, alt)` — 替代裸 `isMetaPressed()`，防止 Win+Alt+4 被误路由为 Win+4

整活部分：`ModuleStatus.isLoaded()` — 模块自己 hook 自己来证明加载状态，~~什么叫自举啊~~（bushi）

## 📂 Project Structure

```
app/src/main/java/moe/lovefirefly/betterzuikey/
├── Hook/                    # Xposed 拦截层 (system_server)
│   ├── MainHook.java        # 入口 + 初始化
│   ├── L0Interceptor.java   # pre-queueing
│   ├── L1Interceptor.java   # pre-dispatching
│   ├── L3Interceptor.java   # AOSP gesture
│   ├── L4Interceptor.java   # ZUI gesture
│   ├── FnKeyManager.java    # 虚拟 Fn + FnLock
│   ├── KeyInjector.java     # 按键注入 + 修饰键匹配
│   ├── HookContext.java     # 共享状态 + 配置热重载
│   ├── ConfigIPCManager.java # IPC 通信
│   └── ForegroundTracker.java
├── Config/
│   ├── Config.java          # 主配置 (50+ 字段)
│   ├── ConfigResolver.java  # 模板解析
│   ├── KeyTemplate.java     # 应用模板数据结构
│   └── PerKeyOverride.java
├── Region/
│   ├── RegionHook.java      # ro.config.lgsi.region 覆写
│   └── FeatureHook.java     # AI 代理 / 区域行为
├── TabsFragments.kt         # Global / Settings / Templates tabs
├── ShortcutMeta.kt          # 48 条快捷键的元数据 DSL
├── ModuleStatus.kt          # 模块自检探针
└── ...                      # Activities, Utils, etc.
```

## 📄 License

GPL-3.0 © 2025–2026 [CommandPrompt-Wang](https://github.com/CommandPrompt-Wang)

> *飞萤之火自无梦的长夜亮起* 🔥
