package moe.lovefirefly.betterzuikey

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.color.DynamicColors
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityMainBinding
import moe.lovefirefly.betterzuikey.Utils.ZuiDetector

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val titles = arrayOf("主页", "快捷键", "模板", "设置")

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        // 动态调色板：必须在 setContentView 之前调用
        if (Config.load().dynamicColorEnabled) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 0=主页  1=快捷键  2=模板  3=设置
        val adapter = TabAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 3

        // 自动通过 su 授予 WRITE_SECURE_SETTINGS
        grantSecureSettings()

        binding.bottomNav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_home      -> 0
                R.id.nav_shortcuts -> 1
                R.id.nav_templates -> 2
                R.id.nav_settings  -> 3
                else -> return@setOnItemSelectedListener false
            }
            binding.viewPager.setCurrentItem(target, false)
            true
        }

        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNav.menu.getItem(position).isChecked = true
                binding.toolbar.title = titles[position]
            }
        })
    }

    private class TabAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 4
        override fun getItemId(position: Int): Long = position.toLong()
        override fun containsItem(itemId: Long): Boolean = itemId in 0L..3L
        override fun createFragment(pos: Int): Fragment = when (pos) {
            0 -> HomeFragment()
            1 -> GlobalFragment()
            2 -> TemplatesFragment()
            3 -> SettingsFragment()
            else -> throw IllegalArgumentException()
        }
    }

    /** 主页 Fragment */
    class HomeFragment : Fragment(R.layout.fragment_home) {
        override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            refreshStatus(view)
        }

        override fun onResume() {
            super.onResume()
            view?.let { refreshStatus(it) }
        }

        private fun refreshStatus(view: android.view.View) {
            val cfg = Config.load()
            val r = ZuiDetector.result
            view.findViewById<android.widget.TextView>(R.id.tv_status)?.text =
                "BetterZUIKey v1.0"
            view.findViewById<android.widget.TextView>(R.id.tv_module_status)?.text =
                if (r.isZux) "✅ 已激活 — ${r.detail}"
                else "❌ 未激活 — ${r.detail}"

            view.findViewById<android.view.View>(R.id.card_help)?.setOnClickListener {
                startActivity(android.content.Intent(requireContext(), HelpActivity::class.java))
            }
        }
    }

        companion object {
        /** 在 setContentView 之前调用，按配置应用夜间模式 */
        fun applyTheme() {
            val cfg = Config.load()
            val nightMode = when (cfg.nightMode) {
                1 -> AppCompatDelegate.MODE_NIGHT_YES
                2 -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    private fun grantSecureSettings() {
        try {
            Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "pm grant moe.lovefirefly.betterzuikey android.permission.WRITE_SECURE_SETTINGS"
            ))
        } catch (_: Exception) { }
    }
}