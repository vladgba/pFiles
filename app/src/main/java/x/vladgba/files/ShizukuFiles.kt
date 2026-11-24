package x.vladgba.files

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs as Shizuku's shell or root user; Android's ordinary File APIs run in this process. */
class ShizukuFileService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (reply == null) return false
        try {
            when (code) {
                ShizukuFiles.LIST -> {
                    val dir = File(data.readString() ?: "")
                    val children = dir.listFiles()
                    val result = (if ((!dir.canRead() || children == null) && android.os.Process.myUid() != 0)
                        RunAs.list(dir) else null) ?: children?.let { files ->
                        JSONArray().apply { files.forEach { put(stat(it)) } }.toString()
                    }
                    reply.writeNoException()
                    reply.writeString(result)
                }
                ShizukuFiles.STAT -> {
                    reply.writeNoException()
                    reply.writeString(stat(File(data.readString() ?: "")).toString())
                }
                ShizukuFiles.OPEN -> {
                    val file = File(data.readString() ?: "")
                    val mode = data.readInt()
                    val fd = if (!file.canRead() && android.os.Process.myUid() != 0 && RunAs.packageFor(file) != null)
                        RunAs.open(file, mode) else ParcelFileDescriptor.open(file, mode)
                    reply.writeNoException()
                    reply.writeParcelable(fd, 0)
                    fd.close()
                }
                ShizukuFiles.MKDIR -> {
                    val file = File(data.readString() ?: "")
                    reply.writeNoException()
                    reply.writeInt(if (file.mkdir() || RunAs.command(file, "mkdir")) 1 else 0)
                }
                ShizukuFiles.MKDIRS -> {
                    val file = File(data.readString() ?: "")
                    reply.writeNoException()
                    reply.writeInt(if (file.isDirectory || file.mkdirs() || RunAs.command(file, "mkdir", "-p")) 1 else 0)
                }
                ShizukuFiles.CREATE -> {
                    val file = File(data.readString() ?: "")
                    reply.writeNoException()
                    reply.writeInt(if (file.createNewFile() || RunAs.command(file, "touch")) 1 else 0)
                }
                ShizukuFiles.RENAME -> {
                    val src = File(data.readString() ?: "")
                    val dst = File(data.readString() ?: "")
                    reply.writeNoException()
                    reply.writeInt(if (src.renameTo(dst) || RunAs.rename(src, dst)) 1 else 0)
                }
                ShizukuFiles.DELETE -> {
                    val file = File(data.readString() ?: "")
                    reply.writeNoException()
                    val ok = if (RunAs.stat(file) != null) RunAs.command(file, "rm", "-r", "-f")
                        else FileOps.delete(file)
                    reply.writeInt(if (ok) 1 else 0)
                }
                ShizukuFiles.TRANSFER -> {
                    val paths = data.createStringArrayList()?.map(::File) ?: emptyList()
                    val dir = File(data.readString() ?: "")
                    val move = data.readInt() != 0
                    reply.writeNoException()
                    val runAs = android.os.Process.myUid() != 0 &&
                        (RunAs.stat(dir) != null || paths.any { RunAs.stat(it) != null })
                    reply.writeInt(if (runAs) RunAs.transfer(paths, dir, move)
                        else FileOps.transfer(paths, dir, move))
                }
                ShizukuFiles.STATS -> {
                    val file = File(data.readString() ?: "")
                    val result = if (android.os.Process.myUid() != 0 && !file.canRead())
                        RunAs.stats(file, 0) ?: FileOps.stats(file) else FileOps.stats(file)
                    reply.writeNoException()
                    reply.writeLongArray(result)
                }
                ShizukuFiles.BACKUP_INTERNAL -> {
                    val pkg = data.readString() ?: ""
                    val source = data.readString() ?: ""
                    val target = File(data.readString() ?: "")
                    reply.writeNoException()
                    reply.writeInt(if (backupInternal(pkg, source, target)) 1 else 0)
                }
                ShizukuFiles.ZIP -> {
                    val files = data.createStringArrayList()?.map(::File) ?: emptyList()
                    val out = File(data.readString() ?: "")
                    val password = data.readString()?.toCharArray()
                    reply.writeNoException()
                    try { reply.writeInt(if (password == null) ZipOps.zip(files, out)
                        else PasswordZipOps.create(files, out, password)) }
                    finally { password?.fill('\u0000') }
                }
                ShizukuFiles.EXTRACT -> {
                    val archive = File(data.readString() ?: "")
                    val dest = File(data.readString() ?: "")
                    val password = data.readString()?.toCharArray()
                    reply.writeNoException()
                    try { reply.writeInt(ArchiveOps.extractAll(archive, dest, password)) }
                    finally { password?.fill('\u0000') }
                }
                ShizukuFiles.ARCHIVE_PASSWORD -> {
                    val archive = File(data.readString() ?: "")
                    val password = data.readString()?.toCharArray()
                    val result = if (password == null) ArchiveOps.needsPassword(archive)
                        else try { PasswordZipOps.validate(archive, password) }
                        finally { password.fill('\u0000') }
                    reply.writeNoException()
                    reply.writeInt(if (result) 1 else 0)
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        } catch (e: Exception) {
            reply.writeException(e)
            return true
        }
    }

    private fun stat(file: File) = JSONObject().apply {
        put("name", file.name)
        val alternate = if (android.os.Process.myUid() != 0 && !file.canRead()) RunAs.stat(file) else null
        put("exists", alternate?.optBoolean("exists") ?: FileOps.existsNoFollow(file))
        put("dir", alternate?.optBoolean("dir") ?: file.isDirectory)
        put("size", alternate?.optLong("size") ?: file.length())
        put("modified", alternate?.optLong("modified") ?: file.lastModified())
        put("link", alternate?.optBoolean("link") ?: FileOps.isLink(file))
        put("read", alternate?.optBoolean("read") ?: file.canRead())
        put("write", alternate?.optBoolean("write") ?: file.canWrite())
        put("execute", alternate?.optBoolean("execute") ?: file.canExecute())
    }

    private fun backupInternal(pkg: String, source: String, target: File): Boolean {
        val tmp = File(target.parentFile, ".${target.name}.pfiles~")
        val command = if (android.os.Process.myUid() == 0)
            listOf("/system/bin/tar", "-C", source, "-cf", "-", ".")
        else listOf("/system/bin/run-as", pkg, "/system/bin/tar", "-C", source, "-cf", "-", ".")
        return try {
            val process = ProcessBuilder(command).start()
            val drain = Thread { process.errorStream.use { it.copyTo(object : java.io.OutputStream() {
                override fun write(b: Int) {}
            }) } }
            drain.start()
            FileOutputStream(tmp).use { output -> process.inputStream.use { it.copyTo(output, 64 * 1024) } }
            val ok = process.waitFor() == 0 && tmp.length() > 0 && tmp.renameTo(target)
            if (!ok) tmp.delete()
            ok
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }
}

class FilesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        ShizukuFiles.init(this)
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}

