package x.vladgba.files

import android.app.Activity
import android.content.Context
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Virtual filesystem provider for FTP connections.
 * Virtual paths:
 *   /pfiles-ftp                     -> list of configured FTP servers
 *   /pfiles-ftp/<connId>            -> root of connection <connId>
 *   /pfiles-ftp/<connId>/<subpath>  -> remote folder or file
 */
object FtpFiles {
    val ROOT = File("/pfiles-ftp")
    private const val REMOTE_MARK = ".remote"

    fun isRoot(f: File): Boolean = f.path == ROOT.path
    fun isFtp(f: File): Boolean = f.path.startsWith(ROOT.path)

    private val clientPool = ConcurrentHashMap<String, FtpClient>()
    private val connectionLocks = ConcurrentHashMap<String, Any>()
    private val dirCache = ConcurrentHashMap<String, Pair<Long, List<Entry>>>()
    private const val CACHE_TTL_MS = 60_000L // 1 minute cache for fast browsing

    /** Last failure message per connection id, shown instead of a generic "can't read". */
    val lastError = ConcurrentHashMap<String, String>()

    fun errorFor(dir: File): String? {
        val id = dir.path.removePrefix(ROOT.path).trimStart('/').substringBefore('/')
        return lastError[id]
    }

    fun getCached(dir: File): List<Entry>? {
        if (isRoot(dir)) return null
        val cached = dirCache[dir.path] ?: return null
        if (System.currentTimeMillis() - cached.first > CACHE_TTL_MS) {
            dirCache.remove(dir.path)
            return null
        }
        return cached.second
    }

    fun invalidateCache(dir: File? = null) {
        if (dir == null) {
            dirCache.clear()
        } else {
            val prefix = dir.path
            dirCache.keys.filter { it.startsWith(prefix) }.forEach { dirCache.remove(it) }
        }
    }

    fun getClient(ctx: Context, configId: String): FtpClient? {
        val config = FtpConfigManager.get(ctx, configId) ?: return null
        val lock = connectionLocks.computeIfAbsent(configId) { Any() }
        synchronized(lock) {
            val existing = clientPool[configId]
            if (existing != null && existing.isConnected) {
                return existing
            }
            try { existing?.close() } catch (_: Exception) {}

            var lastFailure: Exception? = null
            repeat(2) {
                val newClient = FtpClient(ctx, config)
                try {
                    newClient.connect()
                    clientPool[configId] = newClient
                    lastError.remove(configId)
                    return newClient
                } catch (e: Exception) {
                    lastFailure = e
                    try { newClient.close() } catch (_: Exception) {}
                    if (Thread.currentThread().isInterrupted) return null
                }
            }
            lastFailure?.let { lastError[configId] = "${it.javaClass.simpleName}: ${it.message ?: ""}" }
            return null
        }
    }

    data class ResolvedFtpPath(
        val config: FtpConfig,
        val remotePath: String,
        val isConnectionRoot: Boolean
    )

    fun resolve(ctx: Context, file: File): ResolvedFtpPath? {
        if (!isFtp(file) || isRoot(file)) return null
        val path = file.path.removePrefix(ROOT.path).trimStart('/')
        if (path.isEmpty()) return null
        val parts = path.split('/', limit = 2)
        val connId = parts[0]
        val config = FtpConfigManager.get(ctx, connId) ?: return null
        val subPath = if (parts.size > 1) parts[1] else ""
        val isConnectionRoot = subPath.isEmpty()

        val baseRoot = config.rootPath.trim()
        val normalizedBase = if (baseRoot.isEmpty() || baseRoot == "/") "" else "/" + baseRoot.trim('/')
        val remotePath = if (subPath.isEmpty()) {
            if (normalizedBase.isEmpty()) "/" else normalizedBase
        } else {
            "$normalizedBase/$subPath"
        }
        return ResolvedFtpPath(config, remotePath, isConnectionRoot)
    }

    fun listConnections(ctx: Context): List<Entry> {
        return FtpConfigManager.getAll(ctx).map { cfg ->
            val label = "${cfg.name} (${cfg.host}:${cfg.port})"
            Entry(File(ROOT, cfg.id), true, 0L, 0L, label)
        }
    }

