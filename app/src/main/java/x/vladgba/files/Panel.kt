package x.vladgba.files

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.ExecutorService
import kotlin.math.hypot

/**
 * One file list: its own folder, breadcrumb, scroll memory and selection. [MainActivity] shows one or two of them
 * (Total Commander style). Items are dragged by long-pressing and then moving the finger; a drop lands on the
 * folder under the finger, or in the panel's current folder.
 */
@SuppressLint("ViewConstructor")
class Panel(private val activity: Activity, private val host: Host) : LinearLayout(activity) {

    interface Host {
        val loader: ExecutorService
        val roots: List<Storage.Root>
        val showHidden: Boolean
        fun comparator(): Comparator<Entry>
        fun busy(on: Boolean)
        /** A touch went down somewhere in [p]: it becomes the panel the bottom bar acts on. */
        fun onActivate(p: Panel)
        fun beforeNavigate(p: Panel)
        fun onOpen(p: Panel, e: Entry)
        /** Selection started, changed or ended; check [Panel.selecting]. */
        fun onSelectionChanged(p: Panel)
        /** Folder, title or item count changed. */
        fun onLocationChanged(p: Panel)
        fun onDrop(source: Panel, files: List<File>, target: File)
    }

    /** What travels with a drag; only drags started by our own panels are accepted. */
    private class DragState(val source: Panel, val files: List<File>)

    val adapter = FileAdapter(activity)
    val list: GridView
    private val crumbs: LinearLayout
    private val crumbScroll: HorizontalScrollView
    private val activeMark: View
    private val infoView: TextView
    private val emptyView: TextView
    private val frame: FrameLayout
    private val dropMark: View
    private val hoverMark: View

    private val ui = Handler(Looper.getMainLooper())
    private val scrollMemory = HashMap<String, Int>()
    private var loadToken = 0
    private var shownDir: File? = null

    var cwd: File = File("/")
        private set
    var title: String = ""
        private set
    var itemCount: Int? = null
        private set
    var selecting = false
        private set

    // Drag source: armed by a long-press, started once the finger moves past touch slop.
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private var dragArmed = false
    private var downX = 0f
    private var downY = 0f

    // Drop target state.
    private var hoverDir: File? = null
    private var lastDragX = 0f
    private var lastDragY = 0f
    private var autoScroll = 0
    private val autoScroller = object : Runnable {
        override fun run() {
            if (autoScroll == 0) return
            list.scrollListBy(autoScroll * dp(14))
            updateHover(lastDragX, lastDragY)
            ui.postDelayed(this, 30)
        }
    }

    private fun dp(v: Int) = Ui.dp(activity, v)

    private fun themeColor(attr: Int) = TypedValue().also { activity.theme.resolveAttribute(attr, it, true) }.data

