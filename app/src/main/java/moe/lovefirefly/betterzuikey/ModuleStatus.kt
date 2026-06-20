package moe.lovefirefly.betterzuikey

/**
 * Xposed/LSPosed module self-check probe.
 *
 * The method [isLoaded] returns `false` by default (no Xposed).
 * When the module is activated, [MainHook] hooks this method via
 * XC_MethodReplacement.returnConstant(true) in its own package,
 * proving that hooks are actually loaded.
 *
 * This is the standard pattern used by Xposed modules to verify
 * active status — a direct, in-process proof that the framework
 * has loaded and applied hooks.
 */
object ModuleStatus {
    @JvmStatic
    fun isLoaded(): Boolean = false
}
