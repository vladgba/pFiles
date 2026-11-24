package x.vladgba.files

/** Progress of the running file operation. [percent] is 0..100, or -1 when unknown. Thread-safe. */
object OpProgress {
    @Volatile var sink: ((text: String, percent: Int) -> Unit)? = null

    fun report(text: String, percent: Int = -1) {
        sink?.invoke(text, percent)
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
