package x.vladgba.files

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import x.vladgba.files.pui.pColor
import java.io.File
import java.util.concurrent.Executors

/** Table view for CSV/TSV and JSON files. Offered in "Open with…"; a plain tap still opens the text editor. */
object TableModule : FileOpener {
    override val id = "table"
    override val title = R.string.table_view
    override fun canOpen(f: File) = false
    override fun open(a: Activity, f: File) = Openers.launch(a, TableActivity::class.java, f)
    fun supports(f: File) = f.extension.lowercase().let { TableOps.isDelimited(it) || TableOps.isJson(it) }
}

/** SQLite browser module (read-only): lists tables and views, then shows rows page by page. */
object SqliteModule : FileOpener {
    private val EXT = setOf("db", "sqlite", "sqlite3", "db3")
    override val id = "sqlite"
    override val title = R.string.sqlite_viewer
    override fun canOpen(f: File) = !f.isDirectory && f.extension.lowercase() in EXT && hasHeader(f)
    override fun open(a: Activity, f: File) = Openers.launch(a, SqliteActivity::class.java, f)

    fun hasHeader(f: File): Boolean = try {
        f.inputStream().use { s ->
            val b = ByteArray(16)
            s.read(b) == 16 && String(b, Charsets.ISO_8859_1) == "SQLite format 3\u0000"
        }
    } catch (_: Exception) { false }

    fun openDb(f: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
}

class TableActivity : Activity() {
    private lateinit var file: File
    private var sqlTable: String? = null
    private var page = 0
    private var total = -1L // SQLite row count, -1 when unknown
    private var table = TableOps.Table(emptyList(), emptyList())
    private var widths = IntArray(0)
    private lateinit var header: LinearLayout
    private lateinit var list: ListView
    private lateinit var busy: ProgressBar
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        file = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        sqlTable = intent.getStringExtra(EXTRA_TABLE)
        page = state?.getInt("page") ?: 0
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = sqlTable ?: file.name

        header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        list = ListView(this).apply {
            adapter = Rows()
            setOnItemClickListener { _, _, pos, _ -> showRow(pos) }
        }
        busy = ProgressBar(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(WRAP_CONTENT, 0, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(busy, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(HorizontalScrollView(context).apply {
                isFillViewport = true
                addView(column, ViewGroup.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            Ui.applyInsets(this, underActionBar = true)
        }
        setContentView(root)
        load()
    }

    private fun load() {
        busy.visibility = View.VISIBLE
        val p = page
        worker.execute {
            val result = try { read(p) } catch (e: Throwable) { null }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                busy.visibility = View.GONE
                if (result == null) {
                    Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                    if (table.columns.isEmpty()) finish()
                    return@post
                }
                show(result)
            }
        }
    }

    private fun read(p: Int): TableOps.Table {
        val name = sqlTable
        if (name != null) {
            SqliteModule.openDb(file).use { db ->
                if (total < 0) total = try {
                    db.rawQuery("SELECT COUNT(*) FROM " + SqliteModule.quote(name), null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                } catch (_: Exception) { -1L }
                db.rawQuery("SELECT * FROM ${SqliteModule.quote(name)} LIMIT $PAGE OFFSET ${p.toLong() * PAGE}", null).use { c ->
                    val cols = c.columnNames.toList()
                    val rows = ArrayList<List<String>>()
                    while (c.moveToNext()) rows.add(List(cols.size) { i -> cell(c, i) })
                    return TableOps.Table(cols, rows)
                }
            }
        }
        if (file.length() > MAX_BYTES) throw IllegalArgumentException("too large")
        val text = file.readText()
        val ext = file.extension.lowercase()
        return when {
            ext == "ndjson" || ext == "jsonl" -> TableOps.parseJsonLines(text)
            TableOps.isJson(ext) || text.trimStart().let { it.startsWith("[") || it.startsWith("{") } -> TableOps.parseJson(text)
            else -> TableOps.parseDelimited(text)
        }
    }

    private fun cell(c: Cursor, i: Int): String = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> "NULL"
        Cursor.FIELD_TYPE_BLOB -> "<blob ${c.getBlob(i).size} B>"
        else -> c.getString(i) ?: ""
    }

    private fun show(t: TableOps.Table) {
        table = t
        // Width from the longest of the header and the first rows, in characters, within sane bounds.
        val charPx = resources.displayMetrics.scaledDensity * 8.2f
        widths = IntArray(t.columns.size) { c ->
            var longest = t.columns[c].length
            for (r in 0 until minOf(t.rows.size, 60)) longest = maxOf(longest, t.rows[r][c].length.coerceAtMost(60))
            (longest.coerceIn(3, 34) * charPx).toInt() + Ui.dp(this, 16)
        }
        header.removeAllViews()
        header.setBackgroundColor(pColor(R.color.p_surface2))
        t.columns.forEachIndexed { c, name -> header.addView(text(name, widths[c], bold = true)) }
        (list.adapter as Rows).notifyDataSetChanged()
        list.setSelection(0)
        val rows = resources.getQuantityString(R.plurals.table_rows, t.rows.size, t.rows.size)
        actionBar?.subtitle = when {
            sqlTable != null && total >= 0 -> {
                val from = if (t.rows.isEmpty()) 0L else page.toLong() * PAGE + 1
                getString(R.string.table_range, from, page.toLong() * PAGE + t.rows.size, total)
            }
            t.truncated -> rows + " · " + getString(R.string.table_truncated)
            else -> rows
        }
        invalidateOptionsMenu()
    }

    private fun text(value: String, width: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Ui.fg(context))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(Ui.dp(context, 8), Ui.dp(context, 7), Ui.dp(context, 8), Ui.dp(context, 7))
        layoutParams = LinearLayout.LayoutParams(width, WRAP_CONTENT)
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = table.rows.size
        override fun getItem(position: Int) = table.rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            // A recycled row from an older table has the wrong column count: rebuild it.
            val row = (convert as? LinearLayout)?.takeIf { it.childCount == widths.size && it.tag === widths }
                ?: LinearLayout(this@TableActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    tag = widths
                    for (w in widths) addView(text("", w))
                }
            val values = table.rows[position]
            for (c in widths.indices) (row.getChildAt(c) as TextView).text = values[c].let { if (it.length > 200) it.substring(0, 200) else it }
            return row
        }
    }

    private fun showRow(pos: Int) {
        val values = table.rows.getOrNull(pos) ?: return
        val body = table.columns.mapIndexed { c, name -> name + "\n" + values[c] }.joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.table_row, page.toLong() * PAGE + pos + 1))
            .setMessage(body)
            .setNeutralButton(R.string.copy) { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("row", body))
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_PREV, 0, R.string.previous).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, M_NEXT, 1, R.string.next).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val sql = sqlTable != null
        menu.findItem(M_PREV)?.isVisible = sql && page > 0
        menu.findItem(M_NEXT)?.isVisible = sql && table.rows.size == PAGE
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            M_PREV -> { page--; load() }
            M_NEXT -> { page++; load() }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("page", page)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TABLE = "x.vladgba.files.TABLE"
        private const val PAGE = 500
        private const val MAX_BYTES = 16L * 1024 * 1024
        private const val M_PREV = 1
        private const val M_NEXT = 2
    }
}

