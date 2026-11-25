package x.vladgba.files.ftp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import org.json.JSONObject
import java.io.File

/**
 * Runs the FTP or SFTP file server for pFiles as a foreground service of this module, so the main app
 * carries no server code. Started through [IFtpService.serverStart]; the notification texts come from pFiles
 * so they follow its language.
 */
class FtpServerService : Service() {
    private var ftp: FtpServer? = null
    private var sftpServer: SftpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.getStringExtra(EXTRA_CFG) == null) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        val cfg = JSONObject(intent.getStringExtra(EXTRA_CFG)!!)
        val use = cfg.optBoolean("sftp", false)
        val portNumber = cfg.optInt("port", if (use) 2222 else 2121)
        val root = File(cfg.optString("root").ifEmpty { Environment.getExternalStorageDirectory().path })
        val user = cfg.optString("user")
        val pass = cfg.optString("pass")
        startForegroundNotification(portNumber, use, cfg.optString("title", "File server"), cfg.optString("stop", "Stop"))
        stopServer()
        try {
            if (use) {
                val s = SftpServer(root, portNumber, user, pass, File(filesDir, "sftp_host_key"))
                s.start()
                sftpServer = s
            } else {
                val s = FtpServer(root, portNumber, user, pass, cfg.optBoolean("anon", true))
                s.start()
                ftp = s
            }
            running = true
            sftp = use
            port = portNumber
        } catch (_: Throwable) {
            stopServer()
            stopSelf()
        }
        return START_NOT_STICKY // a restart would lose the login, which is never stored
    }

    private fun startForegroundNotification(port: Int, sftp: Boolean, title: String, stopLabel: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val stop = PendingIntent.getService(this, 1, Intent(this, FtpServerService::class.java).setAction(ACTION_STOP), flags)
        val open = packageManager.getLaunchIntentForPackage("x.vladgba.files")
            ?.let { PendingIntent.getActivity(this, 0, it, flags) }
        val ip = FtpServer.getLocalIp() ?: "0.0.0.0"
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = builder
            .setContentTitle(title)
            .setContentText((if (sftp) "sftp://" else "ftp://") + "$ip:$port")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, stopLabel, stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun stopServer() {
        ftp?.stop(); ftp = null
        sftpServer?.stop(); sftpServer = null
        running = false
    }

    /** Android 15 ends a data-sync foreground service after about six hours: stop cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopServer()
        stopSelf()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "x.vladgba.files.ftp.SERVER_STOP"
        const val EXTRA_CFG = "cfg"
        private const val CHANNEL = "ftp_server"
        private const val NOTIFICATION_ID = 2121

        @Volatile var running = false
        @Volatile var sftp = false
        @Volatile var port = 0

        /** The module serves your storage, so it needs its own "All files access" (Android 11+) or storage permission. */
        fun hasAccess(ctx: Context): Boolean = when {
            Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT >= 23 -> ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            else -> true
        }
    }
}
