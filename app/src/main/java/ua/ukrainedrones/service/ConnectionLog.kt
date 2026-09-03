package ua.ukrainedrones

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
import ua.ukrainedrones.service.ServiceState

/** Connection states shown in the status log — mirrors the header pill's two states. */
enum class ConnStatus { ONLINE, OFFLINE, DEGRADED }

/** One logged status change. [durationSec] is the episode length for OFF, null for ONLINE. */
data class ConnLogEntry(
    val atMillis: Long,
    val status: ConnStatus,
    val durationSec: Long?
)

/**
 * Ring buffer of the last [MAX_ENTRIES] connection statuses, persisted to DataStore so the log
 * survives app/service restarts. Fed by [ConnectionSupervisor]'s StateFlow bridge. The currently
 * in-progress offline episode is kept separately (see [currentEpisode]) so the popup can show
 * a live running duration, and is committed to the log the moment the status changes again —
 * every drop is recorded, however brief (the shared grace is zero).
 */
object ConnectionLog {

    private const val MAX_ENTRIES = 50
    private const val LINE_SEP = '\n'

    private val _entries = MutableStateFlow<List<ConnLogEntry>>(emptyList())
    val entries: StateFlow<List<ConnLogEntry>> = _entries.asStateFlow()

    @Volatile private var pending: ConnLogEntry? = null
    @Volatile private var lastStatus: ConnStatus? = null
    @Volatile private var attached = false
    private var appContext: Context? = null
    private val attachScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attachDone = CompletableDeferred<Unit>()

    /**
     * Restore persisted entries + any in-progress episode from DataStore. Call once (from
     * AlertService/MainActivity) before NeptunClient starts its watchdog. Idempotent.
     */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        appContext = context.applicationContext
        // The DataStore reads are dispatched off the calling thread: attach() is invoked from
        // the main thread at app/service startup, and the attachScope runs on IO.
        attachScope.launch {
            val prefs = ServiceState(context.applicationContext)
            val loaded = parse(prefs.connLog().first())
            val since = prefs.connLogPendingSince().first()
            val name = prefs.connLogPendingStatus().first()
            val restored = if (since > 0) {
                ConnLogEntry(since, ConnStatus.entries.firstOrNull { it.name == name } ?: ConnStatus.OFFLINE, null)
            } else null
            pending = restored
            _entries.value = loaded
            attachDone.complete(Unit)
        }
    }

    /** Wait for [attach]'s async restore to finish (before the watchdog's writes race it). */
    suspend fun awaitAttached() = attachDone.await()

    /**
     * Called on every connection state transition. Commits the completed offline
     * episode as soon as the status changes (no grace — every drop counts), bracketing it with
     * a recovery row when it returns online.
     */
    fun observe(status: ConnStatus, now: Long) {
        val prev = lastStatus
        lastStatus = status
        val t = commitLogState(prev, status, now, pending, _entries.value, MAX_ENTRIES, 0L) ?: return
        _entries.value = t.entries
        pending = t.nextPending
        if (t.persistPendingSince >= 0) {
            persistPending(t.persistPendingSince, t.persistPendingStatus)
        }
        if (t.persistLog) persist()
    }

    /** The in-progress offline episode with its running duration, or null when online. */
    fun currentEpisode(now: Long): ConnLogEntry? =
        pending?.let { ConnLogEntry(it.atMillis, it.status, (now - it.atMillis) / 1000) }

    private fun persist() {
        val context = appContext ?: return
        attachScope.launch { ServiceState(context).setConnLog(serialize(_entries.value)) }
    }

    private fun persistPending(since: Long, status: String) {
        val context = appContext ?: return
        attachScope.launch {
            ServiceState(context).setConnLogPendingSince(since)
            ServiceState(context).setConnLogPendingStatus(status)
        }
    }

    private fun serialize(entries: List<ConnLogEntry>): String =
        entries.joinToString(LINE_SEP.toString()) { "${it.atMillis}|${it.status.name}|${it.durationSec ?: ""}" }

    private fun parse(raw: String): List<ConnLogEntry> =
        raw.split(LINE_SEP).mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            val status = ConnStatus.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            val dur = parts[2].toLongOrNull()
            ConnLogEntry(at, status, dur)
        }.takeLast(MAX_ENTRIES)
}

/** Result of one [commitLogState] step: what to persist and what the log becomes. */
internal data class LogTransition(
    val entries: List<ConnLogEntry>,
    val nextPending: ConnLogEntry?,
    /** Pending-episode state to persist; -1 = leave the persisted value untouched. */
    val persistPendingSince: Long,
    val persistPendingStatus: String,
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
    graceMs: Long
): LogTransition? {
    if (prevStatus == null) {
        return if (status == ConnStatus.OFFLINE) {
            LogTransition(
                entries = entries,
                nextPending = ConnLogEntry(now, status, null),
                persistPendingSince = now,
                persistPendingStatus = status.name,
                persistLog = false
            )
        } else null
    }
    if (status == prevStatus) return null
    var newEntries = entries
    var dirty = false
    var clearPending = false
    val episode = pending
    if (episode != null && episode.status != ConnStatus.ONLINE) {
        val durSec = (now - episode.atMillis) / 1000
        if (durSec * 1000 >= graceMs) {
            newEntries = (newEntries + episode.copy(durationSec = durSec)).takeLast(maxEntries)
            dirty = true
        }
        clearPending = true
    }
    val nextPending = if (status == ConnStatus.ONLINE) null else ConnLogEntry(now, status, null)
    if (status == ConnStatus.ONLINE && dirty) {
        newEntries = (newEntries + ConnLogEntry(now, ConnStatus.ONLINE, null)).takeLast(maxEntries)
    }
    return LogTransition(
        entries = newEntries,
        nextPending = nextPending,
        persistPendingSince = when {
            nextPending != null -> now
            clearPending -> 0L
            else -> -1L
        },
        persistPendingStatus = nextPending?.status?.name ?: "",
        persistLog = dirty
    )
}