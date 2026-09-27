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
        // Ctrl+Shift+A：KEYCODE_A = 29
        assertEquals("29:1:1:0", MetaKeyMap(29, shift = true, ctrl = true).serialize())

        val m = MetaKeyMap.parse("29:1:1:0")
        assertEquals(29, m.keyCode)
        assertTrue(m.shift)
        assertTrue(m.ctrl)
        assertFalse(m.alt)
        assertTrue(m.isSet)
        assertTrue(m.hasModifier)
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
        ShortcutMeta.setMetaSingleMap(cfg, MetaKeyMap(29, shift = true, ctrl = true))
        ShortcutMeta.setMetaSingleUiMode(cfg, MetaSingleUiMode.MAP)

        val restored = Config.fromJson(Config.toJson(cfg))
        assertTrue(restored.metaSingleMapEnabled)
        assertEquals("29:1:1:0", restored.metaSingleMap)
        assertEquals(MetaSingleUiMode.MAP, ShortcutMeta.getMetaSingleUiMode(restored))
        assertEquals(29, ShortcutMeta.getMetaSingleMap(restored).keyCode)
    }

    @Test
    fun switchingAwayFromMap_keepsTheTarget_andSwitchingBackRestoresIt() {
        val cfg = Config()
        ShortcutMeta.setMetaSingleMap(cfg, MetaKeyMap(29, ctrl = true))
        ShortcutMeta.setMetaSingleUiMode(cfg, MetaSingleUiMode.MAP)
        assertTrue(cfg.metaSingleMapEnabled)

        // 切到标准五档：开关关掉，但录好的目标留着（否则就得重录一遍）
        ShortcutMeta.setMetaSingleUiMode(cfg, MetaSingleUiMode.BLOCK)
        assertFalse(cfg.metaSingleMapEnabled)
        assertEquals(Config.OverrideMode.BLOCK, cfg.overrideMetaSingle)
        assertEquals("29:0:1:0", cfg.metaSingleMap)
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
        assertFalse(ctrl.ctrl)
        assertFalse(ctrl.hasModifier)

        assertFalse(MetaKeyMap.of(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON).shift)
        assertFalse(MetaKeyMap.of(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON).alt)

        // 组合键照旧：主键是 A，Ctrl 那一位要留着
        val ctrlA = MetaKeyMap.of(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
        assertEquals(KeyEvent.KEYCODE_A, ctrlA.keyCode)
        assertTrue(ctrlA.ctrl)

        // Ctrl+Shift 同按：主键是 Shift，只剔掉它自己那一位，Ctrl 留下
        val ctrlShift = MetaKeyMap.of(
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertTrue(ctrlShift.ctrl)
        assertFalse(ctrlShift.shift)
    }

    @Test
    fun keyName_usesFriendlyNamesForModifiersAndBrightness() {
        // 单独把 Win / Ctrl / Shift 映射成一个键是支持的需求，
        // 名字不该显示成 MetaLeft / CtrlRight。
        // （只断言显式命名的分支：其余走 KeyEvent.keyCodeToString，JVM 单测里没有实现）
        assertEquals("Ctrl", MetaKeyMap.keyName(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals("Ctrl", MetaKeyMap.keyName(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals("Shift", MetaKeyMap.keyName(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals("Alt", MetaKeyMap.keyName(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals("Win", MetaKeyMap.keyName(KeyEvent.KEYCODE_META_LEFT))
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
}
