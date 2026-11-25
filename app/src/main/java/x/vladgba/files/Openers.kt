package x.vladgba.files

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File

/**
 * Module hook. Built-in viewers implement this and are listed in [Openers.modules]; on tap, the first module
 * whose [canOpen] matches handles the file. Unclaimed files show the opener chooser, and
 * "Open with…" lists built-in modules and compatible external apps.
 */
interface FileOpener {
    /** Stable id: stored in "open by extension" preferences and in the enabled-modules setting. */
    val id: String
    /** Name shown in "Open with…". */
    val title: Int
    /** Claim this file on a plain tap. */
    fun canOpen(f: File): Boolean
    fun open(a: Activity, f: File)
}

object ZipModule : FileOpener {
    override val id = "zip"
    override val title = R.string.zip_preview
    override fun canOpen(f: File) = ArchiveOps.isPreviewable(f)
    override fun open(a: Activity, f: File) = Openers.launch(a, ZipActivity::class.java, f)
}

object Openers {
    const val EXTRA_PATH = "x.vladgba.files.PATH"

    /**
     * Every built-in module, in claim order: on a plain tap the first enabled one whose canOpen matches wins.
     * Specific formats come before the text editor, which claims anything that looks like text.
     */
    val all: List<FileOpener> = listOf(
        RtfModule, MarkdownModule, ImageModule, MediaModule, PdfModule, SqliteModule,
        TextModule, ViewerModule, HexModule, ZipModule, TableModule, ApkModule,
    )

    private var appContext: Context? = null

    /** Enabled modules (Settings > Modules). Before the first screen exists everything is enabled. */
    val modules: List<FileOpener>
        get() = appContext?.let { ctx -> all.filter { isEnabled(ctx, it) } } ?: all

    fun isEnabled(ctx: Context, m: FileOpener): Boolean =
        ctx.getSharedPreferences("pfiles", Context.MODE_PRIVATE).getBoolean("module_on_" + m.id, true)

    fun setEnabled(ctx: Context, m: FileOpener, on: Boolean) {
        ctx.getSharedPreferences("pfiles", Context.MODE_PRIVATE).edit().putBoolean("module_on_" + m.id, on).apply()
    }

    fun byId(id: String): FileOpener? = all.firstOrNull { it.id == id }

    fun open(a: Activity, f: File) {
        appContext = a.applicationContext
        Places.addRecent(a, f)
        val raw = getPreferredRaw(a, extension(f))
        if (raw != null) {
            if (raw.startsWith("module:")) {
                when (val id = raw.removePrefix("module:")) {
                    // The editor is forced even for big files; other modules open the way a tap would.
                    "text" -> { launch(a, EditorActivity::class.java, f); return }
                    else -> byId(id)?.takeIf { isEnabled(a, it) }?.let { it.open(a, f); return }
                }
            } else {
                val compStr = if (raw.startsWith("component:")) raw.removePrefix("component:") else raw
                val component = ComponentName.unflattenFromString(compStr)
                if (component != null && component.packageName != a.packageName && launchPreferred(a, f, component)) return
            }
            forget(a, extension(f))
        }
        modules.firstOrNull { it.canOpen(f) }?.let { it.open(a, f); return }
        chooser(a, f)
    }

