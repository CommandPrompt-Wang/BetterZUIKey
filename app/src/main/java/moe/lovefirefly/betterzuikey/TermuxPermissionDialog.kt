package moe.lovefirefly.betterzuikey

import android.content.Context
import android.text.Html
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton

object TermuxPermissionDialog {

    const val LINK_URL = "betterzuikey://termux-permission"

    fun show(context: Context) {
        val view = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_termux_permission, null)
        val tvMessage = view.findViewById<TextView>(R.id.tv_termux_perm_message)
        val granted = TermuxPermissionHelper.isGranted(context)
        tvMessage.text = if (granted) {
            context.getString(R.string.dialog_termux_perm_status_granted) + "\n\n" +
                context.getString(R.string.dialog_termux_perm_message)
        } else {
            context.getString(R.string.dialog_termux_perm_message)
        }
        view.findViewById<TextView>(R.id.tv_termux_perm_cmd_adb).text =
            TermuxPermissionHelper.adbGrantCommand(context.packageName)
        view.findViewById<TextView>(R.id.tv_termux_perm_cmd_su).text =
            TermuxPermissionHelper.suGrantCommand(context.packageName)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.dialog_termux_perm_title)
            .setView(view)
            .create()

        view.findViewById<MaterialButton>(R.id.btn_termux_perm_close).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_termux_perm_try).setOnClickListener {
            tryGrant(context, dialog)
        }
        dialog.show()
    }

    /** Wire the Termux-permission link in script-editor description HTML. */
    fun bindDescriptionLink(tvDesc: TextView, context: Context) {
        val spannable = Html.fromHtml(
            context.getString(R.string.dialog_app_key_command_desc),
            Html.FROM_HTML_MODE_COMPACT,
        ) as Spannable
        spannable.getSpans(0, spannable.length, URLSpan::class.java).forEach { span ->
            if (span.url != LINK_URL) return@forEach
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            spannable.removeSpan(span)
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    show(context)
                }
            }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tvDesc.text = spannable
        tvDesc.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun tryGrant(context: Context, parentDialog: AlertDialog) {
        val btnTry = parentDialog.findViewById<MaterialButton>(R.id.btn_termux_perm_try)
        btnTry?.isEnabled = false

        Thread({
            val suError = TermuxPermissionHelper.tryGrantViaSu(context)
            if (suError == null) {
                parentDialog.window?.decorView?.post {
                    btnTry?.isEnabled = true
                    parentDialog.dismiss()
                    (context as? MainActivity)?.showWarningBanner(
                        context.getString(R.string.dialog_termux_perm_success),
                        copyable = false,
                        timeoutMs = 2500,
                    )
                }
                return@Thread
            }

            parentDialog.window?.decorView?.post {
                val loading = AlertDialog.Builder(context)
                    .setView(R.layout.dialog_termux_grant_loading)
                    .setCancelable(false)
                    .create()
                loading.setCanceledOnTouchOutside(false)
                loading.show()
                loading.window?.setLayout(
                    (context.resources.displayMetrics.widthPixels * 0.72f).toInt(),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                )

                Thread({
                    TermuxPermissionHelper.requestGrantViaProxy(context)
                    Thread.sleep(1500)
                    val granted = TermuxPermissionHelper.isGranted(context)
                    parentDialog.window?.decorView?.post {
                        loading.dismiss()
                        btnTry?.isEnabled = true
                        if (granted) {
                            parentDialog.dismiss()
                            (context as? MainActivity)?.showWarningBanner(
                                context.getString(R.string.dialog_termux_perm_success),
                                copyable = false,
                                timeoutMs = 2500,
                            )
                        } else {
                            (context as? MainActivity)?.showWarningBanner(
                                context.getString(
                                    R.string.dialog_termux_perm_failed,
                                    suError,
                                ),
                                copyable = true,
                                timeoutMs = 8000,
                            )
                        }
                    }
                }, "TermuxPermProxy").start()
            }
        }, "TermuxPermSu").start()
    }
}
