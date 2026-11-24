package x.vladgba.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/** Compares two files, as text (line diff with changed characters marked) or as bytes (hex). Differences are bold. */
class DiffActivity : Activity() {
    private class Row(val text: CharSequence, val diff: Int) // diff: 0 same, 1 removed/old, 2 added/new, 3 hex difference

    private lateinit var a: File
    private lateinit var b: File
    private lateinit var list: ListView
    private lateinit var summary: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var hex = false
    private var onlyDiffs = false
    private var rows: List<Row> = emptyList()
    private var shown: List<Row> = emptyList()
    private var hunkStarts: List<Int> = emptyList()
    private var token = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        a = File(intent.getStringExtra(EXTRA_A) ?: run { finish(); return })
        b = File(intent.getStringExtra(EXTRA_B) ?: run { finish(); return })
        hex = intent.getBooleanExtra(EXTRA_HEX, false) || !(Syntax.isText(a) && Syntax.isText(b))
        title = getString(R.string.compare)
        actionBar?.subtitle = a.name + "  ↔  " + b.name
        summary = TextView(this).apply {
            typeface = Fonts.mono()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 10), Ui.dp(context, 6))
            setBackgroundColor(if (Ui.isNight(context)) 0xFF1B2124.toInt() else 0xFFF1F3F4.toInt())
            text = getString(R.string.comparing)
        }
        list = ListView(this).apply { divider = null; setBackgroundColor(Color.TRANSPARENT) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun add(label: String, f: () -> Unit) =
                addView(Ui.button(context, label, f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            add("▲") { jump(-1) }
            add("▼") { jump(1) }
            add(getString(R.string.diff_only)) { onlyDiffs = !onlyDiffs; apply() }
            add(getString(R.string.diff_mode)) { hex = !hex; compute() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(summary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            Ui.applyInsets(this, underActionBar = true)
        })
        compute()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean = false
    override fun onOptionsItemSelected(item: MenuItem): Boolean = super.onOptionsItemSelected(item)

    private fun jump(dir: Int) {
        if (hunkStarts.isEmpty()) return
        val cur = list.firstVisiblePosition
        val target = if (dir > 0) hunkStarts.firstOrNull { it > cur } ?: hunkStarts.first()
            else hunkStarts.lastOrNull { it < cur } ?: hunkStarts.last()
        list.setSelection(target)
    }

    private fun compute() {
        val my = ++token
        summary.text = getString(R.string.comparing)
        val asHex = hex
        Thread {
            val result = try {
                if (asHex) HexDiff.build(a, b, this) else TextDiff.build(a, b, this)
            } catch (e: Throwable) {
                Triple(emptyList<Row>(), getString(R.string.diff_failed, e.message ?: e.javaClass.simpleName), 0)
            }
            ui.post {
                if (my != token || isFinishing || isDestroyed) return@post
                rows = result.first; summary.text = result.second
                apply()
            }
        }.start()
    }

    private fun apply() {
        shown = if (onlyDiffs) withContext(rows) else rows
        val starts = ArrayList<Int>()
        shown.forEachIndexed { i, r -> if (r.diff != 0 && (i == 0 || shown[i - 1].diff == 0)) starts += i }
        hunkStarts = starts
        val bg = if (Ui.isNight(this)) intArrayOf(0, 0x55E53935, 0x5543A047, 0x55FFB300)
            else intArrayOf(0, 0x33E53935, 0x3343A047, 0x33FFB300)
        val fg = Ui.fg(this)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val tv = (v as? TextView) ?: TextView(this@DiffActivity).apply {
                    typeface = Fonts.mono()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setHorizontallyScrolling(true); isSingleLine = true
                    setPadding(Ui.dp(context, 6), Ui.dp(context, 1), Ui.dp(context, 6), Ui.dp(context, 1))
                }
                val r = shown[p]
                tv.text = r.text
                tv.setBackgroundColor(bg[r.diff])
                tv.setTextColor(fg)
                return tv
            }
        }
    }

    /** Keeps changed rows plus 3 rows of context around them. */
    private fun withContext(all: List<Row>): List<Row> {
        val keep = BooleanArray(all.size)
        all.forEachIndexed { i, r -> if (r.diff != 0) for (j in maxOf(0, i - 3)..minOf(all.size - 1, i + 3)) keep[j] = true }
        val out = ArrayList<Row>()
        var gap = false
        all.forEachIndexed { i, r ->
            if (keep[i]) { gap = false; out += r }
            else if (!gap) { gap = true; out += Row("   ⋯", 0) }
        }
        return out
    }

    // ------------------------------------------------------------------ text

    private object TextDiff {
        private const val MAX_BYTES = 8L * 1024 * 1024
        private const val MAX_D = 2500

        fun lines(f: File): List<String> {
            if (f.length() > MAX_BYTES) throw java.io.IOException("File too large (over 8 MB)")
            val s = ShizukuFiles.input(f).use { String(it.readBytes(), Charsets.UTF_8) }
            val out = ArrayList<String>()
            s.split('\n').forEach { out += it.removeSuffix("\r") }
            if (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
            return out
        }

        /** Edit script as (type, indexA, indexB): 0 equal, 1 delete from A, 2 insert from B. */
        private fun myers(x: IntArray, y: IntArray): List<IntArray>? {
            val n = x.size; val m = y.size
            val dmax = minOf(n + m, MAX_D)
            val off = dmax + 1
            val v = IntArray(2 * dmax + 3)
            val trace = ArrayList<IntArray>()
            for (d in 0..dmax) {
                trace += v.copyOfRange(off - d - 1, off + d + 2)
                var k = -d
                while (k <= d) {
                    var px = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
                    var py = px - k
                    while (px < n && py < m && x[px] == y[py]) { px++; py++ }
                    v[off + k] = px
                    if (px >= n && py >= m) return backtrack(trace, x, y, d)
                    k += 2
                }
            }
            return null
        }

        private fun backtrack(trace: List<IntArray>, x: IntArray, y: IntArray, dEnd: Int): List<IntArray> {
            val ops = ArrayList<IntArray>()
            var px = x.size; var py = y.size
            for (d in dEnd downTo 1) {
                val snap = trace[d]
                fun pv(k: Int) = snap[k + d + 1]
                val k = px - py
                val prevK = if (k == -d || (k != d && pv(k - 1) < pv(k + 1))) k + 1 else k - 1
                val prevX = pv(prevK); val prevY = prevX - prevK
                while (px > prevX && py > prevY) { px--; py--; ops += intArrayOf(0, px, py) }
                if (px == prevX) { py--; ops += intArrayOf(2, px, py) } else { px--; ops += intArrayOf(1, px, py) }
            }
            while (px > 0 && py > 0) { px--; py--; ops += intArrayOf(0, px, py) }
            ops.reverse()
            return ops
        }

        fun build(fa: File, fb: File, ctx: DiffActivity): Triple<List<Row>, String, Int> {
            val la = lines(fa); val lb = lines(fb)
            val ids = HashMap<String, Int>()
            val x = IntArray(la.size) { ids.getOrPut(la[it]) { ids.size } }
            val y = IntArray(lb.size) { ids.getOrPut(lb[it]) { ids.size } }
            // Trim the shared head and tail first: it keeps the Myers search small for typical edits.
            var head = 0
            while (head < x.size && head < y.size && x[head] == y[head]) head++
            var tail = 0
            while (tail < x.size - head && tail < y.size - head && x[x.size - 1 - tail] == y[y.size - 1 - tail]) tail++
            val mid = myers(x.copyOfRange(head, x.size - tail), y.copyOfRange(head, y.size - tail))
                ?: (0 until x.size - head - tail).map { intArrayOf(1, it, 0) } + (0 until y.size - head - tail).map { intArrayOf(2, 0, it) }
            val ops = ArrayList<IntArray>()
            for (i in 0 until head) ops += intArrayOf(0, i, i)
            for (o in mid) ops += intArrayOf(o[0], o[1] + head, o[2] + head)
            for (i in 0 until tail) ops += intArrayOf(0, x.size - tail + i, y.size - tail + i)

            val dark = Ui.isNight(ctx)
            val strongDel = if (dark) 0xFFB71C1C.toInt() else 0xFFEF9A9A.toInt()
            val strongAdd = if (dark) 0xFF1B5E20.toInt() else 0xFFA5D6A7.toInt()
            val rows = ArrayList<Row>()
            var removed = 0; var added = 0; var hunks = 0
            var i = 0
            fun num(n: Int) = (n + 1).toString().padStart(5)
            while (i < ops.size) {
                if (ops[i][0] == 0) { rows += Row("  " + num(ops[i][1]) + " " + la[ops[i][1]], 0); i++; continue }
                val dels = ArrayList<Int>(); val adds = ArrayList<Int>()
                while (i < ops.size && ops[i][0] != 0) { if (ops[i][0] == 1) dels += ops[i][1] else adds += ops[i][2]; i++ }
                hunks++; removed += dels.size; added += adds.size
                for (p in 0 until maxOf(dels.size, adds.size)) {
                    val da = dels.getOrNull(p); val ab = adds.getOrNull(p)
                    // Paired lines: mark only the characters that changed, in bold and underlined.
                    var s1 = -1; var e1 = -1; var s2 = -1; var e2 = -1
                    if (da != null && ab != null) {
                        val l1 = la[da]; val l2 = lb[ab]
                        var h = 0
                        while (h < l1.length && h < l2.length && l1[h] == l2[h]) h++
                        var t = 0
                        while (t < l1.length - h && t < l2.length - h && l1[l1.length - 1 - t] == l2[l2.length - 1 - t]) t++
                        s1 = h; e1 = l1.length - t; s2 = h; e2 = l2.length - t
                    }
                    if (da != null) rows += Row(styled("− " + num(da) + " ", la[da], s1, e1, strongDel, 0xFFE53935.toInt()), 1)
                    if (ab != null) rows += Row(styled("+ " + num(ab) + " ", lb[ab], s2, e2, strongAdd, 0xFF43A047.toInt()), 2)
                }
            }
            val text = if (hunks == 0) ctx.getString(R.string.diff_identical)
                else ctx.getString(R.string.diff_text_summary, hunks, removed, added)
            return Triple(rows, text, hunks)
        }

        private fun styled(prefix: String, line: String, s: Int, e: Int, strongBg: Int, mark: Int): CharSequence {
            val sb = SpannableStringBuilder(prefix)
            val st = sb.length
            sb.append(line)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, 0) // the whole changed line is bold
            sb.setSpan(ForegroundColorSpan(mark), 0, prefix.length, 0)
            if (s >= 0 && e > s) {
                sb.setSpan(BackgroundColorSpan(strongBg), st + s, st + e, 0)
                sb.setSpan(UnderlineSpan(), st + s, st + e, 0)
            } else if (s >= 0 && e <= s && line.isEmpty()) { /* nothing */ }
            return sb
        }
    }

    // ------------------------------------------------------------------ hex

    private object HexDiff {
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val W = 8

        fun build(fa: File, fb: File, ctx: DiffActivity): Triple<List<Row>, String, Int> {
            if (fa.length() > MAX_BYTES || fb.length() > MAX_BYTES) throw java.io.IOException("File too large (over 32 MB)")
            val x = ShizukuFiles.input(fa).use { it.readBytes() }
            val y = ShizukuFiles.input(fb).use { it.readBytes() }
            val dark = Ui.isNight(ctx)
            val strong = if (dark) 0xFFB71C1C.toInt() else 0xFFEF9A9A.toInt()
            val mark = 0xFFE53935.toInt()
            val rows = ArrayList<Row>()
            var diffBytes = 0L; var hunks = 0
            val total = maxOf(x.size, y.size)
            var inHunk = false
            var off = 0
            while (off < total) {
                val end = minOf(off + W, total)
                var rowDiff = false
                for (i in off until end) if (i >= x.size || i >= y.size || x[i] != y[i]) { rowDiff = true; diffBytes++ }
                if (rowDiff && !inHunk) hunks++
                inHunk = rowDiff
                fun line(tag: String, d: ByteArray, o: ByteArray): CharSequence {
                    val sb = SpannableStringBuilder("%08X %s ".format(off, tag))
                    for (i in off until off + W) {
                        val st = sb.length
                        val has = i < d.size
                        sb.append(if (has) "%02X".format(d[i].toInt() and 0xFF) else "--")
                        val differs = i < total && (i >= o.size || !has || d[i] != o[i])
                        if (differs && i < end) {
                            sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, 0)
                            sb.setSpan(BackgroundColorSpan(strong), st, sb.length, 0)
                            sb.setSpan(ForegroundColorSpan(mark), st, sb.length, 0)
                        }
                        sb.append(' ')
                    }
                    sb.append('|')
                    for (i in off until off + W) {
                        val st = sb.length
                        val c = if (i < d.size) (d[i].toInt() and 0xFF).let { if (it in 32..126) it.toChar() else '.' } else ' '
                        sb.append(c)
                        if (i < end && i < total && (i >= d.size || i >= o.size || d[i] != o[i])) {
                            sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, 0)
                            sb.setSpan(ForegroundColorSpan(mark), st, sb.length, 0)
                        }
                    }
                    sb.append('|')
                    return sb
                }
                if (rowDiff) {
                    rows += Row(line("A", x, y), 3)
                    rows += Row(line("B", y, x), 3)
                } else {
                    rows += Row(line("=", x, y), 0)
                }
                off = end
            }
            val text = if (diffBytes == 0L && x.size == y.size) ctx.getString(R.string.diff_identical)
                else ctx.getString(R.string.diff_hex_summary, hunks, diffBytes, x.size.toLong(), y.size.toLong())
            return Triple(rows, text, hunks)
        }
    }

    companion object {
        const val EXTRA_A = "x.vladgba.files.DIFF_A"
        const val EXTRA_B = "x.vladgba.files.DIFF_B"
        const val EXTRA_HEX = "x.vladgba.files.DIFF_HEX"

        fun start(ctx: Context, a: File, b: File) {
            val go = { fa: File, fb: File ->
                ctx.startActivity(Intent(ctx, DiffActivity::class.java)
                    .putExtra(EXTRA_A, fa.absolutePath).putExtra(EXTRA_B, fb.absolutePath))
            }
            go(a, b)
        }
    }
}
