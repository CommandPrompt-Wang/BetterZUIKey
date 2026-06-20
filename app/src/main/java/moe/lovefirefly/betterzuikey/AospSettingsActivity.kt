package moe.lovefirefly.betterzuikey

import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityAospSettingsBinding

/**
 * AOSP 原生辅助功能开关页面。
 * 控制四个系统级辅助功能的启用/禁用。
 * 写入 Settings.Secure 需要 WRITE_SECURE_SETTINGS 权限（通过 su pm grant 或 su settings put 获取）。
 */
class AospSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAospSettingsBinding
    private lateinit var cfg: Config

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityAospSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        cfg = Config.load()

        val cr = contentResolver
        val bounceEnabled = getSecureInt("accessibility_bounce_keys") == 1
        val mouseEnabled = getSecureInt("accessibility_mouse_keys_enabled") == 1
        val stickyEnabled = getSecureInt("accessibility_sticky_keys") == 1
        val slowEnabled = getSecureInt("accessibility_slow_keys") == 1

        binding.swBounceKeys.isChecked = bounceEnabled
        binding.swMouseKeys.isChecked = mouseEnabled
        binding.swStickyKeys.isChecked = stickyEnabled
        binding.swSlowKeys.isChecked = slowEnabled

        binding.swBounceKeys.setOnCheckedChangeListener { _, c ->
            setSecureInt("accessibility_bounce_keys", if (c) 1 else 0)
        }
        binding.swMouseKeys.setOnCheckedChangeListener { _, c ->
            setSecureInt("accessibility_mouse_keys_enabled", if (c) 1 else 0)
        }
        binding.swStickyKeys.setOnCheckedChangeListener { _, c ->
            setSecureInt("accessibility_sticky_keys", if (c) 1 else 0)
        }
        binding.swSlowKeys.setOnCheckedChangeListener { _, c ->
            setSecureInt("accessibility_slow_keys", if (c) 1 else 0)
        }

        // 点整行切换开关
        binding.cardBounceKeys.setOnClickListener { binding.swBounceKeys.toggle() }
        binding.cardMouseKeys.setOnClickListener { binding.swMouseKeys.toggle() }
        binding.cardStickyKeys.setOnClickListener { binding.swStickyKeys.toggle() }
        binding.cardSlowKeys.setOnClickListener { binding.swSlowKeys.toggle() }
    }

    private fun getSecureInt(key: String): Int {
        return try {
            Settings.Secure.getInt(contentResolver, key)
        } catch (_: Exception) { 0 }
    }

    /** 先尝试直接用 API 写入，权限不足则通过 su 写 */
    private fun setSecureInt(key: String, value: Int) {
        try {
            Settings.Secure.putInt(contentResolver, key, value)
        } catch (_: SecurityException) {
            Thread {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf(
                        "su", "-c", "settings put secure $key $value"
                    ))
                    // Consume stdout to prevent pipe buffer deadlock
                    proc.inputStream.bufferedReader().use { it.readText() }
                    proc.errorStream.bufferedReader().use { it.readText() }
                    proc.waitFor()
                } catch (e: Exception) {
                    android.util.Log.e("BetterZUIKey", "su settings put secure failed: $key", e)
                }
            }.start()
        }
    }
}