    /** "Open with…": built-in modules and installed apps; picking an app remembers it by extension. */
    fun chooser(a: Activity, f: File) {
        appContext = a.applicationContext
        val modules = modules
        val intent = viewIntent(a, f)
        val apps = a.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != a.packageName }
            .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
        val labels = (modules.map { a.getString(it.title) } +
            apps.map { it.loadLabel(a.packageManager).toString() }).toTypedArray()
        AlertDialog.Builder(a)
            .setTitle(R.string.open_with)
            .setItems(labels) { _, i ->
                when {
                    i < modules.size -> {
                        forget(a, extension(f))
                        modules[i].open(a, f)
                    }
                    i < modules.size + apps.size -> {
                        val info = apps[i - modules.size].activityInfo
                        val component = ComponentName(info.packageName, info.name)
                        if (launchPreferred(a, f, component)) {
                            setPreferredRaw(a, extension(f), component.flattenToString())
                        } else {
                            toast(a)
                        }
                    }
                }
            }
            .show()
    }

    fun normalizeExt(ext: String): String = ext.trim().trimStart('.').lowercase().ifEmpty { "<none>" }
    fun extension(f: File): String = normalizeExt(f.extension)
    fun preferenceKey(ext: String): String = "open_app_" + normalizeExt(ext)

    fun getPreferredRaw(ctx: Context, ext: String): String? =
        ctx.getSharedPreferences("pfiles", Context.MODE_PRIVATE).getString(preferenceKey(ext), null)

    fun setPreferredRaw(ctx: Context, ext: String, target: String) {
        ctx.getSharedPreferences("pfiles", Context.MODE_PRIVATE).edit()
            .putString(preferenceKey(ext), target).apply()
    }

    fun forget(ctx: Context, ext: String) {
        ctx.getSharedPreferences("pfiles", Context.MODE_PRIVATE).edit()
            .remove(preferenceKey(ext)).apply()
    }

    fun getAllPreferred(ctx: Context): Map<String, String> {
        val prefs = ctx.getSharedPreferences("pfiles", Context.MODE_PRIVATE)
        val result = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith("open_app_") && value is String) {
                result[key.removePrefix("open_app_")] = value
            }
        }
        return result
    }

    fun clearAllPreferred(ctx: Context) {
        val prefs = ctx.getSharedPreferences("pfiles", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith("open_app_")) editor.remove(key)
        }
        editor.apply()
    }

    fun targetLabel(ctx: Context, target: String): String {
        if (target.startsWith("module:")) {
            return when (val id = target.removePrefix("module:")) {
                "viewer" -> ctx.getString(R.string.view_details)
                else -> byId(id)?.let { ctx.getString(it.title) } ?: target
            }
        }
        val compStr = if (target.startsWith("component:")) target.removePrefix("component:") else target
        val comp = ComponentName.unflattenFromString(compStr) ?: return target
        return try {
            val pm = ctx.packageManager
            val info = pm.getActivityInfo(comp, 0)
            info.loadLabel(pm).toString()
        } catch (_: Exception) {
            comp.packageName
        }
    }

    fun queryAppsForExtension(ctx: Context, ext: String): List<ResolveInfo> {
        val dummy = File("test." + normalizeExt(ext))
        val mimeType = mime(dummy)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://${ctx.packageName}.files/test.${normalizeExt(ext)}"), mimeType)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val pm = ctx.packageManager
        val list = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
        } catch (_: Exception) {
            emptyList()
        }
        return if (list.isNotEmpty()) list else queryAllViewApps(ctx)
    }

    fun queryAllViewApps(ctx: Context): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://${ctx.packageName}.files/test"), "*/*")
        }
        val pm = ctx.packageManager
        val list = try {
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
        } catch (_: Exception) {
            emptyList()
        }
        if (list.isNotEmpty()) return list
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(mainIntent, 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun viewIntent(a: Activity, f: File) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(FilesProvider.uri(a, f), mime(f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun launchPreferred(a: Activity, f: File, component: ComponentName): Boolean =
        start(a, viewIntent(a, f).setComponent(component))

    fun launch(a: Activity, cls: Class<out Activity>, f: File) {
        if (ShizukuFiles.useFor(f)) {
            ShizukuFiles.stage(a, f) { launch(a, cls, it) }
            return
        }
        val i = Intent(a, cls).putExtra(EXTRA_PATH, f.absolutePath)
        // Carry the write-back target (and our grant on it) along when switching editor ↔ viewer ↔ hex.
        target(f)?.let { uri ->
            i.data = uri
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uri.authority == a.packageName + ".files" || canWrite(a, uri))
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        a.startActivity(i)
    }

    // ---------------------------------------------------------------- files opened from other apps
    //
    // A content:// URI that can't be resolved to a real path is copied into the cache and the copy is edited.
    // If the sender granted write access, the copy is registered here and every save is written back.

    private val targets = HashMap<String, Uri>()

    /** Write-back URI for a cached copy, or null for ordinary files. */
    fun target(f: File): Uri? = synchronized(targets) { targets[f.absolutePath] }

    fun setTarget(f: File, uri: Uri) = synchronized(targets) { targets[f.absolutePath] = uri }

    /** Re-register a target passed in an activity's intent (survives process death with the task). */
    fun adoptTarget(a: Activity, f: File) {
        val uri = a.intent.data ?: return
        if (uri.scheme == "content" && (uri.authority == a.packageName + ".files" || canWrite(a, uri))) setTarget(f, uri)
    }

    fun canWrite(ctx: Context, uri: Uri): Boolean =
        ctx.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /** Copy [f] back to its target URI. True if there is no target or the copy succeeded. Blocking. */
    fun writeBack(ctx: Context, f: File): Boolean {
        val uri = target(f) ?: return true
        return try {
            val out = try { ctx.contentResolver.openOutputStream(uri, "wt") } catch (e: Exception) { null }
                ?: ctx.contentResolver.openOutputStream(uri, "w")
                ?: return false
            out.use { o -> f.inputStream().use { it.copyTo(o, 64 * 1024) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    private val INSTALLER_PACKAGES = listOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.coloros.packageinstaller",
        "com.oppo.packageinstaller",
        "com.vivo.abe"
    )

    /** Dispatches APK installation via system package installer, prompting for unknown sources if needed. */
    fun install(a: Activity, f: File) {
        if (!f.exists() || f.length() == 0L) {
            Toast.makeText(a, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!a.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${a.packageName}")
                }
                try {
                    a.startActivity(intent)
                    Toast.makeText(a, R.string.install_permission_prompt, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    try {
                        a.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        Toast.makeText(a, R.string.install_permission_prompt, Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {
                        Toast.makeText(a, R.string.failed, Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
        }
        f.setReadable(true, false)
        val uri = FilesProvider.uri(a, f)

        // Blanket grant URI permissions to all known system package installer packages
        for (pkg in INSTALLER_PACKAGES) {
            try {
                a.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }

        // Grant to all matching handlers for ACTION_VIEW and ACTION_INSTALL_PACKAGE
        val viewQuery = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val viewActivities = try {
            a.packageManager.queryIntentActivities(viewQuery, 0)
        } catch (_: Exception) {
            emptyList()
        }
        for (r in viewActivities) {
            try {
                a.grantUriPermission(r.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }

        @Suppress("DEPRECATION")
        val installQuery = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val installActivities = try {
            a.packageManager.queryIntentActivities(installQuery, 0)
        } catch (_: Exception) {
            emptyList()
        }
        for (r in installActivities) {
            try {
                a.grantUriPermission(r.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }

        // Resolve preferred system package installer
        val pm = a.packageManager
        var targetPkg: String? = null
        for (pkg in INSTALLER_PACKAGES) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                targetPkg = pkg
                break
            } catch (_: Exception) {}
        }
        if (targetPkg == null) {
            targetPkg = installActivities.firstOrNull { it.activityInfo.packageName != "android" }?.activityInfo?.packageName
                ?: viewActivities.firstOrNull { it.activityInfo.packageName != "android" }?.activityInfo?.packageName
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri(null, uri)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra("android.intent.extra.NOT_UNKNOWN_SOURCE", true)
            if (targetPkg != null) setPackage(targetPkg)
        }

        try {
            a.startActivity(intent)
        } catch (_: Exception) {
            if (targetPkg != null) {
                try {
                    intent.setPackage(null)
                    a.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(a, R.string.failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(a, R.string.failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun share(a: Activity, files: List<File>) {
        val uris = files.map { FilesProvider.uri(a, it) }
        val type = files.map { mime(it) }.distinct().singleOrNull() ?: "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
        send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!start(a, Intent.createChooser(send, null))) toast(a)
    }

    private fun start(a: Activity, i: Intent): Boolean = try {
        a.startActivity(i)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun toast(a: Activity) = Toast.makeText(a, R.string.no_app, Toast.LENGTH_SHORT).show()

    fun mime(f: File): String {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (Syntax.isText(f)) "text/plain" else "*/*"
    }
}
