package moe.lovefirefly.betterzuikey

/**
 * 遗留探针（deprecated）。
 *
 * 模块激活自检已由 [ModuleServiceBridge]（libxposed service API，XposedServiceHelper）
 * 接管：UI 通过其 isActive() 判断是否激活。本对象的 isLoaded() 不再被 hook，
 * 仅作为历史遗留保留，后续可移除。
 */
object ModuleStatus {
    @JvmStatic
    fun isLoaded(): Boolean = false
}
