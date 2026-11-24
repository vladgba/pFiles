package x.vladgba.files

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File

/**
 * Embedded SFTP server (SSH file transfer, no shell). Password login only; the client is confined to [rootDir].
 * The host key is generated once into [hostKey] so clients keep trusting the same server.
 */
class SftpServer(
    val rootDir: File,
    val port: Int,
    private val username: String,
    private val password: String,
    private val hostKey: File
) {
    private var server: SshServer? = null
    val running get() = server?.isStarted == true

    @Synchronized
    fun start() {
        if (running) return
        require(username.isNotEmpty() && password.isNotEmpty()) { "SFTP needs a username and password" }
        val s = SshServer.setUpDefaultServer()
        s.port = port
        s.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey.toPath()).apply {
            algorithm = "EC"
            keySize = 256
        }
        s.passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { u, p, _ ->
            u == username && java.security.MessageDigest.isEqual(p.toByteArray(), password.toByteArray())
        }
        s.fileSystemFactory = VirtualFileSystemFactory(rootDir.toPath())
        s.subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
        s.start()
        server = s
    }

    @Synchronized
    fun stop() {
        try { server?.stop(true) } catch (_: Exception) {}
        server = null
    }
}
