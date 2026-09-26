package com.odesaplay.oko.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Authoritative brain interface for the threat monitoring engine.
 * Consumers read authoritative state via StateFlow.
 */
interface MonitorCore {
    val threats: StateFlow<List<NormalizedThreat>>
    val alerts: StateFlow<List<OblastAlert>>
    val isInformationStale: StateFlow<Boolean>
    val lastUpdateEpochMs: StateFlow<Long>

    fun onBaselineSyncRequired()
    fun onStreamFrameReceived()
    fun onNetworkDisconnected(reason: String)
    fun onNetworkReconnected()

    fun updateThreats(threats: List<NormalizedThreat>)
    fun upsertThreat(threat: NormalizedThreat)
    fun removeThreat(threatId: String)
    fun updateAlerts(alerts: List<OblastAlert>)

    fun markUserShot(id: String)
    fun wasUserShotRecently(id: String): Boolean
}
