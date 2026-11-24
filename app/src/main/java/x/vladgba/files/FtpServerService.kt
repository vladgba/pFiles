package x.vladgba.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File

class FtpServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, 2121) ?: 2121
        val user = intent?.getStringExtra(EXTRA_USER) ?: ""
        val pass = intent?.getStringExtra(EXTRA_PASS) ?: ""
        val anon = intent?.getBooleanExtra(EXTRA_ANON, true) ?: true
        val rootPath = intent?.getStringExtra(EXTRA_ROOT) ?: Storage.primary().path
        val sftp = intent?.getBooleanExtra(EXTRA_SFTP, false) ?: false

        startForegroundNotification(port)
        if (sftp) startSftp(File(rootPath), port, user, pass) else startServer(File(rootPath), port, user, pass, anon)
        return START_STICKY
    }

    private fun startForegroundNotification(port: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "ftp_server"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.ftp_server),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, FtpServerService::class.java).apply {
            this.action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val ip = FtpServer.getLocalIp() ?: "0.0.0.0"
        val url = "ftp://$ip:$port"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle(getString(R.string.ftp_server_running))
            .setContentText(url)
            .setSmallIcon(R.drawable.ic_storage)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stopPending)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startServer(root: File, port: Int, user: String, pass: String, anon: Boolean) {
        stopServer()
        val s = FtpServer(root, port, user, pass, anon)
        serverInstance = s
        try {
            s.start()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun startSftp(root: File, port: Int, user: String, pass: String) {
        stopServer()
        val s = SftpServer(root, port, user, pass, File(filesDir, "sftp_host_key"))
        try {
            s.start()
            sftpInstance = s
        } catch (_: Throwable) {
            s.stop()
            stopSelf()
        }
    }

    private fun stopServer() {
        serverInstance?.stop()
        serverInstance = null
        sftpInstance?.stop()
        sftpInstance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    companion object {
        const val ACTION_START = "x.vladgba.files.FTP_START"
        const val ACTION_STOP = "x.vladgba.files.FTP_STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_ANON = "anon"
        const val EXTRA_ROOT = "root"
        const val EXTRA_SFTP = "sftp"
        private const val NOTIFICATION_ID = 2121

        private var serverInstance: FtpServer? = null
        private var sftpInstance: SftpServer? = null

        fun isRunning(): Boolean = serverInstance?.running == true || sftpInstance?.running == true

        fun start(ctx: Context, port: Int = 2121, user: String = "", pass: String = "", anon: Boolean = true, root: String = Storage.primary().path, sftp: Boolean = false) {
            val intent = Intent(ctx, FtpServerService::class.java).apply {
                this.action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_USER, user)
                putExtra(EXTRA_PASS, pass)
                putExtra(EXTRA_ANON, anon)
                putExtra(EXTRA_ROOT, root)
                putExtra(EXTRA_SFTP, sftp)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, FtpServerService::class.java).apply {
                this.action = ACTION_STOP
            }
            ctx.startService(intent)
        }
    }
}
