package moe.lovefirefly.betterzuikey.ime

/**
 * subtype 轮转顺序 —— 纯逻辑，不碰 Android，可 JVM 单测。
 *
 * ## 为什么需要它
 *
 * 框架的 `switchToNextInputMethodLocked` **不是"遍历语义"**：它按最近使用（MRU）/
 * 分组走，三门语言常常只在两门之间来回跳（`dumpsys input_method` 的
 * `mSwitchingAwareRotationList` 只装最近两项为证）。所以"下一个是谁"必须由
 * **发起方自己算**：拿当前 subtype 在"已启用列表"里的位置，按用户顺序取下一个。
 *
 * ## 顺序的键：语言标签，不是 hash
 *
 * subtype 的 `hashCode()` 是**输入法私有**的（Gboard 中文=`24c738a3`、
 * 搜狗拼音=`-1311864734`），跨输入法/跨升级都不可靠。语言标签（`zh-CN`/`en-US`）
 * 才是可共享、可让用户排的键。没有语言标签的 subtype（框架里的隐式默认键盘，
 * 例如 Gboard 的英文 `be98c2d1`）用 [KEY_TAGLESS] 表示。
 *
 * ## 链的构造（与 Gboard 模块的 RotationOrder 同一套语义）
 *
 * 1. 先按用户顺序放：`order` 里每一项按序认领**第一个还没被认领**的匹配项；
 * 2. 剩下的已启用项**按框架顺序接在链尾** —— 顺序表里没提到的语言不会被永久跳过；
 * 3. 只有一个可用项 ⇒ 不切（返回 -1，绝不切到别的输入法）；
 * 4. 当前项认不出来（例如输入法内部刚切过、hash 变了）⇒ 从链首开始。
 */
object SubtypeRotation {

    /** 没有语言标签的 subtype 的键（框架里的隐式默认键盘）。 */
    const val KEY_TAGLESS = "*"

    /**
     * 计算一个 subtype 的顺序键。
     *
     * @param languageTag `InputMethodSubtype.getLanguageTag()`（API 34+），可能为 null
     * @param locale      `InputMethodSubtype.getLocale()`（形如 `zh_CN`），可能为 null
     */
    @JvmStatic
    fun keyOf(languageTag: String?, locale: String?): String {
        val tag = languageTag?.trim().orEmpty()
        if (tag.isNotEmpty()) return normalize(tag)
        val loc = locale?.trim().orEmpty()
        if (loc.isNotEmpty()) return normalize(loc)
        return KEY_TAGLESS
    }

    /** `zh_CN` → `zh-CN`；大小写统一成小写便于比较。 */
    @JvmStatic
    fun normalize(tag: String): String = tag.trim().replace('_', '-').lowercase()

    /**
     * 把**会撞车的键**拆开。
     *
     * <p>同一个语言标签可能有多个 subtype：搜狗的"拼音"和"五笔"都是 `zh-CN`
     * （框架靠 mode 区分）⇒ 键都算成 `zh-cn` 就会撞车（顺序表里出现两个 `zh-cn`，
     * 界面分不清、当前项也指不准）。
     *
     * <p>规则：第一个用基键，后面的加 `#<mode>`；再撞就继续 `#<mode>#n`。
     * 顺序表里仍可以只写基键 —— [matches] 的主语言兜底会让它认领第一个匹配项。
     *
     * @param bases          每个 subtype 的基键（见 [keyOf]），顺序 = 框架给的顺序
     * @param discriminators 与 bases 一一对应的区分串（传 `InputMethodSubtype.getMode()`）
     */
    @JvmStatic
    fun uniqueKeys(bases: List<String>, discriminators: List<String>): List<String> {
        val out = ArrayList<String>(bases.size)
        for (i in bases.indices) {
            val base = bases[i]
            var key = base
            if (out.contains(key)) {
                val d = discriminators.getOrElse(i) { "" }.trim().lowercase()
                val stem = if (d.isEmpty()) base else "$base#$d"
                key = stem
                var n = 2
                while (out.contains(key)) {
                    key = "$stem#$n"
                    n++
                }
            }
            out.add(key)
        }
        return out
    }

    /** 解析配置里的顺序串（逗号 / 空白分隔，忽略空项与重复项）。 */
    @JvmStatic
    fun parseOrder(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<String>()
        for (part in raw.split(',', ' ', '\n', '\t')) {
            val k = normalize(part)
            if (k.isEmpty()) continue
            if (!out.contains(k)) out.add(k)
        }
        return out
    }

    /**
     * `order` 里的键与某个 subtype 的键是否算同一条。
     *
     * 先精确比对；再退一步按**主语言**比对（`zh-Hans-CN` 认 `zh-CN`、`zh`），
     * 这样换输入法 / 换标签写法时顺序表不用重排。
     */
    @JvmStatic
    fun matches(orderKey: String, entryKey: String): Boolean {
        if (orderKey == entryKey) return true
        if (orderKey == KEY_TAGLESS || entryKey == KEY_TAGLESS) return false
        val a = primaryLanguage(orderKey) ?: return false
        val b = primaryLanguage(entryKey) ?: return false
        return a == b
    }

    private fun primaryLanguage(tag: String): String? =
        tag.substringBefore('-').takeIf { it.isNotEmpty() }

    /**
     * 构造轮转链：返回**下标**序列（下标指向 [keys]）。
     *
     * @param keys  已启用 subtype 的键，顺序 = 框架给的顺序
     * @param order 用户顺序（可为空 ⇒ 完全按框架顺序）
     */
    @JvmStatic
    fun buildChain(keys: List<String>, order: List<String>): List<Int> {
        val n = keys.size
        val used = BooleanArray(n)
        val chain = ArrayList<Int>(n)
        for (want in order) {
            // 先精确认领（`zh-cn#wubi` 这种带 mode 的全键要能拿到它自己那条），
            // 拿不到再按主语言模糊认领（顺序表里只写 `zh-cn` 时的情况）
            var picked = -1
            for (i in 0 until n) {
                if (!used[i] && keys[i] == want) {
                    picked = i
                    break
                }
            }
            if (picked < 0) {
                for (i in 0 until n) {
                    if (!used[i] && matches(want, keys[i])) {
                        picked = i
                        break
                    }
                }
            }
            if (picked >= 0) {
                used[picked] = true
                chain.add(picked)
            }
        }
        for (i in 0 until n) {
            if (!used[i]) {
                used[i] = true
                chain.add(i)
            }
        }
        return chain
    }

    /**
     * 下一个该切到哪个下标。
     *
     * @param currentIndex 当前 subtype 在 [keys] 里的下标；-1 = 认不出来
     * @return 目标下标；`-1` = 不该切（只有一个可用项 / 参数不合法 / 仍是自己）
     */
    @JvmStatic
    fun nextIndex(keys: List<String>, order: List<String>, currentIndex: Int): Int {
        if (keys.size <= 1) return -1
        val chain = buildChain(keys, order)
        if (chain.isEmpty()) return -1
        val pos = chain.indexOf(currentIndex)
        // 当前项不在链上（认不出/刚被输入法内部切过）⇒ 从链首开始
        val nextPos = if (pos < 0) 0 else (pos + 1) % chain.size
        val next = chain[nextPos]
        // 兜底：算出来还是自己 ⇒ 往前再挪一格（只有两项时会回到自己，直接给 -1）
        if (next == currentIndex) {
            val alt = chain[(nextPos + 1) % chain.size]
            return if (alt == currentIndex) -1 else alt
        }
        return next
    }
}
