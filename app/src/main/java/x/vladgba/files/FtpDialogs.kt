package x.vladgba.files

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

object FtpDialogs {

    private fun dp(ctx: Context, v: Int) = Ui.dp(ctx, v)

    fun showAddEdit(activity: Activity, existing: FtpConfig? = null, onSaved: (FtpConfig) -> Unit) {
        val dp16 = dp(activity, 16)
        val dp8 = dp(activity, 8)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp8, dp16, dp16)
        }

        fun fieldLabel(text: String): TextView = TextView(activity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(activity.getColor(R.color.p_text2))
            setPadding(0, dp8, 0, dp(activity, 2))
        }

        fun inputField(hint: String, defaultVal: String): EditText = EditText(activity).apply {
            this.hint = hint
            setText(defaultVal)
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }

        val nameInput = inputField(activity.getString(R.string.ftp_name_hint), existing?.name ?: "")
        val hostInput = inputField("example.com or 192.168.1.10", existing?.host ?: "")
        val portInput = inputField("21", existing?.port?.toString() ?: "21").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val anonCheck = CheckBox(activity).apply {
            text = activity.getString(R.string.ftp_anonymous)
            isChecked = existing?.anonymous ?: true
        }
        val userInput = inputField("anonymous", existing?.user ?: "anonymous")
        val passInput = inputField("", existing?.pass ?: "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val passiveCheck = CheckBox(activity).apply {
            text = activity.getString(R.string.ftp_passive_mode)
            isChecked = existing?.passive ?: true
        }
        val rootPathInput = inputField("/", existing?.rootPath ?: "/")
        val sftpCheck = CheckBox(activity).apply {
            text = activity.getString(R.string.ftp_use_sftp)
            isChecked = existing?.sftp ?: false
        }
        val keyInput = EditText(activity).apply {
            hint = activity.getString(R.string.ftp_private_key_hint)
            setText(existing?.key ?: "")
            minLines = 3; maxLines = 6
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
        }
        val keyPassInput = inputField("", existing?.keyPass ?: "").apply {
            hint = activity.getString(R.string.ftp_key_passphrase)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val keyViews = listOf(fieldLabel(activity.getString(R.string.ftp_private_key)), keyInput, keyPassInput)

        fun updateAuthFields(isAnon: Boolean) {
            userInput.isEnabled = !isAnon
            passInput.isEnabled = !isAnon
            if (isAnon) {
                if (userInput.text.isNullOrEmpty() || userInput.text.toString() == "anonymous") {
                    userInput.setText("anonymous")
                }
            }
        }
        updateAuthFields(anonCheck.isChecked)
        anonCheck.setOnCheckedChangeListener { _, isChecked -> updateAuthFields(isChecked) }
        fun updateProtocol(sftp: Boolean) {
            anonCheck.visibility = if (sftp) View.GONE else View.VISIBLE
            passiveCheck.visibility = if (sftp) View.GONE else View.VISIBLE
            keyViews.forEach { it.visibility = if (sftp) View.VISIBLE else View.GONE }
            if (sftp) { anonCheck.isChecked = false; updateAuthFields(false) }
            val cur = portInput.text.toString().trim()
            if (sftp && (cur == "21" || cur.isEmpty())) portInput.setText("22")
            if (!sftp && (cur == "22" || cur.isEmpty())) portInput.setText("21")
        }

        container.addView(fieldLabel(activity.getString(R.string.ftp_conn_name)))
        container.addView(nameInput)
        container.addView(sftpCheck)
        container.addView(fieldLabel(activity.getString(R.string.ftp_server_host)))
        container.addView(hostInput)
        container.addView(fieldLabel(activity.getString(R.string.ftp_server_port)))
        container.addView(portInput)
        container.addView(anonCheck)
        container.addView(fieldLabel(activity.getString(R.string.ftp_username)))
        container.addView(userInput)
        container.addView(fieldLabel(activity.getString(R.string.ftp_password)))
        container.addView(passInput)
        keyViews.forEach { container.addView(it) }
        container.addView(passiveCheck)
        container.addView(fieldLabel(activity.getString(R.string.ftp_remote_path)))
        updateProtocol(sftpCheck.isChecked)
        sftpCheck.setOnCheckedChangeListener { _, c -> updateProtocol(c) }
        container.addView(rootPathInput)

        val testButton = Button(activity).apply {
            text = activity.getString(R.string.ftp_test_connection)
            setOnClickListener {
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().trim().toIntOrNull() ?: (if (sftpCheck.isChecked) 22 else 21)
                if (host.isEmpty()) {
                    Toast.makeText(activity, R.string.ftp_enter_host, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val testConfig = FtpConfig(
                    name = nameInput.text.toString().ifEmpty { host },
                    host = host,
                    port = port,
                    user = userInput.text.toString().trim(),
                    pass = passInput.text.toString(),
                    anonymous = anonCheck.isChecked,
                    passive = passiveCheck.isChecked,
                    rootPath = rootPathInput.text.toString().trim().ifEmpty { "/" },
                    sftp = sftpCheck.isChecked,
                    key = keyInput.text.toString().trim(),
                    keyPass = keyPassInput.text.toString()
                )
                isEnabled = false
                text = activity.getString(R.string.ftp_testing)
                Thread {
                    val client = FtpClient(activity, testConfig)
                    var success = false
                    var errorMsg = ""
                    try {
                        client.connect()
                        success = client.noop()
                        client.close()
                        FtpModule.disconnect(activity, testConfig.id)
                    } catch (e: Exception) {
                        errorMsg = e.message ?: e.javaClass.simpleName
                    }
                    activity.runOnUiThread {
                        isEnabled = true
                        text = activity.getString(R.string.ftp_test_connection)
                        if (success) {
                            Toast.makeText(activity, R.string.ftp_test_success, Toast.LENGTH_SHORT).show()
                        } else {
                            val msg = activity.getString(R.string.ftp_test_failed) + if (errorMsg.isNotEmpty()) ": $errorMsg" else ""
                            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }
        container.addView(testButton)

        val scroll = ScrollView(activity).apply { addView(container) }

        val title = if (existing == null) R.string.ftp_add_connection else R.string.ftp_edit_connection
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.save) { _, _ ->
                val host = hostInput.text.toString().trim()
                if (host.isEmpty()) {
                    Toast.makeText(activity, R.string.ftp_enter_host, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val port = portInput.text.toString().trim().toIntOrNull() ?: (if (sftpCheck.isChecked) 22 else 21)
                val cfg = existing?.copy() ?: FtpConfig(name = host, host = host)
                cfg.name = nameInput.text.toString().trim().ifEmpty { host }
                cfg.host = host
                cfg.port = port
                cfg.anonymous = anonCheck.isChecked
                cfg.user = if (cfg.anonymous) "anonymous" else userInput.text.toString().trim()
                cfg.pass = if (cfg.anonymous) "" else passInput.text.toString()
                cfg.passive = passiveCheck.isChecked
                cfg.rootPath = rootPathInput.text.toString().trim().ifEmpty { "/" }
                cfg.sftp = sftpCheck.isChecked
                cfg.key = if (cfg.sftp) keyInput.text.toString().trim() else ""
                cfg.keyPass = if (cfg.sftp) keyPassInput.text.toString() else ""

                FtpConfigManager.save(activity, cfg)
                onSaved(cfg)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showManage(activity: Activity, onNavigate: ((File) -> Unit)? = null, onUpdated: () -> Unit) {
        val dp16 = dp(activity, 16)
        val dp8 = dp(activity, 8)
        val connections = FtpConfigManager.getAll(activity)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp8, dp16, dp16)
        }

        if (connections.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = activity.getString(R.string.ftp_no_connections)
                setPadding(0, dp16, 0, dp16)
                setTextColor(activity.getColor(R.color.p_text2))
            })
        } else {
            connections.forEach { cfg ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp8, 0, dp8)
                    gravity = Gravity.CENTER_VERTICAL
                }
                val info = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    addView(TextView(activity).apply {
                        text = cfg.name
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(activity.getColor(R.color.p_text))
                    })
                    addView(TextView(activity).apply {
                        text = "${cfg.host}:${cfg.port} (${if (cfg.anonymous) "anonymous" else cfg.user})"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(activity.getColor(R.color.p_text2))
                    })
                }
                row.addView(info)

                val editBtn = Button(activity).apply {
                    text = activity.getString(R.string.edit)
                    setOnClickListener {
                        showAddEdit(activity, cfg) {
                            onUpdated()
                            showManage(activity, onNavigate, onUpdated)
                        }
                    }
                }
                val delBtn = Button(activity).apply {
                    text = activity.getString(R.string.remove)
                    setOnClickListener {
                        AlertDialog.Builder(activity)
                            .setMessage(activity.getString(R.string.ftp_delete_confirm, cfg.name))
                            .setPositiveButton(R.string.remove) { _, _ ->
                                FtpConfigManager.delete(activity, cfg.id)
                                onUpdated()
                                showManage(activity, onNavigate, onUpdated)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                row.addView(editBtn)
                row.addView(delBtn)

                if (onNavigate != null) {
                    row.isClickable = true
                    row.setOnClickListener {
                        onNavigate(File(FtpFiles.ROOT, cfg.id))
                    }
                }

                container.addView(row)
            }
        }

        val scroll = ScrollView(activity).apply { addView(container) }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.ftp_connections)
            .setView(scroll)
            .setPositiveButton(R.string.ftp_add_connection) { _, _ ->
                showAddEdit(activity) {
                    onUpdated()
                    showManage(activity, onNavigate, onUpdated)
                }
            }
            .setNeutralButton(R.string.ftp_server) { _, _ ->
                showServer(activity)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()
    }

    fun showServer(activity: Activity) {
        val dp16 = dp(activity, 16)
        val dp8 = dp(activity, 8)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp8, dp16, dp16)
        }

        val isRunning = FtpServerService.isRunning()
        val localIp = FtpServer.getLocalIp() ?: "0.0.0.0"

        val statusText = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isRunning) 0xFF43A047.toInt() else activity.getColor(R.color.p_text2))
            text = if (isRunning) {
                activity.getString(R.string.ftp_server_running_url, "ftp://$localIp:2121")
            } else {
                activity.getString(R.string.ftp_server_stopped)
            }
            setPadding(0, 0, 0, dp16)
        }
        container.addView(statusText)

        val portInput = EditText(activity).apply {
            hint = "2121"
            setText("2121")
            inputType = InputType.TYPE_CLASS_NUMBER
            isEnabled = !isRunning
        }
        val anonCheck = CheckBox(activity).apply {
            text = activity.getString(R.string.ftp_anonymous)
            isChecked = true
            isEnabled = !isRunning
        }
        val userInput = EditText(activity).apply {
            hint = activity.getString(R.string.ftp_username)
            setText("admin")
            isEnabled = !isRunning && !anonCheck.isChecked
        }
        val passInput = EditText(activity).apply {
            hint = activity.getString(R.string.ftp_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isEnabled = !isRunning && !anonCheck.isChecked
        }
        val sftpCheck = CheckBox(activity).apply {
            text = activity.getString(R.string.ftp_server_sftp)
            isEnabled = !isRunning
        }
        fun refreshAuth() {
            val needLogin = sftpCheck.isChecked || !anonCheck.isChecked
            userInput.isEnabled = !isRunning && needLogin
            passInput.isEnabled = !isRunning && needLogin
            anonCheck.isEnabled = !isRunning && !sftpCheck.isChecked
        }
        anonCheck.setOnCheckedChangeListener { _, _ -> refreshAuth() }
        sftpCheck.setOnCheckedChangeListener { _, sftp ->
            val cur = portInput.text.toString().trim()
            if (sftp && cur == "2121") portInput.setText("2222")
            if (!sftp && cur == "2222") portInput.setText("2121")
            refreshAuth()
        }

        container.addView(sftpCheck)
        container.addView(TextView(activity).apply { text = activity.getString(R.string.ftp_server_port) })
        container.addView(portInput)
        container.addView(anonCheck)
        container.addView(TextView(activity).apply { text = activity.getString(R.string.ftp_username) })
        container.addView(userInput)
        container.addView(TextView(activity).apply { text = activity.getString(R.string.ftp_password) })
        container.addView(passInput)

        val scroll = ScrollView(activity).apply { addView(container) }

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.ftp_server)
            .setView(scroll)

        if (isRunning) {
            builder.setPositiveButton(R.string.stop) { _, _ ->
                FtpServerService.stop(activity)
                Toast.makeText(activity, R.string.ftp_server_stopped, Toast.LENGTH_SHORT).show()
            }
        } else {
            builder.setPositiveButton(R.string.start) { _, _ ->
                val sftp = sftpCheck.isChecked
                val port = portInput.text.toString().toIntOrNull() ?: if (sftp) 2222 else 2121
                val anon = anonCheck.isChecked && !sftp
                val user = if (anon) "" else userInput.text.toString().trim()
                val pass = if (anon) "" else passInput.text.toString()
                if (sftp && (user.isEmpty() || pass.isEmpty())) {
                    Toast.makeText(activity, R.string.ftp_server_sftp_needs_login, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                FtpServerService.start(activity, port, user, pass, anon, sftp = sftp)
                val ip = FtpServer.getLocalIp() ?: "0.0.0.0"
                val url = (if (sftp) "sftp://" else "ftp://") + "$ip:$port"
                Toast.makeText(activity, activity.getString(R.string.ftp_server_running_url, url), Toast.LENGTH_LONG).show()
            }
        }
        builder.setNegativeButton(android.R.string.cancel, null)
        builder.show()
    }
}
