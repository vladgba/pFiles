package x.vladgba.files

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Image viewer module: zoom and pan, swipe through the folder's images, EXIF basics. Framework decoders only. */
object ImageModule : FileOpener {
    val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "wbmp")
    override val id = "image"
    override val title = R.string.image_viewer
    override fun canOpen(f: File) = !f.isDirectory && isImage(f)
    override fun open(a: Activity, f: File) = Openers.launch(a, ImageActivity::class.java, f)

    fun isImage(f: File): Boolean {
        val ext = f.extension.lowercase()
        if (ext == "heic" || ext == "heif") return Build.VERSION.SDK_INT >= 28
        if (ext == "avif") return Build.VERSION.SDK_INT >= 31
        return ext in EXTENSIONS
    }
}

/** Draws a drawable through a matrix: pinch to zoom, drag to pan, double tap to toggle, fling sideways to page. */
class ZoomView(ctx: Context) : View(ctx), Drawable.Callback {
    var onSwipe: ((forward: Boolean) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    private var content: Drawable? = null
    private val matrix = Matrix()
    private var base = 1f   // scale that fits the image into the view
    private var scale = 1f  // current scale, base..base * MAX
    private var tx = 0f
    private var ty = 0f

    fun set(d: Drawable?) {
        (content as? Animatable)?.stop()
        content?.callback = null
        content = d
        if (d != null) {
            d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
            d.callback = this
            (d as? Animatable)?.start()
        }
        fit()
    }

    private fun iw() = content?.intrinsicWidth?.toFloat() ?: 0f
    private fun ih() = content?.intrinsicHeight?.toFloat() ?: 0f

    private fun fit() {
        if (width == 0 || height == 0 || iw() <= 0f || ih() <= 0f) { invalidate(); return }
        // Small pictures are shown 1:1 at most 4x; big ones are fitted.
        base = min(width / iw(), height / ih()).let { if (it > 4f) 4f else it }
        scale = base
        clamp()
    }

    private fun clamp() {
        val w = iw() * scale
        val h = ih() * scale
        tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
        invalidate()
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val s = target.coerceIn(base, max(base * MAX, 4f))
        tx = fx - (fx - tx) * (s / scale)
        ty = fy - (fy - ty) * (s / scale)
        scale = s
        clamp()
    }

    private val zoomed get() = scale > base * 1.01f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = fit()

    override fun onDraw(canvas: Canvas) {
        val d = content ?: return
        matrix.setScale(scale, scale)
        matrix.postTranslate(tx, ty)
        canvas.save()
        canvas.concat(matrix)
        d.draw(canvas)
        canvas.restore()
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomTo(scale * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!zoomed) return false
            tx -= dx
            ty -= dy
            clamp()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed) zoomTo(base, e.x, e.y) else zoomTo(max(base * 2.5f, min(1f, base * MAX)), e.x, e.y)
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (zoomed || abs(vx) < abs(vy) * 1.5f || abs(vx) < 800f) return false
            onSwipe?.invoke(vx < 0)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    override fun verifyDrawable(who: Drawable) = who === content || super.verifyDrawable(who)
    override fun invalidateDrawable(who: Drawable) = invalidate()

    private companion object { const val MAX = 8f }
}

class ImageActivity : Activity() {
    private lateinit var view: ZoomView
    private lateinit var busy: ProgressBar
    private var files: List<File> = emptyList()
    private var index = 0
    private var size = "" // "4000×3000" of the shown image
    private var loadId = 0
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val first = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        actionBar?.setDisplayHomeAsUpEnabled(true)
        view = ZoomView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            onSwipe = { forward -> step(if (forward) 1 else -1) }
        }
        busy = ProgressBar(this).apply { visibility = View.GONE }
        val root = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(busy, FrameLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48), android.view.Gravity.CENTER))
            Ui.applyInsets(this, underActionBar = true)
        }
        setContentView(root)
        files = listOf(first)
        val wanted = state?.getString("path")?.let(::File) ?: first
        show(wanted)
        worker.execute {
            // Siblings in name order, so swiping walks the folder the way the list shows it.
            val siblings = first.parentFile?.listFiles { f -> f.isFile && ImageModule.isImage(f) }
                ?.sortedWith(compareBy({ it.name.lowercase() }, { it.name })).orEmpty()
            ui.post {
                if (isFinishing || isDestroyed || siblings.isEmpty()) return@post
                val current = files.getOrNull(index) ?: wanted
                val i = siblings.indexOfFirst { it.absolutePath == current.absolutePath }
                if (i >= 0) { files = siblings; index = i; updateTitle() }
            }
        }
    }

    private fun step(d: Int) {
        if (files.size < 2) return
        show(files[(index + d + files.size) % files.size])
    }

    private fun show(f: File) {
        index = files.indexOfFirst { it.absolutePath == f.absolutePath }.coerceAtLeast(0)
        size = ""
        updateTitle()
        busy.visibility = View.VISIBLE
        val id = ++loadId
        val maxSide = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        worker.execute {
            val d = try { decode(f, maxSide) } catch (_: Throwable) { null } // OutOfMemoryError too
            ui.post {
                if (isFinishing || isDestroyed || id != loadId) return@post
                busy.visibility = View.GONE
                if (d == null) Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                view.set(d?.first)
                size = d?.second ?: ""
                updateTitle()
            }
        }
    }

    /** Decodes downsampled to about [maxSide]; returns the drawable and the original "width×height". */
    private fun decode(f: File, maxSide: Int): Pair<Drawable, String> {
        if (Build.VERSION.SDK_INT >= 28) {
            var dims = ""
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { decoder, info, _ ->
                dims = "${info.size.width}×${info.size.height}"
                decoder.setTargetSampleSize(sample(info.size.width, info.size.height, maxSide))
            }
            return d to dims
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) throw IllegalArgumentException("not an image")
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, maxSide) }
        var bmp = BitmapFactory.decodeFile(f.path, opts) ?: throw IllegalArgumentException("decode failed")
        val degrees = when (try { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } catch (_: Exception) { 1 }) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        }
        return BitmapDrawable(resources, bmp) to "${bounds.outWidth}×${bounds.outHeight}"
    }

    private fun sample(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    private fun updateTitle() {
        val f = files.getOrNull(index) ?: return
        actionBar?.title = f.name
        val parts = listOfNotNull(
            if (files.size > 1) "${index + 1}/${files.size}" else null,
            size.ifEmpty { null },
            Formatter.formatShortFileSize(this, f.length()),
        )
        actionBar?.subtitle = parts.joinToString(" · ")
    }

    private fun showInfo() {
        val f = files.getOrNull(index) ?: return
        val text = StringBuilder()
        fun line(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(label).append('\n').append(value)
        }
        line(getString(R.string.prop_path), f.absolutePath)
        line(getString(R.string.prop_size), Formatter.formatFileSize(this, f.length()) + if (size.isNotEmpty()) " · $size" else "")
        line(getString(R.string.prop_modified), DateFormat.getDateTimeInstance().format(Date(f.lastModified())))
        try {
            val exif = ExifInterface(f.path)
            line(getString(R.string.exif_taken), exif.getAttribute(ExifInterface.TAG_DATETIME))
            val camera = listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))
                .joinToString(" ").trim()
            line(getString(R.string.exif_camera), camera)
            val shot = listOfNotNull(
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let {
                    if (it > 0 && it < 1) "1/${Math.round(1 / it)} s" else "$it s"
                },
                exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { "ISO $it" },
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { r ->
                    val p = r.split('/')
                    val mm = if (p.size == 2) (p[0].toDoubleOrNull() ?: 0.0) / (p[1].toDoubleOrNull() ?: 1.0) else r.toDoubleOrNull()
                    mm?.takeIf { it > 0 }?.let { "%.1f mm".format(it) }
                },
            ).joinToString(" · ")
            line(getString(R.string.exif_shot), shot)
            val pos = FloatArray(2)
            if (exif.getLatLong(pos)) line(getString(R.string.exif_location), "%.6f, %.6f".format(pos[0], pos[1]))
        } catch (_: Exception) {
            // no EXIF in this format
        }
        AlertDialog.Builder(this).setTitle(f.name).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_PREV, 0, R.string.previous).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, M_NEXT, 1, R.string.next).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, M_INFO, 2, R.string.properties).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, M_SHARE, 3, R.string.share).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, M_OPEN_WITH, 4, R.string.open_with).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(M_PREV)?.isVisible = files.size > 1
        menu.findItem(M_NEXT)?.isVisible = files.size > 1
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val f = files.getOrNull(index)
        when (item.itemId) {
            android.R.id.home -> finish()
            M_PREV -> step(-1)
            M_NEXT -> step(1)
            M_INFO -> showInfo()
            M_SHARE -> f?.let { Openers.share(this, listOf(it)) }
            M_OPEN_WITH -> f?.let { Openers.chooser(this, it) }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onSaveInstanceState(out: Bundle) {
        files.getOrNull(index)?.let { out.putString("path", it.absolutePath) }
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        loadId++
        if (::view.isInitialized) view.set(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val M_PREV = 1
        const val M_NEXT = 2
        const val M_INFO = 3
        const val M_SHARE = 4
        const val M_OPEN_WITH = 5
    }
}
