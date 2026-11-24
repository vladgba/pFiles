package x.vladgba.files.ftp

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

data class FtpCfg(
    val id: String,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val anonymous: Boolean,
    val passive: Boolean,
    val encoding: String,
    val sftp: Boolean = false,
    /** Private key in PEM text (SFTP only); empty means password login. */
    val key: String = "",
    val keyPass: String = ""
) {
    companion object {
        fun fromJson(raw: String): FtpCfg {
            val j = JSONObject(raw)
            return FtpCfg(
                id = j.optString("id", ""),
                host = j.optString("host", ""),
                port = j.optInt("port", 21),
                user = j.optString("user", "anonymous"),
                pass = j.optString("pass", ""),
                anonymous = j.optBoolean("anonymous", true),
                passive = j.optBoolean("passive", true),
                encoding = j.optString("encoding", "UTF-8").ifEmpty { "UTF-8" },
                sftp = j.optString("protocol", "ftp") == "sftp",
                key = j.optString("key", ""),
                keyPass = j.optString("keyPass", "")
            )
        }
    }
}

data class RemoteEntry(val name: String, val isDir: Boolean, val size: Long, val modified: Long)

/**
 * Pool of FTP control connections per server. Each operation borrows its own connection, so a slow or
 * abandoned request never blocks the next one and replies of different commands can never mix.
 */
class FtpSessions(
    private val connectTimeoutMs: Int = 15_000,
    private val socketTimeoutMs: Int = 20_000,
    private val maxPerServer: Int = 4
) {
    private class Idle(val client: FTPClient, val since: Long)

    private class Pool(max: Int) {
        val idle = ArrayDeque<Idle>()
        val slots = Semaphore(max)
    }

    private val pools = ConcurrentHashMap<String, Pool>()

    private fun key(cfg: FtpCfg) = "${cfg.id}#${cfg.hashCode()}"

    fun <T> use(cfg: FtpCfg, block: (FTPClient) -> T): T {
        val pool = pools.getOrPut(key(cfg)) { Pool(maxPerServer) }
        if (!pool.slots.tryAcquire(30, TimeUnit.SECONDS)) throw IOException("Too many FTP operations at once")
        try {
            val client = borrow(pool, cfg)
            try {
                val result = block(client)
                synchronized(pool) { pool.idle.addLast(Idle(client, System.currentTimeMillis())) }
                return result
            } catch (e: Throwable) {
                quietClose(client)
                throw e
            }
        } finally {
            pool.slots.release()
        }
    }

    private fun borrow(pool: Pool, cfg: FtpCfg): FTPClient {
        while (true) {
            val idle = synchronized(pool) { pool.idle.removeLastOrNull() } ?: return open(cfg)
            val age = System.currentTimeMillis() - idle.since
            val alive = age < 120_000 && idle.client.isConnected &&
                (age < 5_000 || runCatching { idle.client.sendNoOp() }.getOrDefault(false))
            if (alive) return idle.client
            quietClose(idle.client)
        }
    }

    fun disconnect(id: String?) {
        for ((k, pool) in pools) {
            if (id != null && !k.startsWith("$id#")) continue
            synchronized(pool) {
                pool.idle.forEach { quietClose(it.client) }
                pool.idle.clear()
            }
        }
    }

    private fun open(cfg: FtpCfg): FTPClient {
        if (cfg.host.isBlank()) throw IOException("Host is empty")
        val c = FTPClient()
        try {
            c.connectTimeout = connectTimeoutMs
            c.controlEncoding = cfg.encoding
            c.setAutodetectUTF8(true)
            c.connect(cfg.host, cfg.port)
            c.setTcpNoDelay(true)
            c.soTimeout = socketTimeoutMs
            c.setDataTimeout(Duration.ofMillis(socketTimeoutMs.toLong()))
            if (!FTPReply.isPositiveCompletion(c.replyCode)) {
                throw IOException("Server refused connection: ${c.replyString.trim()}")
            }
            val user = if (cfg.anonymous) "anonymous" else cfg.user
            val pass = if (cfg.anonymous) "pfiles@android.local" else cfg.pass
            if (!c.login(user, pass)) throw IOException("Login failed: ${c.replyString.trim()}")
            c.setFileType(FTP.BINARY_FILE_TYPE)
            c.bufferSize = 64 * 1024
            c.isRemoteVerificationEnabled = false
            c.listHiddenFiles = true
            c.setUseEPSVwithIPv4(false)
            if (cfg.passive) c.enterLocalPassiveMode() else c.enterLocalActiveMode()
            return c
        } catch (e: Throwable) {
            quietClose(c)
            throw e
        }
    }

    private fun quietClose(c: FTPClient) {
        try { if (c.isConnected) c.disconnect() } catch (_: Exception) {}
    }
}

object FtpOps {
    fun normalize(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        val clean = trimmed.replace('\\', '/').trimEnd('/')
        return if (clean.startsWith("/")) clean else "/$clean"
    }

    private fun failure(c: FTPClient, what: String) = IOException("$what: ${c.replyString.trim()}")

    fun list(c: FTPClient, path: String): List<RemoteEntry> {
        val p = normalize(path)
        var files = if ((c.hasFeature("MLST") || c.hasFeature("MLSD"))) c.mlistDir(p) else null
        if (files == null || !FTPReply.isPositiveCompletion(c.replyCode)) files = c.listFiles(p)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) throw failure(c, "Can't list $p")
        return files.mapNotNull { f ->
            if (f == null) return@mapNotNull null
            val name = f.name?.substringAfterLast('/') ?: return@mapNotNull null
            if (name.isEmpty() || name == "." || name == "..") return@mapNotNull null
            // MLSD also lists the folder itself and its parent (type=cdir / type=pdir).
            val raw = f.rawListing?.lowercase().orEmpty()
            if (raw.contains("type=cdir") || raw.contains("type=pdir")) return@mapNotNull null
            // A symlink gives no hint about its target; names without an extension are most often folders.
            val dir = f.isDirectory || (f.isSymbolicLink && !name.contains('.'))
            RemoteEntry(name, dir, maxOf(f.size, 0L), f.timestamp?.timeInMillis ?: 0L)
        }
    }

    fun download(c: FTPClient, path: String, out: OutputStream) {
        if (!c.retrieveFile(normalize(path), out)) throw failure(c, "Download failed")
    }

    fun upload(c: FTPClient, path: String, input: InputStream) {
        if (!c.storeFile(normalize(path), input)) throw failure(c, "Upload failed")
    }

    fun mkdir(c: FTPClient, path: String): Boolean = c.makeDirectory(normalize(path))

    fun deleteOne(c: FTPClient, path: String): Boolean {
        val p = normalize(path)
        return p != "/" && (c.deleteFile(p) || c.removeDirectory(p))
    }

    /** Deletes a file, or a folder together with everything inside it. */
    fun delete(c: FTPClient, path: String): Boolean {
        val p = normalize(path)
        if (p == "/") return false
        if (c.deleteFile(p)) return true
        val children = try { list(c, p) } catch (_: Exception) { emptyList() }
        var ok = true
        for (child in children) {
            if (!delete(c, "${p.trimEnd('/')}/${child.name}")) ok = false
        }
        return ok && c.removeDirectory(p)
    }

    fun rename(c: FTPClient, from: String, to: String): Boolean = c.rename(normalize(from), normalize(to))
}
