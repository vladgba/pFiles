package x.vladgba.files

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.LruCache
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ListView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** PDF viewer module on the platform PdfRenderer: a scrolling list of pages, rendered as they come into view. */
object PdfModule : FileOpener {
    override val id = "pdf"
    override val title = R.string.pdf_viewer
    override fun canOpen(f: File) = !f.isDirectory && f.extension.equals("pdf", ignoreCase = true)
    override fun open(a: Activity, f: File) = Openers.launch(a, PdfActivity::class.java, f)
}

class PdfActivity : Activity() {
    private var renderer: PdfRenderer? = null
    private var fd: ParcelFileDescriptor? = null
    private lateinit var list: ListView
    private lateinit var scroller: HorizontalScrollView
    private var pageCount = 0
    private var ratios = FloatArray(0) // height / width per page, measured lazily (0 = not yet known)
    private var zoom = 1f
    private var fileName = ""
    private val ui = Handler(Looper.getMainLooper())
    // PdfRenderer allows one open page at a time and is not thread-safe: every use goes through this thread.
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val file = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        fileName = file.name
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = fileName
        try {
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd!!)
        } catch (_: Exception) { // damaged or password-protected (SecurityException)
            try { fd?.close() } catch (_: Exception) {}
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        pageCount = renderer!!.pageCount
        ratios = FloatArray(pageCount)
        zoom = state?.getFloat("zoom") ?: 1f

        list = ListView(this).apply {
            setBackgroundColor(0xFF606060.toInt())
            divider = null
            dividerHeight = 0
            adapter = Pages()
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView, s: Int) {}
                override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) = updateSubtitle(first)
            })
        }
        scroller = HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(list, ViewGroup.LayoutParams(pageWidth(), MATCH_PARENT))
            Ui.applyInsets(this, underActionBar = true)
        }
        setContentView(scroller)
        state?.getInt("page")?.let { list.setSelection(it) }
        updateSubtitle(state?.getInt("page") ?: 0)
    }

    private fun pageWidth() = (resources.displayMetrics.widthPixels * zoom).toInt()

    private fun updateSubtitle(first: Int) {
        actionBar?.subtitle = getString(R.string.pdf_page_of, (first + 1).coerceAtMost(pageCount), pageCount) +
            if (zoom != 1f) " · ${(zoom * 100).toInt()}%" else ""
    }

    private fun setZoom(z: Float) {
        val next = z.coerceIn(1f, 4f)
        if (next == zoom) return
        val first = list.firstVisiblePosition
        zoom = next
        cache.evictAll()
        list.layoutParams = list.layoutParams.apply { width = pageWidth() }
        (list.adapter as Pages).notifyDataSetChanged()
        list.setSelection(first)
        updateSubtitle(first)
    }

    private inner class Pages : BaseAdapter() {
        override fun getCount() = pageCount
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val gap = Ui.dp(this@PdfActivity, 6)
            val view = (convert as? ImageView) ?: ImageView(this@PdfActivity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setPadding(0, 0, 0, gap)
            }
            val w = pageWidth()
            // A4 until the page has been measured; the row is resized when its bitmap arrives.
            val ratio = ratios[position].takeIf { it > 0f } ?: 1.414f
            view.layoutParams = AbsListView.LayoutParams(w, (w * ratio).toInt() + gap)
            val key = "$position@$w"
            view.tag = key
            val cached = cache.get(key)
            if (cached != null) {
                view.setImageBitmap(cached)
                return view
            }
            view.setImageBitmap(null)
            view.setBackgroundColor(Color.WHITE)
            worker.execute {
                // The row may have scrolled away while this waited in the queue.
                if (view.tag != key) return@execute
                val bmp = try { render(position, w) } catch (_: Throwable) { null } ?: return@execute
                ui.post {
                    if (isFinishing || isDestroyed) return@post
                    cache.put(key, bmp)
                    if (view.tag == key) {
                        view.layoutParams = view.layoutParams.apply { height = (w * ratios[position]).toInt() + gap }
                        view.setImageBitmap(bmp)
                    }
                }
            }
            return view
        }
    }

    private fun render(position: Int, width: Int): Bitmap? {
        val r = renderer ?: return null
        val page = r.openPage(position)
        try {
            val ratio = page.height.toFloat() / page.width
            ratios[position] = ratio
            // Cap the bitmap: at 4x zoom on a tall page it would otherwise take hundreds of megabytes.
            var w = width
            var h = (width * ratio).toInt().coerceAtLeast(1)
            val limit = 4096
            if (w > limit || h > limit) {
                val k = limit.toFloat() / maxOf(w, h)
                w = (w * k).toInt().coerceAtLeast(1)
                h = (h * k).toInt().coerceAtLeast(1)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_ZOOM_IN, 0, R.string.zoom_in).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, M_ZOOM_OUT, 1, R.string.zoom_out).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, M_GOTO, 2, R.string.pdf_go_to_page).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            M_ZOOM_IN -> setZoom(zoom + 0.5f)
            M_ZOOM_OUT -> setZoom(zoom - 0.5f)
            M_GOTO -> Ui.input(this, getString(R.string.pdf_go_to_page), (list.firstVisiblePosition + 1).toString(),
                InputType.TYPE_CLASS_NUMBER, android.R.string.ok, { text ->
                    text.trim().toIntOrNull()?.let { list.setSelection((it - 1).coerceIn(0, pageCount - 1)) }
                })
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onSaveInstanceState(out: Bundle) {
        if (::list.isInitialized) out.putInt("page", list.firstVisiblePosition)
        out.putFloat("zoom", zoom)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        // Close on the render thread, after any page still being drawn.
        val r = renderer
        val f = fd
        renderer = null
        worker.execute {
            try { r?.close() } catch (_: Exception) {}
            try { f?.close() } catch (_: Exception) {}
        }
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val M_ZOOM_IN = 1
        const val M_ZOOM_OUT = 2
        const val M_GOTO = 3
    }
}