    fun list(ctx: Context, dir: File): List<Entry>? {
        if (isRoot(dir)) return listConnections(ctx)
        val resolved = resolve(ctx, dir) ?: return null
        if (Thread.currentThread().isInterrupted) return dirCache[dir.path]?.second
        val client = getClient(ctx, resolved.config.id) ?: return dirCache[dir.path]?.second
        return try {
            val remoteEntries = client.listFiles(resolved.remotePath)
            val entries = remoteEntries.map { re ->
                Entry(
                    file = File(dir, re.name),
                    isDir = re.isDir,
                    size = re.size,
                    modified = re.modified,
                    name = re.name
                )
            }
            dirCache[dir.path] = Pair(System.currentTimeMillis(), entries)
            lastError.remove(resolved.config.id)
            entries
        } catch (e: Exception) {
            lastError[resolved.config.id] = "${e.javaClass.simpleName}: ${e.message ?: ""}"

            if (e is IOException && !Thread.currentThread().isInterrupted) {
                try {
                    clientPool.remove(resolved.config.id)?.close()
                    val freshClient = getClient(ctx, resolved.config.id)
                    if (freshClient != null) {
                        val remoteEntries = freshClient.listFiles(resolved.remotePath)
                        val entries = remoteEntries.map { re ->
                            Entry(
                                file = File(dir, re.name),
                                isDir = re.isDir,
                                size = re.size,
                                modified = re.modified,
                                name = re.name
                            )
                        }
                        dirCache[dir.path] = Pair(System.currentTimeMillis(), entries)
                        lastError.remove(resolved.config.id)
                        return entries
                    }
                } catch (e2: Exception) {
                    lastError[resolved.config.id] = "${e2.javaClass.simpleName}: ${e2.message ?: ""}"
                }
            }
            dirCache[dir.path]?.second
        }
    }