/** Binder client used by the file browser, file operations and content provider. */
object ShizukuFiles {
    private const val TAG = "pFiles-Shizuku"
    const val LIST = IBinder.FIRST_CALL_TRANSACTION
    const val STAT = LIST + 1
    const val OPEN = LIST + 2
    const val MKDIR = LIST + 3
    const val CREATE = LIST + 4
    const val RENAME = LIST + 5
    const val DELETE = LIST + 6
    const val TRANSFER = LIST + 7
    const val STATS = LIST + 8
    const val MKDIRS = LIST + 9
    const val BACKUP_INTERNAL = LIST + 10
    const val ZIP = LIST + 11
    const val EXTRACT = LIST + 12
    const val ARCHIVE_PASSWORD = LIST + 13
    private const val REQUEST = 712

    @Volatile private var service: IBinder? = null
    @Volatile private var connected = CountDownLatch(1)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var privatePath = ""
    private var initialized = false
    private var binding = false
    var onChange: (() -> Unit)? = null

    fun available() = service?.isBinderAlive == true
    fun isRoot() = available() && try { Shizuku.getUid() == 0 } catch (_: Exception) { false }
    fun awaitReady() {
        if (!available() && Shizuku.pingBinder()) connected.await(5, TimeUnit.SECONDS)
    }
    fun useFor(file: File) = available() && (privatePath.isEmpty() ||
        !file.absolutePath.startsWith("$privatePath/"))

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        val app = ctx.applicationContext
        privatePath = app.filesDir.parentFile?.absolutePath ?: ""
        Shizuku.addBinderReceivedListenerSticky { bindIfAllowed(app) }
        Shizuku.addBinderDeadListener {
            Log.w(TAG, "Shizuku binder disconnected")
            service = null; binding = false; connected = CountDownLatch(1); onChange?.invoke()
        }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == REQUEST && result == PackageManager.PERMISSION_GRANTED) bindIfAllowed(app)
        }
    }

    fun request(activity: Activity) {
        init(activity)
        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "Shizuku binder unavailable when access was requested")
            Toast.makeText(activity, R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
            return
        }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindIfAllowed(activity)
            else Shizuku.requestPermission(REQUEST)
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku permission check failed: ${e.javaClass.simpleName}")
            Toast.makeText(activity, R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
        }
    }

    private fun bindIfAllowed(ctx: Context) {
        if (available() || binding || !Shizuku.pingBinder()) return
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            binding = true
            Log.i(TAG, "Binding file service")
            val args = Shizuku.UserServiceArgs(ComponentName(ctx, ShizukuFileService::class.java))
                .tag("pfiles-file-access").version(2).daemon(false)
            Shizuku.bindUserService(args, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    Log.i(TAG, "File service connected; alive=${binder.isBinderAlive}")
                    service = binder
                    connected.countDown()
                    binding = false
                    onChange?.invoke()
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    Log.w(TAG, "File service disconnected")
                    service = null
                    connected = CountDownLatch(1)
                    binding = false
                    onChange?.invoke()
                }
            })
            mainHandler.postDelayed({
                if (binding && !available()) {
                    Log.e(TAG, "File service connection timed out")
                    binding = false
                    onChange?.invoke()
                }
            }, 8000)
        } catch (e: Exception) {
            Log.e(TAG, "File service binding failed: ${e.javaClass.simpleName}")
            binding = false
            onChange?.invoke()
        }
    }

    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T? {
        val binder = service ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            write(data)
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            read(reply)
        } catch (e: Exception) {
            Log.e(TAG, "File service operation $code failed: ${e.javaClass.simpleName}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun list(dir: File): List<Entry>? {
        val json = call(LIST, { it.writeString(dir.path) }, { it.readString() }) ?: return null
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Entry(File(dir, item.getString("name")), item.getBoolean("dir"),
                item.getLong("size"), item.getLong("modified"))
        }
    }

    fun stat(file: File): JSONObject? = if (useFor(file))
        call(STAT, { it.writeString(file.path) }, { it.readString() })?.let(::JSONObject) else null
    fun isDirectory(file: File) = stat(file)?.optBoolean("dir") ?: file.isDirectory
    fun exists(file: File) = stat(file)?.optBoolean("exists") ?: FileOps.existsNoFollow(file)
    fun isLink(file: File) = stat(file)?.optBoolean("link") ?: false
    fun mkdir(file: File) = call(MKDIR, { it.writeString(file.path) }, { it.readInt() != 0 }) ?: false
    fun mkdirs(file: File) = call(MKDIRS, { it.writeString(file.path) }, { it.readInt() != 0 }) ?: false
    fun create(file: File) = call(CREATE, { it.writeString(file.path) }, { it.readInt() != 0 }) ?: false
    fun rename(src: File, dst: File) = call(RENAME, { it.writeString(src.path); it.writeString(dst.path) }, { it.readInt() != 0 }) ?: false
    fun delete(file: File) = call(DELETE, { it.writeString(file.path) }, { it.readInt() != 0 }) ?: false
    fun transfer(src: List<File>, dest: File, move: Boolean) = call(TRANSFER,
        { it.writeStringList(src.map(File::getPath)); it.writeString(dest.path); it.writeInt(if (move) 1 else 0) },
        { it.readInt() })
    fun stats(file: File) = call(STATS, { it.writeString(file.path) }, { it.createLongArray() })
    fun backupInternal(pkg: String, source: String, target: File) = call(BACKUP_INTERNAL,
        { it.writeString(pkg); it.writeString(source); it.writeString(target.path) }, { it.readInt() != 0 }) ?: false
    fun zip(files: List<File>, out: File, password: CharArray? = null) = call(ZIP,
        { it.writeStringList(files.map(File::getPath)); it.writeString(out.path)
            it.writeString(password?.concatToString()) }, { it.readInt() })
    fun extract(archive: File, dest: File, password: CharArray? = null) = call(EXTRACT,
        { it.writeString(archive.path); it.writeString(dest.path); it.writeString(password?.concatToString()) },
        { it.readInt() })
    fun archiveNeedsPassword(file: File) = call(ARCHIVE_PASSWORD,
        { it.writeString(file.path); it.writeString(null) }, { it.readInt() != 0 }) ?: false
    fun validateArchivePassword(file: File, password: CharArray) = call(ARCHIVE_PASSWORD,
        { it.writeString(file.path); it.writeString(password.concatToString()) }, { it.readInt() != 0 }) ?: false

    fun open(file: File, mode: Int): ParcelFileDescriptor? = call(OPEN,
        { it.writeString(file.path); it.writeInt(mode) },
        { it.readParcelable(ParcelFileDescriptor::class.java.classLoader) })

    fun input(file: File): InputStream = if (useFor(file)) {
        val fd = open(file, ParcelFileDescriptor.MODE_READ_ONLY) ?: throw IOException("Cannot read file")
        ParcelFileDescriptor.AutoCloseInputStream(fd)
    } else file.inputStream()

    fun length(file: File): Long = stat(file)?.optLong("size") ?: file.length()

    fun writeAtomic(file: File, bytes: ByteArray): Boolean {
        val temp = File(file.parentFile, ".${file.name}.pfiles~")
        return try {
            val fd = open(temp, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE) ?: return false
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { it.write(bytes) }
            if (rename(temp, file)) true else { delete(temp); false }
        } catch (_: IOException) {
            delete(temp)
            false
        }
    }

    fun stage(activity: Activity, file: File, onReady: (File) -> Unit) {
        if (!useFor(file)) { onReady(file); return }
        val app = activity.applicationContext
        Thread {
            val folder = File(app.cacheDir, "shizuku-open/${file.absolutePath.hashCode()}")
            val staged = File(folder, file.name)
            val ok = try {
                folder.mkdirs()
                val fd = open(file, ParcelFileDescriptor.MODE_READ_ONLY) ?: throw IOException("Cannot read file")
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    staged.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                true
            } catch (_: Exception) { false }
            Handler(Looper.getMainLooper()).post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (ok) {
                    Openers.setTarget(staged, FilesProvider.uri(activity, file))
                    onReady(staged)
                } else Toast.makeText(activity, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}

/** Shell's run-as path to debuggable applications' private files. Non-debuggable apps require root. */
private object RunAs {
    private val pathPattern = Regex("^/data/(?:user/0|data)/([A-Za-z0-9_.]+)(?:/.*)?$")
    fun packageFor(file: File): String? = pathPattern.matchEntire(file.absolutePath)?.groupValues?.get(1)

    private fun start(file: File, command: String, vararg args: String): Process? {
        val pkg = packageFor(file) ?: return null
        return try {
            ProcessBuilder(listOf("/system/bin/run-as", pkg, "/system/bin/$command") + args + file.absolutePath).start()
        } catch (_: IOException) { null }
    }

    fun command(file: File, command: String, vararg args: String): Boolean {
        val process = start(file, command, *args) ?: return false
        return try {
            process.inputStream.close()
            process.errorStream.readBytes()
            process.waitFor() == 0
        } catch (_: Exception) { false }
    }

    fun rename(src: File, dst: File): Boolean {
        if (packageFor(src) != packageFor(dst)) return false
        val process = start(dst, "mv", src.absolutePath) ?: return false
        return try {
            process.inputStream.close()
            process.errorStream.readBytes()
            process.waitFor() == 0
        } catch (_: Exception) { false }
    }

    fun stat(file: File): JSONObject? {
        val process = start(file, "stat", "-c", "%f|%s|%Y") ?: return null
        return try {
            val parts = process.inputStream.bufferedReader().use { it.readText().trim() }.split('|')
            process.errorStream.readBytes()
            if (process.waitFor() != 0 || parts.size != 3) return null
            val mode = parts[0].toLong(16)
            JSONObject().apply {
                put("name", file.name)
                put("exists", true)
                put("dir", mode and 0xF000 == 0x4000L)
                put("link", mode and 0xF000 == 0xA000L)
                put("size", parts[1].toLong())
                put("modified", parts[2].toLong() * 1000)
                put("read", true)
                put("write", true)
                put("execute", mode and 0x49 != 0L)
            }
        } catch (_: Exception) { null }
    }

    fun list(dir: File): String? {
        val process = start(dir, "ls", "-a1") ?: return null
        return try {
            val names = process.inputStream.bufferedReader().use { it.readLines() }
            process.errorStream.readBytes()
            if (process.waitFor() != 0) return null
            JSONArray().apply {
                names.filter { it != "." && it != ".." }.forEach { name ->
                    stat(File(dir, name))?.let { put(it) }
                }
            }.toString()
        } catch (_: Exception) { null }
    }

    fun stats(file: File, depth: Int): LongArray? {
        val meta = stat(file) ?: return null
        if (!meta.optBoolean("dir")) return longArrayOf(1, meta.optLong("size"))
        val out = longArrayOf(0, 0)
        if (depth > 64 || (depth > 0 && meta.optBoolean("link"))) return out
        val children = list(file)?.let(::JSONArray) ?: return out
        for (i in 0 until children.length()) {
            val child = File(file, children.getJSONObject(i).getString("name"))
            val part = stats(child, depth + 1) ?: continue
            out[0] += part[0]
            out[1] += part[1]
        }
        return out
    }

    fun open(file: File, mode: Int): ParcelFileDescriptor {
        if (stat(file) == null && mode and ParcelFileDescriptor.MODE_CREATE == 0)
            throw IOException("Cannot access file")
        val write = mode and ParcelFileDescriptor.MODE_WRITE_ONLY != 0 ||
            mode and ParcelFileDescriptor.MODE_READ_WRITE != 0
        val process = start(file, if (write) "tee" else "cat") ?: throw IOException("run-as unavailable")
        val pipe = ParcelFileDescriptor.createPipe()
        if (write) {
            Thread {
                try {
                    val drain = Thread { process.inputStream.use { it.copyTo(object : java.io.OutputStream() {
                        override fun write(b: Int) {}
                    }) } }
                    drain.start()
                    ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                        process.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    drain.join()
                    process.errorStream.readBytes()
                    process.waitFor()
                } catch (_: Exception) { process.destroy() }
            }.start()
            return pipe[1]
        }
        Thread {
            try {
                process.inputStream.use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                process.errorStream.readBytes()
                process.waitFor()
            } catch (_: Exception) { process.destroy() }
        }.start()
        return pipe[0]
    }

    fun transfer(files: List<File>, destDir: File, move: Boolean): Int {
        var failed = 0
        for (source in files) {
            if (source.parentFile?.absolutePath == destDir.absolutePath && move) continue
            if (destDir.absolutePath.startsWith(source.absolutePath.trimEnd('/') + "/") &&
                (stat(source)?.optBoolean("dir") ?: source.isDirectory)) { failed++; continue }
            var target = File(destDir, source.name)
            var suffix = 1
            while (stat(target) != null || FileOps.existsNoFollow(target)) {
                val dot = source.name.lastIndexOf('.')
                val base = if (dot > 0) source.name.substring(0, dot) else source.name
                val ext = if (dot > 0) source.name.substring(dot) else ""
                target = File(destDir, "$base (${suffix++})$ext")
            }
            if (!copy(source, target, 0)) { failed++; continue }
            if (move && !(if (stat(source) != null) command(source, "rm", "-r", "-f")
                    else FileOps.delete(source))) failed++
        }
        return failed
    }

    private fun copy(src: File, dst: File, depth: Int): Boolean {
        if (depth > 64) return false
        val remote = stat(src)
        val dir = remote?.optBoolean("dir") ?: src.isDirectory
        return try {
            if (dir) {
                if (!(dst.isDirectory || dst.mkdirs() || command(dst, "mkdir", "-p"))) return false
                val children = if (remote != null) list(src)?.let(::JSONArray) else null
                val names = if (children != null) (0 until children.length()).map {
                    children.getJSONObject(it).getString("name")
                } else src.list()?.toList() ?: return false
                names.all { copy(File(src, it), File(dst, it), depth + 1) }
            } else {
                val read = if (remote != null) open(src, ParcelFileDescriptor.MODE_READ_ONLY)
                    else ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
                val write = if (packageFor(dst) != null && dst.parentFile?.canWrite() != true)
                    open(dst, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY)
                    else ParcelFileDescriptor.open(dst, ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
                ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                true
            }
        } catch (_: Exception) { false }
    }
}
