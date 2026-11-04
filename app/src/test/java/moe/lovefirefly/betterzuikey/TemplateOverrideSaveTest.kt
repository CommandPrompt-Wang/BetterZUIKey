package moe.lovefirefly.betterzuikey

import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Config.ConfigResolver
import moe.lovefirefly.betterzuikey.Config.KeyTemplate
import moe.lovefirefly.betterzuikey.Config.PerKeyOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板覆写的**落库**路径。
 *
 * 背景：模板里的「映射到…」「执行命令…」曾经保存后静默消失 —— 调用方习惯写
 * 「先 new 一个空覆写放进 map，再往它身上填字段」，而 `KeyTemplate.put` 当时会
 * 按 `isInherit()` 把空覆写直接丢掉，于是字段全填在了一个游离对象上。
 * 日志照打 "saved"，用户却看不到任何效果。
 *
 * 这里把「空覆写放进 map 后仍能填字段并落盘」固化成契约。
 */
class TemplateOverrideSaveTest {

    private fun templateWith(packageName: String = "com.example.app"): Pair<Config, KeyTemplate> {
        val cfg = Config()
        val t = KeyTemplate("测试模板")
        t.packages.add(packageName)
        cfg.templates.add(t)
        return cfg to t
    }

    private fun resolverFor(cfg: Config, packageName: String = "com.example.app") =
        ConfigResolver(cfg).also { it.setForegroundPackage(packageName) }

    @Test
    fun emptyOverrideSurvivesPut_soFieldsCanBeFilledAfterwards() {
        val (_, t) = templateWith()
        val ov = PerKeyOverride()
        t.put("metaSingle", ov)          // 此刻还是全 null
        assertNotNull("空覆写被 put 丢弃了", t.get("metaSingle"))

        ov.useMap = true                 // 典型的「先入 map 再填字段」写法
        ov.mapTarget = "44:0:0:0:25"
        assertTrue(t.get("metaSingle").useMap)
    }

    @Test
    fun savedMapTarget_survivesJsonRoundTrip_andIsResolved() {
        val (cfg, t) = templateWith()
        // 与对话框 TemplateStore.save 完全相同的顺序
        val ov = t.get("metaSingle") ?: PerKeyOverride()
        ov.useMap = true
        ov.mapTarget = "44:0:0:0:25"
        ov.overrideMode = null
        t.put("metaSingle", ov)

        val reloaded = Config.fromJson(Config.toJson(cfg))
        val r = resolverFor(reloaded)
        assertTrue("模板的「映射到…」没能落盘", r.usesMap("metaSingle"))
        assertEquals("44:0:0:0:25", r.effectiveMapTarget("metaSingle"))
    }

    @Test
    fun savedCommand_survivesJsonRoundTrip_andIsResolved() {
        val (cfg, t) = templateWith()
        val ov = t.get("winLongPress") ?: PerKeyOverride()
        ov.useCommand = true
        ov.command = "input keyevent 26"
        ov.commandRoot = true
        ov.commandSingleton = false
        ov.commandTimeoutMin = 5
        t.put("winLongPress", ov)

        val reloaded = Config.fromJson(Config.toJson(cfg))
        val r = resolverFor(reloaded)
        assertTrue(r.effectiveUseCommand("winLongPress"))
        assertEquals("input keyevent 26", r.effectiveCommand("winLongPress"))
        assertTrue(r.effectiveCommandRoot("winLongPress"))
        assertFalse(r.effectiveCommandSingleton("winLongPress"))
        assertEquals(5L, r.effectiveCommandTimeoutMin("winLongPress").toLong())
    }

    @Test
    fun copyKeepsEveryField() {
        val src = PerKeyOverride()
        src.switchState = Config.SwitchState.ON
        src.overrideMode = Config.OverrideMode.ZUI
        src.useMap = true
        src.mapTarget = "44:0:0:0:25"
        src.useCommand = true
        src.command = "echo hi"
        src.commandRoot = true
        src.commandSingleton = true
        src.commandTimeoutMin = 3
        src.appKeyMode = Config.AppKeyMode.CUSTOM

        val copy = src.copy()
        assertEquals(src.switchState, copy.switchState)
        assertEquals(src.overrideMode, copy.overrideMode)
        assertEquals(src.useMap, copy.useMap)
        assertEquals(src.mapTarget, copy.mapTarget)
        assertEquals(src.useCommand, copy.useCommand)
        assertEquals(src.command, copy.command)
        assertEquals(src.commandRoot, copy.commandRoot)
        assertEquals(src.commandSingleton, copy.commandSingleton)
        assertEquals(src.commandTimeoutMin, copy.commandTimeoutMin)
        assertEquals(src.appKeyMode, copy.appKeyMode)
        assertFalse("复制品不该是空覆写", copy.isInherit())
    }

    @Test
    fun copiedTemplateKeepsSpecialFeatures() {
        val (cfg, t) = templateWith()
        val ov = PerKeyOverride()
        ov.useMap = true
        ov.mapTarget = "44:0:0:0:25"
        t.put("metaSingle", ov)

        // 与「复制模板」相同的做法
        val copy = KeyTemplate("测试模板-2")
        copy.packages.add("com.example.app")
        copy.overrides.putAll(t.overrides.mapValues { it.value.copy() })
        cfg.templates.add(copy)

        val r = resolverFor(Config.fromJson(Config.toJson(cfg)))
        assertTrue(r.usesMap("metaSingle"))
        assertEquals("44:0:0:0:25", r.effectiveMapTarget("metaSingle"))
    }

    @Test
    fun removeIfInherit_dropsOnlyEmptyOverrides() {
        val (_, t) = templateWith()
        val empty = PerKeyOverride()
        t.put("metaSingle", empty)
        assertTrue(t.removeIfInherit("metaSingle"))
        assertNull(t.get("metaSingle"))

        val filled = PerKeyOverride()
        filled.useMap = true
        t.put("metaSingle", filled)
        assertFalse(t.removeIfInherit("metaSingle"))
        assertNotNull(t.get("metaSingle"))
    }
}
