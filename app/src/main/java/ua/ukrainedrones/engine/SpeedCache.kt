package ua.ukrainedrones.engine

enum class SpeedSource { RECORDED, TYPICAL }

class SpeedCache {
    private data class Fix(val t: Long, val lat: Double, val lon: Double)
    private val fixes = HashMap<String, ArrayDeque<Fix>>()

    @Synchronized
    fun record(id: String, t: Long, lat: Double, lon: Double) {
        val q = fixes.getOrPut(id) { ArrayDeque() }
        val last = q.lastOrNull()
        if (last != null && last.t == t) return
        q.addLast(Fix(t, lat, lon))
        while (q.size > 4) q.removeFirst()
    }

    @Synchronized
    fun clear() {
        fixes.clear()
    }

    fun estimate(id: String, t: NormalizedThreat, props: ThreatProps): Double? =
        estimateWithSource(id, t, props)?.first

    @Synchronized
    fun estimateWithSource(id: String, t: NormalizedThreat, props: ThreatProps): Pair<Double, SpeedSource>? {
        val serverSpeed = t.speedKmh
        if (serverSpeed != null && serverSpeed >= 5.0) {
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
    }
}
