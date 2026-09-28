package moe.lovefirefly.betterzuikey

import android.view.KeyEvent
import moe.lovefirefly.betterzuikey.Config.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Win 单按「映射到…」的配置项读写回归测试 —— 纯逻辑，不需要真机。
 *
 * 这里锁住三件曾经踩过 / 容易回归的事：
 *  1. `metaSingleMap` 的序列化 / 解析必须能完整往返；
 *  2. 六档模式与 `metaSingleMapEnabled` / `metaSingleMap` 的互斥关系 —— 只有
 *     `MAP` 会打开开关，切到别的档位只关开关、**不清空**已录好的目标
 *     （否则「映射一次 → 切回跟随系统 → 再切回来」就把录好的键弄丢了）；
 *  3. 走 Gson 的存盘 / 读盘（`Config.toJson` / `fromJson`）之后映射仍在 ——
 *     这是「配置项保存」真正落盘的那条路。
 */
class MetaSingleMapTest {

    @Test
    fun serializeParse_roundTrips() {
        // 左 Shift + 右 Ctrl + A：KEYCODE_A = 29；第 5 段是 scanCode
        assertEquals("29:L:R:0:0",
            MetaKeyMap(29, shift = KeySide.LEFT, ctrl = KeySide.RIGHT).serialize())

        val m = MetaKeyMap.parse("29:L:R:0:0")
        assertEquals(29, m.keyCode)
        assertEquals(KeySide.LEFT, m.shift)
        assertEquals(KeySide.RIGHT, m.ctrl)
        assertEquals(KeySide.NONE, m.alt)
        assertTrue(m.isSet)
        assertTrue(m.hasModifier)
    }

    @Test
    fun legacyOnBit_parsesAsUnknownSide_andRoundTripsUnchanged() {
        // 旧格式用 1 表示「按了修饰键」，分不清左右：不能假装知道是左侧
        val legacy = MetaKeyMap.parse("29:1:1:0:0")
        assertEquals(KeySide.ANY, legacy.shift)
        assertEquals(KeySide.ANY, legacy.ctrl)
        assertEquals(KeySide.NONE, legacy.alt)
        // 原样写回，不把旧值改写成 L
        assertEquals("29:1:1:0:0", legacy.serialize())
    }

    @Test
    fun scanCode_roundTrips_andOldFourFieldFormatStillParses() {
        // ZUI 顶行虚拟键要连 scanCode 一起存/回放（500 = 最大化，scanCode 0xf0）
        val zui = MetaKeyMap(500, scanCode = 0xf0)
        assertEquals("500:0:0:0:240", zui.serialize())
        assertEquals(0xf0, MetaKeyMap.parse(zui.serialize()).scanCode)

        // 老格式（只有 4 段）必须照旧可读，scanCode 缺省 0
        val legacy = MetaKeyMap.parse("29:1:1:0")
        assertEquals(29, legacy.keyCode)
        assertEquals(KeySide.ANY, legacy.shift)
        assertEquals(0, legacy.scanCode)
    }

    @Test
    fun unset_serializesEmpty_andParsesBackToUnset() {
        assertEquals("", MetaKeyMap.UNSET.serialize())
        assertFalse(MetaKeyMap.parse(MetaKeyMap.UNSET.serialize()).isSet)
    }

    @Test
    fun parse_fallsBackToUnsetOnAnythingBroken() {
        assertFalse(MetaKeyMap.parse(null).isSet)
        assertFalse(MetaKeyMap.parse("").isSet)
        assertFalse(MetaKeyMap.parse("   ").isSet)
        // 缺修饰段（老格式只有 keyCode:shift）仍应可解析
        assertEquals(29, MetaKeyMap.parse("29:0").keyCode)
        // 以下都属于非法：非数字 / 非正键码 / 截断
        assertFalse(MetaKeyMap.parse("abc:1:0:0").isSet)
        assertFalse(MetaKeyMap.parse("0:1:1:0").isSet)
        assertFalse(MetaKeyMap.parse("-5:0:0:0").isSet)
        assertFalse(MetaKeyMap.parse("29").isSet)
    }

