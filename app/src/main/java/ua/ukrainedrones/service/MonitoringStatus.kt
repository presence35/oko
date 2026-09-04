package ua.ukrainedrones.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory flag: is the [AlertService] foreground monitor actually running in this process?
 * The UI uses it to surface a full-header "Service OFFLINE" banner whenever monitoring is
 * silently dead while the app is open — the NEPTUN socket alone is not proof that alerting
 * works, since the connection is owned by the app process, not the service.
 */
object MonitoringStatus {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
    }
}