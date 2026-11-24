package x.vladgba.files

import x.vladgba.files.ftp.FtpCfg
import x.vladgba.files.ftp.FtpOps
import x.vladgba.files.ftp.FtpSessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

class FtpTest {

    @Test
    fun testNormalizeRemotePath() {
        assertEquals("/", FtpOps.normalize(""))
        assertEquals("/foo/bar", FtpOps.normalize("foo\\bar"))
        assertEquals("/a/b/c", FtpOps.normalize("/a/b/c/"))
    }

    @Test
    fun testFtpConfigJsonSerialization() {
        val config = FtpConfig(
            id = "test1234",
            name = "Test Server",
            host = "192.168.1.50",
            port = 2121,
            user = "alice",
            pass = "secret123",
            anonymous = false,
            passive = true,
            rootPath = "/storage",
            encoding = "UTF-8"
        )
        val json = config.toJson()
        val restored = FtpConfig.fromJson(json)

        assertEquals(config.id, restored.id)
        assertEquals(config.name, restored.name)
        assertEquals(config.host, restored.host)
        assertEquals(config.port, restored.port)
        assertEquals(config.user, restored.user)
        assertEquals(config.pass, restored.pass)
        assertEquals(config.anonymous, restored.anonymous)
        assertEquals(config.passive, restored.passive)
        assertEquals(config.rootPath, restored.rootPath)
        assertEquals(config.encoding, restored.encoding)
    }

    @Test
    fun testLoopbackFtpServerAndClient() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ftp_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        // Find a free port
        val freePort = ServerSocket(0).use { it.localPort }

        val server = FtpServer(
            rootDir = tempDir,
            port = freePort,
            username = "testuser",
            password = "testpass",
            anonymous = false
        )
        server.start()

        try {
            val config = FtpConfig(
                name = "Localhost Test",
                host = "127.0.0.1",
                port = freePort,
                user = "testuser",
                pass = "testpass",
                anonymous = false,
                passive = true
            )
            val sessions = FtpSessions()
            val cfg = FtpCfg.fromJson(config.toJson().toString())

            assertTrue(sessions.use(cfg) { it.sendNoOp() })
            assertTrue(sessions.use(cfg) { FtpOps.mkdir(it, "/testdir") })
            assertTrue(File(tempDir, "testdir").isDirectory)

            val payload = "Hello FTP in pFiles!".toByteArray(StandardCharsets.UTF_8)
            sessions.use(cfg) { FtpOps.upload(it, "/testdir/hello.txt", ByteArrayInputStream(payload)) }
            assertEquals("Hello FTP in pFiles!", File(tempDir, "testdir/hello.txt").readText())

            val list = sessions.use(cfg) { FtpOps.list(it, "/testdir") }
            assertEquals(1, list.size)
            assertEquals("hello.txt", list[0].name)
            assertFalse(list[0].isDir)
            assertEquals(payload.size.toLong(), list[0].size)

            val baos = ByteArrayOutputStream()
            sessions.use(cfg) { FtpOps.download(it, "/testdir/hello.txt", baos) }
            assertEquals("Hello FTP in pFiles!", String(baos.toByteArray(), StandardCharsets.UTF_8))

            assertTrue(sessions.use(cfg) { FtpOps.rename(it, "/testdir/hello.txt", "/testdir/renamed.txt") })
            assertTrue(File(tempDir, "testdir/renamed.txt").exists())

            assertTrue(sessions.use(cfg) { FtpOps.delete(it, "/testdir/renamed.txt") })
            assertTrue(sessions.use(cfg) { FtpOps.delete(it, "/testdir") })
            assertFalse(File(tempDir, "testdir").exists())

            // Recursive delete of a non-empty folder tree.
            File(tempDir, "tree/sub").mkdirs()
            File(tempDir, "tree/a.txt").writeText("a")
            File(tempDir, "tree/sub/b.txt").writeText("b")
            assertTrue(sessions.use(cfg) { FtpOps.delete(it, "/tree") })
            assertFalse(File(tempDir, "tree").exists())

            // A failing listing must surface as an error, not an empty folder.
            val failed = runCatching { sessions.use(cfg) { FtpOps.list(it, "/nope") } }
            assertTrue(failed.isFailure)

            sessions.disconnect(null)
        } finally {
            server.stop()
            tempDir.deleteRecursively()
        }
    }
}