    /**
     * The local copy of a remote file: `ftp_cache/<connection>/<hash of the remote path>/<name>`. The remote path
     * is kept in a `.remote` file beside the copy, so a save can upload it even after the app was restarted, and
     * two remote files with the same name in different folders no longer share one copy.
     */
    private fun cacheCopy(ctx: Context, resolved: ResolvedFtpPath, name: String): File {
        val hash = java.security.MessageDigest.getInstance("MD5").digest(resolved.remotePath.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
        val dir = File(ctx.cacheDir, "ftp_cache/${resolved.config.id}/$hash")
        dir.mkdirs()
        File(dir, REMOTE_MARK).writeText(resolved.remotePath)
        return File(dir, name)
    }

    /** Uploads an edited local copy back to its FTP server. Null when [f] is not a copy of a remote file. Blocking. */
    fun uploadBack(ctx: Context, f: File): Boolean? {
        val dir = f.parentFile ?: return null
        val mark = File(dir, REMOTE_MARK)
        val cacheRoot = File(ctx.cacheDir, "ftp_cache").absolutePath + "/"
        if (!f.absolutePath.startsWith(cacheRoot) || !mark.isFile) return null
        val configId = dir.parentFile?.name ?: return null
        val remote = try { mark.readText() } catch (_: IOException) { return false }
        val client = getClient(ctx, configId) ?: return false
        return try {
            FileInputStream(f).use { client.upload(it, remote) }
            invalidateCache()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun open(activity: Activity, file: File) {
        val resolved = resolve(activity, file) ?: return
        val cachedFile = cacheCopy(activity, resolved, file.name)

        Toast.makeText(activity, R.string.saving, Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val downloadClient = FtpClient(activity, resolved.config)
                FileOutputStream(cachedFile).use { out ->
                    downloadClient.download(resolved.remotePath, out)
                }
                activity.runOnUiThread {
                    Openers.open(activity, cachedFile)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    fun mkdir(ctx: Context, dir: File, name: String): Boolean {
        val resolved = resolve(ctx, dir) ?: return false
        val client = getClient(ctx, resolved.config.id) ?: return false
        val targetRemote = "${resolved.remotePath.trimEnd('/')}/$name"
        val ok = try {
            client.mkdir(targetRemote)
        } catch (_: Exception) {
            false
        }
        if (ok) invalidateCache(dir)
        return ok
    }

    fun create(ctx: Context, dir: File, name: String): Boolean {
        val resolved = resolve(ctx, dir) ?: return false
        val client = getClient(ctx, resolved.config.id) ?: return false
        val targetRemote = "${resolved.remotePath.trimEnd('/')}/$name"
        val ok = try {
            client.upload(ByteArrayInputStream(ByteArray(0)), targetRemote)
            true
        } catch (_: Exception) {
            false
        }
        if (ok) invalidateCache(dir)
        return ok
    }

    fun delete(ctx: Context, file: File): Boolean {
        if (isRoot(file)) return false
        if (file.parentFile?.path == ROOT.path) {
            // Delete connection config
            FtpConfigManager.delete(ctx, file.name)
            clientPool.remove(file.name)?.close()
            FtpModule.disconnect(ctx, file.name)
            invalidateCache()
            return true
        }
        val resolved = resolve(ctx, file) ?: return false
        val client = getClient(ctx, resolved.config.id) ?: return false
        val ok = try {
            deleteTree(client, resolved.remotePath, file.name, intArrayOf(0))
        } catch (_: Exception) {
            false
        }
        if (ok) invalidateCache(file.parentFile)
        return ok
    }

    /** Deletes a file or a folder tree one item at a time, so progress can be shown. */
    private fun deleteTree(client: FtpClient, path: String, label: String, count: IntArray): Boolean {
        if (client.deleteOne(path)) {
            count[0]++
            OpProgress.report("$label: ${count[0]}")
            return true
        }
        val children = try { client.listFiles(path) } catch (_: Exception) { return false }
        var ok = true
        for (child in children) {
            if (!deleteTree(client, "${path.trimEnd('/')}/${child.name}", label, count)) ok = false
        }
        return ok && client.deleteOne(path).also { if (it) { count[0]++; OpProgress.report("$label: ${count[0]}") } }
    }

    fun rename(ctx: Context, file: File, newName: String): Boolean {
        if (isRoot(file)) return false
        if (file.parentFile?.path == ROOT.path) {
            // Rename connection
            val cfg = FtpConfigManager.get(ctx, file.name) ?: return false
            cfg.name = newName
            FtpConfigManager.save(ctx, cfg)
            invalidateCache()
            return true
        }
        val resolved = resolve(ctx, file) ?: return false
        val client = getClient(ctx, resolved.config.id) ?: return false
        val parentRemote = resolved.remotePath.substringBeforeLast('/', "")
        val targetRemote = if (parentRemote.isEmpty()) "/$newName" else "$parentRemote/$newName"
        val ok = try {
            client.rename(resolved.remotePath, targetRemote)
        } catch (_: Exception) {
            false
        }
        if (ok) invalidateCache(file.parentFile)
        return ok
    }

    fun transfer(ctx: Context, src: List<File>, destDir: File, move: Boolean): Int {
        var failed = 0
        val isDestFtp = isFtp(destDir)

        if (isDestFtp) {
            val destResolved = resolve(ctx, destDir) ?: return src.size
            val client = getClient(ctx, destResolved.config.id) ?: return src.size

            for ((idx, f) in src.withIndex()) {
                OpProgress.report("${f.name} (${idx + 1}/${src.size})", idx * 100 / src.size)
                if (isFtp(f)) {
                    val srcResolved = resolve(ctx, f)
                    if (srcResolved != null && srcResolved.config.id == destResolved.config.id && move) {
                        // Same connection fast server-side move
                        val targetRemote = "${destResolved.remotePath.trimEnd('/')}/${f.name}"
                        val ok = try { client.rename(srcResolved.remotePath, targetRemote) } catch (_: Exception) { false }
                        if (!ok) failed++
                        continue
                    }
                    // Remote FTP to remote FTP or copy: download to temp, upload
                    val tmp = File(ctx.cacheDir, "ftp_trans_${System.currentTimeMillis()}_${f.name}")
                    val srcClient = srcResolved?.let { getClient(ctx, it.config.id) }
                    val downloadOk = if (srcClient != null && srcResolved != null) {
                        try {
                            FileOutputStream(tmp).use { srcClient.download(srcResolved.remotePath, it) }
                            true
                        } catch (_: Exception) { false }
                    } else false

                    if (downloadOk) {
                        val targetRemote = "${destResolved.remotePath.trimEnd('/')}/${f.name}"
                        val uploadOk = try {
                            FileInputStream(tmp).use { client.upload(it, targetRemote) }
                            true
                        } catch (_: Exception) { false }
                        tmp.delete()
                        if (uploadOk && move && srcClient != null && srcResolved != null) {
                            try { srcClient.deleteFile(srcResolved.remotePath) } catch (_: Exception) {}
                        }
                        if (!uploadOk) failed++
                    } else {
                        tmp.delete()
                        failed++
                    }
                } else {
                    // Local file -> upload to FTP
                    val ok = uploadLocalRecursive(client, f, destResolved.remotePath)
                    if (ok && move) {
                        FileOps.delete(f)
                    }
                    if (!ok) failed++
                }
            }
        } else {
            // Source is FTP, destination is Local
            for ((idx, f) in src.withIndex()) {
                OpProgress.report("${f.name} (${idx + 1}/${src.size})", idx * 100 / src.size)
                if (isFtp(f)) {
                    val srcResolved = resolve(ctx, f)
                    if (srcResolved == null) { failed++; continue }
                    val client = getClient(ctx, srcResolved.config.id)
                    if (client == null) { failed++; continue }
                    val targetLocal = FileOps.unique(destDir, f.name)
                    val isDir = remoteIsDir(client, srcResolved.remotePath)
                    val ok = downloadFtpRecursive(client, srcResolved.remotePath, f.name, targetLocal, isDir)
                    if (ok && move) {
                        try { client.deleteFile(srcResolved.remotePath) } catch (_: Exception) {}
                    }
                    if (!ok) failed++
                } else {
                    // Normal local transfer
                    if (FileOps.transfer(listOf(f), destDir, move) != 0) failed++
                }
            }
        }
        invalidateCache(destDir)
        if (move) src.forEach { f -> f.parentFile?.let { invalidateCache(it) } }
        return failed
    }

    private fun uploadLocalRecursive(client: FtpClient, localFile: File, remoteDir: String): Boolean {
        val targetRemote = "${remoteDir.trimEnd('/')}/${localFile.name}"
        return if (localFile.isDirectory) {
            client.mkdir(targetRemote)
            var allOk = true
            localFile.listFiles()?.forEach { child ->
                if (!uploadLocalRecursive(client, child, targetRemote)) allOk = false
            }
            allOk
        } else {
            try {
                val total = localFile.length()
                FileInputStream(localFile).use {
                    client.upload(it, targetRemote) { done ->
                        OpProgress.report("${localFile.name}: ${OpProgress.size(done)} / ${OpProgress.size(total)}",
                            if (total > 0) (done * 100 / total).toInt() else -1)
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Folder or file? Asked of the parent's listing: listing a plain file path can return the file itself. */
    private fun remoteIsDir(client: FtpClient, remotePath: String): Boolean {
        val trimmed = remotePath.trimEnd('/')
        if (trimmed.isEmpty()) return true
        val parent = trimmed.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = trimmed.substringAfterLast('/')
        val entry = try { client.listFiles(parent).firstOrNull { it.name == name } } catch (_: Exception) { null }
        if (entry != null) return entry.isDir
        val own = try { client.listFiles(trimmed) } catch (_: Exception) { null }
        return own != null && own.isNotEmpty() && !(own.size == 1 && own[0].name == name && !own[0].isDir)
    }

    private fun downloadFtpRecursive(client: FtpClient, remotePath: String, name: String, targetLocal: File, isDir: Boolean): Boolean {
        return try {
            if (isDir) {
                if (!targetLocal.mkdirs() && !targetLocal.isDirectory) return false
                var allOk = true
                for (item in client.listFiles(remotePath)) {
                    val childRemote = "${remotePath.trimEnd('/')}/${item.name}"
                    val childLocal = File(targetLocal, item.name)
                    if (!downloadFtpRecursive(client, childRemote, item.name, childLocal, item.isDir)) allOk = false
                }
                allOk
            } else {
                targetLocal.parentFile?.mkdirs()
                try {
                    FileOutputStream(targetLocal).use {
                        client.download(remotePath, it) { done ->
                            OpProgress.report("$name: ${OpProgress.size(done)}", -1)
                        }
                    }
                } catch (e: Exception) {
                    targetLocal.delete() // never leave a 0-byte stub behind
                    throw e
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun stats(ctx: Context, file: File): LongArray {
        val resolved = resolve(ctx, file) ?: return longArrayOf(0, 0)
        val client = getClient(ctx, resolved.config.id) ?: return longArrayOf(0, 0)
        return try {
            val list = client.listFiles(resolved.remotePath)
            var totalBytes = 0L
            list.forEach { totalBytes += it.size }
            longArrayOf(list.size.toLong(), totalBytes)
        } catch (_: Exception) {
            longArrayOf(0, 0)
        }
    }

    fun closeAll() {
        clientPool.keys.forEach { id -> clientPool.remove(id)?.let { try { it.close() } catch (_: Exception) {} } }
    }
}
