package x.vladgba.files

import android.app.Activity
import android.os.Bundle
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File

object RtfModule : FileOpener {
    override val id = "rtf"
    override val title = R.string.rtf_preview
    override fun canOpen(f: File) = !f.isDirectory && f.extension.equals("rtf", ignoreCase = true)
    override fun open(a: Activity, f: File) = Openers.launch(a, RtfActivity::class.java, f)
}

class RtfActivity : Activity() {
    private lateinit var web: WebView
    private var content = ""
    private var originalLayout = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val file = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = file.name
        actionBar?.subtitle = "RTF · ${Formatter.formatShortFileSize(this, file.length())}"
        content = try {
            if (file.length() > 4L * 1024 * 1024) throw IllegalArgumentException("Document too large")
            RtfOps.preview(file.readBytes())
        } catch (_: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        originalLayout = state?.getBoolean("originalLayout") ?: false
        web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = WebViewClient()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            Ui.applyInsets(this, underActionBar = true)
        }
        setContentView(container)
        render()
    }

    private fun render() {
        val font = getSharedPreferences("pfiles", MODE_PRIVATE).getFloat("ed_font", 14f)
        web.loadDataWithBaseURL(null, RtfOps.renderPage(content, originalLayout, Ui.isNight(this), font),
            "text/html", "UTF-8", null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, ORIGINAL_LAYOUT, 0, R.string.rtf_original_layout).apply {
            isCheckable = true
            isChecked = originalLayout
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            ORIGINAL_LAYOUT -> {
                originalLayout = !originalLayout
                item.isChecked = originalLayout
                render()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("originalLayout", originalLayout)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object { private const val ORIGINAL_LAYOUT = 1 }
}
