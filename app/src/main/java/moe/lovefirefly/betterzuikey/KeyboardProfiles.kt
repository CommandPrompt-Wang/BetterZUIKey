package moe.lovefirefly.betterzuikey

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import moe.lovefirefly.betterzuikey.Config.Config

/**
 * 键盘配置文件管理。
 *
 * 配置格式（JSON）：
 *   "F1": { "scan": 787345 }      — 有 MSC_SCAN 的 ZUI 专用键
 *   "F2": { "keyCode": 164 }     — 无 MSC_SCAN 的标准 HID 键（Android keyCode）
 */
object KeyboardProfiles {

    /** 单个按键映射条目 */
    data class KeyEntry(
        val scan: Int = 0,
        val keyCode: Int = 0
    ) {
        val isValid get() = scan > 0 || keyCode > 0
    }

    data class Profile(
        val name: String = "",
        val friendlyName: String = "",
        val keys: Map<String, KeyEntry> = emptyMap(),
        val isCustom: Boolean = false
    )

    /** 所有可用 profile（内置 + 自定义） */
    fun all(context: Context): Map<String, Profile> {
        val result = linkedMapOf<String, Profile>()
        loadBuiltIn(context)?.forEach { (k, v) ->
            result[k] = v
        }
        Config.load().fnCustomProfiles?.forEach { (k, v) ->
            result[k] = v.copy(isCustom = true)
        }
        return result
    }

    /** 所有 profile 的 friendly name 列表 */
    fun getProfileNames(context: Context): List<Pair<String, String>> {
        return all(context).map { it.key to it.value.friendlyName }
    }

    /**
     * 获取特定 profile 的 F-key 映射表。
     * 返回两个映射：scanCode→fKey 和 keyCode→fKey
     */
    data class FnMapping(
        val scanMap: Map<Int, Int> = emptyMap(),
        val keyCodeMap: Map<Int, Int> = emptyMap()
    )

    fun getMapping(profileKey: String, context: Context): FnMapping {
        val p = all(context)[profileKey] ?: return FnMapping()
        val scanMap = mutableMapOf<Int, Int>()
        val keyCodeMap = mutableMapOf<Int, Int>()
        for (i in 1..12) {
            val entry = p.keys["F$i"] ?: continue
            val fKey = 130 + i
            if (entry.scan > 0) scanMap[entry.scan] = fKey
            if (entry.keyCode > 0) keyCodeMap[entry.keyCode] = fKey
        }
        return FnMapping(scanMap, keyCodeMap)
    }

    /** 根据设备 vendor:product 匹配 profile key */
    fun detectProfileKey(context: Context): String? {
        val im = context.getSystemService(android.hardware.input.InputManager::class.java) ?: return null
        for (id in im.inputDeviceIds) {
            val dev = im.getInputDevice(id) ?: continue
            val key = String.format("%04x:%04x", dev.vendorId, dev.productId)
            if (all(context).containsKey(key)) return key
        }
        return null
    }

    /** 导入自定义配置 */
    fun importProfile(key: String, data: Profile) {
        val cfg = Config.load()
        cfg.fnCustomProfiles[key] = data
        cfg.save()
    }

    /** 删除自定义配置 */
    fun deleteProfile(context: Context, key: String) {
        val cfg = Config.load()
        cfg.fnCustomProfiles.remove(key)
        cfg.save()
    }

    // ── internal ──

    private var builtInCache: Map<String, Profile>? = null

    private fun loadBuiltIn(context: Context): Map<String, Profile>? {
        if (builtInCache != null) return builtInCache
        try {
            val json = context.resources.openRawResource(R.raw.keyboard_profiles)
                .bufferedReader().readText()
            val type = object : TypeToken<Map<String, Map<String, Profile>>>() {}.type
            val raw: Map<String, Map<String, Profile>> = Gson().fromJson(json, type)
            val profiles = raw["profiles"] ?: emptyMap()
            builtInCache = profiles.mapValues { (_, v) -> v }
        } catch (e: Exception) {
            builtInCache = emptyMap()
        }
        return builtInCache
    }
}
