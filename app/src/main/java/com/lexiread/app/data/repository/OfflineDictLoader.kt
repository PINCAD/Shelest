package com.lexiread.app.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.lexiread.app.data.db.OfflineDictDao
import com.lexiread.app.data.model.OfflineDictEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the bundled offline_dict.json into Room on first run.
 */
class OfflineDictLoader(
    private val context: Context,
    private val dao: OfflineDictDao
) {
    data class RawEntry(val w: String, val d: String, val p: String = "")

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        val state = context.getSharedPreferences(IMPORT_STATE, Context.MODE_PRIVATE)
        if (state.getBoolean(IMPORT_COMPLETE, false)) return@withContext
        try {
            context.assets.open("offline_dict.json").bufferedReader().use { input ->
                JsonReader(input).use { reader ->
                    val gson = Gson()
                    val batch = ArrayList<OfflineDictEntry>(500)
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val raw = gson.fromJson<RawEntry>(reader, RawEntry::class.java)
                        batch += OfflineDictEntry(raw.w, raw.d, raw.p)
                        if (batch.size == 500) {
                            dao.insertAll(batch)
                            batch.clear()
                        }
                    }
                    reader.endArray()
                    if (batch.isNotEmpty()) dao.insertAll(batch)
                }
            }
            // Written only after the closing bracket was read and the final batch reached Room.
            // A killed process therefore restarts the idempotent import rather than accepting a
            // partially populated dictionary merely because its row count is non-zero.
            state.edit().putBoolean(IMPORT_COMPLETE, true).commit()
        } catch (e: Exception) {
            // Silent — offline dict is optional
        }
    }

    private companion object {
        const val IMPORT_STATE = "offline_dictionary_bootstrap"
        const val IMPORT_COMPLETE = "v1_complete"
    }
}
