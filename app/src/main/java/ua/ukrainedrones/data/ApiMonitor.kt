package ua.ukrainedrones.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ua.ukrainedrones.service.ServiceState
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class SystemEntryKind { SDK_CHANGED, SDK_CHECK_FAILED, MALFORMED_FRAME, UNKNOWN_TYPE_DETECTED }

data class SystemEntry(
    val atMillis: Long,
    val kind: SystemEntryKind,
    val detail: String
)

sealed interface ManifestResult {
    data class Changed(val oldHash: String, val newHash: String) : ManifestResult
    data object Unchanged : ManifestResult
    data class Failed(val message: String) : ManifestResult
}

object ApiMonitor {

    private const val TAG = "ApiMonitor"
    internal const val MAX_ENTRIES = 100
    internal const val AUTO_CLEAR_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val MANIFEST_URL = "https://neptun.in.ua/sdk/build-manifest.json"

    private val _entries = MutableStateFlow<List<SystemEntry>>(emptyList())
    val entries: StateFlow<List<SystemEntry>> = _entries.asStateFlow()

    @Volatile private var attached = false
    private var appContext: Context? = null
    private val attachScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attachDone = CompletableDeferred<Unit>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun attach(context: Context) {
        if (attached) return
        attached = true
        appContext = context.applicationContext
        attachScope.launch {
            val raw = ServiceState(appContext!!).systemLog().first()
            val now = System.currentTimeMillis()
            _entries.value = parseSystemLog(raw).filter { now - it.atMillis < AUTO_CLEAR_AGE_MS }
            attachDone.complete(Unit)
        }
    }

    suspend fun awaitAttached() = attachDone.await()

    fun record(entry: SystemEntry) {
        val now = System.currentTimeMillis()
        _entries.value =
            (_entries.value + entry).filter { now - it.atMillis < AUTO_CLEAR_AGE_MS }.takeLast(MAX_ENTRIES)
        persist()
    }

    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    suspend fun checkManifest(context: Context): ManifestResult {
        val svcState = ServiceState(context)
        val oldHash = svcState.lastSdkManifestHash().first()
        return try {
            val request = Request.Builder().url(MANIFEST_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "HTTP ${response.code}"
                    Log.w(TAG, "Manifest check failed: $msg")
                    ManifestResult.Failed(msg)
                } else {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val newHash = json.optString("sha256", "").ifEmpty {
                        sha256(body)
                    }
                    if (newHash.isEmpty()) {
                        ManifestResult.Failed("No sha256 in manifest")
                    } else if (newHash == oldHash) {
                        ManifestResult.Unchanged
                    } else {
                        svcState.setLastSdkManifestHash(newHash)
                        Log.w(TAG, "NEPTUN SDK changed! SHA: $oldHash -> $newHash")
                        ManifestResult.Changed(oldHash, newHash)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Manifest check error: ${e.message}")
            ManifestResult.Failed(e.message ?: "Unknown error")
        }
    }

    private fun persist() {
        val context = appContext ?: return
        attachScope.launch { ServiceState(context).setSystemLog(serializeSystemLog(_entries.value)) }
    }

    private fun sha256(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

internal fun serializeSystemLog(entries: List<SystemEntry>): String =
    entries.joinToString("\n") { "${it.atMillis}|${it.kind.name}|${it.detail}" }

internal fun parseSystemLog(raw: String): List<SystemEntry> =
    raw.split('\n').mapNotNull { line ->
        val parts = line.split('|', limit = 3)
        if (parts.size != 3) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        val kind = SystemEntryKind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
        SystemEntry(at, kind, parts[2])
    }.takeLast(ApiMonitor.MAX_ENTRIES)
