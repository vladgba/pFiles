package x.vladgba.files

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Configuration for an FTP server connection. */
data class FtpConfig(
    val id: String = UUID.randomUUID().toString().take(8),
    var name: String,
    var host: String,
    var port: Int = 21,
    var user: String = "anonymous",
    var pass: String = "",
    var anonymous: Boolean = true,
    var passive: Boolean = true,
    var rootPath: String = "/",
    var encoding: String = "UTF-8",
    var sftp: Boolean = false,
    var key: String = "",
    var keyPass: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("user", user)
        put("pass", pass)
        put("anonymous", anonymous)
        put("passive", passive)
        put("rootPath", rootPath)
        put("encoding", encoding)
        put("protocol", if (sftp) "sftp" else "ftp")
        put("key", key)
        put("keyPass", keyPass)
    }

    companion object {
        fun fromJson(json: JSONObject): FtpConfig = FtpConfig(
            id = json.optString("id", UUID.randomUUID().toString().take(8)),
            name = json.optString("name", "FTP Server"),
            host = json.optString("host", ""),
            port = json.optInt("port", 21),
            user = json.optString("user", "anonymous"),
            pass = json.optString("pass", ""),
            anonymous = json.optBoolean("anonymous", true),
            passive = json.optBoolean("passive", true),
            rootPath = json.optString("rootPath", "/"),
            encoding = json.optString("encoding", "UTF-8"),
            sftp = json.optString("protocol", "ftp") == "sftp",
            key = json.optString("key", ""),
            keyPass = json.optString("keyPass", "")
        )
    }
}

object FtpConfigManager {
    private const val PREFS_NAME = "pfiles_ftp"
    private const val KEY_CONNECTIONS = "connections"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(ctx: Context): List<FtpConfig> {
        val raw = prefs(ctx).getString(KEY_CONNECTIONS, null) ?: return emptyList()
        val list = mutableListOf<FtpConfig>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                list += FtpConfig.fromJson(array.getJSONObject(i))
            }
        } catch (_: Exception) {}
        return list
    }

    fun get(ctx: Context, id: String): FtpConfig? = getAll(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, config: FtpConfig) {
        val list = getAll(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        val list = getAll(ctx).filterNot { it.id == id }
        saveAll(ctx, list)
    }

    private fun saveAll(ctx: Context, list: List<FtpConfig>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_CONNECTIONS, array.toString()).apply()
    }
}
