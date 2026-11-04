package moe.lovefirefly.betterzuikey

import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Config.ConfigResolver
import moe.lovefirefly.betterzuikey.Config.KeyTemplate
import moe.lovefirefly.betterzuikey.Config.PerKeyOverride
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用模板对「映射到…」的覆写解析（[ConfigResolver.usesMap]）。
 *
 * 背景：模板原先只能表达标准五档（`Config.OverrideMode`），卡片上新增的「映射到…」
 * 在模板里无从选择 —— 而「映射 + 应用模板」恰恰是推荐用法。现给
 * [PerKeyOverride] 加了 `useMap`，语义如下：
 *  1. 模板对这条 key 没表态 → 用全局 `metaSingleMapEnabled`（与以前一致）
 *  2. 模板显式选了「映射到…」 → 按它（全局没开也生效）
 *  3. 模板显式选了标准五档 → 映射让位（模板说「忽略」就真是忽略）
 */
class TemplateMetaMapTest {

    private fun resolver(
        globalMapEnabled: Boolean,
        packageName: String = "com.example.app",
        fill: (PerKeyOverride) -> Unit = {},
    ): ConfigResolver {
        val cfg = Config()
        cfg.metaSingleMapEnabled = globalMapEnabled
        val t = KeyTemplate("测试模板")
        t.packages.add("com.example.app")
        val ov = PerKeyOverride()
        fill(ov)
        t.put("metaSingle", ov)
        cfg.templates.add(t)
        return ConfigResolver(cfg).also { it.setForegroundPackage(packageName) }
    }

    @Test
    fun noTemplate_followsTheGlobalFlag() {
        val off = ConfigResolver(Config().apply { metaSingleMapEnabled = false })
        assertFalse(off.usesMap("metaSingle"))

        val on = ConfigResolver(Config().apply { metaSingleMapEnabled = true })
        assertTrue(on.usesMap("metaSingle"))
    }

    @Test
    fun templateSilent_followsTheGlobalFlag() {
        assertFalse(resolver(globalMapEnabled = false).usesMap("metaSingle"))
        assertTrue(resolver(globalMapEnabled = true).usesMap("metaSingle"))
    }

    @Test
    fun templateSelectingMap_wins_overTheGlobalFlag() {
        val r = resolver(globalMapEnabled = false) { it.useMap = true }
        assertTrue(r.usesMap("metaSingle"))
    }

    @Test
    fun templateSelectingStandardMode_beatsTheMap() {
        val r = resolver(globalMapEnabled = true) { it.overrideMode = Config.OverrideMode.BLOCK }
        assertFalse(r.usesMap("metaSingle"))
    }

    @Test
    fun nonMatchingPackage_ignoresTheTemplate() {
        val r = resolver(globalMapEnabled = false, packageName = "com.other.app") { it.useMap = true }
        assertFalse(r.usesMap("metaSingle"))
    }
}
