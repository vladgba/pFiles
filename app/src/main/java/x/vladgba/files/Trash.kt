package x.vladgba.files

import java.io.File

/**
 * App trash. Each storage volume has its own hidden folder, so trashing is a rename on the same volume and
 * never copies data. An item is `<trash>/<id>/<name>` plus `<trash>/<id>.info` holding the original path and
 * the deletion time. Items older than [KEEP_DAYS] are purged at start.
 */
object Trash {
    const val DIR = ".pFilesTrash"
    const val KEEP_DAYS = 30

    class Item(val id: String, val trashDir: File, val original: File, val deletedAt: Long) {
        val payload: File get() = File(File(trashDir, id), original.name)
        val name: String get() = original.name
    }

    /** The volume holding [f], or null when [f] is not on one of [volumes] (then it can only be deleted). */
    fun volumeOf(f: File, volumes: List<File>): File? {
        val path = f.absolutePath
        return volumes.filter { path.startsWith(it.absolutePath.trimEnd('/') + "/") }.maxByOrNull { it.absolutePath.length }
    }

    fun inTrash(f: File): Boolean = ("/" + f.absolutePath.trim('/') + "/").contains("/$DIR/")

    /** Local files on a storage volume, outside the trash itself. */
    fun eligible(f: File, volumes: List<File>): Boolean = !inTrash(f) && volumeOf(f, volumes) != null

    /**
     * Symlink test on plain java.io (the trash and the analyzer are unit tested off-device, where the lstat
     * used by [FileOps] is not available): a link's canonical path leaves its parent's folder.
     */
    fun isLink(f: File): Boolean = try {
        val parent = f.absoluteFile.parentFile?.canonicalFile
        parent != null && File(parent, f.name).path != f.canonicalPath
    } catch (_: Exception) { false }

    private fun present(f: File) = f.exists() || isLink(f)

    /** Recursive delete that removes a link itself and never what it points to. */
    private fun deleteTree(f: File): Boolean {
        if (f.isDirectory && !isLink(f)) f.listFiles()?.forEach { deleteTree(it) }
        return f.delete() || !present(f)
    }

    private fun freeName(dir: File, name: String): File {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 1
        while (true) {
            val candidate = File(dir, name.substring(0, dot) + " ($n)" + name.substring(dot))
            if (!present(candidate)) return candidate
            n++
        }
    }

    private var counter = 0

    @Synchronized
    private fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + "-" + (counter++ % 1296).toString(36)

    /** Moves [f] into its volume's trash. False when it is not eligible or the rename failed. */
    fun moveToTrash(f: File, volumes: List<File>, now: Long = System.currentTimeMillis()): Boolean {
        if (!eligible(f, volumes)) return false
        val trash = File(volumeOf(f, volumes) ?: return false, DIR)
        val id = newId()
        val holder = File(trash, id)
        if (!holder.mkdirs()) return false
        File(trash, ".nomedia").let { if (!it.exists()) try { it.createNewFile() } catch (_: Exception) {} }
        val info = File(trash, "$id.info")
        return try {
            info.writeText(f.absolutePath + "\n" + now + "\n")
            if (f.renameTo(File(holder, f.name))) true
            else { info.delete(); holder.delete(); false }
        } catch (_: Exception) {
            info.delete(); holder.delete(); false
        }
    }

    fun list(volumes: List<File>): List<Item> {
        val out = ArrayList<Item>()
        for (v in volumes) {
            val trash = File(v, DIR)
            val infos = trash.listFiles { f -> f.isFile && f.name.endsWith(".info") } ?: continue
            for (info in infos) {
                val lines = try { info.readLines() } catch (_: Exception) { continue }
                val path = lines.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: continue
                val item = Item(info.name.removeSuffix(".info"), trash, File(path), lines.getOrNull(1)?.toLongOrNull() ?: info.lastModified())
                if (present(item.payload)) out.add(item) else { info.delete(); File(trash, item.id).delete() }
            }
        }
        return out.sortedByDescending { it.deletedAt }
    }

    /** Puts the item back; a taken name gets a numbered twin. Returns the restored file or null. */
    fun restore(item: Item): File? {
        val parent = item.original.parentFile ?: return null
        if (!parent.isDirectory && !parent.mkdirs()) return null
        val dest = if (present(item.original)) freeName(parent, item.original.name) else item.original
        if (!item.payload.renameTo(dest)) return null
        forget(item)
        return dest
    }

    fun purge(item: Item): Boolean {
        val ok = deleteTree(item.payload)
        if (ok) forget(item)
        return ok
    }

    private fun forget(item: Item) {
        File(item.trashDir, item.id).delete()
        File(item.trashDir, item.id + ".info").delete()
    }

    /** Returns the number of items that could not be removed. */
    fun empty(volumes: List<File>): Int = list(volumes).count { !purge(it) }

    fun purgeOld(volumes: List<File>, now: Long = System.currentTimeMillis(), keepDays: Int = KEEP_DAYS): Int {
        val limit = now - keepDays * 24L * 3600 * 1000
        var n = 0
        for (item in list(volumes)) if (item.deletedAt < limit && purge(item)) n++
        return n
    }
}
