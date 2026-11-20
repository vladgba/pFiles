package x.vladgba.files

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.IOException

/** Encrypted ZIP support, including AES and traditional ZIP encryption. */
object PasswordZipOps {
    fun create(files: List<File>, out: File, password: CharArray): Int {
        val outputPath = out.canonicalPath
        val visited = HashSet<String>()
        var skipped = 0
        try {
            ZipOutputStream(out.outputStream().buffered(64 * 1024), password).use { zip ->
                fun add(file: File, name: String, depth: Int) {
                    if (file.canonicalPath == outputPath) return
                    if (depth > 64) { skipped++; return }
                    val params = ZipParameters().apply {
                        fileNameInZip = if (file.isDirectory) "$name/" else name
                        isEncryptFiles = !file.isDirectory
                        encryptionMethod = EncryptionMethod.AES
                    }
                    if (file.isDirectory) {
                        if (!visited.add(file.canonicalPath)) { skipped++; return }
                        val children = file.listFiles()
                        if (children == null) { skipped++; return }
                        zip.putNextEntry(params)
                        zip.closeEntry()
                        children.sortedBy { it.name }.forEach { add(it, "$name/${it.name}", depth + 1) }
                    } else {
                        val input = try { file.inputStream() } catch (_: IOException) { skipped++; return }
                        input.use {
                            zip.putNextEntry(params)
                            it.copyTo(zip, 64 * 1024)
                            zip.closeEntry()
                        }
                    }
                }
                files.forEach { add(it, it.name, 0) }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return skipped
    }

    fun requiresPassword(file: File): Boolean = ZipFile(file).isEncrypted

    fun readTree(file: File): ZipOps.ZipTree {
        val dirs = HashMap<String, LinkedHashMap<String, ZipOps.EntryInfo>>()
        val paths = HashMap<String, ZipOps.EntryInfo>()
        var count = 0
        var size = 0L
        var packed = 0L
        fun addDir(parent: String, name: String, path: String) {
            val children = dirs.getOrPut(parent) { LinkedHashMap() }
            if (children.containsKey(name)) return
            val entry = ZipOps.EntryInfo(name, path, true, 0, 0, 0)
            children[name] = entry
            paths[path] = entry
        }
        for (header in ZipFile(file).fileHeaders) {
            val name = clean(header.fileName) ?: continue
            val segments = name.trimEnd('/').split('/')
            var parent = ""
            for (segment in segments.dropLast(1)) {
                val path = "$parent$segment/"
                addDir(parent, segment, path)
                parent = path
            }
            val leaf = segments.last()
            if (header.isDirectory || name.endsWith('/')) {
                addDir(parent, leaf, "$parent$leaf/")
            } else {
                val entry = ZipOps.EntryInfo(leaf, "$parent$leaf", false,
                    header.uncompressedSize, header.compressedSize, 0)
                dirs.getOrPut(parent) { LinkedHashMap() }[leaf] = entry
                paths[entry.path] = entry
                count++
                size += entry.size.coerceAtLeast(0)
                packed += entry.compressedSize.coerceAtLeast(0)
            }
        }
        val comparator = compareBy<ZipOps.EntryInfo>({ !it.isDir }, { it.name.lowercase() }, { it.name })
        return ZipOps.ZipTree(count, paths.values.count { it.isDir }, size, packed,
            dirs.mapValues { it.value.values.sortedWith(comparator) }, paths)
    }

    fun validate(file: File, password: CharArray): Boolean = try {
        val zip = ZipFile(file, password)
        val first = zip.fileHeaders.firstOrNull { it.isEncrypted && !it.isDirectory }
        if (first != null) zip.getInputStream(first).use { it.read() }
        true
    } catch (_: Exception) { false }

    fun extractEntry(file: File, path: String, target: File, password: CharArray) {
        val zip = ZipFile(file, password)
        val header = zip.fileHeaders.firstOrNull { clean(it.fileName) == path || clean(it.fileName) == "$path/" }
            ?: throw IOException("Entry not found")
        write(zip, header, target)
    }

    fun extractSubtree(file: File, path: String, dest: File, password: CharArray): Int {
        val prefix = ZipOps.normalizeDir(path)
        val zip = ZipFile(file, password)
        var failed = 0
        for (header in zip.fileHeaders) {
            val name = clean(header.fileName)
            if (name == null) { failed++; continue }
            if (!name.startsWith(prefix)) continue
            val rel = name.removePrefix(prefix).trimEnd('/')
            if (rel.isEmpty()) continue
            val target = safeTarget(dest, rel)
            if (target == null) { failed++; continue }
            try { write(zip, header, target) } catch (_: Exception) { failed++ }
        }
        return failed
    }

    fun extractAll(file: File, dest: File, password: CharArray): Int {
        val zip = ZipFile(file, password)
        var failed = 0
        for (header in zip.fileHeaders) {
            val name = clean(header.fileName)
            if (name == null) { failed++; continue }
            val target = safeTarget(dest, name.trimEnd('/'))
            if (target == null) { failed++; continue }
            try { write(zip, header, target) } catch (_: Exception) { failed++ }
        }
        return failed
    }

    private fun write(zip: ZipFile, header: FileHeader, target: File) {
        if (header.isDirectory) {
            if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create directory")
            return
        }
        val parent = target.parentFile ?: throw IOException("No destination folder")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create destination folder")
        if (target.exists()) throw IOException("Destination exists")
        try {
            zip.getInputStream(header).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private fun clean(raw: String): String? {
        val path = raw.replace('\\', '/')
        if (path.startsWith('/') || path.startsWith("//") || path.contains(':')) return null
        if (path.trimEnd('/').split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return path
    }

    private fun safeTarget(root: File, name: String): File? {
        val path = clean(name) ?: return null
        val target = File(root, path)
        val base = root.canonicalFile.path + File.separator
        return target.takeIf { (it.canonicalFile.path + File.separator).startsWith(base) }
    }
}
