package com.presaince.oko

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.presaince.oko.service.ServiceState

/** Connection states shown in the status log — mirrors the header pill's two states. */
enum class ConnStatus { ONLINE, OFFLINE, DEGRADED }

/** Network transport carrying the feed during an episode (null = no active network). */
enum class NetTransport { WIFI, CELLULAR, OTHER }

/** One logged status change. [durationSec] is the episode length for OFF, null for ONLINE.
 *  [activeSource] names the source providing alerts during this episode (null = primary/Neptun).
 *  [transport] is the network the status change was observed on (null = none). */
data class ConnLogEntry(
    val atMillis: Long,
    val status: ConnStatus,
    val durationSec: Long?,
    val activeSource: String? = null,
    val transport: NetTransport? = null
)

/**
 * Ring buffer of the last [MAX_ENTRIES] connection statuses, persisted to DataStore so the log
 * survives app/service restarts. Fed by [ConnectionSupervisor]'s StateFlow bridge. The currently
 * in-progress offline episode is kept separately (see [currentEpisode]) so the popup can show
 * a live running duration, and is committed to the log the moment the status changes again —
 * sub-grace flaps (under [PRODUCTION_GRACE_MS]) never hit the log or disk.
 */
object ConnectionLog {

    private const val MAX_ENTRIES = 50
    private const val LINE_SEP = '\n'
    private const val PRODUCTION_GRACE_MS = 15_000L

    private val _entries = MutableStateFlow<List<ConnLogEntry>>(emptyList())
    val entries: StateFlow<List<ConnLogEntry>> = _entries.asStateFlow()

    @Volatile private var pending: ConnLogEntry? = null
    @Volatile private var lastStatus: ConnStatus? = null
    @Volatile private var attached = false
    private var appContext: Context? = null
    private val attachScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attachDone = CompletableDeferred<Unit>()

    /**
     * Restore persisted completed entries from DataStore. Call once (from
     * AlertService/MainActivity) before NeptunClient starts its watchdog. Idempotent.
     */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        appContext = context.applicationContext
        attachScope.launch {
            val prefs = ServiceState(context.applicationContext)
            val loaded = parse(prefs.connLog().first())
            _entries.value = loaded
            attachDone.complete(Unit)
        }
    }

    /** Wait for [attach]'s async restore to finish. */
    suspend fun awaitAttached() = attachDone.await()

    /**
     * Called on every connection state transition. Commits the completed offline
     * episode as soon as the status changes once it has outlasted the production grace,
     * bracketing it with a recovery row when it returns online.
     */
    fun observe(
        status: ConnStatus,
        now: Long,
        activeSource: String? = null,
        transport: NetTransport? = null
    ) {
        val prev = lastStatus
        lastStatus = status
        val t = commitLogState(prev, status, now, pending, _entries.value, MAX_ENTRIES, PRODUCTION_GRACE_MS, activeSource, transport) ?: return
        _entries.value = t.entries
        pending = t.nextPending
        if (t.persistLog) persist()
    }

    /** The in-progress offline episode with its running duration, or null when online. */
    fun currentEpisode(now: Long): ConnLogEntry? =
        pending?.let { ConnLogEntry(it.atMillis, it.status, (now - it.atMillis) / 1000, it.activeSource, it.transport) }

    /** Update the source currently owning the alert feed on the in-progress episode. Called
     *  whenever the registry's active source changes (e.g. a fallback takes over mid-outage),
     *  so the committed entry reflects which source actually covered the episode. */
    fun setPendingSource(source: String?) {
        val p = pending ?: return
        if (p.status == ConnStatus.ONLINE) return
        pending = p.copy(activeSource = source)
    }

    /** Update the transport of the in-progress episode when the device switches networks
     *  mid-outage (e.g. WiFi drops to cellular), so the committed entry reflects it. */
    fun setPendingTransport(transport: NetTransport?) {
        val p = pending ?: return
        if (p.status == ConnStatus.ONLINE || p.transport == transport) return
        pending = p.copy(transport = transport)
    }

    private fun persist() {
        val context = appContext ?: return
        attachScope.launch { ServiceState(context).setConnLog(serialize(_entries.value)) }
    }

    private fun serialize(entries: List<ConnLogEntry>): String =
        entries.joinToString(LINE_SEP.toString()) {
            "${it.atMillis}|${it.status.name}|${it.durationSec ?: ""}|${it.activeSource ?: ""}|${it.transport?.name ?: ""}"
        }

    private fun parse(raw: String): List<ConnLogEntry> =
        raw.split(LINE_SEP).mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 3) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            val status = ConnStatus.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            val dur = parts[2].toLongOrNull()
            val source = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
            val transport = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }
                ?.let { name -> NetTransport.entries.firstOrNull { it.name == name } }
            ConnLogEntry(at, status, dur, source, transport)
        }.takeLast(MAX_ENTRIES)
}

/** Result of one [commitLogState] step: what to persist and what the log becomes. */
internal data class LogTransition(
    val entries: List<ConnLogEntry>,
    val nextPending: ConnLogEntry?,
    val persistLog: Boolean
)

/**
 * Pure episode-commit decision for [ConnectionLog.observe] (extracted so the grace-window and
 * ring-buffer rules are unit-testable without DataStore). Returns null when the status didn't
 * actually change. A completed offline episode is committed to the ring buffer once it has
 * outlasted [graceMs] (the production call passes zero, so every episode is recorded); a
 * recovery to [ConnStatus.ONLINE] adds a bracketing row when the episode was committed.
 * [maxEntries] caps the ring buffer.
 */
internal fun commitLogState(
    prevStatus: ConnStatus?,
    status: ConnStatus,
    now: Long,
    pending: ConnLogEntry?,
    entries: List<ConnLogEntry>,
    maxEntries: Int,
    graceMs: Long,
    activeSource: String? = null,
    transport: NetTransport? = null
): LogTransition? {
    if (prevStatus == null) {
        return if (status == ConnStatus.OFFLINE) {
            LogTransition(
                entries = entries,
                nextPending = ConnLogEntry(now, status, null, activeSource, transport),
                persistLog = false
            )
        } else null
    }
    if (status == prevStatus) return null
    var newEntries = entries
    var dirty = false
    val episode = pending
    if (episode != null && episode.status != ConnStatus.ONLINE) {
        val durSec = (now - episode.atMillis) / 1000
        if (durSec * 1000 >= graceMs) {
            newEntries = (newEntries + episode.copy(durationSec = durSec)).takeLast(maxEntries)
            dirty = true
        }
    }
    val nextPending = if (status == ConnStatus.ONLINE) null else ConnLogEntry(now, status, null, activeSource, transport)
    if (status == ConnStatus.ONLINE && dirty) {
        newEntries = (newEntries + ConnLogEntry(now, ConnStatus.ONLINE, null, transport = transport)).takeLast(maxEntries)
    }
    return LogTransition(
        entries = newEntries,
        nextPending = nextPending,
        persistLog = dirty
    )
}
