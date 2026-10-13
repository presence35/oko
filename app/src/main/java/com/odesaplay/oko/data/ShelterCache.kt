package com.odesaplay.oko

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Last-good shelter JSON on disk. The index is fetched from the server on start, but a failed
 * fetch (roaming, no data) must not leave the app with no shelters at all — the most recent
 * successful copy is read back here and served until the network returns.
 */
object ShelterCache {
    private const val FILE_NAME = "shelters.json"

    /** The cached JSON, or null when nothing usable has been stored yet. */
    suspend fun read(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText() else null
        }.getOrNull()
    }

    /** Stores the freshly fetched JSON, replacing the previous copy. */
    suspend fun write(context: Context, json: String) = withContext(Dispatchers.IO) {
        runCatching { File(context.filesDir, FILE_NAME).writeText(json) }
        Unit
    }
}
