package x.vladgba.files

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import x.vladgba.files.ftp.IFtpService

/** Starts, stops and asks about the FTP and SFTP file servers, which run inside the FTP module. Calls block: use a worker thread. */
object FtpServerControl {
    class Status(val running: Boolean, val sftp: Boolean, val port: Int, val ip: String, val access: Boolean)

    class Failure(message: String, val needsAccess: Boolean = false) : Exception(message)

    /** The last status seen, for screens that cannot wait for the module. */
    @Volatile var last: Status? = null
        private set

    fun isRunning(): Boolean = last?.running == true

    private fun service(ctx: Context): IFtpService {
        val s = FtpModule.get(ctx)
        if (s.version() < IFtpService.VERSION_SERVERS) throw Failure(ctx.getString(R.string.ftp_module_old))
        return s
    }

    fun status(ctx: Context): Status {
        val j = JSONObject(service(ctx).serverStatus())
        return Status(j.optBoolean("running"), j.optBoolean("sftp"), j.optInt("port"), j.optString("ip", "0.0.0.0"),
            j.optBoolean("access", true)).also { last = it }
    }

    /** Starts the server; throws [Failure] with a message to show. */
    fun start(ctx: Context, port: Int, user: String, pass: String, anon: Boolean, sftp: Boolean, root: String = Storage.primary().path) {
        val s = service(ctx)
        val cfg = JSONObject().put("port", port).put("user", user).put("pass", pass).put("anon", anon).put("sftp", sftp)
            .put("root", root).put("title", ctx.getString(R.string.ftp_server_running)).put("stop", ctx.getString(R.string.stop))
        val r = JSONObject(s.serverStart(cfg.toString()))
        if (!r.optBoolean("ok")) {
            if (r.optString("error") == "no_access") throw Failure(ctx.getString(R.string.ftp_server_needs_access), needsAccess = true)
            throw Failure(r.optString("error"))
        }
        last = Status(true, sftp, port, "", true)
    }

    fun stop(ctx: Context) {
        service(ctx).serverStop()
        last = last?.let { Status(false, it.sftp, it.port, it.ip, it.access) }
    }

    /** Opens the module's own screen that asks for access to all files. */
    fun requestAccess(ctx: Context) {
        ctx.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(FtpModule.PACKAGE, "${FtpModule.PACKAGE}.StatusActivity"))
                .putExtra("grant", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
