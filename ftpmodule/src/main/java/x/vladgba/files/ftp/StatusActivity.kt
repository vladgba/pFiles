package x.vladgba.files.ftp

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Launching this once takes the module out of the "stopped" state so pFiles can bind to it. */
class StatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // pFiles starts the module this way after the system force-stopped it (e.g. swiped from recents).
        if (intent.getBooleanExtra("wake", false)) {
            finish()
            overridePendingTransition(0, 0)
            return
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(TextView(this).apply {
            text = "pFiles FTP module is active.\nOpen FTP connections in pFiles to use it."
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF202124.toInt())
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        })
    }
}
