package moe.lovefirefly.betterzuikey

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import moe.lovefirefly.betterzuikey.Config.Config
import moe.lovefirefly.betterzuikey.Utils.LogHelper
import moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel
import moe.lovefirefly.betterzuikey.ime.SubtypeRotation

/**
 * 语言轮转顺序（**按输入法一份**）。
 *
 * 「使用系统框架」时切换快捷键按这个顺序轮转语言；入口是输入法适配页里
 * **长按那一条框架方案**（见 [IMEAdapterActivity]）。
 *
 * 交互与搜狗那边的语言顺序页一致：RecyclerView + ItemTouchHelper **长按拖动**、
 * 拖动时 elevation/缩放反馈、**松手才落盘**；行是代码拼的，没有额外 layout 文件。
 *
 * 顺序键 = 语言标签（无标签那条是 [SubtypeRotation.KEY_TAGLESS]），
 * 与 system_server 侧（`IMEDispatcher`）算出来的**完全一致** —— 界面顺序就是真会切的顺序。
 */
class SubtypeOrderActivity : AppCompatActivity() {

    companion object {
        /** 要排哪个输入法的顺序（包名）——不传则用当前输入法。 */
        const val EXTRA_IME_PACKAGE = "ime_package"
    }

    private class Entry(val key: String, val label: String)

    private val items = ArrayList<Entry>()
    private lateinit var adapter: Adapter
    private lateinit var cfg: Config
    private var imePkg: String = ""
    private var currentKey: String? = null
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load()
        if (cfg.dynamicColorEnabled) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        pad = (16 * resources.displayMetrics.density).toInt()
        imePkg = intent.getStringExtra(EXTRA_IME_PACKAGE)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: currentImePackage().orEmpty()

