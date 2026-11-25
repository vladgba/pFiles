package x.vladgba.files

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Virtual folder listing installed packages and creating a user-selected APK/data backup. */
object InstalledApps {
    val ROOT = File("/pfiles-installed-apps")
    fun isRoot(file: File) = file.path == ROOT.path
    fun packageFor(file: File): String? = file.name.takeIf { file.parentFile?.path == ROOT.path }

    fun entries(ctx: Context): List<Entry> = ctx.packageManager.getInstalledApplications(0).map { app ->
        val apk = File(app.sourceDir)
        Entry(File(ROOT, app.packageName), false, apk.length(), apk.lastModified(),
            app.loadLabel(ctx.packageManager).toString() + "  (" + app.packageName + ")")
    }

    data class Selection(val pkg: String, val folder: File, val apk: Boolean, val internal: Boolean,
                         val external: Boolean, val obb: Boolean)

    fun showBackup(activity: Activity, pkg: String, defaultDir: File, onBrowseData: (File) -> Unit,
                   onBackup: (Selection) -> Unit) {
        val info = try { activity.packageManager.getApplicationInfo(pkg, 0) }
            catch (_: PackageManager.NameNotFoundException) { return }
        val dp = activity.resources.displayMetrics.density
        val padding = (20 * dp).toInt()
        val options = listOf(
            CheckBox(activity).apply { setText(R.string.backup_apk); isChecked = true },
            CheckBox(activity).apply { setText(R.string.backup_internal_data) },
            CheckBox(activity).apply { setText(R.string.backup_external_data) },
            CheckBox(activity).apply { setText(R.string.backup_obb) },
        )
        options[1].isEnabled = ShizukuFiles.available() &&
            (info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 || ShizukuFiles.isRoot())
        val destination = EditText(activity).apply {
            setSingleLine()
            setText(defaultDir.path)
        }
        val browse = android.widget.Button(activity).apply {
            setText(R.string.choose_folder)
            setOnClickListener {
                FolderPicker.show(activity, File(destination.text.toString().trim().ifEmpty { defaultDir.path })) {
                    destination.setText(it.path)
                }
            }
        }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, (8 * dp).toInt(), padding, 0)
            options.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)) }
            addView(TextView(activity).apply { setText(R.string.backup_destination) })
            addView(destination)
            addView(browse)
        }
        AlertDialog.Builder(activity)
            .setTitle(info.loadLabel(activity.packageManager))
            .setView(view)
            .setNeutralButton(R.string.browse_data) { _, _ ->
                if (ShizukuFiles.available() && !options[1].isEnabled)
                    android.widget.Toast.makeText(activity, R.string.private_data_unavailable,
                        android.widget.Toast.LENGTH_LONG).show()
                else onBrowseData(File(info.dataDir))
            }
            .setPositiveButton(R.string.backup_app) { _, _ ->
                val folder = destination.text.toString().trim()
                if (folder.isNotEmpty() && options.any { it.isChecked }) {
                    onBackup(Selection(pkg, File(folder), options[0].isChecked, options[1].isChecked,
                        options[2].isChecked, options[3].isChecked))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Virtual entries of the apps list (dragged, copied or cut) rather than real files. */
    fun areApps(files: List<File>) = files.isNotEmpty() && files.all { packageFor(it) != null }

    /** Copies the APK of each package (with all split APKs) into its own folder "pkg-stamp" in [target]; returns failures. */
    fun backupApks(ctx: Context, files: List<File>, target: File): Int {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var failed = 0
        files.forEachIndexed { i, f ->
            val pkg = packageFor(f) ?: run { failed++; return@forEachIndexed }
            OpProgress.report("$pkg (${i + 1}/${files.size})", i * 100 / files.size)
            val app = try { ctx.packageManager.getApplicationInfo(pkg, 0) }
                catch (_: PackageManager.NameNotFoundException) { failed++; return@forEachIndexed }
            val name = "$pkg-$stamp"
            val dest = File(target, name)
            val made = when {
                FtpFiles.isFtp(target) -> FtpFiles.mkdir(ctx, target, name)
                ShizukuFiles.available() && ShizukuFiles.useFor(dest) -> ShizukuFiles.mkdirs(dest)
                else -> dest.mkdirs()
            }
            if (!made) { failed++; return@forEachIndexed }
            val apks = (listOf(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())).map(::File)
            if (FileOps.transfer(apks, dest, false) != 0) failed++
        }
        return failed
    }

    /** Returns number of selected components that failed. Runs on the worker thread. */
    fun backup(ctx: Context, selection: Selection): Int {
        val app = try { ctx.packageManager.getApplicationInfo(selection.pkg, 0) }
            catch (_: PackageManager.NameNotFoundException) { return 1 }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dest = File(selection.folder, "${selection.pkg}-$stamp")
        if (!(if (ShizukuFiles.available()) ShizukuFiles.mkdirs(dest) else dest.mkdirs())) return 1
        var failed = 0
        if (selection.apk) {
            val apks = (listOf(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())).map(::File)
            failed += FileOps.transfer(apks, dest, false)
        }
        if (selection.internal) {
            if (!ShizukuFiles.available() || !ShizukuFiles.backupInternal(selection.pkg,
                    app.dataDir, File(dest, "internal-data.tar"))) failed++
        }
        if (selection.external) {
            val source = File(Storage.primary(), "Android/data/${selection.pkg}")
            if (!FileOps.existsNoFollow(source) || FileOps.transfer(listOf(source),
                    File(dest, "external-data").also { if (ShizukuFiles.available()) ShizukuFiles.mkdirs(it) else it.mkdirs() }, false) != 0) failed++
        }
        if (selection.obb) {
            val source = File(Storage.primary(), "Android/obb/${selection.pkg}")
            if (!FileOps.existsNoFollow(source) || FileOps.transfer(listOf(source),
                    File(dest, "obb").also { if (ShizukuFiles.available()) ShizukuFiles.mkdirs(it) else it.mkdirs() }, false) != 0) failed++
        }
        return failed
    }
}
