package x.vladgba.files.ftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * SFTP over SSH. One SSH session per server is shared; every operation opens its own short-lived SFTP channel,
 * so concurrent transfers never mix. Host keys are trusted on first use and then pinned in [knownHosts]:
 * a changed key is refused.
 */
class SftpSessions(private val knownHosts: File?, private val connectTimeoutMs: Int = 15_000) {
    private val sessions = ConcurrentHashMap<String, Session>()

    private fun key(cfg: FtpCfg) = "${cfg.id}#${cfg.hashCode()}"

    private fun session(cfg: FtpCfg): Session {
        if (cfg.host.isBlank()) throw IOException("Host is empty")
        sessions[key(cfg)]?.let { if (it.isConnected) return it else sessions.remove(key(cfg)) }
        val jsch = JSch()
        knownHosts?.let { f -> if (!f.exists()) f.createNewFile(); jsch.setKnownHosts(f.path) }
        if (cfg.key.isNotBlank()) {
            jsch.addIdentity("pfiles-${cfg.id}", cfg.key.toByteArray(),
                null, cfg.keyPass.takeIf { it.isNotEmpty() }?.toByteArray())
        }
        val s = jsch.getSession(cfg.user, cfg.host, if (cfg.port > 0) cfg.port else 22)
        if (cfg.pass.isNotEmpty()) s.setPassword(cfg.pass)
        s.setConfig("StrictHostKeyChecking", "ask") // unknown host -> UserInfo; changed key -> hard failure
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        s.userInfo = object : UserInfo {
            override fun getPassphrase(): String? = cfg.keyPass.takeIf { it.isNotEmpty() }
            override fun getPassword(): String? = cfg.pass.takeIf { it.isNotEmpty() }
            override fun promptPassword(m: String?) = cfg.pass.isNotEmpty()
            override fun promptPassphrase(m: String?) = cfg.keyPass.isNotEmpty()
            override fun promptYesNo(m: String?) = true // trust on first use; JSch pins it in known_hosts
            override fun showMessage(m: String?) {}
        }
        s.serverAliveInterval = 30_000
        s.connect(connectTimeoutMs)
        sessions[key(cfg)] = s
        return s
    }

    fun <T> use(cfg: FtpCfg, block: (ChannelSftp) -> T): T {
        val ch = try {
            session(cfg).openChannel("sftp") as ChannelSftp
        } catch (e: Exception) { sessions.remove(key(cfg))?.disconnect(); throw e }
        try {
            ch.connect(connectTimeoutMs)
            return block(ch)
        } finally {
            ch.disconnect()
        }
    }

    fun disconnect(id: String?) {
        for ((k, s) in sessions) if (id == null || k.startsWith("$id#")) { sessions.remove(k); s.disconnect() }
    }
}

object SftpOps {
    private fun p(path: String) = FtpOps.normalize(path)

    fun list(c: ChannelSftp, path: String): List<RemoteEntry> {
        val dir = p(path)
        val out = ArrayList<RemoteEntry>()
        try {
            @Suppress("UNCHECKED_CAST")
            for (e in c.ls(dir) as java.util.Vector<ChannelSftp.LsEntry>) {
                val name = e.filename
                if (name == "." || name == "..") continue
                var a = e.attrs
                var isDir = a.isDir
                if (a.isLink) { // follow the link to learn what it points at
                    runCatching { a = c.stat(dir.trimEnd('/') + "/" + name); isDir = a.isDir }
                }
                out += RemoteEntry(name, isDir, maxOf(a.size, 0L), a.mTime * 1000L)
            }
        } catch (e: SftpException) { throw IOException("Can't list $dir: ${e.message}") }
        return out
    }

    fun download(c: ChannelSftp, path: String, out: OutputStream) {
        try { c.get(p(path), out) } catch (e: SftpException) { throw IOException("Download failed: ${e.message}") }
    }

    fun upload(c: ChannelSftp, path: String, input: InputStream) {
        try { c.put(input, p(path), ChannelSftp.OVERWRITE) } catch (e: SftpException) { throw IOException("Upload failed: ${e.message}") }
    }

    private inline fun ok(block: () -> Unit) = try { block(); true } catch (e: SftpException) { false }

    fun mkdir(c: ChannelSftp, path: String) = ok { c.mkdir(p(path)) }

    fun deleteOne(c: ChannelSftp, path: String): Boolean {
        val d = p(path)
        return d != "/" && (ok { c.rm(d) } || ok { c.rmdir(d) })
    }

    fun delete(c: ChannelSftp, path: String): Boolean {
        val d = p(path)
        if (d == "/") return false
        if (ok { c.rm(d) }) return true
        var all = true
        for (child in runCatching { list(c, d) }.getOrDefault(emptyList())) {
            if (child.isDir) { if (!delete(c, d.trimEnd('/') + "/" + child.name)) all = false }
            else if (!ok { c.rm(d.trimEnd('/') + "/" + child.name) }) all = false
        }
        return all && ok { c.rmdir(d) }
    }

    fun rename(c: ChannelSftp, from: String, to: String) = ok { c.rename(p(from), p(to)) }
}
