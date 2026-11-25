package x.vladgba.files

import android.content.Context
import java.io.File

/** Bookmarked folders and recently opened files, kept as newline-separated paths in the app preferences. */
object Places {
    private const val BOOKMARKS = "bookmarks"
    private const val RECENTS = "recents"
    const val MAX_RECENTS = 30

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pfiles", Context.MODE_PRIVATE)

    private fun read(ctx: Context, key: String): List<String> =
        prefs(ctx).getString(key, "").orEmpty().split('\n').filter { it.isNotEmpty() }

    private fun write(ctx: Context, key: String, paths: List<String>) =
        prefs(ctx).edit().putString(key, paths.joinToString("\n")).apply()

    /** Newest first, no duplicates, at most [max] entries. */
    fun pushed(list: List<String>, path: String, max: Int): List<String> =
        (listOf(path) + list.filter { it != path }).take(max)

    fun bookmarks(ctx: Context): List<File> = read(ctx, BOOKMARKS).map(::File)

    fun isBookmarked(ctx: Context, dir: File) = dir.absolutePath in read(ctx, BOOKMARKS)

    fun toggleBookmark(ctx: Context, dir: File): Boolean {
        val all = read(ctx, BOOKMARKS)
        val path = dir.absolutePath
        val added = path !in all
        write(ctx, BOOKMARKS, if (added) all + path else all - path)
        return added
    }

    fun recents(ctx: Context): List<File> = read(ctx, RECENTS).map(::File)

    fun addRecent(ctx: Context, f: File) {
        // Cached copies of FTP or shared files have no stable place to return to.
        if (f.absolutePath.startsWith(ctx.cacheDir.absolutePath)) return
        write(ctx, RECENTS, pushed(read(ctx, RECENTS), f.absolutePath, MAX_RECENTS))
    }

    fun clearRecents(ctx: Context) = write(ctx, RECENTS, emptyList())
}
