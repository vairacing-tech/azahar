package org.citra.citra_emu.moonlight.server

import android.content.Context
import java.util.UUID

class HostIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("host-identity", Context.MODE_PRIVATE)

    val uniqueId: String = prefs.getString(KEY_UNIQUE_ID, null)
        ?: UUID.randomUUID().toString().replace("-", "").also { generated ->
            prefs.edit().putString(KEY_UNIQUE_ID, generated).apply()
        }

    companion object {
        private const val KEY_UNIQUE_ID = "unique_id"

        fun reset(context: Context) {
            context.applicationContext.getSharedPreferences("host-identity", Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_UNIQUE_ID)
                .apply()
        }
    }
}
