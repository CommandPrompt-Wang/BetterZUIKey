package moe.lovefirefly.betterzuikey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import moe.lovefirefly.betterzuikey.databinding.ActivityKeyboardDetectBinding
import moe.lovefirefly.betterzuikey.Config.Config
import java.lang.reflect.Method

class KeyboardDetectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeyboardDetectBinding
    private var detected = false
    private var currentKeyCode: Int? = null
    private var currentScanCode: Int? = null
    private var lastKeyProp: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var spGetMethod: Method? = null

    // F1-F12 对应的 keyCode 值，null 表示未填写
    private val fKeyCodes = arrayOfNulls<Int>(12)
    // RadioButton 数组
    private val radioButtons = arrayOfNulls<RadioButton>(12)
    // 显示 keyCode 的 TextView 数组
    private val valueTexts = arrayOfNulls<TextView>(12)

    private val propPollRunnable = object : Runnable {
        override fun run() {
            readSystemProperty()
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityKeyboardDetectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnCopyTemplate.setOnClickListener { copyTemplate() }
        binding.btnCopyVidpid.setOnClickListener { copyVidPid() }
        binding.btnFill.setOnClickListener { fillSelectedRow() }

        // 初始化 SystemProperties 反射
        try {
            val cls = Class.forName("android.os.SystemProperties")
            spGetMethod = cls.getMethod("get", String::class.java, String::class.java)
        } catch (_: Exception) { }

        // 构建 F1-F12 表格行
        buildFKeyRows()

        // 默认选中第一个
        radioButtons[0]?.isChecked = true

        // 启动轮询
        handler.post(propPollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(propPollRunnable)
    }

    private fun buildFKeyRows() {
        val rg = binding.rgFkeys
        rg.removeAllViews()
        for (i in 0 until 12) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
            }

            // F1-F12 标签
            val label = TextView(this).apply {
                text = "F${i + 1}"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { width = dp(48) }
            }
            row.addView(label)

            // keyCode 值
            val valueTv = TextView(this).apply {
                text = "请输入"
                textSize = 14f
                setPadding(dp(8))
                setTextColor(getColor(android.R.color.tab_indicator_text))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            row.addView(valueTv)
            valueTexts[i] = valueTv

            // RadioButton（手动管理单选）
            val idx = i
            val rb = RadioButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        radioButtons.forEachIndexed { j, other ->
                            if (j != idx) other?.isChecked = false
                        }
                    }
                }
            }
            row.addView(rb)
            radioButtons[i] = rb

            rg.addView(row)
        }
    }

    private fun fillSelectedRow() {
        val keyCode = currentKeyCode ?: return
        val selIdx = radioButtons.indexOfFirst { it?.isChecked == true }
        if (selIdx < 0) {
            Toast.makeText(this, "请先在表格中选中目标 F 键", Toast.LENGTH_SHORT).show()
            return
        }
        fKeyCodes[selIdx] = keyCode
        valueTexts[selIdx]?.text = keyCode.toString()
        // 自动跳到下一行，越界则回到第一个
        val nextIdx = (selIdx + 1) % 12
        radioButtons[selIdx]?.isChecked = false
        radioButtons[nextIdx]?.isChecked = true
    }

    private fun readSystemProperty() {
        val method = spGetMethod ?: return
        try {
            // 读取 keyCode
            val value = method.invoke(null, "debug.bzuikey.last_key", "") as String
            if (value.isNotBlank() && value != lastKeyProp) {
                lastKeyProp = value
                val parts = value.split(":")
                if (parts.size >= 2) {
                    val keyCode = parts[0].toIntOrNull() ?: return
                    val scanCode = parts[1].toIntOrNull() ?: return
                    currentKeyCode = keyCode
                    currentScanCode = scanCode
                    updateKeycodeDisplay(keyCode, scanCode)
                }
            }
            // 读取 VID:PID（首次检测）
            if (!detected) {
                val devInfo = method.invoke(null, "debug.bzuikey.dev_info", "") as String
                if (devInfo.isNotBlank()) {
                    val dp = devInfo.split(":", limit = 3)
                    if (dp.size >= 2) {
                        val vid = dp[0].toIntOrNull() ?: 0
                        val pid = dp[1].toIntOrNull() ?: 0
                        if (vid != 0 || pid != 0) {
                            detected = true
                            val vidPid = String.format("%04x:%04x", vid, pid)
                            binding.tvVidpid.text = vidPid
                            binding.tvDeviceName.text = if (dp.size >= 3) dp[2] else ""
                            binding.layoutResult.visibility = View.VISIBLE
                            binding.tvHint.text = "选中目标 F 键 → 按键 → 获取并填入"
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun updateKeycodeDisplay(keyCode: Int, scanCode: Int) {
        val keyCodeStr = try {
            KeyEvent.keyCodeToString(keyCode)
        } catch (_: Exception) {
            keyCode.toString()
        }
        val scanCodeStr = if (scanCode > 0) "0x%x".format(scanCode) else "无"
        binding.tvKeycodeInfo.text = "按键: $keyCode ($keyCodeStr) / 扫描码: $scanCodeStr"
    }

    private fun copyVidPid() {
        val text = binding.tvVidpid.text.toString()
        if (text.isNotBlank() && text != "0000:0000") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("VID:PID", text))
            Toast.makeText(this, "已复制 $text", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyTemplate() {
        val vidPid = binding.tvVidpid.text.toString()
        if (vidPid.isBlank() || vidPid == "0000:0000") {
            Toast.makeText(this, "请先按下键盘按键进行检测", Toast.LENGTH_SHORT).show()
            return
        }
        val deviceName = binding.tvDeviceName.text.toString().ifBlank { "自定义键盘" }
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"profiles\": {\n")
        sb.append("    \"$vidPid\": {\n")
        sb.append("      \"name\": \"$deviceName\",\n")
        sb.append("      \"friendlyName\": \"$deviceName\",\n")
        sb.append("      \"keys\": {\n")
        for (i in 0 until 12) {
            val v = fKeyCodes[i]
            val valStr = v?.toString() ?: "【请填写】"
            sb.append("        \"F${i + 1}\": { \"keyCode\": $valStr }")
            if (i < 11) sb.append(",")
            sb.append("\n")
        }
        sb.append("      }\n")
        sb.append("    }\n")
        sb.append("  }\n")
        sb.append("}")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("配置", sb.toString()))
        Toast.makeText(this, "已复制配置文件", Toast.LENGTH_SHORT).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val device = event.device ?: return super.dispatchKeyEvent(event)
        val vid = device.vendorId
        val pid = device.productId

        if (vid == 0 && pid == 0) return super.dispatchKeyEvent(event)

        val keyCode = event.keyCode
        val scanCode = event.scanCode
        currentKeyCode = keyCode
        currentScanCode = scanCode
        updateKeycodeDisplay(keyCode, scanCode)

        if (!detected) {
            detected = true
            val vidPid = String.format("%04x:%04x", vid, pid)
            binding.tvVidpid.text = vidPid
            binding.tvDeviceName.text = device.name
            binding.layoutResult.visibility = View.VISIBLE
            binding.tvHint.text = "选中目标 F 键 → 按键 → 获取并填入"
        }

        return true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
