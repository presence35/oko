package com.odesaplay.oko.engine

import com.odesaplay.oko.isNationalMig

enum class SpeedSource { RECORDED, TYPICAL }

class SpeedCache {
    private data class Fix(val t: Long, val lat: Double, val lon: Double)
    private val fixes = HashMap<String, ArrayDeque<Fix>>()
    private var newestT = 0L
    private var lastPruneT = Long.MIN_VALUE

    @Synchronized
    fun record(id: String, t: Long, lat: Double, lon: Double) {
        newestT = maxOf(newestT, t)
        if (lastPruneT == Long.MIN_VALUE || t - lastPruneT >= PRUNE_INTERVAL_MS) {
            pruneExpired()
            lastPruneT = t
        }
        val q = fixes.getOrPut(id) { ArrayDeque() }
        val last = q.lastOrNull()
        if (last != null && last.t == t) return
        q.addLast(Fix(t, lat, lon))
        while (q.size > 4) q.removeFirst()
        if (fixes.size > MAX_TRACKS) {
            fixes.entries.minByOrNull { it.value.lastOrNull()?.t ?: 0L }?.let { fixes.remove(it.key) }
        }
    }

    /** Drops tracks whose newest fix is older than [SPEED_TTL_MS]. The cutoff is measured against
     *  the newest timestamp ever seen, never the incoming fix, so a backdated server `updatedAt`
     *  cannot erase a live track. Throttled to once per [PRUNE_INTERVAL_MS]; the caller holds the
     *  lock (only [record] calls this). */
    private fun pruneExpired() {
        val cutoff = newestT - SPEED_TTL_MS
        val it = fixes.entries.iterator()
        while (it.hasNext()) {
            if ((it.next().value.lastOrNull()?.t ?: Long.MIN_VALUE) < cutoff) it.remove()
        }
    }

    @Synchronized
    fun clear() {
        fixes.clear()
        newestT = 0L
        lastPruneT = Long.MIN_VALUE
    }

    fun estimate(id: String, t: NormalizedThreat, props: ThreatProps): Double? =
        estimateWithSource(id, t, props)?.first

    @Synchronized
    fun estimateWithSource(id: String, t: NormalizedThreat, props: ThreatProps): Pair<Double, SpeedSource>? {
        // The national MiG pin is a country centroid, not a position — no speed or ETA may be
        // derived from it (a "MiG in N min" from centroid-distance ÷ nominal cruise speed is
        // fabricated precision; the missile hasn't launched). Null hides the speed/ETA pills.
        if (isNationalMig(t)) return null
        val serverSpeed = t.speedKmh
        // Trust the server field only inside a sane envelope; anything beyond it is a corrupt
        // value that would fabricate a near-zero ETA, so fall through to trail/nominal speed.
        if (serverSpeed != null && serverSpeed in 5.0..20_000.0) {
            return serverSpeed / 3.6 to SpeedSource.RECORDED
        }
        val q = fixes[id]
        if (q != null && q.size >= 2) {
            val a = q.first()
            val b = q.last()
            val dt = (b.t - a.t) / 1000.0
            if (dt in 2.0..600.0) {
                val v = distanceHaversine(a.lat, a.lon, b.lat, b.lon) / dt
                if (v >= 5.0) return v to SpeedSource.RECORDED
            }
        }
        if (t.trail.size >= 2) {
            val a = t.trail[t.trail.size - 2]
            val b = t.trail[t.trail.size - 1]
            if (a.tMillis != null && b.tMillis != null) {
                val dt = (b.tMillis - a.tMillis) / 1000.0
                if (dt in 5.0..600.0) {
                    val v = distanceHaversine(a.lat, a.lon, b.lat, b.lon) / dt
                    if (v >= 5.0) return v to SpeedSource.RECORDED
                }
            }
        }
        return props.nominalSpeedMps?.let { it to SpeedSource.TYPICAL }
    }

    @Synchronized
    fun measuredHeading(id: String): Double? {
        val q = fixes[id] ?: return null
        if (q.size < 2) return null
        val a = q.first()
        val b = q.last()
        val dt = (b.t - a.t) / 1000.0
        if (dt !in 2.0..600.0) return null
        if (distanceHaversine(a.lat, a.lon, b.lat, b.lon) < HEADING_MIN_METERS) return null
        return bearingHaversine(a.lat, a.lon, b.lat, b.lon)
    }

    companion object {
        private const val HEADING_MIN_METERS = 100.0
        private const val MAX_TRACKS = 500
        private const val SPEED_TTL_MS = 30 * 60 * 1000L
        private const val PRUNE_INTERVAL_MS = 60_000L
    }
}