    @Test
    fun jsonRoundTrip_keepsTheRecordedTarget() {
        val cfg = Config()
        ShortcutMeta.setMetaSingleMap(cfg,
            MetaKeyMap(29, shift = KeySide.LEFT, ctrl = KeySide.RIGHT))
        ShortcutMeta.setMetaSingleUiMode(cfg, MetaSingleUiMode.MAP)

        val restored = Config.fromJson(Config.toJson(cfg))
        assertTrue(restored.metaSingleMapEnabled)
        assertEquals("29:L:R:0:0", restored.metaSingleMap)
        assertEquals(MetaSingleUiMode.MAP, ShortcutMeta.getMetaSingleUiMode(restored))
        assertEquals(29, ShortcutMeta.getMetaSingleMap(restored).keyCode)
    }

    @Test
    fun switchingAwayFromMap_keepsTheTarget_andSwitchingBackRestoresIt() {
        val cfg = Config()
        ShortcutMeta.setMetaSingleMap(cfg, MetaKeyMap(29, ctrl = KeySide.RIGHT))
        ShortcutMeta.setMetaSingleUiMode(cfg, MetaSingleUiMode.MAP)
        assertTrue(cfg.metaSingleMapEnabled)

        // 切到标准五档：开关关掉，但录好的目标留着（否则就得重录一遍）
        ShortcutMeta.setMetaSingleUiMode(cfg, MetaSingleUiMode.BLOCK)
        assertFalse(cfg.metaSingleMapEnabled)
        assertEquals(Config.OverrideMode.BLOCK, cfg.overrideMetaSingle)
        assertEquals("29:0:R:0:0", cfg.metaSingleMap)
        assertEquals(MetaSingleUiMode.BLOCK, ShortcutMeta.getMetaSingleUiMode(cfg))

        // 再切回 MAP：不用重录，目标还在
        ShortcutMeta.setMetaSingleUiMode(cfg, MetaSingleUiMode.MAP)
        assertTrue(cfg.metaSingleMapEnabled)
        assertEquals(MetaSingleUiMode.MAP, ShortcutMeta.getMetaSingleUiMode(cfg))
        assertEquals(29, ShortcutMeta.getMetaSingleMap(cfg).keyCode)
    }

    @Test
    fun of_stripsTheModifierBitOfTheKeyItself() {
        // 单按 Ctrl：事件 metaState 天然带 META_CTRL_ON，不剔掉就显示成「Ctrl+Ctrl」
        val ctrl = MetaKeyMap.of(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON)
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, ctrl.keyCode)
        assertEquals(KeySide.NONE, ctrl.ctrl)
        assertFalse(ctrl.hasModifier)

        assertEquals(KeySide.NONE,
            MetaKeyMap.of(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON).shift)
        assertEquals(KeySide.NONE,
            MetaKeyMap.of(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON).alt)

        // 右侧修饰键自己当主键时，也要把自己那两位一起剔掉
        assertEquals(KeySide.NONE,
            MetaKeyMap.of(KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.META_CTRL_RIGHT_ON).ctrl)

