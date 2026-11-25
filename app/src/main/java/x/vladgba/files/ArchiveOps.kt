package x.vladgba.files

import java.io.File

/**
 * Unified gateway for all supported archives: ZIP, JAR, AAR, APK, EPUB, TAR, TGZ/TAR.GZ, GZ, and read-only
 * 7z and RAR4 (see [ExtraArchives]).
 */
object ArchiveOps {
    val EXTRACTABLE = setOf("zip", "jar", "aar", "apk", "epub", "tar", "tgz", "gz", "7z", "rar")
    val PREVIEWABLE = setOf("zip", "jar", "aar", "apk", "epub", "tar", "tgz", "gz", "7z", "rar")

    fun isArchive(f: File): Boolean {
        if (f.isDirectory) return false
        val name = f.name.lowercase()
        return name.endsWith(".tar.gz") || f.name.substringAfterLast('.', "").lowercase() in EXTRACTABLE
    }

    fun isPreviewable(f: File): Boolean {
        if (f.isDirectory) return false
        val name = f.name.lowercase()
        return name.endsWith(".tar.gz") || f.name.substringAfterLast('.', "").lowercase() in PREVIEWABLE
    }

    fun isTar(f: File): Boolean {
        val name = f.name.lowercase()
        return name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz")
    }

    fun isGzOnly(f: File): Boolean {
        val name = f.name.lowercase()
        return name.endsWith(".gz") && !name.endsWith(".tar.gz")
    }

    fun isApk(f: File): Boolean = !f.isDirectory && f.name.substringAfterLast('.', "").lowercase() == "apk"

    fun needsPassword(f: File) = when {
        ExtraArchives.handles(f) -> ExtraArchives.requiresPassword(f)
        else -> !isTar(f) && !isGzOnly(f) && PasswordZipOps.requiresPassword(f)
    }

    /** With [password] the tree of an archive whose file names are encrypted too (7z) can be read. */
    fun readTree(f: File, password: CharArray?): ZipOps.ZipTree =
        if (ExtraArchives.handles(f)) ExtraArchives.readTree(f, password) else readTree(f)

    fun readTree(f: File): ZipOps.ZipTree = when {
        ExtraArchives.handles(f) -> ExtraArchives.readTree(f)
        isTar(f) -> TarOps.readTree(f)
        isGzOnly(f) -> GzOps.readTree(f)
        needsPassword(f) -> PasswordZipOps.readTree(f)
        else -> ZipOps.openTree(f)
    }

    fun extractEntry(archive: File, entryPath: String, dest: File, password: CharArray? = null) {
        when {
            ExtraArchives.handles(archive) -> ExtraArchives.extractEntry(archive, entryPath, dest, password)
            isTar(archive) -> TarOps.extractEntry(archive, entryPath, dest)
            isGzOnly(archive) -> GzOps.ungz(archive, dest)
            needsPassword(archive) -> PasswordZipOps.extractEntry(archive, entryPath, dest,
                password ?: throw IllegalArgumentException("Password required"))
            else -> ZipOps.extractEntry(archive, entryPath, dest)
        }
    }

    fun extractSubtree(archive: File, entryPath: String, destDir: File, password: CharArray? = null): Int =
        if (ExtraArchives.handles(archive)) ExtraArchives.extractSubtree(archive, entryPath, destDir, password)
        else if (needsPassword(archive)) PasswordZipOps.extractSubtree(archive, entryPath, destDir,
            password ?: throw IllegalArgumentException("Password required"))
        else ZipOps.extractSubtree(archive, entryPath, destDir)

    fun extractAll(archive: File, destDir: File, password: CharArray? = null): Int = when {
        ExtraArchives.handles(archive) -> ExtraArchives.extractAll(archive, destDir, password)
        isTar(archive) -> TarOps.untar(archive, destDir)
        isGzOnly(archive) -> {
            val innerName = archive.name.removeSuffix(".gz")
            val target = File(destDir, innerName)
            if (!destDir.isDirectory) destDir.mkdirs()
            GzOps.ungz(archive, target)
            0
        }
        needsPassword(archive) -> PasswordZipOps.extractAll(archive, destDir,
            password ?: throw IllegalArgumentException("Password required"))
        else -> ZipOps.unzip(archive, destDir)
    }
}
