package ua.ukrainedrones.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Production implementation of Ingestion Kinematics Engine.
 *
 * Sits directly on the ingestion boundary between the raw socket decoder and downstream state.
 * Performs dead-reckoning projection, velocity vector derivation, course smoothing,
 * and track expiration so that downstream evaluators and map UI receive clean, normalized targets.
 */
class DeadReckoningEngine(
    private val activeConfidenceThresholdSec: Double = 45.0,
    private val degradedConfidenceThresholdSec: Double = 90.0,
    private val maxTrackTtlSec: Double = 120.0
) {
    private data class InternalTrackState(
        val threat: NormalizedThreat,
        val anchorLat: Double,
        val anchorLon: Double,
        val anchorEpochMs: Long,
        var currentSpeedKmh: Double,
        var currentBearingDeg: Double,
        var baseConfidence: Double
    )

    private val tracks = ConcurrentHashMap<String, InternalTrackState>()

    /**
     * Ingests or updates a threat track from raw network telemetry.
     */
    fun ingestThreat(threat: NormalizedThreat, nowMs: Long = System.currentTimeMillis()) {
        val speed = threat.speedKmh ?: 180.0
        val bearing = threat.bearingDeg ?: threat.heading ?: fallbackCourse(threat.id)
        val anchorTime = threat.updatedAtMillis ?: threat.confirmedAtMillis ?: nowMs

        tracks.compute(threat.id) { _, existing ->
            if (existing == null) {
                InternalTrackState(
                    threat = threat,
                    anchorLat = threat.lat,
                    anchorLon = threat.lon,
                    anchorEpochMs = anchorTime,
                    currentSpeedKmh = speed,
                    currentBearingDeg = bearing,
                    baseConfidence = 0.95
                )
            } else {
                // Invariant: Discard out-of-order stale telemetry packets to prevent clock and position rollback
                if (anchorTime < existing.anchorEpochMs) {
                    return@compute existing
                }
                if (anchorTime == existing.anchorEpochMs) {
                    return@compute existing.copy(threat = threat)
                }

                val dtSec = (anchorTime - existing.anchorEpochMs) / 1000.0
                var updatedSpeed = speed
                var updatedBearing = bearing

                if (dtSec in 1.0..60.0) {
                    val distMeters = GeoUtils.distanceMeters(
                        existing.anchorLat, existing.anchorLon,
                        threat.lat, threat.lon
                    )
                    val calculatedSpeedKmh = (distMeters / dtSec) * 3.6
                    if (calculatedSpeedKmh in 30.0..4500.0) {
                        updatedSpeed = (0.7 * speed) + (0.3 * calculatedSpeedKmh)
                        updatedBearing = GeoUtils.initialBearingDegrees(
                            existing.anchorLat, existing.anchorLon,
                            threat.lat, threat.lon
                        )
                    }
                }

                InternalTrackState(
                    threat = threat,
                    anchorLat = threat.lat,
                    anchorLon = threat.lon,
                    anchorEpochMs = anchorTime,
                    currentSpeedKmh = updatedSpeed,
                    currentBearingDeg = updatedBearing,
                    baseConfidence = 0.95
                )
            }
        }
    }

    fun getActiveTrackIds(): Set<String> = tracks.keys.toSet()

    fun removeThreat(id: String): NormalizedThreat? {
        return tracks.remove(id)?.threat
    }

    /**
     * Performs dead reckoning projection across all active tracks for the current timestamp.
     * Evaluates confidence decay and prunes tracks past TTL threshold.
     */
    fun evaluateSnapshot(nowMs: Long = System.currentTimeMillis()): List<NormalizedThreat> {
        val result = mutableListOf<NormalizedThreat>()
        val iterator = tracks.entries.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val state = entry.value
            val ageSec = ((nowMs - state.anchorEpochMs).coerceAtLeast(0L)) / 1000.0

            // Prune expired tracks past TTL
            if (ageSec > maxTrackTtlSec) {
                iterator.remove()
                continue
            }

            // Calculate confidence decay
            val confidence = when {
                ageSec <= activeConfidenceThresholdSec -> {
                    state.baseConfidence * (1.0 - (ageSec / activeConfidenceThresholdSec) * 0.20)
                }
                ageSec <= degradedConfidenceThresholdSec -> {
                    val progress = (ageSec - activeConfidenceThresholdSec) /
                            (degradedConfidenceThresholdSec - activeConfidenceThresholdSec)
                    state.baseConfidence * 0.80 * (1.0 - progress * 0.50)
                }
                else -> {
                    state.baseConfidence * 0.20
                }
            }.coerceIn(0.0, 1.0)

            // Dead reckoning projection along bearing vector
            val projectedDistanceMeters = (state.currentSpeedKmh / 3.6) * ageSec
            val (projLat, projLon) = GeoUtils.projectCoordinate(
                lat = state.anchorLat,
                lon = state.anchorLon,
                distanceMeters = projectedDistanceMeters,
                bearingDegrees = state.currentBearingDeg
            )

            // Emit updated NormalizedThreat with dead-reckoned position.
            // Invariant: Retain original threat.updatedAtMillis so UI elapsed clocks measure real radar telemetry age.
            val updatedThreat = state.threat.copy(
                lat = projLat,
                lon = projLon,
                bearingDeg = state.currentBearingDeg,
                speedKmh = state.currentSpeedKmh,
                updatedAtMillis = state.threat.updatedAtMillis,
                uncertaintyKm = (state.threat.uncertaintyKm ?: 10.0) + (ageSec * 0.05),
                reliability = if (confidence > 0.6) "high" else if (confidence > 0.3) "medium" else "low"
            )
            result.add(updatedThreat)
        }

        return result
    }

    fun clear() {
        tracks.clear()
    }
}
