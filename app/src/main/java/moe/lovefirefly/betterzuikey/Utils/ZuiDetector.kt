package moe.lovefirefly.betterzuikey.Utils

/**
 * ZUXOS 系统检测。
 * 依据: ro.com.zui.version 非空 (ro.build.zui.version 在 17.5 上为空)
 */
object ZuiDetector {

    private var _cached: Boolean? = null

    val isZuxOS: Boolean
        get() {
            if (_cached != null) return _cached!!
            _cached = !rawZuiVersion.isNullOrBlank()
            return _cached!!
        }

    data class Result(val isZux: Boolean, val zuiVersion: String?) {
        val detail: String
            get() = if (isZux) "ZUXOS $zuiVersion"
            else "非 ZUXOS (ro.com.zui.version 为空)"
    }

    val result: Result by lazy {
        Result(isZux = isZuxOS, zuiVersion = rawZuiVersion)
    }

    val rawZuiVersion: String?
        get() = try {
            val v = getSystemProperty("ro.com.zui.version", "")
            if (v.isNullOrBlank()) null else v
        } catch (_: Exception) { null }

    fun invalidateCache() { _cached = null }

    private fun getSystemProperty(key: String, def: String): String {
        return try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, key, def) as? String ?: def
        } catch (_: Exception) { def }
    }
}
