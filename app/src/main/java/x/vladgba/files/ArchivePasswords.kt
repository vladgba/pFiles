package x.vladgba.files

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File

/** Requests a password only while needed; passwords are never saved to preferences or disk. */
object ArchivePasswords {
    fun choose(activity: Activity, onReady: (CharArray) -> Unit) {
        fun field(hint: Int) = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setHint(hint)
        }
        val first = field(R.string.archive_password)
        val second = field(R.string.archive_confirm_password)
        val pad = Ui.dp(activity, 16)
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(first)
            addView(second)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.archive_password)
            .setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = first.text.toString().toCharArray()
                val match = password.isNotEmpty() && password.contentEquals(second.text.toString().toCharArray())
                first.text.clear(); second.text.clear()
                if (match) onReady(password)
                else {
                    password.fill('\u0000')
                    Toast.makeText(activity, R.string.archive_password_mismatch, Toast.LENGTH_SHORT).show()
                    choose(activity, onReady)
                }
            }
            .show()
    }

    fun ask(activity: Activity, archive: File, onReady: (CharArray) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            val pad = Ui.dp(activity, 16)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.archive_password)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = input.text.toString().toCharArray()
                input.text.clear()
                Thread {
                    val valid = if (ShizukuFiles.useFor(archive))
                        ShizukuFiles.validateArchivePassword(archive, password)
                    else PasswordZipOps.validate(archive, password)
                    Handler(Looper.getMainLooper()).post {
                        if (activity.isFinishing || activity.isDestroyed) {
                            password.fill('\u0000')
                        } else if (valid) onReady(password)
                        else {
                            password.fill('\u0000')
                            Toast.makeText(activity, R.string.archive_wrong_password, Toast.LENGTH_SHORT).show()
                            ask(activity, archive, onReady)
                        }
                    }
                }.start()
            }
            .show()
    }
}