        // 只有 ON 位、没有侧位（旧配置 / 合成事件）：知道按了、不知道哪边
        val ctrlOnOnly = MetaKeyMap.of(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
        assertEquals(KeyEvent.KEYCODE_A, ctrlOnOnly.keyCode)
        assertEquals(KeySide.ANY, ctrlOnOnly.ctrl)
        assertEquals("Ctrl+Key29", ctrlOnOnly.displayName())

        // 真机事件三层位齐全：主键是 A，左 Ctrl 要原样记住
        val ctrlA = MetaKeyMap.of(
            KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        assertEquals(KeyEvent.KEYCODE_A, ctrlA.keyCode)
        assertEquals(KeySide.LEFT, ctrlA.ctrl)

        // Ctrl+Shift 同按：主键是 Shift，只剔掉它自己那两位，Ctrl 留下
        val ctrlShift = MetaKeyMap.of(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON)
        assertEquals(KeySide.LEFT, ctrlShift.ctrl)
        assertEquals(KeySide.NONE, ctrlShift.shift)
    }

    @Test
    fun keyName_usesFriendlyNamesForModifiersAndBrightness() {
        // 单独把 Win / Ctrl / Shift 映射成一个键是支持的需求，
        // 名字不该显示成 MetaLeft / CtrlRight。
        // （只断言显式命名的分支：其余走 KeyEvent.keyCodeToString，JVM 单测里没有实现）
        assertEquals("Ctrl(L)", MetaKeyMap.keyName(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals("Ctrl(R)", MetaKeyMap.keyName(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals("Shift(L)", MetaKeyMap.keyName(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals("Shift(R)", MetaKeyMap.keyName(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals("Alt(L)", MetaKeyMap.keyName(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals("Alt(R)", MetaKeyMap.keyName(KeyEvent.KEYCODE_ALT_RIGHT))
        assertEquals("Win(L)", MetaKeyMap.keyName(KeyEvent.KEYCODE_META_LEFT))
        assertEquals("Win(R)", MetaKeyMap.keyName(KeyEvent.KEYCODE_META_RIGHT))
        assertEquals("Brightness+", MetaKeyMap.keyName(KeyEvent.KEYCODE_BRIGHTNESS_UP))
        assertEquals("Brightness-", MetaKeyMap.keyName(KeyEvent.KEYCODE_BRIGHTNESS_DOWN))
    }

    @Test
    fun flagWithoutTarget_fallsBackToOff() {
        // 开关开着、目标为空（手改配置才会出现）：不能停在「映射到…（未设置）」
        // 这种看着生效、实际什么都不做的档位 —— 按「关闭」算。
        // 正常路径下录制窗口也不允许把空值存成 MAP。
        val cfg = Config()
        cfg.metaSingleMapEnabled = true
        cfg.metaSingleMap = ""
        assertEquals(MetaSingleUiMode.OFF, ShortcutMeta.getMetaSingleUiMode(cfg))
        assertFalse(ShortcutMeta.getMetaSingleMap(cfg).isSet)
    }

    @Test
    fun displayName_showsTheSideOfEveryModifier() {
        // 用户实际录到的样子：右 Alt + 右 Shift + 空格
        val m = MetaKeyMap.of(
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_SHIFT_RIGHT_ON)
        assertEquals(KeySide.RIGHT, m.alt)
        assertEquals(KeySide.RIGHT, m.shift)
        assertEquals("Shift(R)+Alt(R)+Space", m.displayName())

        // 左右同时按住时两侧都写出来，不合并
        val both = MetaKeyMap.of(
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON)
        assertEquals(KeySide.BOTH, both.shift)
        assertEquals("Shift(LR)+Space", both.displayName())

        // 旧值侧别未知：只写键名，不编造左右
        assertEquals("Shift+Space", MetaKeyMap.parse("62:1:0:0:0").displayName())
    }

    @Test
    fun modifierKeySequence_expandsToTheExactKeysAndBits() {
        val seq = MetaKeyMap.of(
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_ALT_LEFT_ON).modifierKeySequence()
        assertEquals(2, seq.size)
        // Ctrl 先于 Alt；右侧 Ctrl 用的是右侧键码与右侧位
        assertEquals(KeyEvent.KEYCODE_CTRL_RIGHT, seq[0].first)
        assertEquals(KeyEvent.META_CTRL_RIGHT_ON, seq[0].second)
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, seq[1].first)
        assertEquals(KeyEvent.META_ALT_LEFT_ON, seq[1].second)

        // 两侧同按 → 展开成两个键
        val both = MetaKeyMap.of(
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON).modifierKeySequence()
        assertEquals(2, both.size)
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, both[0].first)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, both[1].first)

        // 旧值（侧别未知）按历史行为用左侧
        val legacy = MetaKeyMap.parse("62:1:0:0:0").modifierKeySequence()
        assertEquals(1, legacy.size)
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, legacy[0].first)
        assertEquals(KeyEvent.META_SHIFT_LEFT_ON, legacy[0].second)
    }
}
