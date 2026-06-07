package org.citra.citra_emu.moonlight.pairing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing-store", Context.MODE_PRIVATE)

    fun add(name: String, certificatePem: String): PairedClient {
        val client = PairedClient(
            uuid = UUID.randomUUID().toString(),
            name = name.ifBlank { "Moonlight" },
            certificatePem = certificatePem,
            pairedAtEpochMillis = System.currentTimeMillis(),
        )
        val updated = list().filterNot { it.certificatePem == certificatePem } + client
        save(updated)
        return client
    }

    fun remove(uuid: String) {
        save(list().filterNot { it.uuid == uuid })
    }

    fun clear() {
        save(emptyList())
    }

    fun list(): List<PairedClient> {
        val raw = prefs.getString(KEY_CLIENTS, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    PairedClient(
                        uuid = item.optString("uuid"),
                        name = item.optString("name"),
                        certificatePem = item.optString("certificatePem"),
                        pairedAtEpochMillis = item.optLong("pairedAtEpochMillis"),
                    ),
                )
            }
        }
    }

    private fun save(clients: List<PairedClient>) {
        val array = JSONArray()
        clients.forEach { client ->
            array.put(
                JSONObject()
                    .put("uuid", client.uuid)
                    .put("name", client.name)
                    .put("certificatePem", client.certificatePem)
                    .put("pairedAtEpochMillis", client.pairedAtEpochMillis),
            )
        }
        prefs.edit().putString(KEY_CLIENTS, array.toString()).apply()
    }

    companion object {
        private const val KEY_CLIENTS = "clients"
    }
}
