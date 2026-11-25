package x.vladgba.files

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import java.io.File

/** Folder chooser: an editable path field on top of a list of subfolders ("..", then folders). */
object FolderPicker {
    fun show(activity: Activity, start: File, onPick: (File) -> Unit) {
        var cur = start.takeIf { ShizukuFiles.isDirectory(it) } ?: Storage.primary()
        val path = EditText(activity).apply {
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        val list = ListView(activity).apply { this.adapter = adapter }
        var names: List<File> = emptyList()

        fun go(dir: File) {
            val kids = if (ShizukuFiles.useFor(dir)) ShizukuFiles.list(dir)?.filter { it.isDir }?.map { it.file }
                else dir.listFiles()?.filter { it.isDirectory }
            if (kids == null) { Toast.makeText(activity, R.string.failed, Toast.LENGTH_SHORT).show(); return }
            cur = dir
            names = kids.sortedBy { it.name.lowercase() }
            path.setText(dir.path)
            path.setSelection(path.text.length)
            adapter.clear()
            adapter.add("..")
            adapter.addAll(names.map { it.name + "/" })
        }
        path.setOnEditorActionListener { _, _, _ ->
            val d = File(path.text.toString().trim())
            if (ShizukuFiles.isDirectory(d)) go(d) else Toast.makeText(activity, R.string.failed, Toast.LENGTH_SHORT).show()
            true
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos == 0) cur.parentFile?.let { go(it) } else go(names[pos - 1])
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(path, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, (320 * resources.displayMetrics.density).toInt()))
        }
        go(cur)
        AlertDialog.Builder(activity)
            .setTitle(R.string.choose_folder)
            .setView(body)
            .setPositiveButton(R.string.choose_this_folder) { _, _ ->
                val typed = File(path.text.toString().trim())
                onPick(if (ShizukuFiles.isDirectory(typed)) typed else cur)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
