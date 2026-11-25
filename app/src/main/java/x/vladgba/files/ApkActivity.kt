package x.vladgba.files

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import x.vladgba.files.pui.PButton
import x.vladgba.files.pui.pButton
import x.vladgba.files.pui.pCaption
import x.vladgba.files.pui.pSection
import x.vladgba.files.pui.pSubtitle
import java.io.File
import java.security.MessageDigest

/** APK inspector module: what a package is and asks for, before installing it. Offered in "Open with…". */
object ApkModule : FileOpener {
    override val id = "apk"
    override val title = R.string.apk_inspector
    override fun canOpen(f: File) = false
    override fun open(a: Activity, f: File) = Openers.launch(a, ApkActivity::class.java, f)
}

class ApkActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val file = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = file.name
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 24))
        }
        val root = ScrollView(this).apply {
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            Ui.applyInsets(this, underActionBar = true)
        }
        setContentView(root)
        val ui = Handler(Looper.getMainLooper())
        Thread {
            val info = try { read(file) } catch (_: Throwable) { null }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (info == null) {
                    Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                    finish()
                    return@post
                }
                build(content, file, info)
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun read(file: File): PackageInfo? {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        // Big APKs can overflow the binder buffer with every component requested: fall back to the basics.
        val info = packageManager.getPackageArchiveInfo(file.path, flags)
            ?: packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_PERMISSIONS)
            ?: return null
        // Without these the label and icon would be resolved against this app instead of the APK.
        info.applicationInfo?.sourceDir = file.path
        info.applicationInfo?.publicSourceDir = file.path
        return info
    }

    @Suppress("DEPRECATION")
    private fun build(content: LinearLayout, file: File, info: PackageInfo) {
        val app = info.applicationInfo
        val label = app?.let { try { it.loadLabel(packageManager).toString() } catch (_: Exception) { null } } ?: info.packageName
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val icon = app?.let { try { it.loadIcon(packageManager) } catch (_: Exception) { null } }
            addView(ImageView(context).apply { setImageDrawable(icon) },
                LinearLayout.LayoutParams(Ui.dp(context, 56), Ui.dp(context, 56)).apply { rightMargin = Ui.dp(context, 14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(pSubtitle(label))
                addView(pCaption(info.packageName).apply { setTextIsSelectable(true) })
            })
        }
        content.addView(head)

        val installed = try { packageManager.getPackageInfo(info.packageName, 0) } catch (_: Exception) { null }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun field(title: Int, value: String) {
            content.addView(pSection(getString(title)))
            content.addView(TextView(this).apply {
                text = value
                textSize = 14f
                setTextColor(Ui.fg(context))
                setTextIsSelectable(true)
            })
        }
        field(R.string.apk_version, "${info.versionName ?: "?"} ($code)")
        field(R.string.apk_installed, if (installed == null) getString(R.string.apk_not_installed) else {
            val have = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
            val relation = getString(when {
                have == code -> R.string.apk_same_version
                have < code -> R.string.apk_is_update
                else -> R.string.apk_is_downgrade
            })
            "${installed.versionName ?: "?"} ($have) · $relation"
        })
        field(R.string.apk_sdk, getString(R.string.apk_sdk_value, app?.minSdkVersion ?: 0, app?.targetSdkVersion ?: 0))
        field(R.string.prop_size, Formatter.formatFileSize(this, file.length()))

        val certs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val prints = certs.orEmpty().map { Checksums.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
        if (prints.isNotEmpty()) field(R.string.apk_signature, prints.joinToString("\n"))

        val counts = listOf(
            R.string.apk_activities to (info.activities?.size ?: 0), R.string.apk_services to (info.services?.size ?: 0),
            R.string.apk_receivers to (info.receivers?.size ?: 0), R.string.apk_providers to (info.providers?.size ?: 0),
        ).filter { it.second > 0 }.joinToString(" · ") { "${getString(it.first)} ${it.second}" }
        if (counts.isNotEmpty()) field(R.string.apk_components, counts)

        val permissions = info.requestedPermissions.orEmpty().map { it.removePrefix("android.permission.") }.sorted()
        field(R.string.apk_permissions, if (permissions.isEmpty()) getString(R.string.apk_none)
            else "${permissions.size}\n" + permissions.joinToString("\n"))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(context, 18), 0, 0)
            addView(pButton(getString(R.string.apk_install), PButton.FILLED) { Openers.install(this@ApkActivity, file) })
            addView(pButton(getString(R.string.zip_preview), PButton.TEXT) { ZipModule.open(this@ApkActivity, file) })
            addView(pButton(getString(R.string.copy), PButton.TEXT) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("package", info.packageName))
                Toast.makeText(context, info.packageName, Toast.LENGTH_SHORT).show()
            })
        }
        content.addView(buttons)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
