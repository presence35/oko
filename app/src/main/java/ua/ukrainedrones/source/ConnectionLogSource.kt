package ua.ukrainedrones.source

import kotlinx.coroutines.flow.StateFlow
import ua.ukrainedrones.connection.ConnEvent
import ua.ukrainedrones.connection.ConnEventKind
import ua.ukrainedrones.connection.ConnRetryState

/**
 * Optional capability of a [Source]: rich reconnect diagnostics (offline milestones, retry
 * countdown, the connection-log card) surfaced through [SourceRegistry] for the Logs sheet.
 * Only the WS transport source implements it today.
 */
interface ConnectionLogSource {
    val connEvents: StateFlow<List<ConnEvent>>
    val retryState: StateFlow<ConnRetryState?>
    fun dismissLogCard()
    fun annotateConnectionLog(kind: ConnEventKind, attempt: Int? = null, delayMs: Long? = null, detail: String? = null)
    /** Mirrors which source owns the alert feed into the per-episode log entry. */
    fun setActiveAlertSource(sourceId: String?)
}