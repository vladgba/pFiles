package x.vladgba.files

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import x.vladgba.files.ftp.IFtpService
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class FtpEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val modified: Long = 0L
)

/** Connection to the separate "pFiles FTP" module APK. Never call it from the main thread. */
object FtpModule {
    const val PACKAGE = "x.vladgba.files.ftp"

    @Volatile private var service: IFtpService? = null
    private val lock = Any()
    private val ioPool = Executors.newCachedThreadPool()

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun get(ctx: Context): IFtpService {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        check(Looper.myLooper() != Looper.getMainLooper()) { "FTP calls must run off the main thread" }
        synchronized(lock) {
            service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            val app = ctx.applicationContext
            if (!isInstalled(app)) throw IOException(app.getString(R.string.ftp_module_missing))
            if (!tryBind(app, 3)) {
                // The system force-stops the module when it is swiped from recents and then refuses
                // to bind it. Launching its (invisible) activity once clears the stopped state.
                wake(app)
                if (!tryBind(app, 10)) throw IOException(app.getString(R.string.ftp_module_unavailable))
            }
            return service ?: throw IOException(app.getString(R.string.ftp_module_unavailable))
        }
    }

    private fun tryBind(app: Context, waitSec: Long): Boolean {
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IFtpService.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
        val intent = Intent().setComponent(ComponentName(PACKAGE, "$PACKAGE.FtpService"))
        val bound = try {
            app.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            false
        }
        if (bound && latch.await(waitSec, TimeUnit.SECONDS)) return true
        try { app.unbindService(conn) } catch (_: Exception) {}
        return false
    }

    private fun wake(app: Context) {
        try {
            app.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName(PACKAGE, "$PACKAGE.StatusActivity"))
                    .putExtra("wake", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            Thread.sleep(700)
        } catch (_: Exception) {
        }
    }

    fun disconnect(ctx: Context, id: String) {
        ioPool.execute { try { get(ctx).disconnect(id) } catch (_: Exception) {} }
    }

    /** Runs a binder call that fills a pipe, while [consume] reads it. Returns after the call finished. */
    fun <T> pipeFromModule(call: (ParcelFileDescriptor) -> String, consume: (InputStream) -> T): T {
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        val status = ioPool.submit<String> { try { call(write) } finally { write.close() } }
        val result = try {
            ParcelFileDescriptor.AutoCloseInputStream(read).use(consume)
        } catch (e: Throwable) {
            status.cancel(false)
            throw e
        }
        return check(result, status.get())
    }

    /** Runs a binder call that drains a pipe, while [produce] fills it. */
    fun pipeToModule(call: (ParcelFileDescriptor) -> String, produce: (OutputStream) -> Unit) {
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        val status = ioPool.submit<String> { try { call(read) } finally { read.close() } }
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use(produce)
        } catch (e: Throwable) {
            status.cancel(false)
            throw e
        }
        check(Unit, status.get())
    }

    fun <T> check(value: T, json: String): T {
        parse(json)
        return value
    }

    fun parse(json: String): JSONObject {
        val j = JSONObject(json)
        if (!j.optBoolean("ok")) throw IOException(j.optString("error", "FTP module error"))
        return j
    }
}

/** Same API as the former in-app client, now served by the FTP module APK. */
class FtpClient(ctx: Context, val config: FtpConfig) : Closeable {
    private val app = ctx.applicationContext
    private val cfg = config.toJson().toString()

    var isConnected: Boolean = false
        private set

    private fun svc() = FtpModule.get(app)

    @Throws(IOException::class)
    fun connect() {
        FtpModule.parse(svc().connect(cfg))
        isConnected = true
    }

    fun noop(): Boolean = try {
        FtpModule.parse(svc().connect(cfg))
        true
    } catch (_: Exception) {
        false
    }

    @Throws(IOException::class)
    fun listFiles(remotePath: String): List<FtpEntry> {
        val s = svc()
        val text = FtpModule.pipeFromModule({ s.list(cfg, remotePath, it) }) { it.readBytes().toString(Charsets.UTF_8) }
        val arr = JSONArray(text.ifEmpty { "[]" })
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            FtpEntry(o.getString("n"), o.getBoolean("d"), o.optLong("s"), o.optLong("m"))
        }
    }

    @Throws(IOException::class)
    fun download(remotePath: String, output: OutputStream, onProgress: ((Long) -> Unit)? = null) {
        val s = svc()
        FtpModule.pipeFromModule({ s.download(cfg, remotePath, it) }) { input ->
            val buf = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                total += n
                onProgress?.invoke(total)
            }
            output.flush()
        }
    }

    @Throws(IOException::class)
    fun upload(input: InputStream, remotePath: String, onProgress: ((Long) -> Unit)? = null) {
        val s = svc()
        FtpModule.pipeToModule({ s.upload(cfg, remotePath, it) }) { out ->
            val buf = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                onProgress?.invoke(total)
            }
        }
    }

    @Throws(IOException::class)
    fun mkdir(remotePath: String): Boolean = FtpModule.parse(svc().mkdir(cfg, remotePath)).optBoolean("result")

    @Throws(IOException::class)
    fun deleteFile(remotePath: String): Boolean = FtpModule.parse(svc().delete(cfg, remotePath)).optBoolean("result")

    /** Removes one file or one empty folder. */
    @Throws(IOException::class)
    fun deleteOne(remotePath: String): Boolean = FtpModule.parse(svc().deleteOne(cfg, remotePath)).optBoolean("result")

    /** The module deletes a file or an empty folder with one call. */
    @Throws(IOException::class)
    fun rmdir(remotePath: String): Boolean = deleteFile(remotePath)

    @Throws(IOException::class)
    fun rename(oldPath: String, newPath: String): Boolean =
        FtpModule.parse(svc().rename(cfg, oldPath, newPath)).optBoolean("result")

    override fun close() {
        isConnected = false
    }

    fun abort() {
        isConnected = false
    }
}
