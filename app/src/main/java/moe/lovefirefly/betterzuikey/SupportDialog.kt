package moe.lovefirefly.betterzuikey

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.io.File

/**
 * “求投喂”对话框（赞助 / 关注提示）。
 *
 * 每个版本提示一次：
 *  - 点击“此版本不再提示”→ 把当前 **版本号**（BuildConfig.VERSION_NAME，如 1.7.0）
 *    写入 filesDir 下的持久文件（注意不是版本代号 versionCode）；
 *  - 下次启动时读取该文件：文件不存在 → 弹；当前版本号 > 记录的版本号 → 弹；否则不弹。
 */
object SupportDialog {

    /** 持久文件名（App 私有目录，卸载才会清除） */
    private const val FILE_NAME = "support_prompt.json"
    private const val KEY_VERSION = "dismissed_version"

    const val AFDIAN_URL = "https://afdian.com/a/CommandPrompt"

    private fun recordFile(context: Context) = File(context.filesDir, FILE_NAME)

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    /** 读取已记录的版本号；无记录返回 null */
    fun dismissedVersion(context: Context): String? = try {
        val f = recordFile(context)
        if (!f.exists()) {
            null
        } else {
            JSONObject(f.readText()).optString(KEY_VERSION).takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 是否应该弹出求投喂对话框。
     * 文件不存在 → true；当前版本号比记录的新 → true；否则 false。
     */
    fun shouldShow(context: Context): Boolean {
        val recorded = dismissedVersion(context) ?: return true
        return compareVersions(BuildConfig.VERSION_NAME, recorded) > 0
    }

    /** 记录“此版本不再提示”（写入的是版本号，不是版本代号） */
    fun markDismissed(context: Context) {
        try {
            recordFile(context).writeText(
                JSONObject().put(KEY_VERSION, BuildConfig.VERSION_NAME).toString()
            )
        } catch (_: Exception) {
            // 写失败就算了，最多下次再弹一次
        }
    }

    /** 调试用：清除记录，让对话框重新具备弹出条件 */
    fun clearDismissed(context: Context) {
        try {
            recordFile(context).delete()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // 版本号比较（"1.7.0" 这类字符串）
    // ------------------------------------------------------------------

    /** a > b 返回正数，a < b 返回负数，相等返回 0 */
    fun compareVersions(a: String, b: String): Int {
        val (aNum, aSuffix) = splitVersion(a)
        val (bNum, bSuffix) = splitVersion(b)
        val ap = aNum.split('.').map { it.toIntOrNull() ?: 0 }
        val bp = bNum.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val x = ap.getOrElse(i) { 0 }
            val y = bp.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        // 数字部分相同：无后缀 > 有后缀（1.7.0 > 1.7.0-beta）
        return when {
            aSuffix == bSuffix -> 0
            aSuffix.isEmpty() -> 1
            bSuffix.isEmpty() -> -1
            else -> aSuffix.compareTo(bSuffix)
        }
    }

    /** "v1.7.0-beta" → ("1.7.0", "beta") */
    private fun splitVersion(v: String): Pair<String, String> {
        val s = v.trim().removePrefix("v").removePrefix("V")
        val idx = s.indexOf('-')
        return if (idx < 0) s to "" else s.substring(0, idx) to s.substring(idx + 1)
    }

    // ------------------------------------------------------------------
    // 显示
    // ------------------------------------------------------------------

    /**
     * 弹出求投喂对话框。
     * @param onDismiss 对话框关闭时回调（用于放行 [ModalDialogGate] 里排队的下一个弹窗）。
     *        调用方需保证 [context] 是未 finishing 的 Activity。
     */
    fun show(context: Context, onDismiss: (() -> Unit)? = null) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_support, null)

        // 正文含 <a href>，用 Html 解析并让链接可点
        val body = view.findViewById<TextView>(R.id.tv_support_body)
        body.text = Html.fromHtml(
            context.getString(R.string.support_body), Html.FROM_HTML_MODE_LEGACY
        )
        body.movementMethod = LinkMovementMethod.getInstance()

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()

        dialog.setOnDismissListener { onDismiss?.invoke() }

        view.findViewById<TextView>(R.id.btn_support_ok).setOnClickListener {
            dialog.dismiss()
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AFDIAN_URL)))
            } catch (_: Exception) {
            }
        }
        view.findViewById<TextView>(R.id.btn_support_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.btn_support_never).setOnClickListener {
            markDismissed(context)
            dialog.dismiss()
        }

        dialog.show()

        // 收窄 / 定宽：大屏上 AlertDialog 会按内容拉得很宽，这里限制到屏宽的 72%（上限 560dp）；
        // 高度交给内容撑开（右侧图片占 1/3 宽，两张叠起来足够高）
        val dm = context.resources.displayMetrics
        val maxWidth = (560 * dm.density).toInt()
        val targetWidth = minOf((dm.widthPixels * 0.72f).toInt(), maxWidth)
        dialog.window?.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