    init {
        orientation = VERTICAL
        val accent = themeColor(android.R.attr.colorAccent)

        crumbs = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        crumbScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(crumbs, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        activeMark = View(activity).apply {
            setBackgroundColor(accent)
            visibility = INVISIBLE
        }
        // Item count (or selection count and size) on the right of the breadcrumb.
        infoView = TextView(activity).apply {
            setTextColor(Ui.fgDim(activity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setSingleLine()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(10), 0)
        }
        val crumbRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            addView(crumbScroll, LayoutParams(0, MATCH_PARENT, 1f))
            addView(infoView, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        val header = LinearLayout(activity).apply {
            orientation = VERTICAL
            setBackgroundColor(themeColor(android.R.attr.colorPrimary))
            addView(crumbRow, LayoutParams(MATCH_PARENT, dp(32)))
            addView(activeMark, LayoutParams(MATCH_PARENT, dp(2)))
        }

        emptyView = TextView(activity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            alpha = 0.6f
        }
        list = GridView(activity).apply {
            isFastScrollEnabled = true
            clipToPadding = false
            choiceMode = AbsListView.CHOICE_MODE_NONE
            adapter = this@Panel.adapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ -> onItemClick(pos) }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ -> onItemLongClick(pos) }
            setOnTouchListener { _, ev -> trackDrag(ev); false }
        }
        list.emptyView = emptyView
        adapter.isChecked = { pos -> selecting && list.isItemChecked(pos) }

        dropMark = View(activity).apply {
            background = GradientDrawable().apply { setStroke(dp(2), accent); setColor(accent and 0x14FFFFFF) }
            visibility = GONE
        }
        hoverMark = View(activity).apply {
            background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(accent and 0x55FFFFFF) }
            visibility = GONE
        }
        frame = FrameLayout(activity).apply {
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(emptyView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(hoverMark, FrameLayout.LayoutParams(0, 0))
            addView(dropMark, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setOnDragListener { _, ev -> onFrameDrag(ev) }
        }

        addView(header, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(frame, LayoutParams(MATCH_PARENT, 0, 1f))
    }

    /** Any touch in the panel (list, empty view, breadcrumb) makes it active; the touch itself goes on as usual. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) host.onActivate(this)
        return false
    }

    /** Active marker only matters with two panels; the inactive one's breadcrumb is dimmed. */
    fun setActive(active: Boolean, dual: Boolean) {
        activeMark.visibility = if (dual && active) VISIBLE else INVISIBLE
        crumbs.alpha = if (!dual || active) 1f else 0.55f
        infoView.alpha = crumbs.alpha
    }

    fun setInfo(text: CharSequence) {
        infoView.text = text
    }

    // ---------------------------------------------------------------- navigation & loading

    /** Sets the starting folder without remembering the scroll position of the previous one. */
    fun start(dir: File) {
        cwd = dir
    }

    fun navigate(dir: File) {
        host.beforeNavigate(this)
        scrollMemory[cwd.path] = list.firstVisiblePosition
        finishSelection()
        cwd = dir
        load()
    }

    /** One level up, unless already at a storage root. Returns false when there is nowhere to go. */
    fun goUp(): Boolean {
        val parent = cwd.parentFile ?: return false
        if (host.roots.any { it.dir == cwd }) return false
        navigate(parent)
        return true
    }

    fun load() {
        if (activity.isDestroyed || host.loader.isShutdown) return
        val dir = cwd
        val token = ++loadToken
        val hidden = host.showHidden
        val cmp = host.comparator()
        renderCrumbs()
        host.busy(true)
        host.loader.execute {
            val entries = dir.listFiles()
                ?.filter { hidden || !it.name.startsWith(".") }
                ?.map(::Entry)
                ?.sortedWith(cmp)
            ui.post {
                host.busy(false)
                if (token != loadToken || activity.isFinishing) return@post
                val pos = scrollMemory.remove(dir.path) ?: if (shownDir == dir) list.firstVisiblePosition else 0
                finishSelection()
                adapter.setData(entries ?: emptyList())
                shownDir = dir
                emptyView.setText(if (entries == null) R.string.cannot_read else R.string.empty)
                itemCount = entries?.size
                list.setSelection(pos)
                host.onLocationChanged(this)
            }
        }
    }

    private fun renderCrumbs() {
        val chain = ArrayList<Pair<String, File>>()
        var d: File? = cwd
        while (d != null) {
            val root = host.roots.firstOrNull { it.dir == d && it.dir.path != "/" }
            if (root != null) { chain += root.label to d; break }
            chain += (if (d.path == "/") "/" else d.name) to d
            d = d.parentFile
        }
        chain.reverse()
        crumbs.removeAllViews()
        chain.forEachIndexed { i, (label, dir) ->
            if (i > 0) crumbs.addView(TextView(activity).apply { text = "›"; setTextColor(Ui.fgDim(activity)); alpha = 0.7f })
            crumbs.addView(TextView(activity).apply {
                text = label
                setTextColor(Ui.fg(activity))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setBackgroundResource(Ui.attrRes(activity, android.R.attr.selectableItemBackground))
                if (i == chain.lastIndex) setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { if (dir != cwd) navigate(dir) }
            })
        }
        title = chain.last().first
        crumbScroll.post { crumbScroll.fullScroll(FOCUS_RIGHT) }
        host.onLocationChanged(this)
    }

    /** One GridView serves both modes: a single stretched column is the list, auto-fit tiles are the grid. */
    fun applyViewMode(grid: Boolean) {
        val pos = list.firstVisiblePosition
        finishSelection()
        if (grid) {
            list.numColumns = GridView.AUTO_FIT
            list.columnWidth = dp(104)
            list.stretchMode = GridView.STRETCH_COLUMN_WIDTH
            list.horizontalSpacing = dp(4)
            list.verticalSpacing = dp(4)
            list.setPadding(dp(4), dp(4), dp(4), dp(4))
        } else {
            list.numColumns = 1
            list.stretchMode = GridView.STRETCH_COLUMN_WIDTH
            list.horizontalSpacing = 0
            list.verticalSpacing = 0
            list.setPadding(0, 0, 0, 0)
        }
        adapter.grid = grid
        list.setSelection(pos)
    }

    // ---------------------------------------------------------------- selection

    private fun onItemClick(pos: Int) {
        if (selecting) {
            // CHOICE_MODE_MULTIPLE already toggled the item.
            if (list.checkedItemCount == 0) finishSelection() else host.onSelectionChanged(this)
            return
        }
        host.onOpen(this, adapter.getItem(pos))
    }

    /** Long-press selects the item (tap toggles it afterwards) and arms a drag of the whole selection. */
    private fun onItemLongClick(pos: Int): Boolean {
        if (!selecting) startSelection()
        list.setItemChecked(pos, true)
        host.onSelectionChanged(this)
        dragArmed = true
        return true
    }

    fun selected(): List<Entry> {
        val c = list.checkedItemPositions ?: return emptyList()
        return (0 until c.size()).filter { c.valueAt(it) }.map { adapter.getItem(c.keyAt(it)) }
    }

    private fun startSelection() {
        selecting = true
        list.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
    }

    fun selectAll() {
        if (!selecting) startSelection()
        for (i in 0 until adapter.count) list.setItemChecked(i, true)
        host.onSelectionChanged(this)
    }

    fun finishSelection() {
        if (!selecting) return
        selecting = false
        list.clearChoices()
        list.choiceMode = AbsListView.CHOICE_MODE_NONE
        // With choice mode off the list no longer updates the highlight, so clear it on visible rows
        // ourselves; recycled rows are reset by the adapter (isChecked) when they're rebound.
        for (i in 0 until list.childCount) list.getChildAt(i).isActivated = false
        list.invalidateViews()
        host.onSelectionChanged(this)
    }

    // ---------------------------------------------------------------- drag source

    private fun trackDrag(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragArmed = false; downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_MOVE ->
                if (dragArmed && hypot(ev.x - downX, ev.y - downY) > touchSlop) { dragArmed = false; startDrag() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragArmed = false
        }
    }

    private fun startDrag() {
        val files = selected().map { it.file }
        if (files.isEmpty()) return
        val label = files.singleOrNull()?.name ?: resources.getQuantityString(R.plurals.items, files.size, files.size)
        val data = ClipData.newPlainText(label, files.joinToString("\n") { it.path })
        val shadow = LabelShadow(list, label, themeColor(android.R.attr.colorAccent))
        val state = DragState(this, files)
        if (Build.VERSION.SDK_INT >= 24) list.startDragAndDrop(data, shadow, state, 0)
        else @Suppress("DEPRECATION") list.startDrag(data, shadow, state, 0)
    }

    // ---------------------------------------------------------------- drop target

    private fun onFrameDrag(ev: DragEvent): Boolean {
        val state = ev.localState as? DragState ?: return false
        when (ev.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_ENTERED -> dropMark.visibility = VISIBLE
            DragEvent.ACTION_DRAG_LOCATION -> {
                lastDragX = ev.x
                lastDragY = ev.y
                updateHover(ev.x, ev.y, state.files)
                updateAutoScroll(ev.y)
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> clearDropState()
            DragEvent.ACTION_DROP -> {
                updateHover(ev.x, ev.y, state.files)
                val target = hoverDir ?: cwd
                clearDropState()
                host.onDrop(state.source, state.files, target)
            }
        }
        return true
    }

    private var draggedFiles: List<File> = emptyList()

    /** Highlights the folder under the finger, if any; that folder becomes the drop target. */
    private fun updateHover(x: Float, y: Float, files: List<File> = draggedFiles) {
        draggedFiles = files
        val pos = if (list.visibility == VISIBLE) list.pointToPosition(x.toInt(), y.toInt()) else AdapterView.INVALID_POSITION
        val entry = if (pos == AdapterView.INVALID_POSITION) null else adapter.getItem(pos)
        val dir = entry?.takeIf { it.isDir && it.file !in files }?.file
        val child = if (dir == null) null else list.getChildAt(pos - list.firstVisiblePosition)
        hoverDir = dir.takeIf { child != null }
        if (child == null) { hoverMark.visibility = GONE; return }
        val lp = hoverMark.layoutParams
        if (lp.width != child.width || lp.height != child.height) {
            lp.width = child.width
            lp.height = child.height
            hoverMark.layoutParams = lp
        }
        hoverMark.translationX = (list.left + child.left).toFloat()
        hoverMark.translationY = (list.top + child.top).toFloat()
        hoverMark.visibility = VISIBLE
    }

    /** Scrolls while the finger rests near the top or bottom edge, so far-away folders can be reached. */
    private fun updateAutoScroll(y: Float) {
        val edge = dp(48)
        val dir = when {
            y < edge -> -1
            y > frame.height - edge -> 1
            else -> 0
        }
        if (dir == autoScroll) return
        autoScroll = dir
        ui.removeCallbacks(autoScroller)
        if (dir != 0) ui.postDelayed(autoScroller, 30)
    }

    private fun clearDropState() {
        autoScroll = 0
        ui.removeCallbacks(autoScroller)
        hoverDir = null
        draggedFiles = emptyList()
        hoverMark.visibility = GONE
        dropMark.visibility = GONE
    }

    /** A rounded label (file name or "3 items") floating just above the finger. */
    private class LabelShadow(v: View, label: String, color: Int) : View.DragShadowBuilder(v) {
        private val d = v.resources.displayMetrics.density
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, v.resources.displayMetrics)
            typeface = Typeface.DEFAULT_BOLD
        }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val label = TextUtils.ellipsize(label, text, 220 * d, TextUtils.TruncateAt.MIDDLE).toString()
        private val pillW = (text.measureText(this.label) + 28 * d).toInt()
        private val pillH = (36 * d).toInt()
        private val gap = (28 * d).toInt() // keeps the label visible above the finger

        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            size.set(pillW, pillH + gap)
            touch.set(pillW / 2, pillH + gap - 1)
        }

        override fun onDrawShadow(c: Canvas) {
            val r = pillH / 2f
            c.drawRoundRect(RectF(0f, 0f, pillW.toFloat(), pillH.toFloat()), r, r, bg)
            c.drawText(label, 14 * d, pillH / 2f - (text.descent() + text.ascent()) / 2, text)
        }
    }
}
