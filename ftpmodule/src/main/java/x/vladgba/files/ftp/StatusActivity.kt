package x.vladgba.files.ftp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
        // pFiles asks for this before starting a file server: the module serves storage, so it needs its own access.
        if (intent.getBooleanExtra("grant", false) && !FtpServerService.hasAccess(this)) {
            requestAccess()
            return
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(TextView(this).apply {
            text = "pFiles FTP module is active.\n" +
                (if (FtpServerService.hasAccess(context)) "It can serve your files over FTP and SFTP.\nOpen FTP connections or FTP server in pFiles to use it."
                else "To serve your files over FTP or SFTP it needs access to all files.\nStart the FTP server in pFiles to be asked.")
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF202124.toInt())
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        })
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            finish()
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        } else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        finish()
    }
}
