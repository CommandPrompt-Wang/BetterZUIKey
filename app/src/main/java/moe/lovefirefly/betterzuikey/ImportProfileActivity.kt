package moe.lovefirefly.betterzuikey

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.databinding.ActivityImportProfileBinding

class ImportProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportProfileBinding

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return@registerForActivityResult
            importJson(json)
        } catch (e: Exception) {
            Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityImportProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rgMode.setOnCheckedChangeListener { _, id ->
            binding.etJson.visibility = if (id == binding.rbText.id)
                android.view.View.VISIBLE else android.view.View.GONE
        }

        binding.btnSubmit.setOnClickListener {
            if (binding.rbFile.isChecked) {
                filePicker.launch(arrayOf("application/json", "*/*"))
            } else {
                val text = binding.etJson.text.toString().trim()
                if (text.isBlank()) {
                    Toast.makeText(this, "请输入 JSON 内容", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                try {
                    importJson(text)
                } catch (e: Exception) {
                    Toast.makeText(this, "JSON 解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun importJson(json: String) {
        val type = object : TypeToken<Map<String, KeyboardProfiles.Profile>>() {}.type
        val imported: Map<String, KeyboardProfiles.Profile> = Gson().fromJson(json, type)
        if (imported.isEmpty()) {
            Toast.makeText(this, "未找到有效的 profile 数据", Toast.LENGTH_SHORT).show()
            return
        }
        val cfg = Config.load()
        val ts = System.currentTimeMillis()
        imported.forEach { (_, v) ->
            val safeName = v.friendlyName.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff_-]"), "_")
            val key = "${safeName}_$ts"
            cfg.fnCustomProfiles[key] = v
        }
        cfg.save()
        Toast.makeText(this, "已导入 ${imported.size} 个配置", Toast.LENGTH_SHORT).show()
        finish()
    }
}
