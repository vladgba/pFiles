package x.vladgba.files

import java.io.File
import java.util.PriorityQueue

/** Storage analyzer logic: per-child sizes, the biggest files and duplicates. No Android types, unit tested. */
object AnalyzeOps {
    class Child(val file: File, val isDir: Boolean, val size: Long, val files: Int)
    class Scan(val children: List<Child>, val largest: List<File>, val total: Long, val fileCount: Int,
               internal val sizes: Map<Long, List<File>>)
    class DupGroup(val size: Long, val files: List<File>) {
        val wasted: Long get() = size * (files.size - 1)
    }

    const val TOP = 200
    private const val MAX_DEPTH = 64

    /** Walks [dir] once. Symlinks are not followed and the trash is skipped. */
    fun scan(dir: File, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): Scan? {
        val top = PriorityQueue<File>(TOP + 1, compareBy { it.length() })
        val bySize = HashMap<Long, MutableList<File>>()
        var seen = 0

        fun walk(f: File, depth: Int): LongArray { // [bytes, files]
            if (cancelled()) return longArrayOf(0, 0)
            if (Trash.isLink(f)) return longArrayOf(0, 0)
            if (f.isDirectory) {
                if (depth > MAX_DEPTH || f.name == Trash.DIR) return longArrayOf(0, 0)
                var bytes = 0L
                var files = 0L
                f.listFiles()?.forEach { c -> val r = walk(c, depth + 1); bytes += r[0]; files += r[1] }
                return longArrayOf(bytes, files)
            }
            val len = f.length()
            top.add(f)
            if (top.size > TOP) top.poll()
            if (len > 0) bySize.getOrPut(len) { ArrayList(1) }.add(f)
            if (++seen % 500 == 0) progress(seen)
            return longArrayOf(len, 1)
        }

        val children = ArrayList<Child>()
        var total = 0L
        var count = 0
        for (c in dir.listFiles() ?: emptyArray()) {
            if (cancelled()) return null
            val r = walk(c, 1)
            val isDir = c.isDirectory && !Trash.isLink(c)
            if (isDir && c.name == Trash.DIR) continue
            children.add(Child(c, isDir, r[0], r[1].toInt()))
            total += r[0]
            count += r[1].toInt()
        }
        if (cancelled()) return null
        children.sortWith(compareByDescending<Child> { it.size }.thenBy { it.file.name.lowercase() })
        val largest = top.sortedWith(compareByDescending<File> { it.length() }.thenBy { it.path })
        return Scan(children, largest, total, count, bySize.filterValues { it.size > 1 })
    }

    /**
     * Files with identical content. Candidates share a size; a hash of the first 64 KB weeds out most of them
     * before the full SHA-256. Groups come biggest waste first.
     */
    fun duplicates(scan: Scan, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): List<DupGroup>? {
        val out = ArrayList<DupGroup>()
        val candidates = scan.sizes.entries.sortedByDescending { it.key }
        val totalFiles = candidates.sumOf { it.value.size }.coerceAtLeast(1)
        var done = 0
        for ((size, files) in candidates) {
            val byHead = HashMap<String, MutableList<File>>()
            for (f in files) {
                if (cancelled()) return null
                val h = try { Checksums.sha256(f, 64 * 1024L, cancelled) } catch (_: Exception) { null }
                if (h != null) byHead.getOrPut(h) { ArrayList(2) }.add(f)
                progress(++done * 100 / totalFiles)
            }
            for (group in byHead.values) {
                if (group.size < 2) continue
                if (size <= 64 * 1024L) { out.add(DupGroup(size, group.sortedBy { it.path })); continue }
                val byFull = HashMap<String, MutableList<File>>()
                for (f in group) {
                    if (cancelled()) return null
                    val h = try { Checksums.sha256(f, -1, cancelled) } catch (_: Exception) { null }
                    if (h != null) byFull.getOrPut(h) { ArrayList(2) }.add(f)
                }
                byFull.values.filter { it.size > 1 }.forEach { out.add(DupGroup(size, it.sortedBy { f -> f.path })) }
            }
        }
        if (cancelled()) return null
        return out.sortedByDescending { it.wasted }
    }
}
