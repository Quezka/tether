package io.github.quezka.tether

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** This phone's id and every computer it's paired with (private preferences: only Tether can read them). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("tether", Context.MODE_PRIVATE)

    data class Pairing(val id: String, val name: String, val host: String?, val key: ByteArray)

    val phoneId: String = prefs.getString("id", null) ?: Protocol.randomBytes(8).joinToString("") { "%02x".format(it) }
        .also { prefs.edit().putString("id", it).apply() }

    init { migrate() }

    /** 0.2 kept one computer in flat keys -> list (0.3, many computers). */
    private fun migrate() {
        val id = prefs.getString("computer_id", null) ?: return
        val key = prefs.getString("key", null)
        if (key != null) save(pairings().filter { it.id != id } +
            Pairing(id, prefs.getString("computer_name", null) ?: "?", prefs.getString("host", null), Base64.decode(key, Base64.NO_WRAP)))
        prefs.edit().remove("computer_id").remove("computer_name").remove("host").remove("key").apply()
    }

    @Synchronized fun pairings(): List<Pairing> = runCatching {
        val a = JSONArray(prefs.getString("pairings", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Pairing(it.getString("id"), it.optString("name", "?"), it.optString("host").ifEmpty { null },
                Base64.decode(it.getString("key"), Base64.NO_WRAP))
        }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(list: List<Pairing>) {
        prefs.edit().putString("pairings", JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("host", it.host ?: "")
                .put("key", Base64.encodeToString(it.key, Base64.NO_WRAP))
        }).toString()).apply()
    }

    @Synchronized fun put(p: Pairing) = save(pairings().filter { it.id != p.id } + p)
    @Synchronized fun setHost(id: String, host: String) =
        pairings().firstOrNull { it.id == id }?.takeIf { it.host != host }?.let { put(it.copy(host = host)) }
    @Synchronized fun forget(id: String) = save(pairings().filter { it.id != id })

    val paired get() = pairings().isNotEmpty()
}
