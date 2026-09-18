package moe.lovefirefly.betterzuikey.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SubtypeRotation] 的 JVM 单测 —— 顺序轮转是纯逻辑，不上真机就能验。
 *
 * 用的是真机上的真实形状：Gboard 的三门 = `zh-CN` / `ja-JP` / 无标签的英文（`*`）。
 */
class SubtypeRotationTest {

    private val gboard = listOf("zh-CN", "ja-JP", SubtypeRotation.KEY_TAGLESS)

    @Test
    fun keyOf_prefersLanguageTag_thenLocale_thenTagless() {
        assertEquals("zh-cn", SubtypeRotation.keyOf("zh-CN", "zh_CN"))
        assertEquals("ja-jp", SubtypeRotation.keyOf(null, "ja_JP"))
        assertEquals("zh-hans-cn", SubtypeRotation.keyOf("zh-Hans-CN", null))
        assertEquals(SubtypeRotation.KEY_TAGLESS, SubtypeRotation.keyOf(null, null))
        assertEquals(SubtypeRotation.KEY_TAGLESS, SubtypeRotation.keyOf("  ", ""))
    }

    @Test
    fun parseOrder_normalizesDedupesAndToleratesSeparators() {
        assertEquals(listOf("zh-cn", "ja-jp", "*"),
            SubtypeRotation.parseOrder(" zh_CN , ja-JP,*,zh-CN "))
        assertEquals(emptyList<String>(), SubtypeRotation.parseOrder(null))
        assertEquals(emptyList<String>(), SubtypeRotation.parseOrder("   "))
    }

    @Test
    fun emptyOrder_followsFrameworkOrderAndWrapsAround() {
        // 中 → 日 → 英 → 中 ……（这正是"轮满三门"，原生 MRU 只在中日之间跳）
        assertEquals(1, SubtypeRotation.nextIndex(gboard, emptyList(), 0))
        assertEquals(2, SubtypeRotation.nextIndex(gboard, emptyList(), 1))
        assertEquals(0, SubtypeRotation.nextIndex(gboard, emptyList(), 2))
    }

    @Test
    fun userOrder_isRespected() {
        val order = SubtypeRotation.parseOrder("ja-JP,zh-CN,*")
        // 链 = [日, 中, 英]
        assertEquals(1, SubtypeRotation.nextIndex(gboard, order, 2)) // 英 → 日
        assertEquals(0, SubtypeRotation.nextIndex(gboard, order, 1)) // 日 → 中
        assertEquals(2, SubtypeRotation.nextIndex(gboard, order, 0)) // 中 → 英
    }

    @Test
    fun unlistedLanguages_areAppendedNotSkipped() {
        val keys = listOf("zh-CN", "ja-JP", "en-US")
        val order = SubtypeRotation.parseOrder("ja-JP")
        // 链 = [日, 中, 英]：顺序表只提了日文，其余按框架顺序接尾
        assertEquals(0, SubtypeRotation.nextIndex(keys, order, 1))
        assertEquals(2, SubtypeRotation.nextIndex(keys, order, 0))
        assertEquals(1, SubtypeRotation.nextIndex(keys, order, 2))
    }

    @Test
    fun primaryLanguageMatch_survivesTagVariants() {
        val keys = listOf("zh-Hans-CN", "en-US")
        val order = SubtypeRotation.parseOrder("zh-CN,en-US")
        // zh-CN 认领 zh-Hans-CN（主语言相同），链 = [中, 英]
        assertEquals(listOf(0, 1), SubtypeRotation.buildChain(keys, order))
        assertEquals(1, SubtypeRotation.nextIndex(keys, order, 0))
        assertEquals(0, SubtypeRotation.nextIndex(keys, order, 1))
    }

    @Test
    fun tagless_isNotMatchedByLanguageKey() {
        assertTrue(SubtypeRotation.matches("*", "*"))
        assertEquals(false, SubtypeRotation.matches("en-US", "*"))
        assertEquals(false, SubtypeRotation.matches("*", "en-US"))
    }

    @Test
    fun singleSubtype_neverSwitches() {
        assertEquals(-1, SubtypeRotation.nextIndex(listOf("zh-CN"), emptyList(), 0))
        assertEquals(-1, SubtypeRotation.nextIndex(emptyList(), emptyList(), -1))
    }

    @Test
    fun unknownCurrent_startsFromChainHead() {
        assertEquals(1, SubtypeRotation.nextIndex(gboard, SubtypeRotation.parseOrder("ja-JP"), -1))
        assertEquals(0, SubtypeRotation.nextIndex(gboard, emptyList(), -1))
    }

    @Test
    fun twoSubtypes_goBackAndForth() {
        val keys = listOf("zh-CN", "en-US")
        assertEquals(1, SubtypeRotation.nextIndex(keys, emptyList(), 0))
        assertEquals(0, SubtypeRotation.nextIndex(keys, emptyList(), 1))
    }

    @Test
    fun uniqueKeys_splitsSameLocaleByMode() {
        // 搜狗：拼音(zh-CN/keyboard) + 英语(en-US) + 五笔(zh-CN/wubi)
        val bases = listOf("zh-cn", "en-us", "zh-cn")
        val modes = listOf("keyboard", "keyboard", "wubi")
        assertEquals(listOf("zh-cn", "en-us", "zh-cn#wubi"),
            SubtypeRotation.uniqueKeys(bases, modes))
        // 去重后顺序表能完整表达三个（不会像 parseOrder 那样合并）
        val keys = SubtypeRotation.uniqueKeys(bases, modes)
        val order = SubtypeRotation.parseOrder("zh-cn#wubi,zh-cn,en-us")
        assertEquals(listOf(2, 0, 1), SubtypeRotation.buildChain(keys, order))
    }

    @Test
    fun uniqueKeys_fallsBackToNumberingWhenModeAlsoCollides() {
        val keys = SubtypeRotation.uniqueKeys(
            listOf("zh-cn", "zh-cn", "zh-cn"),
            listOf("keyboard", "keyboard", "keyboard"))
        assertEquals(listOf("zh-cn", "zh-cn#keyboard", "zh-cn#keyboard#2"), keys)
        // 键唯一 ⇒ 链不会丢项
        assertEquals(3, SubtypeRotation.buildChain(keys, emptyList()).distinct().size)
    }

    @Test
    fun uniqueKeys_keepsBaseKeyMatchableByLanguage() {
        // 顺序表里只写基键也能认领（matches 的主语言兜底）
        val keys = SubtypeRotation.uniqueKeys(listOf("zh-cn", "zh-cn"), listOf("keyboard", "wubi"))
        val order = SubtypeRotation.parseOrder("zh-cn")
        assertEquals(listOf(0, 1), SubtypeRotation.buildChain(keys, order))
    }

    @Test
    fun duplicateTaglessKeys_doNotLoopForever() {
        // 万一有两个无标签 subtype，第一个被顺序表认领，第二个按框架顺序接尾
        val keys = listOf("*", "*", "zh-CN")
        val chain = SubtypeRotation.buildChain(keys, SubtypeRotation.parseOrder("*"))
        assertEquals(listOf(0, 1, 2), chain)
        for (cur in keys.indices) {
            val next = SubtypeRotation.nextIndex(keys, SubtypeRotation.parseOrder("*"), cur)
            assertTrue("next must differ from current ($cur)", next != cur)
        }
    }
}
