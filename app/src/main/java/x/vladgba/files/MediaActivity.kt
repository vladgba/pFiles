package x.vladgba.files

import android.app.Activity
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File

/** Audio and video player module on the platform's VideoView and MediaController: no codecs of its own. */
object MediaModule : FileOpener {
    private val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov")
    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "mid", "midi", "amr")
    override val id = "media"
    override val title = R.string.media_player
    override fun canOpen(f: File) = !f.isDirectory && isMedia(f)
    override fun open(a: Activity, f: File) = Openers.launch(a, MediaActivity::class.java, f)

    fun isVideo(f: File) = f.extension.lowercase() in VIDEO
    fun isAudio(f: File) = f.extension.lowercase() in AUDIO
    fun isMedia(f: File) = isVideo(f) || isAudio(f)
}

class MediaActivity : Activity() {
    private lateinit var video: VideoView
    private lateinit var label: TextView
    private lateinit var controller: MediaController
    private var files: List<File> = emptyList()
    private var index = 0
    private var resumeAt = 0
    private var wasPlaying = true

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val first = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        actionBar?.setDisplayHomeAsUpEnabled(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Same kind only: a video does not continue into the folder's music.
        val video0 = MediaModule.isVideo(first)
        files = first.parentFile?.listFiles { f -> f.isFile && (if (video0) MediaModule.isVideo(f) else MediaModule.isAudio(f)) }
            ?.sortedWith(compareBy({ it.name.lowercase() }, { it.name })).orEmpty().ifEmpty { listOf(first) }
        val wanted = state?.getString("path") ?: first.absolutePath
        index = files.indexOfFirst { it.absolutePath == wanted }.coerceAtLeast(0)
        if (files[index].absolutePath != wanted) files = listOf(first)
        resumeAt = state?.getInt("pos") ?: 0
        wasPlaying = state?.getBoolean("playing") ?: true

        video = VideoView(this)
        label = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 24), 0, Ui.dp(context, 24), 0)
        }
        controller = MediaController(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(video, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            addView(label, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            Ui.applyInsets(this, underActionBar = true)
            setOnClickListener { if (controller.isShowing) controller.hide() else controller.show(0) }
        }
        setContentView(root)

        controller.setAnchorView(root)
        if (files.size > 1) controller.setPrevNextListeners({ play(index + 1) }, { play(index - 1) })
        video.setMediaController(controller)
        video.setOnPreparedListener {
            if (resumeAt > 0) video.seekTo(resumeAt)
            resumeAt = 0
            if (wasPlaying) video.start()
            // Audio has nothing to tap on, so its controls stay up.
            controller.show(if (MediaModule.isAudio(files[index])) 0 else 3000)
        }
        video.setOnCompletionListener {
            // Music plays through the folder; a video stops at its end.
            if (MediaModule.isAudio(files[index]) && index + 1 < files.size) play(index + 1)
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            if (files.size < 2) finish()
            true
        }
        play(index)
    }

    private fun play(i: Int) {
        if (files.isEmpty()) return
        index = (i + files.size) % files.size
        val f = files[index]
        actionBar?.title = f.name
        actionBar?.subtitle = listOfNotNull(
            if (files.size > 1) "${index + 1}/${files.size}" else null,
            Formatter.formatShortFileSize(this, f.length()),
        ).joinToString(" · ")
        label.text = if (MediaModule.isAudio(f)) f.name else ""
        wasPlaying = true
        video.setVideoPath(f.absolutePath)
    }

    override fun onPause() {
        // Leaving the screen pauses playback; the position survives rotation and process death.
        resumeAt = video.currentPosition
        wasPlaying = video.isPlaying
        video.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(out: Bundle) {
        files.getOrNull(index)?.let { out.putString("path", it.absolutePath) }
        out.putInt("pos", resumeAt)
        out.putBoolean("playing", wasPlaying)
        super.onSaveInstanceState(out)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        if (::video.isInitialized) video.stopPlayback()
        super.onDestroy()
    }
}
