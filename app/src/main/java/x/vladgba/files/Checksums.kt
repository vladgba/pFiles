package x.vladgba.files

import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/** File digests for the Properties dialog and the duplicate finder. Streams the file once; never loads it whole. */
object Checksums {
    class Result(val crc32: String, val md5: String, val sha1: String, val sha256: String)

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(digits[(b.toInt() shr 4) and 0xF]).append(digits[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    /** All digests in one pass. [progress] gets 0..100; [cancelled] is polled between blocks. Null if cancelled. */
    fun compute(f: File, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): Result? {
        val crc = CRC32()
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val total = f.length().coerceAtLeast(1L)
        var done = 0L
        var last = -1
        val buf = ByteArray(256 * 1024)
        f.inputStream().use { input ->
            while (true) {
                if (cancelled()) return null
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
                md5.update(buf, 0, n)
                sha1.update(buf, 0, n)
                sha256.update(buf, 0, n)
                done += n
                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                if (pct != last) { last = pct; progress(pct) }
            }
        }
        return Result("%08x".format(crc.value), hex(md5.digest()), hex(sha1.digest()), hex(sha256.digest()))
    }

    /** SHA-256 of the first [limit] bytes (the whole file when [limit] is negative). */
    fun sha256(f: File, limit: Long = -1, cancelled: () -> Boolean = { false }): String? {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(128 * 1024)
        var left = if (limit < 0) Long.MAX_VALUE else limit
        f.inputStream().use { input ->
            while (left > 0) {
                if (cancelled()) return null
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                md.update(buf, 0, n)
                left -= n
            }
        }
        return hex(md.digest())
    }
}
