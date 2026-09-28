package moe.lovefirefly.betterzuikey

import android.view.KeyEvent
import moe.lovefirefly.betterzuikey.Config.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按住 Win + 其它键」的兜底合并判定（[ShortcutMeta.shouldMergeWinCombo]）。
 *
 * 规则：模块对这个 Win+键 **没有生效中的特殊处理** 时才让位给映射合并；
 * 有处理就绝不干涉。三种典型：
 *  - Win+E 跟随系统 → 交给系统开文件管理，不合并
 *  - Win+R 表里根本没这个组合 → 合并
 *  - Win+D 被设为「关闭」(OFF) → 合并
 */
class WinComboMergeTest {

    private fun merge(
        keyCode: Int,
        sw: Config.SwitchState? = null,
        mode: Config.OverrideMode? = null,
    ) = ShortcutMeta.shouldMergeWinCombo(keyCode, sw, mode)

    @Test
    fun winKeyTable_mapsKeyCodesToShortcutIds() {
        assertEquals("winE", ShortcutMeta.winComboKeyId(KeyEvent.KEYCODE_E))
        assertEquals("winD", ShortcutMeta.winComboKeyId(KeyEvent.KEYCODE_D))
        assertEquals("winTab", ShortcutMeta.winComboKeyId(KeyEvent.KEYCODE_TAB))
        assertEquals("winBack", ShortcutMeta.winComboKeyId(KeyEvent.KEYCODE_BACK))
        assertEquals("winNumber", ShortcutMeta.winComboKeyId(KeyEvent.KEYCODE_3))
        // 方向键归到 getSwitch / getOverride 真正认的那两项
        assertEquals("winUp", ShortcutMeta.winComboKeyId(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("winUp", ShortcutMeta.winComboKeyId(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("winLeft", ShortcutMeta.winComboKeyId(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("winLeft", ShortcutMeta.winComboKeyId(KeyEvent.KEYCODE_DPAD_RIGHT))

        // Win+R / Win+Y 之类：模块从来没有这个组合
        assertNull(ShortcutMeta.winComboKeyId(KeyEvent.KEYCODE_R))
        assertNull(ShortcutMeta.winComboKeyId(KeyEvent.KEYCODE_Y))
    }

    @Test
    fun undefinedWinCombo_merges() {
        // Win+R：表里没有条目 → 兜底合并（传 null 表示「查不到条目」）
        assertTrue(merge(KeyEvent.KEYCODE_R))
        assertTrue(merge(KeyEvent.KEYCODE_Y, null, null))
    }

    @Test
    fun winComboWithLiveHandling_isLeftAlone() {
        // Win+E：跟随系统，交给系统开文件管理
        assertFalse(merge(KeyEvent.KEYCODE_E, Config.SwitchState.FORCED_ON,
            Config.OverrideMode.FOLLOW_SYSTEM))
        // 不生效的档位也都算「有处理」，别抢
        assertFalse(merge(KeyEvent.KEYCODE_E, Config.SwitchState.ON, Config.OverrideMode.ZUI))
        assertFalse(merge(KeyEvent.KEYCODE_E, Config.SwitchState.ON, Config.OverrideMode.AOSP))
        assertFalse(merge(KeyEvent.KEYCODE_E, Config.SwitchState.ON, Config.OverrideMode.BLOCK))
    }

    @Test
    fun winComboTurnedOff_merges() {
        // Win+D 被设为「关闭」：正是用户要合并的那种
        assertTrue(merge(KeyEvent.KEYCODE_D, Config.SwitchState.ON, Config.OverrideMode.OFF))
        // 开关本身关着（系统不支持 / 用户关掉）也算没处理
        assertTrue(merge(KeyEvent.KEYCODE_D, Config.SwitchState.OFF,
            Config.OverrideMode.FOLLOW_SYSTEM))
        assertTrue(merge(KeyEvent.KEYCODE_D, Config.SwitchState.FORCED_OFF,
            Config.OverrideMode.ZUI))
    }

    @Test
    fun winTab_hasNoSystemSwitch_soOnlyModeDecides() {
        // winTab 没有系统开关：档位是跟随系统/ZUI 时仍有处理
        assertFalse(merge(KeyEvent.KEYCODE_TAB, Config.SwitchState.OFF,
            Config.OverrideMode.FOLLOW_SYSTEM))
        assertFalse(merge(KeyEvent.KEYCODE_TAB, Config.SwitchState.OFF,
            Config.OverrideMode.ZUI))
        // 只有档位=关闭时才合并
        assertTrue(merge(KeyEvent.KEYCODE_TAB, Config.SwitchState.OFF, Config.OverrideMode.OFF))
    }

    @Test
    fun entryWithoutResolvedState_mergesDefensively() {
        // 有条目但调用方没解析出开关/档位：宁可当「没处理」，也别漏掉合并
        assertTrue(merge(KeyEvent.KEYCODE_E, null, Config.OverrideMode.ZUI))
        assertTrue(merge(KeyEvent.KEYCODE_E, Config.SwitchState.ON, null))
    }
}
