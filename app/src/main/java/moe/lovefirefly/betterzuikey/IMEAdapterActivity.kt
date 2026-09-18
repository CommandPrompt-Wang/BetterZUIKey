package moe.lovefirefly.betterzuikey

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Config.Config.IMEBinding
import moe.lovefirefly.betterzuikey.databinding.ActivityImeAdapterBinding
import moe.lovefirefly.betterzuikey.databinding.ItemImeRowBinding
import moe.lovefirefly.betterzuikey.ime.IMEProfile
import moe.lovefirefly.betterzuikey.ime.IMEProfileManager
import moe.lovefirefly.betterzuikey.ime.Strategy

/**
 * 输入法增强 —— 两段式配置界面。
 *
 * 上面「使用系统框架」= 由**系统输入法框架**接管（strategy=framework，输入法自己暴露 subtype）；
 * 下面「重映射快捷键」  = 拦截该输入法**原本的**语言切换快捷键，改由 BZK 执行切换
 * （strategy=keyremap，右侧下拉选它原本用哪个组合键）。
 *
 * 底层仍是 [IMEProfile] / [IMEProfileManager]，这里只换了一层界面：
 * 两种模式各是一个列表，行内勾选框即 [IMEProfile.enabled]。
 * 同一条输入法在两个模式都开启时，框架优先（见 [IMEProfileManager.executeForIME]）。
 */
class IMEAdapterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImeAdapterBinding

    /** 重映射下拉的可选项（复用既有的 IMEBinding）。 */
    private val remapBindings = listOf(
        IMEBinding.FOLLOW_SYSTEM,
        IMEBinding.CTRL_SHIFT,
        IMEBinding.CTRL_SPACE,
        IMEBinding.ALT_SHIFT,
        IMEBinding.RIGHT_ALT,
        IMEBinding.WIN,
        IMEBinding.OFF,
        IMEBinding.BLOCK,
    )

    private val pickFramework = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onPicked(result.resultCode, result.data, Strategy.framework) }

    private val pickRemap = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onPicked(result.resultCode, result.data, Strategy.keyremap) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Config.load().dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityImeAdapterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val cfg = Config.load()

        binding.swEnabled.isChecked = cfg.imeMasterEnabled
        binding.swEnabled.setOnCheckedChangeListener { _, checked ->
            cfg.imeMasterEnabled = checked
            cfg.save()
            Config.syncToSharedPrefs(this, cfg)
        }
        binding.swToast.isChecked = cfg.imeToastEnabled
        binding.swToast.setOnCheckedChangeListener { _, checked ->
            cfg.imeToastEnabled = checked
            cfg.save()
            Config.syncToSharedPrefs(this, cfg)
        }
        binding.rowImeEnabled.setOnClickListener { binding.swEnabled.toggle() }
        binding.rowImeToast.setOnClickListener { binding.swToast.toggle() }

        binding.btnAddFramework.setOnClickListener { launchPicker(pickFramework) }
        binding.btnAddRemap.setOnClickListener { launchPicker(pickRemap) }

        binding.tvImport.setOnClickListener {
            startActivity(Intent(this, IMEImportActivity::class.java))
        }
        // 恢复内置配置：把 BUILTIN_DEFAULTS upsert 回来（用户自建条目不动）
        binding.tvRestoreBuiltins.setOnClickListener {
            IMEProfileManager.restoreBuiltins(this)
            refresh()
            Toast.makeText(this, getString(R.string.restore_done), Toast.LENGTH_SHORT).show()
        }
        binding.tvMakeProfile.setOnClickListener {
            startActivity(Intent(this, ProfileMakerActivity::class.java))
        }
        binding.tvForceUpdate.setOnClickListener {
            IMEProfileManager.seedBuiltinsIfEmpty(this)
            Toast.makeText(this, getString(R.string.ime_force_update_done_toast), Toast.LENGTH_SHORT).show()
        }

        // 内置默认配置要落在本地（system_server 写不了 App 的 filesDir）
        IMEProfileManager.seedBuiltinsIfEmpty(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ────────────────────────── 列表渲染 ──────────────────────────

    private fun refresh() {
        val profiles = IMEProfileManager.getAllProfiles()
        render(binding.listFramework, profiles.filter { it.strategy == Strategy.framework }, true)
        render(binding.listRemap, profiles.filter { it.strategy == Strategy.keyremap }, false)
        renderInstalled()
    }

    private fun render(
        container: android.widget.LinearLayout,
        items: List<IMEProfile>,
        framework: Boolean,
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (profile in items) container.addView(buildRow(inflater, container, profile, framework))
    }

    private fun buildRow(
        inflater: LayoutInflater,
        container: android.widget.LinearLayout,
        profile: IMEProfile,
        framework: Boolean,
    ): View {
        val row = ItemImeRowBinding.inflate(inflater, container, false)
        row.tvName.text = rowTitle(profile)
        row.tvPkg.text = profile.ime ?: ""

        // 非内置条目可改显示名（内置名由代码定义，改了也会被"恢复内置配置"覆盖）
        if (IMEProfile.isBuiltin(profile.uuid)) {
            row.tvName.setOnClickListener(null)
            row.tvName.isClickable = false
        } else {
            row.tvName.isClickable = true
            row.tvName.setOnClickListener { showRenameDialog(profile) }
        }

        // 勾选框 = profile.enabled
        row.cbEnabled.setOnCheckedChangeListener(null)
        row.cbEnabled.isChecked = profile.enabled
        row.cbEnabled.setOnCheckedChangeListener { _, checked ->
            applyProfile(profile.copy(enabledRaw = checked))
        }

        if (framework) {
            // 框架模式没有快捷键可选；**长按这一条**排它自己的语言轮转顺序
            row.tilSpin.visibility = View.GONE
            val openOrder = View.OnLongClickListener {
                startActivity(
                    Intent(this, SubtypeOrderActivity::class.java)
                        .putExtra(SubtypeOrderActivity.EXTRA_IME_PACKAGE, profile.ime)
                )
                true
            }
            // 子 View（名字/包名）会把长按事件吃在自己身上，所以每处都要挂
            row.root.setOnLongClickListener(openOrder)
            row.tvName.setOnLongClickListener(openOrder)
            row.tvPkg.setOnLongClickListener(openOrder)
        } else {
            row.tilSpin.visibility = View.VISIBLE
            val names = remapBindings.map { getBindingName(it) }
            val currentName = getBindingName(bindingOf(profile.remapTo))
            val adapter = ArrayAdapter(this, R.layout.dropdown_item_wrap, names)
            row.spin.setAdapter(adapter)
            row.spin.setText(currentName, false)
            row.spin.setOnItemClickListener { _, _, pos, _ ->
                val picked = remapBindings[pos]
                val raw = rawRemapName(picked)
                if (raw != profile.remapTo) {
                    applyProfile(profile.copy(remapTo = raw))
                }
            }
        }

        // 删除（右下角，spinner 下面）
        row.tvDelete.setOnClickListener { confirmDelete(profile) }
        return row.root
    }

    /**
     * 落盘一处修改。
     *
     * <p>必须同时 `saveToConfig`（写 SP + 推送/通知 system_server）—— 只 `putProfile`
     * 的话改动仅存在于内存，进程重启就丢。
     */
    private fun applyProfile(updated: IMEProfile) {
        IMEProfileManager.putProfile(updated)
        IMEProfileManager.saveToConfig(this)
    }

    /** 重命名非内置条目。 */
    private fun showRenameDialog(profile: IMEProfile) {
        val input = android.widget.EditText(this).apply {
            setText(profile.name ?: "")
            selectAll()
            isSingleLine = true
            setPadding(48, 32, 48, 0)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                applyProfile(profile.copy(name = newName.ifBlank { null }))
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 删除一条配置（界面上的删除按钮）。
     *
     * <p>内置条目（[IMEProfile.BUILTIN_DEFAULTS]，uuid 带 `bzuikey-builtin-` 前缀）不给删：
     * 它们的名字与默认值由代码定义，删掉也会被 [IMEProfileManager.seedBuiltinsIfEmpty]
     * 或「恢复内置配置」原样补回来，所以直接拦掉并提示。
     *
     * <p>删除必须按「包名 + 策略」精确匹配 —— 同一个输入法可以在两段里各有一条，
     * 只按包名删会把另一段一起带走。
     */
    private fun confirmDelete(profile: IMEProfile) {
        if (IMEProfile.isBuiltin(profile.uuid)) {
            Toast.makeText(this, R.string.ime_builtin_nodelete, Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_msg, rowTitle(profile)))
            .setPositiveButton(R.string.profile_delete_confirm) { _, _ ->
                IMEProfileManager.removeProfile(profile.ime, profile.strategy)
                IMEProfileManager.saveToConfig(this)
                refresh()
                Toast.makeText(this, R.string.profile_deleted_toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_confirm_cancel, null)
            .show()
    }

    /** 底部「当前已安装」：扫 moe.lovefirefly.bzk 前缀的模块，一行一个。 */
    private fun renderInstalled() {
        binding.listInstalled.removeAllViews()
        val pm = packageManager
        val rows = pm.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it.startsWith("moe.lovefirefly.bzk") }
            .sorted()
        for (pkg in rows) {
            val tv = TextView(this)
            tv.text = "$pkg\n${displayName(pkg)}"
            tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            tv.setPadding(0, 4, 0, 4)
            binding.listInstalled.addView(tv)
        }
        binding.listInstalled.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    // ────────────────────────── 添加应用 ──────────────────────────

    private fun launchPicker(launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
        launcher.launch(Intent(this, AppPickerActivity::class.java))
    }

    private fun onPicked(resultCode: Int, data: Intent?, strategy: Strategy) {
        if (resultCode != RESULT_OK) return
        val pkgs = data?.getStringArrayListExtra("packages") ?: return
        if (pkgs.isEmpty()) return
        var added = 0
        var duplicate = false
        for (pkg in pkgs) {
            val exists = IMEProfileManager.getAllProfiles()
                .any { it.ime == pkg && it.strategy == strategy }
            if (exists) {
                duplicate = true
                continue
            }
            applyProfile(
                IMEProfile(
                    ime = pkg,
                    strategy = strategy,
                    name = displayName(pkg),
                    uuid = IMEProfile.generateUUID(),
                    remapTo = if (strategy == Strategy.keyremap) "Ctrl+Space" else null,
                    enabledRaw = true,
                )
            )
            added++
        }
        if (duplicate) Toast.makeText(this, R.string.ime_already_added, Toast.LENGTH_SHORT).show()
        if (added > 0) refresh()
    }

    // ────────────────────────── 显示辅助 ──────────────────────────

    /**
     * 行标题：**已安装的应用优先显示真实应用名**（跟着系统语言走），
     * 取不到（没装 / 查不到）才回退到 profile 里存的内置名。
     */
    private fun rowTitle(profile: IMEProfile): String {
        val real = appLabel(profile.ime)
        if (!real.isNullOrBlank()) return real
        return profile.name ?: profile.ime ?: ""
    }

    /** 应用真实名称；未安装返回 null。 */
    private fun appLabel(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(
                    pkg, PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, 0)
            }
            packageManager.getApplicationLabel(info)?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    /** 添加应用时的初始名字：优先真实应用名，取不到才用包名。 */
    private fun displayName(pkg: String?): String {
        if (pkg.isNullOrBlank()) return ""
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(
                    pkg, PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        } catch (t: Throwable) {
            pkg
        }
    }

    private fun bindingOf(remap: String?): IMEBinding = when (remap?.trim()?.lowercase()) {
        "ctrl+shift" -> IMEBinding.CTRL_SHIFT
        "ctrl+space" -> IMEBinding.CTRL_SPACE
        "alt+shift" -> IMEBinding.ALT_SHIFT
        "right alt", "rightalt" -> IMEBinding.RIGHT_ALT
        "win", "meta" -> IMEBinding.WIN
        "off" -> IMEBinding.OFF
        "block" -> IMEBinding.BLOCK
        else -> IMEBinding.FOLLOW_SYSTEM
    }

    private fun rawRemapName(binding: IMEBinding): String = when (binding) {
        IMEBinding.FOLLOW_SYSTEM -> ""
        IMEBinding.CTRL_SHIFT -> "Ctrl+Shift"
        IMEBinding.CTRL_SPACE -> "Ctrl+Space"
        IMEBinding.ALT_SHIFT -> "Alt+Shift"
        IMEBinding.RIGHT_ALT -> "Right Alt"
        IMEBinding.WIN -> "Win"
        IMEBinding.OFF -> "Off"
        IMEBinding.BLOCK -> "Block"
    }

    private fun getBindingName(binding: IMEBinding): String = when (binding) {
        IMEBinding.FOLLOW_SYSTEM -> getString(R.string.ime_binding_follow_system)
        IMEBinding.CTRL_SHIFT -> getString(R.string.ime_binding_ctrl_shift)
        IMEBinding.CTRL_SPACE -> getString(R.string.ime_binding_ctrl_space)
        IMEBinding.ALT_SHIFT -> getString(R.string.ime_binding_alt_shift)
        IMEBinding.RIGHT_ALT -> getString(R.string.ime_binding_right_alt)
        IMEBinding.WIN -> getString(R.string.ime_binding_win, Config.load().metaKeyLabel)
        IMEBinding.OFF -> getString(R.string.ime_binding_off)
        IMEBinding.BLOCK -> getString(R.string.ime_binding_block)
    }

    companion object {
        fun getBindingNameStatic(ctx: Context, binding: IMEBinding): String = when (binding) {
            IMEBinding.FOLLOW_SYSTEM -> ctx.getString(R.string.ime_binding_follow_system)
            IMEBinding.CTRL_SHIFT -> ctx.getString(R.string.ime_binding_ctrl_shift)
            IMEBinding.CTRL_SPACE -> ctx.getString(R.string.ime_binding_ctrl_space)
            IMEBinding.ALT_SHIFT -> ctx.getString(R.string.ime_binding_alt_shift)
            IMEBinding.RIGHT_ALT -> ctx.getString(R.string.ime_binding_right_alt)
            IMEBinding.WIN -> ctx.getString(R.string.ime_binding_win, Config.load().metaKeyLabel)
            IMEBinding.OFF -> ctx.getString(R.string.ime_binding_off)
            IMEBinding.BLOCK -> ctx.getString(R.string.ime_binding_block)
        }
    }
}
