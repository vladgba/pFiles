package x.vladgba.files.ftp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Lightweight embedded FTP server for local device file sharing.
 * Implements RFC 959 and RFC 3659 commands.
 */
class FtpServer(
    val rootDir: File,
    val port: Int = 2121,
    val username: String = "",
    val password: String = "",
    val anonymous: Boolean = true
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var listenerThread: Thread? = null

    val running get() = isRunning

    @Synchronized
    fun start() {
        if (isRunning) return
        val ss = ServerSocket(port)
        serverSocket = ss
        isRunning = true

        val t = Thread({
            while (isRunning && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    Thread({ handleClient(client) }, "FtpClientHandler").start()
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        }, "FtpServerListener")
        listenerThread = t
        t.start()
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        listenerThread?.interrupt()
        listenerThread = null
    }

    private fun handleClient(client: Socket) {
        var pasvServer: ServerSocket? = null
        var cwd = "/"
        var renameFrom: File? = null
        var authenticated = anonymous && username.isEmpty()

        try {
            client.soTimeout = 300000 // 5 minutes timeout
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))

            fun send(msg: String) {
                writer.write(msg + "\r\n")
                writer.flush()
            }

            send("220 pFiles FTP server ready")

            while (isRunning && !client.isClosed) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                val space = trimmed.indexOf(' ')
                val cmd = (if (space > 0) trimmed.substring(0, space) else trimmed).uppercase(Locale.US)
                val arg = if (space > 0) trimmed.substring(space + 1).trim() else ""

                when (cmd) {
                    "USER" -> {
                        if (anonymous && (arg.equals("anonymous", ignoreCase = true) || arg.isEmpty())) {
                            authenticated = true
                            send("230 Anonymous user logged in")
                        } else if (arg == username) {
                            if (password.isEmpty()) {
                                authenticated = true
                                send("230 User logged in")
                            } else {
                                send("331 Password required for $arg")
                            }
                        } else {
                            send("530 User not found")
                        }
                    }
                    "PASS" -> {
                        if (arg == password || (anonymous && username.isEmpty())) {
                            authenticated = true
                            send("230 User logged in")
                        } else {
                            send("530 Login incorrect")
                        }
                    }
                    "QUIT" -> {
                        send("221 Goodbye")
                        break
                    }
                    "NOOP" -> send("200 OK")
                    "SYST" -> send("215 UNIX Type: L8")
                    "FEAT" -> {
                        send("211-Features:")
                        send(" UTF8")
                        send(" MLSD")
                        send(" SIZE")
                        send("211 End")
                    }
                    "OPTS" -> {
                        if (arg.uppercase(Locale.US).startsWith("UTF8")) {
                            send("200 Always in UTF8 mode")
                        } else {
                            send("502 Option not supported")
                        }
                    }
                    "TYPE" -> send("200 Type set to binary")
                    "PWD" -> send("257 \"$cwd\" is current directory")
                    "CWD" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val target = resolvePath(cwd, arg)
                        if (target.exists() && target.isDirectory) {
                            cwd = toVirtual(target)
                            send("250 Directory changed to \"$cwd\"")
                        } else {
                            send("550 Directory not found")
                        }
                    }
                    "CDUP" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val target = resolvePath(cwd, "..")
                        if (target.exists() && target.isDirectory) {
                            cwd = toVirtual(target)
                            send("250 Directory changed to \"$cwd\"")
                        } else {
                            send("550 Cannot change directory")
                        }
                    }
                    "PASV" -> {
                        try { pasvServer?.close() } catch (_: Exception) {}
                        val ps = ServerSocket(0)
                        pasvServer = ps
                        val localPort = ps.localPort
                        val ip = client.localAddress.address
                        val p1 = (localPort shr 8) and 0xFF
                        val p2 = localPort and 0xFF
                        send("227 Entering Passive Mode (${ip[0].toInt() and 0xFF},${ip[1].toInt() and 0xFF},${ip[2].toInt() and 0xFF},${ip[3].toInt() and 0xFF},$p1,$p2)")
                    }
                    "EPSV" -> {
                        try { pasvServer?.close() } catch (_: Exception) {}
                        val ps = ServerSocket(0)
                        pasvServer = ps
                        send("229 Entering Extended Passive Mode (|||${ps.localPort}|)")
                    }
                    "LIST", "NLST" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val ps = pasvServer
                        if (ps == null) { send("425 Use PASV first"); continue }
                        // Clients commonly send options such as "LIST -a /path": ignore them.
                        val listArg = arg.split(' ').dropWhile { it.startsWith("-") }.joinToString(" ")
                        val target = resolvePath(cwd, listArg)
                        if (!target.exists()) {
                            send("550 No such file or directory")
                            try { ps.close() } catch (_: Exception) {}
                            pasvServer = null
                            continue
                        }
                        send("150 Opening ASCII mode data connection for file list")
                        val dataSock = ps.accept()
                        try {
                            val dataWriter = BufferedWriter(OutputStreamWriter(dataSock.getOutputStream(), Charsets.UTF_8))
                            val files = if (target.isDirectory) target.listFiles() ?: emptyArray() else arrayOf(target)
                            val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                            for (f in files) {
                                val isDir = f.isDirectory
                                val prefix = if (isDir) "drwxr-xr-x" else "-rw-r--r--"
                                val size = if (isDir) 4096L else f.length()
                                val dateStr = sdf.format(Date(f.lastModified()))
                                val name = f.name
                                if (cmd == "NLST") {
                                    dataWriter.write("$name\r\n")
                                } else {
                                    dataWriter.write("$prefix 1 owner group $size $dateStr $name\r\n")
                                }
                            }
                            dataWriter.flush()
                            dataSock.close()
                            send("226 Transfer complete")
                        } finally {
                            try { ps.close() } catch (_: Exception) {}
                            pasvServer = null
                        }
                    }
                    "MLSD" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val ps = pasvServer
                        if (ps == null) { send("425 Use PASV first"); continue }
                        val target = if (arg.isEmpty()) resolvePath(cwd, "") else resolvePath(cwd, arg)
                        if (!target.exists()) {
                            send("550 No such file or directory")
                            try { ps.close() } catch (_: Exception) {}
                            pasvServer = null
                            continue
                        }
                        send("150 Opening data connection for MLSD")
                        val dataSock = ps.accept()
                        try {
                            val dataWriter = BufferedWriter(OutputStreamWriter(dataSock.getOutputStream(), Charsets.UTF_8))
                            val files = if (target.isDirectory) target.listFiles() ?: emptyArray() else arrayOf(target)
                            val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }
                            for (f in files) {
                                val type = if (f.isDirectory) "dir" else "file"
                                val size = if (f.isDirectory) 0L else f.length()
                                val modify = sdf.format(Date(f.lastModified()))
                                dataWriter.write("type=$type;size=$size;modify=$modify; ${f.name}\r\n")
                            }
                            dataWriter.flush()
                            dataSock.close()
                            send("226 Transfer complete")
                        } finally {
                            try { ps.close() } catch (_: Exception) {}
                            pasvServer = null
                        }
                    }
                    "RETR" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val ps = pasvServer
                        if (ps == null) { send("425 Use PASV first"); continue }
                        val file = resolvePath(cwd, arg)
                        if (!file.exists() || file.isDirectory) {
                            send("550 File not found")
                            try { ps.close() } catch (_: Exception) {}
                            pasvServer = null
                            continue
                        }
                        send("150 Opening binary mode data connection")
                        val dataSock = ps.accept()
                        try {
                            FileInputStream(file).use { fin ->
                                dataSock.getOutputStream().use { fout ->
                                    fin.copyTo(fout, 32 * 1024)
                                }
                            }
                            send("226 Transfer complete")
                        } finally {
                            try { ps.close() } catch (_: Exception) {}
                            pasvServer = null
                        }
                    }
                    "STOR" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val ps = pasvServer
                        if (ps == null) { send("425 Use PASV first"); continue }
                        val file = resolvePath(cwd, arg)
                        send("150 Opening binary mode data connection")
                        val dataSock = ps.accept()
                        try {
                            dataSock.getInputStream().use { fin ->
                                FileOutputStream(file).use { fout ->
                                    fin.copyTo(fout, 32 * 1024)
                                }
                            }
                            send("226 Transfer complete")
                        } finally {
                            try { ps.close() } catch (_: Exception) {}
                            pasvServer = null
                        }
                    }
                    "DELE" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val file = resolvePath(cwd, arg)
                        if (file.exists() && !file.isDirectory && file.delete()) {
                            send("250 File deleted")
                        } else {
                            send("550 Cannot delete file")
                        }
                    }
                    "RMD" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val dir = resolvePath(cwd, arg)
                        if (dir.exists() && dir.isDirectory && dir.delete()) {
                            send("250 Directory removed")
                        } else {
                            send("550 Cannot remove directory")
                        }
                    }
                    "MKD" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val dir = resolvePath(cwd, arg)
                        if (dir.mkdirs()) {
                            send("257 \"$arg\" created")
                        } else {
                            send("550 Cannot create directory")
                        }
                    }
                    "RNFR" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val file = resolvePath(cwd, arg)
                        if (file.exists()) {
                            renameFrom = file
                            send("350 Ready for RNTO")
                        } else {
                            send("550 File not found")
                        }
                    }
                    "RNTO" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val from = renameFrom
                        if (from == null) {
                            send("503 Bad sequence of commands")
                            continue
                        }
                        val to = resolvePath(cwd, arg)
                        if (from.renameTo(to)) {
                            renameFrom = null
                            send("250 Rename successful")
                        } else {
                            send("550 Rename failed")
                        }
                    }
                    "SIZE" -> {
                        if (!authenticated) { send("530 Please login first"); continue }
                        val file = resolvePath(cwd, arg)
                        if (file.exists() && !file.isDirectory) {
                            send("213 ${file.length()}")
                        } else {
                            send("550 File not found")
                        }
                    }
                    else -> send("502 Command not implemented")
                }
            }
        } catch (_: Exception) {
        } finally {
            try { pasvServer?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun resolvePath(currentVirtual: String, target: String): File {
        val cleanTarget = target.trim()
        val combined = if (cleanTarget.startsWith("/")) {
            cleanTarget
        } else {
            "${currentVirtual.trimEnd('/')}/$cleanTarget"
        }
        val normalized = normalizeVirtualPath(combined)
        return File(rootDir, normalized.removePrefix("/"))
    }

    private fun toVirtual(file: File): String {
        val rootPath = rootDir.canonicalPath
        val filePath = file.canonicalPath
        if (!filePath.startsWith(rootPath)) return "/"
        val relative = filePath.removePrefix(rootPath)
        return if (relative.isEmpty() || !relative.startsWith("/")) "/$relative" else relative
    }

    private fun normalizeVirtualPath(path: String): String {
        val segments = mutableListOf<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(part)
            }
        }
        return "/" + segments.joinToString("/")
    }

    companion object {
        fun getLocalIp(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (_: Exception) {}
            return null
        }
    }
}
