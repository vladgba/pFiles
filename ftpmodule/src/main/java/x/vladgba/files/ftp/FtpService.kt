package x.vladgba.files.ftp

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStreamWriter

/** Bound service used by pFiles. Only apps signed with the same key as this module may call it. */
class FtpService : Service() {
    private val ftpPool = FtpSessions()
    private val sftpPool by lazy { SftpSessions(java.io.File(filesDir, "known_hosts")) }

    private fun ok(block: JSONObject.() -> Unit = {}): String =
        JSONObject().put("ok", true).apply(block).toString()

    private fun run(block: () -> String): String = try {
        block()
    } catch (e: Throwable) {
        JSONObject().put("ok", false)
            .put("error", "${e.javaClass.simpleName}: ${e.message ?: ""}").toString()
    }

    private val binder = object : IFtpService.Stub() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val uid = Binder.getCallingUid()
            if (uid != Process.myUid() &&
                packageManager.checkSignatures(uid, Process.myUid()) != PackageManager.SIGNATURE_MATCH
            ) throw SecurityException("Caller is not signed with the pFiles key")
            return super.onTransact(code, data, reply, flags)
        }

        override fun version() = IFtpService.VERSION_SERVERS

        override fun connect(cfg: String) = run {
            val c = FtpCfg.fromJson(cfg)
            if (c.sftp) sftpPool.use(c) { it.pwd() } else ftpPool.use(c) { if (!it.sendNoOp()) throw java.io.IOException("NOOP failed") }
            ok()
        }

        override fun list(cfg: String, path: String, out: ParcelFileDescriptor) = run {
            out.use { pfd ->
                val entries = with(FtpCfg.fromJson(cfg)) { if (sftp) sftpPool.use(this) { SftpOps.list(it, path) } else ftpPool.use(this) { FtpOps.list(it, path) } }
                val arr = JSONArray()
                entries.forEach {
                    arr.put(JSONObject().put("n", it.name).put("d", it.isDir).put("s", it.size).put("m", it.modified))
                }
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { os ->
                    OutputStreamWriter(BufferedOutputStream(os), Charsets.UTF_8).use { it.write(arr.toString()) }
                }
            }
            ok()
        }

        override fun download(cfg: String, path: String, out: ParcelFileDescriptor) = run {
            out.use { pfd ->
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { os ->
                    with(FtpCfg.fromJson(cfg)) { if (sftp) sftpPool.use(this) { SftpOps.download(it, path, os) } else ftpPool.use(this) { FtpOps.download(it, path, os) } }
                }
            }
            ok()
        }

        override fun upload(cfg: String, path: String, src: ParcelFileDescriptor) = run {
            src.use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ins ->
                    with(FtpCfg.fromJson(cfg)) { if (sftp) sftpPool.use(this) { SftpOps.upload(it, path, ins) } else ftpPool.use(this) { FtpOps.upload(it, path, ins) } }
                }
            }
            ok()
        }

        override fun mkdir(cfg: String, path: String) = run {
            val r = with(FtpCfg.fromJson(cfg)) { if (sftp) sftpPool.use(this) { SftpOps.mkdir(it, path) } else ftpPool.use(this) { FtpOps.mkdir(it, path) } }
            ok { put("result", r) }
        }

        override fun delete(cfg: String, path: String) = run {
            val r = with(FtpCfg.fromJson(cfg)) { if (sftp) sftpPool.use(this) { SftpOps.delete(it, path) } else ftpPool.use(this) { FtpOps.delete(it, path) } }
            ok { put("result", r) }
        }

        override fun deleteOne(cfg: String, path: String) = run {
            val r = with(FtpCfg.fromJson(cfg)) { if (sftp) sftpPool.use(this) { SftpOps.deleteOne(it, path) } else ftpPool.use(this) { FtpOps.deleteOne(it, path) } }
            ok { put("result", r) }
        }

        override fun rename(cfg: String, from: String, to: String) = run {
            val r = with(FtpCfg.fromJson(cfg)) { if (sftp) sftpPool.use(this) { SftpOps.rename(it, from, to) } else ftpPool.use(this) { FtpOps.rename(it, from, to) } }
            ok { put("result", r) }
        }

        override fun serverStart(cfg: String) = run {
            val c = JSONObject(cfg)
            if (!FtpServerService.hasAccess(this@FtpService)) {
                return@run JSONObject().put("ok", false).put("error", "no_access").toString()
            }
            val intent = Intent(this@FtpService, FtpServerService::class.java).putExtra(FtpServerService.EXTRA_CFG, c.toString())
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            ok()
        }

        override fun serverStop() = run {
            startService(Intent(this@FtpService, FtpServerService::class.java).setAction(FtpServerService.ACTION_STOP))
            ok()
        }

        override fun serverStatus() = run {
            ok {
                put("running", FtpServerService.running)
                put("sftp", FtpServerService.sftp)
                put("port", FtpServerService.port)
                put("ip", FtpServer.getLocalIp() ?: "0.0.0.0")
                put("access", FtpServerService.hasAccess(this@FtpService))
            }
        }

        override fun disconnect(id: String) {
            ftpPool.disconnect(id); sftpPool.disconnect(id)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        ftpPool.disconnect(null); sftpPool.disconnect(null)
        super.onDestroy()
    }
}
