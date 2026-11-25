package x.vladgba.files

import com.github.junrar.Archive
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Read-only 7z (Apache Commons Compress + XZ for Java) and RAR4 (junrar) support, shaped like [ZipOps] so the
 * archive browser and "Extract" work on them unchanged. RAR5 archives are not readable by junrar.
 */
object ExtraArchives {
    class Item(val name: String, val isDir: Boolean, val size: Long, val packed: Long, val time: Long)

    interface Reader : Closeable {
        val items: List<Item>
        fun extract(index: Int, out: OutputStream)
    }

    fun is7z(f: File) = f.name.lowercase().endsWith(".7z")
    fun isRar(f: File) = f.name.lowercase().endsWith(".rar")
    fun handles(f: File) = is7z(f) || isRar(f)

    private class SevenZ(f: File, password: CharArray?) : Reader {
        private val channel = FileInputStream(f).channel
        private val sz: SevenZFile = try {
            SevenZFile.builder().setSeekableByteChannel(channel).apply { if (password != null) setPassword(password) }.get()
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
        private val entries = sz.entries.toList()
        override val items = entries.map {
            Item(it.name ?: "", it.isDirectory, it.size, 0L, if (it.hasLastModifiedDate) it.lastModifiedDate.time else 0L)
        }
        override fun extract(index: Int, out: OutputStream) {
            sz.getInputStream(entries[index]).use { it.copyTo(out, 64 * 1024) }
        }
        override fun close() { try { sz.close() } finally { channel.close() } }
    }

    private class Rar(f: File) : Reader {
        private val archive = Archive(f)
        private val headers = archive.fileHeaders.toList()
        override val items = headers.map {
            Item(it.fileName ?: "", it.isDirectory, it.fullUnpackSize, it.fullPackSize, it.mTime?.time ?: 0L)
        }
        override fun extract(index: Int, out: OutputStream) {
            if (headers[index].isEncrypted) throw IOException("Encrypted RAR entries are not supported")
            archive.extractFile(headers[index], out)
        }
        override fun close() = archive.close()
    }

    fun open(f: File, password: CharArray? = null): Reader = if (is7z(f)) SevenZ(f, password) else Rar(f)

    /** True for a 7z whose headers or first file need a password. RAR passwords are not supported. */
    fun requiresPassword(f: File): Boolean {
        if (!is7z(f)) return false
        return try {
            open(f).use { r ->
                val first = r.items.indexOfFirst { !it.isDir && it.size > 0 }
                if (first >= 0) r.extract(first, object : OutputStream() {
                    var seen = 0
                    override fun write(b: Int) { if (++seen > 0) throw Probed() }
                    override fun write(b: ByteArray, off: Int, len: Int) { if (len > 0) throw Probed() }
                })
            }
            false
        } catch (_: Probed) {
            false
        } catch (_: PasswordRequiredException) {
            true
        }
    }

    private class Probed : IOException()

    /** Entry path as the archive browser expects it: forward slashes, no leading slash, no "." or ".." parts. */
    internal fun clean(name: String): String? {
        val n = name.replace('\\', '/').trim('/')
        if (n.isEmpty()) return null
        return if (n.split('/').any { it.isEmpty() || it == "." || it == ".." }) null else n
    }

