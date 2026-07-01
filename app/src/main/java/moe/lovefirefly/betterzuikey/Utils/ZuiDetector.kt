package moe.lovefirefly.betterzuikey.Utils

/**
 * ZUXOS 系统检测。
 * 依据: ro.com.zui.version 非空 (ro.build.zui.version 在 17.5 上为空)
 */
object ZuiDetector {

    private var _cached: Boolean? = null

    private var _cachedVersion: String? = null
    private var _versionCacheValid = false

    val isZuxOS: Boolean
        get() {
            if (_cached != null) return _cached!!
            val ver = rawZuiVersion
            _cachedVersion = ver
            _versionCacheValid = true
            _cached = !ver.isNullOrBlank()
            return _cached!!
        }

    data class Result(val isZux: Boolean, val zuiVersion: String?) {
        val detail: String
            get() = if (isZux) "ZUXOS $zuiVersion"
            else "非 ZUXOS (ro.com.zui.version 为空)"
    }

    val result: Result by lazy {
        Result(isZux = isZuxOS, zuiVersion = _cachedVersion)
    }

    val rawZuiVersion: String?
        get() {
            if (_versionCacheValid) return _cachedVersion
            val v = getSystemProperty("ro.com.zui.version", "")
            _cachedVersion = if (v.isNullOrBlank()) null else v
            _versionCacheValid = true
            return _cachedVersion
        }

    fun invalidateCache() { _cached = null; _versionCacheValid = false }

    private fun getSystemProperty(key: String, def: String): String {
        return try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, key, def) as? String ?: def
        } catch (_: Exception) { def }
    }
}
