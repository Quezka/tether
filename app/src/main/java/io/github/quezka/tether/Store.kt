package io.github.quezka.tether

import android.content.Context
import android.util.Base64

/** This phone's id and the computer it's paired with (private preferences: only Tether can read them). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("tether", Context.MODE_PRIVATE)

    val phoneId: String = prefs.getString("id", null) ?: Protocol.randomBytes(8).joinToString("") { "%02x".format(it) }
        .also { prefs.edit().putString("id", it).apply() }

    var computerId: String?
        get() = prefs.getString("computer_id", null)
        set(v) = prefs.edit().putString("computer_id", v).apply()
    var computerName: String?
        get() = prefs.getString("computer_name", null)
        set(v) = prefs.edit().putString("computer_name", v).apply()
    var lastHost: String?
        get() = prefs.getString("host", null)
        set(v) = prefs.edit().putString("host", v).apply()
    var key: ByteArray?
        get() = prefs.getString("key", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(v) = prefs.edit().putString("key", v?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).apply()

    val paired get() = key != null && computerId != null

    fun forget() = prefs.edit().remove("computer_id").remove("computer_name").remove("host").remove("key").apply()
}
