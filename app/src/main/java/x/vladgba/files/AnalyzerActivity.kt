package x.vladgba.files

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import x.vladgba.files.pui.pChip
import x.vladgba.files.pui.pColor
import x.vladgba.files.pui.pSetChipSelected
import java.io.File
import java.util.concurrent.Executors

/** Storage analyzer: what takes the space in a folder, the biggest files below it, and duplicate files. */
class AnalyzerActivity : Activity() {
    private class Row(val title: String, val detail: String, val fraction: Float, val file: File?, val isDir: Boolean,
                      val header: Boolean = false)

    private lateinit var dir: File
    private var scan: AnalyzeOps.Scan? = null
    private var dups: List<AnalyzeOps.DupGroup>? = null
    private var mode = FOLDERS
    private var rows: List<Row> = emptyList()
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var busy: ProgressBar
    private lateinit var chips: List<TextView>
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var job = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        dir = File(state?.getString("dir") ?: intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        mode = state?.getInt("mode") ?: FOLDERS
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.setTitle(R.string.analyze_storage)

        chips = listOf(R.string.analyze_folders, R.string.analyze_largest, R.string.analyze_duplicates).mapIndexed { i, label ->
            pChip(getString(label), i == mode).apply { setOnClickListener { setMode(i) } }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 4))
            chips.forEach { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = Ui.dp(context, 8) }) }
        }
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.fgDim(context))
            setPadding(Ui.dp(context, 16), Ui.dp(context, 4), Ui.dp(context, 16), Ui.dp(context, 6))
        }
        busy = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        list = ListView(this).apply {
            adapter = Rows()
            setOnItemClickListener { _, view, pos, _ -> onRow(view, rows[pos]) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(status)
            addView(busy, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            Ui.applyInsets(this, underActionBar = true)
        }
        setContentView(root)
        startScan()
    }

    private fun startScan() {
        val id = ++job
        scan = null
        dups = null
        rows = emptyList()
        (list.adapter as Rows).notifyDataSetChanged()
        actionBar?.subtitle = dir.absolutePath
        busy.visibility = View.VISIBLE
        busy.isIndeterminate = true
        status.setText(R.string.analyze_scanning)
        val target = dir
        worker.execute {
            val result = AnalyzeOps.scan(target, { id != job }) { n ->
                ui.post { if (id == job) status.text = getString(R.string.analyze_scanned, n) }
            }
            ui.post {
                if (isFinishing || isDestroyed || id != job || result == null) return@post
                scan = result
                render()
            }
        }
    }

    private fun setMode(m: Int) {
        mode = m
        chips.forEachIndexed { i, c -> c.pSetChipSelected(i == m) }
        if (scan != null) render()
    }

    private fun size(bytes: Long) = Formatter.formatFileSize(this, bytes)

    private fun render() {
        val s = scan ?: return
        busy.visibility = View.GONE
        status.text = getString(R.string.analyze_total, size(s.total), s.fileCount)
        rows = when (mode) {
            FOLDERS -> s.children.map { c ->
                val detail = if (c.isDir) size(c.size) + " · " + resources.getQuantityString(R.plurals.files, c.files, c.files) else size(c.size)
                Row(c.file.name, detail, if (s.total > 0) c.size.toFloat() / s.total else 0f, c.file, c.isDir)
            }
            LARGEST -> {
                val top = s.largest.firstOrNull()?.length()?.coerceAtLeast(1L) ?: 1L
                s.largest.map { f -> Row(f.name, size(f.length()) + " · " + relative(f.parentFile), f.length().toFloat() / top, f, false) }
            }
            else -> {
                val found = dups
                if (found == null) { findDuplicates(s); emptyList() }
                else {
                    val wasted = found.sumOf { it.wasted }
                    status.text = if (found.isEmpty()) getString(R.string.analyze_no_duplicates)
                        else getString(R.string.analyze_wasted, found.size, size(wasted))
                    found.flatMap { g ->
                        listOf(Row(getString(R.string.analyze_group, g.files.size, size(g.size)), "", 0f, null, false, header = true)) +
                            g.files.map { f -> Row(f.name, relative(f.parentFile), 0f, f, false) }
                    }
                }
            }
        }
        (list.adapter as Rows).notifyDataSetChanged()
    }

    private fun relative(f: File?): String {
        val p = f?.absolutePath ?: return ""
        val base = dir.absolutePath
        return if (p == base) "." else p.removePrefix("$base/")
    }

    private fun findDuplicates(s: AnalyzeOps.Scan) {
        val id = job
        busy.visibility = View.VISIBLE
        busy.isIndeterminate = false
        busy.progress = 0
        status.setText(R.string.analyze_hashing)
        worker.execute {
            val found = AnalyzeOps.duplicates(s, { id != job }) { pct -> ui.post { if (id == job) busy.progress = pct } }
            ui.post {
                if (isFinishing || isDestroyed || id != job || found == null) return@post
                dups = found
                if (mode == DUPLICATES) render() else busy.visibility = View.GONE
            }
        }
    }

    /** A row: name, detail and a bar showing its share. */
    private inner class RowView : LinearLayout(this@AnalyzerActivity) {
        val title = TextView(context).apply { textSize = 15f; setTextColor(Ui.fg(context)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        val detail = TextView(context).apply { textSize = 12f; setTextColor(Ui.fgDim(context)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START }
        var fraction = 0f
        private val paint = Paint().apply { color = pColor(R.color.p_accent); alpha = 46 }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setWillNotDraw(false)
            setPadding(Ui.dp(context, 16), Ui.dp(context, 9), Ui.dp(context, 16), Ui.dp(context, 9))
            addView(title)
            addView(detail)
        }

        override fun onDraw(canvas: Canvas) {
            if (fraction > 0f) canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), paint)
            super.onDraw(canvas)
        }
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = !rows[position].header
        override fun areAllItemsEnabled() = false
        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? RowView) ?: RowView()
            val r = rows[position]
            v.title.text = if (r.isDir) r.title + "/" else r.title
            v.title.setTextColor(if (r.header) pColor(R.color.p_accent_text) else Ui.fg(this@AnalyzerActivity))
            v.detail.text = r.detail
            v.detail.visibility = if (r.detail.isEmpty()) View.GONE else View.VISIBLE
            v.fraction = r.fraction
            v.invalidate()
            return v
        }
    }

    private fun onRow(anchor: View, r: Row) {
        val f = r.file ?: return
        if (r.isDir) { dir = f; startScan(); return }
        val p = PopupMenu(this, anchor)
        p.menu.add(0, 1, 0, R.string.open)
        p.menu.add(0, 2, 1, R.string.analyze_show_in_folder)
        p.menu.add(0, 3, 2, R.string.delete)
        p.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                1 -> Openers.open(this, f)
                2 -> { setResult(RESULT_OK, Intent().putExtra(Openers.EXTRA_PATH, f.parent)); finish() }
                3 -> confirmDelete(f)
            }
            true
        }
        p.show()
    }

    private fun confirmDelete(f: File) {
        val volumes = Storage.volumes(this)
        val trash = Trash.eligible(f, volumes) && getSharedPreferences("pfiles", MODE_PRIVATE).getBoolean("trash", true)
        AlertDialog.Builder(this)
            .setMessage(getString(if (trash) R.string.analyze_trash_confirm else R.string.trash_delete_confirm, f.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                worker.execute {
                    val ok = if (trash) Trash.moveToTrash(f, volumes) else FileOps.delete(f)
                    ui.post {
                        if (isFinishing || isDestroyed) return@post
                        if (!ok) Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show() else startScan()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back climbs to the parent until the folder the analysis started in.
        val start = intent.getStringExtra(Openers.EXTRA_PATH)
        val parent = dir.parentFile
        if (parent != null && dir.absolutePath != start && start != null && dir.absolutePath.startsWith(start)) {
            dir = parent
            startScan()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("dir", dir.absolutePath)
        out.putInt("mode", mode)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        job++
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val FOLDERS = 0
        const val LARGEST = 1
        const val DUPLICATES = 2
    }
}