class SqliteActivity : Activity() {
    private class Obj(val name: String, val type: String, val sql: String, val rows: Long)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val file = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = file.name
        val list = ListView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            Ui.applyInsets(this, underActionBar = true)
        }
        setContentView(root)
        val ui = Handler(Looper.getMainLooper())
        Thread {
            val objects = try {
                SqliteModule.openDb(file).use { db ->
                    val found = ArrayList<Obj>()
                    db.rawQuery("SELECT name, type, sql FROM sqlite_master WHERE type IN ('table','view') ORDER BY type, name", null).use { c ->
                        while (c.moveToNext()) found.add(Obj(c.getString(0), c.getString(1), c.getString(2) ?: "", -1))
                    }
                    found.map { o ->
                        val n = try {
                            db.rawQuery("SELECT COUNT(*) FROM " + SqliteModule.quote(o.name), null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
                        } catch (_: Exception) { -1L }
                        Obj(o.name, o.type, o.sql, n)
                    }
                }
            } catch (_: Throwable) { null }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (objects == null) {
                    Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                    finish()
                    return@post
                }
                actionBar?.subtitle = resources.getQuantityString(R.plurals.sqlite_tables, objects.size, objects.size)
                list.adapter = object : BaseAdapter() {
                    override fun getCount() = objects.size
                    override fun getItem(position: Int) = objects[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
                        val v = (convert as? TextView) ?: TextView(this@SqliteActivity).apply {
                            textSize = 16f
                            setTextColor(Ui.fg(context))
                            setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12))
                        }
                        val o = objects[position]
                        val rows = if (o.rows >= 0) resources.getQuantityString(R.plurals.table_rows, o.rows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), o.rows) else o.type
                        v.text = o.name + "\n" + (if (o.type == "view") "view · " else "") + rows
                        return v
                    }
                }
                list.setOnItemClickListener { _, _, pos, _ ->
                    startActivity(Intent(this, TableActivity::class.java)
                        .putExtra(Openers.EXTRA_PATH, file.absolutePath)
                        .putExtra(TableActivity.EXTRA_TABLE, objects[pos].name))
                }
                list.setOnItemLongClickListener { _, _, pos, _ ->
                    AlertDialog.Builder(this).setTitle(objects[pos].name).setMessage(objects[pos].sql.ifEmpty { objects[pos].type })
                        .setPositiveButton(android.R.string.ok, null).show()
                    true
                }
            }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
