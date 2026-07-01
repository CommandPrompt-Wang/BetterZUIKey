package moe.lovefirefly.betterzuikey.Hook;

import moe.lovefirefly.betterzuikey.Hook.HookCompat;

import moe.lovefirefly.betterzuikey.Config.Config;

/**
 * ZUI 专用物理键 (keyCode 501–521) 拦截 — 已禁用。
 *
 * <p>此前在 L0/L1/L4 分散处理静音、设置、超级互联、键盘翻转等键；A15/A16 上行为仍不稳定
 * （重复触发、与 ZUI 原生路径冲突、scanCode 被覆盖等），已从 Hook 层与 UI 移除 wiring。
 * {@link Config} 中对应字段仍保留，便于旧配置文件迁移；待实现稳定后再恢复。
 *
 * <p>本类未被 {@code MainHook} 引用，保留作占位与文档。
 */
public class ZUIKeyHook {

    @SuppressWarnings("unused")
    private final Config mConfig;

    public ZUIKeyHook(Config config) {
        this.mConfig = config;
    }

    /** 50x 物理键拦截已关闭 — 事件交给 ZUI / 系统默认处理。 */
    public void intercept(HookCompat.HookParam param) {
        // intentionally no-op
    }
}
