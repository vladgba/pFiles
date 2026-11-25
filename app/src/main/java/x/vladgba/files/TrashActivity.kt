package x.vladgba.files

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** Lists what is in the trash of every storage volume: restore, delete for good, or empty it. */
class TrashActivity : Activity() {
    private var items: List<Trash.Item> = emptyList()
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var changed = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.setTitle(R.string.trash)
        list = ListView(this).apply {
            adapter = Items()
            setOnItemClickListener { _, view, pos, _ -> showMenu(view, items[pos]) }
        }
        empty = TextView(this).apply {
            setText(R.string.trash_empty)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Ui.fgDim(context))
            visibility = View.GONE
        }
        val root = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            Ui.applyInsets(this, underActionBar = true)
        }
        setContentView(root)
        reload()
    }

    private fun reload() {
        val volumes = Storage.volumes(this)
        worker.execute {
            val found = Trash.list(volumes)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                items = found
                (list.adapter as Items).notifyDataSetChanged()
                empty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
                actionBar?.subtitle = if (found.isEmpty()) null
                    else resources.getQuantityString(R.plurals.trash_items, found.size, found.size, Trash.KEEP_DAYS)
                invalidateOptionsMenu()
            }
        }
    }

    private inner class Items : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? TextView) ?: TextView(this@TrashActivity).apply {
                textSize = 15f
                setTextColor(Ui.fg(context))
                setPadding(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 10))
            }
            val item = items[position]
            v.text = item.name + "\n" + (item.original.parent ?: "/") + "\n" +
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.deletedAt))
            return v
        }
    }

    private fun showMenu(anchor: View, item: Trash.Item) {
        val p = PopupMenu(this, anchor)
        p.menu.add(0, 1, 0, R.string.trash_restore)
        p.menu.add(0, 2, 1, R.string.trash_delete_forever)
        p.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                1 -> run { worker.execute { val ok = Trash.restore(item) != null; done(ok) } }
                2 -> AlertDialog.Builder(this)
                    .setMessage(getString(R.string.trash_delete_confirm, item.name))
                    .setPositiveButton(R.string.delete) { _, _ -> worker.execute { done(Trash.purge(item)) } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            true
        }
        p.show()
    }

    private fun done(ok: Boolean) = ui.post {
        if (isFinishing || isDestroyed) return@post
        changed = true
        setResult(RESULT_OK)
        if (!ok) Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show()
        reload()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.trash_empty_action).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(1)?.isEnabled = items.isNotEmpty()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> AlertDialog.Builder(this)
                .setMessage(resources.getQuantityString(R.plurals.trash_empty_confirm, items.size, items.size))
                .setPositiveButton(R.string.trash_empty_action) { _, _ ->
                    val volumes = Storage.volumes(this)
                    worker.execute { done(Trash.empty(volumes) == 0) }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