        setContentView(buildUi())
        load()
    }

    // ────────────────────────── 界面 ──────────────────────────

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(this).apply {
            text = getString(R.string.subtype_order_title, appLabel(imePkg) ?: imePkg)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
            setPadding(pad * 2, pad * 2, pad * 2, pad / 2)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.subtype_order_hint)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(pad * 2, 0, pad * 2, pad / 2)
        })
        // 「框架顺序」= 清掉这份顺序，回到输入法自己声明的顺序
        root.addView(TextView(this).apply {
            text = getString(R.string.subtype_order_reset)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(pad * 2, pad / 2, pad * 2, pad / 2)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                cfg.imeSubtypeOrders = LinkedHashMap(cfg.imeSubtypeOrders).apply { put(imePkg, "") }
                cfg.save()
                Config.syncToSharedPrefs(this@SubtypeOrderActivity, cfg)
                load()
            }
        })

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SubtypeOrderActivity)
            clipToPadding = false
            setPadding(0, pad / 2, 0, pad * 2)
            // 项数很少：让整页滚动、列表按内容撑开（拖动仍然可用）
            isNestedScrollingEnabled = false
        }
        adapter = Adapter()
        rv.adapter = adapter
        attachDrag(rv)

        root.addView(NestedScrollView(this).apply { addView(rv) })
        return root
    }

    /** 长按拖动（与搜狗的语言顺序页同一套）。 */
    private fun attachDrag(rv: RecyclerView) {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                view: RecyclerView, src: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                @Suppress("DEPRECATION")
                val from = src.adapterPosition
                @Suppress("DEPRECATION")
                val to = target.adapterPosition
                if (from == to || from < 0 || to < 0) return false
                items.add(to, items.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onChildDraw(
                canvas: Canvas, view: RecyclerView, holder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                    holder.itemView.elevation = 12f
                    holder.itemView.scaleX = 0.98f
                    holder.itemView.scaleY = 0.98f
                }
                super.onChildDraw(canvas, view, holder, dX, dY, actionState, isCurrentlyActive)
            }

            /** **松手才落盘** ✓ */
            override fun clearView(view: RecyclerView, holder: RecyclerView.ViewHolder) {
                holder.itemView.elevation = 0f
                holder.itemView.scaleX = 1f
                holder.itemView.scaleY = 1f
                super.clearView(view, holder)
                save()
            }
        }).attachToRecyclerView(rv)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(row())

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.itemView.findViewById<TextView>(android.R.id.text1).text = e.label
            holder.itemView.findViewById<TextView>(android.R.id.text2).text =
                if (e.key == currentKey) "$e.key\n" + getString(R.string.subtype_order_current_tag).trim()
                else e.key
        }

        override fun getItemCount(): Int = items.size
    }

    /** 一行：MaterialCardView + 拖动柄 + 名字 + 键（键在副标题里，方便对上日志）。 */
    private fun row(): View {
        val card = MaterialCardView(this)
        card.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(pad * 3 / 4, pad * 3 / 8, pad * 3 / 4, pad * 3 / 8) }
        card.radius = pad * 3 / 4f
        card.cardElevation = pad / 16f

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 3 / 4, pad * 3 / 4, pad * 3 / 4, pad * 3 / 4)
        }
        line.addView(TextView(this).apply {
            text = "≡"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(0, 0, pad * 3 / 4, 0)
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            id = android.R.id.text1
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        })
        texts.addView(TextView(this).apply {
            id = android.R.id.text2
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(line)
        return card
    }

    // ────────────────────────── 数据 ──────────────────────────

    /** 读当前输入法（或 intent 指定的那个）框架里已启用的 subtype，按现有顺序排好。 */
    private fun load() {
        items.clear()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        // 按包名找 InputMethodInfo：注册表优先 → 已启用 → 只有当它正好是当前输入法时兜底
        val imi = imm?.inputMethodList?.firstOrNull { it.packageName == imePkg }
            ?: imm?.enabledInputMethodList?.firstOrNull { it.packageName == imePkg }
            ?: imm?.currentInputMethodInfo?.takeIf { it.packageName == imePkg }
        val raw = if (imm == null || imi == null) emptyList() else {
            (imm.getEnabledInputMethodSubtypeList(imi, true) ?: emptyList())
        }
        if (raw.isEmpty()) {
            LogHelper.log(VerboseLevel.WARNING,
                "SubtypeOrder: no subtypes for ", imePkg,
                " (imm=", (imm != null).toString(), " imi=", (imi != null).toString(), ")")
            android.widget.Toast.makeText(this, R.string.subtype_order_empty, android.widget.Toast.LENGTH_LONG).show()
            adapter.notifyDataSetChanged()
            return
        }
        currentKey = try {
            imm?.currentInputMethodSubtype?.takeIf { imePkg == currentImePackage() }?.let { subtypeKey(it) }
        } catch (t: Throwable) {
            null
        }
        val entries = raw.map { Entry(subtypeKey(it), subtypeLabel(it, imi!!)) }
        val keys = entries.map { it.key }
        val order = SubtypeRotation.parseOrder(
            cfg.imeSubtypeOrders?.get(imePkg)?.takeUnless { it.isNullOrBlank() } ?: cfg.imeSubtypeOrder
        )
        SubtypeRotation.buildChain(keys, order).forEach { items.add(entries[it]) }
        LogHelper.log(VerboseLevel.INFO,
            "SubtypeOrder: ime=", imePkg,
            " raw=", raw.size.toString(),
            " keys=", entries.joinToString("/") { it.key },
            " order=", order.joinToString("/").ifEmpty { "<framework>" },
            " current=", currentKey ?: "-")
        adapter.notifyDataSetChanged()
    }

    /** 落盘：只写**这个输入法**那份顺序；配置随 IPC 走 ⇒ 下一次按键即生效（不用重启）。 */
    private fun save() {
        if (items.isEmpty()) return
        val chain = items.joinToString(",") { it.key }
        cfg.imeSubtypeOrders = LinkedHashMap(cfg.imeSubtypeOrders).apply { put(imePkg, chain) }
        cfg.save()
        Config.syncToSharedPrefs(this, cfg)
        LogHelper.log(VerboseLevel.INFO, "SubtypeOrder: saved ", imePkg, " -> ", chain)
    }

    // ────────────────────────── 小工具 ──────────────────────────

    private fun currentImePackage(): String? = try {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.currentInputMethodInfo?.packageName?.trim()
    } catch (t: Throwable) {
        null
    }

    /** 与 system_server 侧**完全一致**的键：有语言标签用标签，否则 locale 串，都没有才是 `*`。 */
    private fun subtypeKey(s: InputMethodSubtype): String {
        val tag = if (Build.VERSION.SDK_INT >= 34) {
            try { s.languageTag } catch (t: Throwable) { null }
        } else null
        return SubtypeRotation.keyOf(tag, s.locale)
    }

    private fun subtypeLabel(s: InputMethodSubtype, imi: android.view.inputmethod.InputMethodInfo): String {
        val key = subtypeKey(s)
        val name = try {
            s.getDisplayName(this, imi.packageName, imi.serviceInfo.applicationInfo)?.toString()
        } catch (t: Throwable) {
            null
        }
        return when {
            name.isNullOrBlank() -> key
            key == SubtypeRotation.KEY_TAGLESS -> "$name （$key）"
            else -> name
        }
    }

    private fun appLabel(pkg: String): String? = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") packageManager.getApplicationInfo(pkg, 0)
        }
        packageManager.getApplicationLabel(info)?.toString()
    } catch (t: Throwable) {
        null
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
        } else 0
    }
}