    fun buildTree(items: List<Item>): ZipOps.ZipTree {
        val dirs = HashMap<String, LinkedHashMap<String, ZipOps.EntryInfo>>()
        val paths = HashMap<String, ZipOps.EntryInfo>()
        var files = 0
        var dirCount = 0
        var size = 0L
        var packed = 0L
        fun ensureDir(parent: String, name: String, path: String, time: Long) {
            val map = dirs.getOrPut(parent) { LinkedHashMap() }
            val old = map[name]
            if (old == null) {
                val info = ZipOps.EntryInfo(name, path, true, 0L, 0L, time)
                map[name] = info
                paths[path] = info
                dirCount++
            } else if (old.time <= 0L && time > 0L) {
                val info = old.copy(time = time)
                map[name] = info
                paths[path] = info
            }
        }
        for (item in items) {
            val name = clean(item.name) ?: continue
            val segments = name.split('/')
            var parent = ""
            for (i in 0 until segments.size - 1) {
                ensureDir(parent, segments[i], parent + segments[i] + "/", 0L)
                parent += segments[i] + "/"
            }
            val leaf = segments.last()
            if (item.isDir) ensureDir(parent, leaf, "$name/", item.time)
            else {
                files++
                size += item.size.coerceAtLeast(0L)
                packed += item.packed.coerceAtLeast(0L)
                val info = ZipOps.EntryInfo(leaf, name, false, item.size, item.packed, item.time)
                dirs.getOrPut(parent) { LinkedHashMap() }[leaf] = info
                paths[name] = info
            }
        }
        val order = compareBy<ZipOps.EntryInfo>({ !it.isDir }, { it.name.lowercase() }, { it.name })
        return ZipOps.ZipTree(files, dirCount, size, packed, dirs.mapValues { it.value.values.sortedWith(order) }, paths)
    }

    fun readTree(f: File, password: CharArray? = null): ZipOps.ZipTree = open(f, password).use { buildTree(it.items) }

    fun extractEntry(f: File, entryPath: String, dest: File, password: CharArray? = null) {
        val want = entryPath.replace('\\', '/').trim('/')
        open(f, password).use { r ->
            val i = r.items.indexOfFirst { clean(it.name) == want }
            if (i < 0) throw IOException("Entry not found: $entryPath")
            if (r.items[i].isDir) {
                if (!dest.isDirectory && !dest.mkdirs()) throw IOException("can't create directory $dest")
                return
            }
            val parent = dest.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("can't create directory $parent")
            try {
                dest.outputStream().use { r.extract(i, it) }
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
            if (r.items[i].time > 0) dest.setLastModified(r.items[i].time)
        }
    }

    /** Extracts everything under [subPath] ("" for the whole archive) into [destDir]. Returns the failure count. */
    fun extractSubtree(f: File, subPath: String, destDir: File, password: CharArray? = null): Int {
        val prefix = subPath.replace('\\', '/').trim('/').let { if (it.isEmpty()) "" else "$it/" }
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("can't create $destDir")
        val root = canon(destDir) + File.separator
        var failed = 0
        val dirTimes = ArrayList<Pair<File, Long>>()
        open(f, password).use { r ->
            r.items.forEachIndexed { i, item ->
                val name = clean(item.name)
                if (name == null) { if (prefix.isEmpty()) failed++; return@forEachIndexed }
                if (!"$name/".startsWith(prefix)) return@forEachIndexed
                val rel = name.removePrefix(prefix.trimEnd('/')).trimStart('/')
                if (rel.isEmpty()) return@forEachIndexed
                val target = File(destDir, rel)
                if (!(canon(target) + File.separator).startsWith(root)) { failed++; return@forEachIndexed }
                OpProgress.report("${target.name} (${i + 1}/${r.items.size})", i * 100 / r.items.size)
                try {
                    if (item.isDir) {
                        if (!(target.isDirectory || target.mkdirs())) failed++
                        else if (item.time > 0) dirTimes += target to item.time
                    } else {
                        val p = target.parentFile
                        if (p != null && !(p.isDirectory || p.mkdirs())) { failed++; return@forEachIndexed }
                        if (target.exists()) { failed++; return@forEachIndexed }
                        target.outputStream().use { r.extract(i, it) }
                        if (item.time > 0) target.setLastModified(item.time)
                    }
                } catch (e: PasswordRequiredException) {
                    target.delete()
                    throw e
                } catch (_: Exception) {
                    target.delete()
                    failed++
                }
            }
        }
        for ((d, t) in dirTimes.asReversed()) d.setLastModified(t)
        return failed
    }

    fun extractAll(f: File, destDir: File, password: CharArray? = null): Int = extractSubtree(f, "", destDir, password)

    private fun canon(f: File): String = try { f.canonicalPath } catch (_: IOException) { f.absolutePath }
}
