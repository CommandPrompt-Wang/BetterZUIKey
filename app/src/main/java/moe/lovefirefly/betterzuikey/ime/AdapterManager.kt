package moe.lovefirefly.betterzuikey.ime

import dalvik.system.DexClassLoader
import moe.lovefirefly.betterzuikey.Utils.LogHelper
import moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel
import java.io.File

/**
 * IME 适配器管理器 — 通过 [DexClassLoader] 动态加载外部适配器。
 *
 * ## 适配器加载
 *
 * 外部适配器以 .dex 或 .jar 文件形式存放在 app 数据目录（或用户指定路径）。
 * [DexClassLoader] 将 dex 加载到独立 ClassLoader，通过反射实例化。
 *
 * 加载流程：
 * 1. 检查缓存
 * 2. 创建 DexClassLoader(apkPath, optimizedDir, null, parentCl)
 * 3. 遍历 dex 中所有类，找到实现 [IMEAdapter] 的类
 * 4. 通过无参构造函数实例化
 * 5. 缓存并返回
 *
 * ## 适配器查找
 *
 * [getAdapterForShortcut] 根据快捷键 key 查找绑定路径，加载并返回适配器。
 * 绑定关系存储在 [Config.imeAdapterBindings] 中。
 */
object AdapterManager {

    private const val TAG = "AdapterManager"

    /** 已加载的适配器缓存：路径 → 适配器实例 */
    private val loadedAdapters = mutableMapOf<String, IMEAdapter>()

    /** 已创建的 ClassLoader 缓存：路径 → DexClassLoader */
    private val classLoaders = mutableMapOf<String, DexClassLoader>()

    /** 快捷键 key → 适配器 jar 路径（从 Config 同步） */
    private val shortcutBindings = mutableMapOf<String, String>()

    /** dex 优化输出目录（app dex 缓存目录） */
    private var optimizedDir: File? = null

    /** 当前配置中的绑定快照（用于 resolve 时匹配） */
    private var configBindings: Map<String, String> = emptyMap()

    // -----------------------------------------------------------------
    // 初始化
    // -----------------------------------------------------------------

    /**
     * 初始化管理器。
     *
     * @param dexOptDir dex 优化输出目录（通常为 context.codeCacheDir）
     */
    @JvmStatic
    fun init(dexOptDir: File?) {
        optimizedDir = dexOptDir
        if (optimizedDir != null && !optimizedDir!!.exists()) {
            optimizedDir!!.mkdirs()
        }
        LogHelper.log(VerboseLevel.INFO, "$TAG: init optimizedDir=${optimizedDir?.absolutePath}")
    }

    /**
     * 从 Config 同步适配器绑定。
     * 当 Config 热更新时调用。
     */
    @JvmStatic
    fun syncBindings(bindings: Map<String, String>) {
        configBindings = bindings
        // 清除不再需要的绑定
        shortcutBindings.clear()
        shortcutBindings.putAll(bindings)
        LogHelper.log(VerboseLevel.INFO, "$TAG: synced ${bindings.size} bindings")
    }

    // -----------------------------------------------------------------
    // 加载 & 查找
    // -----------------------------------------------------------------

    /**
     * 根据快捷键 key 查找并加载适配器。
     *
     * @param shortcutKey 快捷键内部 key，如 "ctrlShift"
     * @return 加载的适配器实例；没有绑定或加载失败返回 null
     */
    @JvmStatic
    fun getAdapterForShortcut(shortcutKey: String): IMEAdapter? {
        val jarPath = shortcutBindings[shortcutKey] ?: return null
        return loadAdapter(jarPath)
    }

    /**
     * 加载指定路径的适配器。
     * 缓存命中直接返回，未命中时通过 DexClassLoader 加载。
     *
     * @param jarPath 适配器 dex/jar 的绝对路径
     * @return 适配器实例；加载失败返回 null
     */
    @JvmStatic
    fun loadAdapter(jarPath: String): IMEAdapter? {
        // 缓存命中
        loadedAdapters[jarPath]?.let { return it }

        if (!File(jarPath).exists()) {
            LogHelper.log(VerboseLevel.WARNING, "$TAG: adapter file not found: $jarPath")
            return null
        }

        val optDir = optimizedDir
        if (optDir == null) {
            LogHelper.log(VerboseLevel.ERROR, "$TAG: optimizedDir not set — call init() first")
            return null
        }

        return try {
            val parent = IMEAdapter::class.java.classLoader
            val dexLoader = DexClassLoader(
                jarPath,
                optDir.absolutePath,
                null,  // librarySearchPath: no native libs needed for adapters
                parent
            )
            classLoaders[jarPath] = dexLoader

            // 查找实现了 IMEAdapter 的类
            val adapter = findAdapterInDex(dexLoader, jarPath)
            if (adapter != null) {
                loadedAdapters[jarPath] = adapter
                LogHelper.log(VerboseLevel.INFO,
                    "$TAG: loaded adapter '${adapter.displayName}' for IME '${adapter.imePackageName}' from $jarPath")
            } else {
                LogHelper.log(VerboseLevel.WARNING,
                    "$TAG: no IMEAdapter implementation found in $jarPath")
            }
            adapter
        } catch (t: Throwable) {
            LogHelper.log(VerboseLevel.ERROR,
                "$TAG: failed to load adapter from $jarPath:", t.message)
            null
        }
    }

    /**
     * 在 DexClassLoader 加载的 dex 中查找 [IMEAdapter] 实现。
     *
     * 注意：由于 DexClassLoader 隔离，我们无法直接枚举 dex 中的类。
     * 采用约定命名策略：适配器的主类名由文件名推导（去掉扩展名）。
     * 例如 "GBoardAdapter.dex" → 查找 "adapters.gboard.GBoardAdapter"
     * 或 "GBoardAdapter"（根包）。
     *
     * 同时支持 [GenericIMEAdapter] 作为内置 fallback。
     */
    private fun findAdapterInDex(dexLoader: DexClassLoader, jarPath: String): IMEAdapter? {
        val fileName = File(jarPath).nameWithoutExtension
        // .dex 或 .jar 文件名可能就是类名，但也可能包含路径编码

        val candidateNames = listOf(
            fileName,                              // 根包
            "adapters.$fileName",                  // adapters.xxx
            "adapters.${fileName}Adapter",         // adapters.xxxAdapter
            "ime.$fileName",                       // ime.xxx
        )

        for (name in candidateNames) {
            try {
                val clazz = dexLoader.loadClass(name)
                if (IMEAdapter::class.java.isAssignableFrom(clazz) && !clazz.isInterface) {
                    val instance = clazz.getDeclaredConstructor().newInstance()
                    return instance as IMEAdapter
                }
            } catch (_: ClassNotFoundException) {
                // 尝试下一个候选项
            }
        }

        // 兜底：如果 jarPath 指的就是 GenericIMEAdapter（内置），直接返回
        if (fileName == "GenericIMEAdapter") {
            return GenericIMEAdapter()
        }

        return null
    }

    // -----------------------------------------------------------------
    // 内置适配器
    // -----------------------------------------------------------------

    /** 返回内置的通用 Ctrl+Space 适配器 */
    @JvmStatic
    fun getBuiltinAdapter(): IMEAdapter = GenericIMEAdapter()

    // -----------------------------------------------------------------
    // 清理
    // -----------------------------------------------------------------

    /** 清除所有缓存 */
    @JvmStatic
    fun clear() {
        loadedAdapters.clear()
        classLoaders.clear()
        shortcutBindings.clear()
        configBindings = emptyMap()
    }
}
