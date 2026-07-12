# BetterZUIKey 1.5.0-beta1

[Please scroll down for English / 英语请向下滚动](#eng)

## 摘要

本次更新修复了更多遗留的问题。

## Bug 修复

- **修复 Fn 映射失效**：修复了一个导致 App1/2 （智能键）映射失效的反射问题。
- **命令执行重构**：集成 [TermuxAm](https://github.com/CommandPrompt-Wang/TermuxAm) 作为子模块。现在非 root 模式下，脚本的 `am` 将被 `termuxam` 替代。
  - 这解决了非 root 模式下 `am` 因权限不足（134 SIGABRT）执行失败的问题。

## 其他

- **警告抑制**：消除了部分编译器警告，包括不得不使用的过时 API。
- **日志级别调整**：`MetaTrace`、`PassthroughTrace` 的追踪日志从 INFO 降为 DEBUG。

---

<div id="eng"></div>

# BetterZUIKey 1.5.0-beta1

# Highlights

This update fixes more legacy issues.

## Bug Fixes

- **Fixed broken Fn key mapping**: Fixed a reflection issue that caused App1/2 (smart key) mapping to fail.
- **Command Executor Overhaul**: Integrated [TermuxAm](https://github.com/CommandPrompt-Wang/TermuxAm) as a Git submodule. In non-root mode, script `am` is now replaced by `termuxam`.
  - This fixes "am" exit code 134 (SIGABRT) due to insufficient permissions in non-root mode.

## Other

- **Warnings suppressed**: Eliminated some compiler warnings, including unavoidable deprecated API usage.
- **Log level adjustments**: Downgraded MetaTrace/PassthroughTrace logs from INFO to DEBUG.
