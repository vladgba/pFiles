package x.vladgba.files

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(private val ctx: Context) : BaseAdapter() {
    private var all: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private var query = ""
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    val total get() = all.size

    /** Grid (icon tiles) vs list (rows). Both layouts are recycled separately via view types. */
    var style = 0
        set(v) { field = v; notifyDataSetChanged() }

    fun setData(d: List<Entry>) { all = d; refilter() }
    fun filter(q: String) { query = q.trim(); refilter() }

    private fun refilter() {
        shown = if (query.isEmpty()) all else all.filter { it.name.contains(query, ignoreCase = true) }
        notifyDataSetChanged()
    }

    override fun getCount() = shown.size
    override fun getItem(position: Int) = shown[position]
    override fun getItemId(position: Int) = position.toLong()

    /** Whether a position is selected. Recycled views keep their old highlight otherwise. */
    var isChecked: (Int) -> Boolean = { false }

    override fun getViewTypeCount() = 5
    override fun getItemViewType(position: Int) = style

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val e = shown[position]
        if (style == 4) {
            val row = convertView as? TableLine ?: TableLine(ctx)
            row.badge.set(e)
            row.name.text = e.name
            row.size.text = if (e.isDir) "" else Formatter.formatShortFileSize(ctx, e.size)
            row.date.text = dateFmt.format(Date(e.modified))
            row.isActivated = isChecked(position)
            return row
        }
        if (style >= 2) {
            val cell = convertView as? Cell ?: Cell(ctx, style == 3)
            cell.badge.set(e)
            cell.name.text = e.name
            cell.meta.text = if (e.isDir) "" else Formatter.formatShortFileSize(ctx, e.size)
            cell.meta.visibility = if (e.isDir) View.GONE else View.VISIBLE
            cell.isActivated = isChecked(position)
            return cell
        }
        val compact = style == 1
        val row = convertView as? Row ?: Row(ctx, compact)
        row.badge.set(e)
        row.name.text = e.name
        val date = dateFmt.format(Date(e.modified))
        row.meta.text = if (e.isDir) date else Formatter.formatShortFileSize(ctx, e.size) + "  ·  " + date
        row.meta.visibility = if (compact) View.GONE else View.VISIBLE
        row.isActivated = isChecked(position)
        return row
    }

    private class TableLine(ctx: Context) : LinearLayout(ctx) {
        val badge = Badge(ctx)
        val name = TextView(ctx)
        val size = TextView(ctx)
        val date = TextView(ctx)

        init {
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(42)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.activatedBackgroundIndicator, tv, true)
            setBackgroundResource(tv.resourceId)
            listOf(name, size, date).forEach {
                it.setSingleLine()
                it.ellipsize = TextUtils.TruncateAt.END
                it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            val nameBox = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(badge, LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(5) })
                addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(nameBox, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.55f))
            addView(size, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.18f))
            addView(date, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.27f))
        }
    }

    @SuppressLint("ResourceType") // obtainStyledAttributes index, not a resource id
    private class Cell(ctx: Context, large: Boolean) : LinearLayout(ctx) {
        val badge = Badge(ctx)
        val name = TextView(ctx)
        val meta = TextView(ctx)

        init {
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = dp(if (large) 160 else 112)
            setPadding(dp(6), dp(10), dp(6), dp(8))
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.activatedBackgroundIndicator, tv, true)
            setBackgroundResource(tv.resourceId)

            val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary))
            name.setTextColor(a.getColorStateList(0))
            meta.setTextColor(a.getColorStateList(1))
            a.recycle()

            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            name.gravity = Gravity.CENTER_HORIZONTAL
            name.maxLines = 2
            name.ellipsize = TextUtils.TruncateAt.END
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            meta.gravity = Gravity.CENTER_HORIZONTAL
            meta.setSingleLine()

            addView(badge, LayoutParams(dp(if (large) 88 else 52), dp(if (large) 88 else 52)).apply { bottomMargin = dp(6) })
            addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(meta, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    @SuppressLint("ResourceType") // obtainStyledAttributes index, not a resource id
    private class Row(ctx: Context, compact: Boolean) : LinearLayout(ctx) {
        val badge = Badge(ctx)
        val name = TextView(ctx)
        val meta = TextView(ctx)

        init {
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (compact) 40 else 60)
            setPadding(dp(if (compact) 8 else 16), dp(if (compact) 4 else 8),
                dp(if (compact) 8 else 16), dp(if (compact) 4 else 8))
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.activatedBackgroundIndicator, tv, true)
            setBackgroundResource(tv.resourceId)

            val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary))
            name.setTextColor(a.getColorStateList(0))
            meta.setTextColor(a.getColorStateList(1))
            a.recycle()

            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 14f else 16f)
            name.setSingleLine()
            name.ellipsize = TextUtils.TruncateAt.MIDDLE
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            meta.setSingleLine()

            addView(badge, LayoutParams(dp(if (compact) 28 else 40), dp(if (compact) 28 else 40)).apply {
                marginEnd = dp(if (compact) 8 else 16)
            })
            val col = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(name)
                addView(meta)
            }
            addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** Folder silhouette or a colored page with the extension on it. Drawn, not loaded: zero image assets. */
    internal class Badge(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val r = RectF()
        private var dir = false
        private var label = ""
        private var appPackage: String? = null
        private var appIcon: Drawable? = null

        fun set(isDir: Boolean, ext: String) {
            appPackage = null
            appIcon = null
            dir = isDir
            label = ext.take(4).uppercase()
            fill.color = Kinds.color(isDir, ext)
            invalidate()
        }

        fun set(e: Entry) {
            set(e.isDir, e.ext)
            InstalledApps.packageFor(e.file)?.let { pkg ->
                appPackage = pkg
                AppIcons.bind(context, pkg, this)
            }
        }

        fun showAppIcon(pkg: String, icon: Drawable?) {
            if (appPackage != pkg) return // this recycled row now shows another app
            appIcon = icon
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            appIcon?.let {
                it.setBounds(0, 0, width, height)
                it.draw(c)
                return
            }
            val rad = w * 0.1f
            if (dir) {
                r.set(w * 0.04f, h * 0.14f, w * 0.48f, h * 0.36f); c.drawRoundRect(r, rad, rad, fill)
                r.set(w * 0.04f, h * 0.24f, w * 0.96f, h * 0.86f); c.drawRoundRect(r, rad, rad, fill)
            } else {
                r.set(w * 0.12f, h * 0.04f, w * 0.88f, h * 0.96f); c.drawRoundRect(r, rad, rad, fill)
                if (label.isNotEmpty()) {
                    text.textSize = w * if (label.length > 3) 0.2f else 0.25f
                    c.drawText(label, w / 2, h / 2 - (text.descent() + text.ascent()) / 2, text)
                }
            }
        }
    }
}
