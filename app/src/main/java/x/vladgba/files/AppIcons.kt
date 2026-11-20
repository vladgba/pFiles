package x.vladgba.files

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** Loads only icons that appear on screen; all cache and callback access stays on the UI thread. */
internal object AppIcons {
    private val cache = LruCache<String, Drawable>(128)
    private val missing = HashSet<String>()
    private val waiting = HashMap<String, MutableList<WeakReference<FileAdapter.Badge>>>()
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    fun bind(context: Context, packageName: String, badge: FileAdapter.Badge) {
        cache.get(packageName)?.let { badge.showAppIcon(packageName, it); return }
        if (packageName in missing) return
        val callbacks = waiting[packageName]
        if (callbacks != null) {
            callbacks.add(WeakReference(badge))
            return
        }
        waiting[packageName] = mutableListOf(WeakReference(badge))
        val app = context.applicationContext
        worker.execute {
            val icon = try { app.packageManager.getApplicationIcon(packageName) }
                catch (_: Exception) { null }
            ui.post {
                if (icon == null) missing.add(packageName) else cache.put(packageName, icon)
                waiting.remove(packageName)?.forEach { it.get()?.showAppIcon(packageName, icon) }
            }
        }
    }
}
