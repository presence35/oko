package ua.ukrainedrones

import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.ThreatZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogTest {

    private fun entry(
        at: Long,
        kind: DebugLogKind = DebugLogKind.ZONE_ENTER,
        night: Boolean = true,
        siren: Boolean = false,
        vibr: Int? = 3,
        notified: Boolean = true,
        reason: DebugLogReason = DebugLogReason.FIRED,
        threatId: String? = "t1",
        type: ThreatType? = ThreatType.SHAHED,
        tier: ThreatZone? = ThreatZone.INNER,
        dist: Double? = 12.5,
        locality: String? = "Одеса"
    ) = DebugLogEntry(at, kind, night, siren, vibr, notified, reason, threatId, type, tier, dist, locality)

    @Test
    fun `full round trip preserves every field`() {
        val src = listOf(
            entry(1_000, kind = DebugLogKind.OFFICIAL_ON, threatId = null, type = null, tier = null, locality = "Одеська", vibr = 4),
            entry(2_000, kind = DebugLogKind.ZONE_ENTER, tier = ThreatZone.INNER),
            entry(3_000, kind = DebugLogKind.REGION_THREAT, reason = DebugLogReason.OUTSIDE_ZONES, notified = false, tier = null, dist = 90.0, locality = null)
        )
        assertEquals(src, parseDebugLog(serializeDebugLog(src)))
    }

    @Test
    fun `malformed lines are skipped`() {
        val raw = "garbage\n" + serializeDebugLog(listOf(entry(1))) + "\nbroken|line"
        assertEquals(listOf(entry(1)), parseDebugLog(raw))
    }

    @Test
    fun `ring buffer caps at max entries`() {
        val src = (1..600).map { entry(it.toLong()) }
        val parsed = parseDebugLog(serializeDebugLog(src), maxEntries = 500)
        assertEquals(500, parsed.size)
        assertEquals(101L, parsed.first().atMillis)
        assertEquals(600L, parsed.last().atMillis)
    }

    @Test
    fun `entries inside the 24h window are all kept`() {
        val src = (1..150).map { entry(1_000_000L - 150_000L * it) }
        val parsed = parseDebugLog(serializeDebugLog(src), maxEntries = 500)
        assertEquals(150, parsed.size)
    }

    @Test
    fun `empty log round trips to empty`() {
        assertEquals("", serializeDebugLog(emptyList()))
        assertTrue(parseDebugLog("").isEmpty())
    }

    @Test
    fun `prune drops entries at or older than the max age`() {
        val now = 1_000_000L
        val maxAge = DebugLog.AUTO_CLEAR_AGE_MS
        val fresh = entry(now - 1_000)
        val boundary = entry(now - maxAge) // exactly maxAge old: dropped (kept only if strictly younger)
        val expired = entry(now - maxAge - 1)
        assertEquals(
            listOf(fresh),
            pruneDebugEntries(listOf(fresh, boundary, expired), now, maxAge)
        )
    }

    private fun ctx(
        threats: Map<String, NormalizedThreat>,
        zoneThreats: Map<String, ThreatZone> = emptyMap(),
        alertable: Map<String, ThreatZone> = emptyMap(),
        knownZones: Map<String, ThreatZone> = emptyMap(),
        postedId: String? = null,
        enabled: Set<ThreatType> = ThreatTypeCatalog.INFO.keys,
        now: Long = 1_000_000L
    ) = DebugLogContext(
        threats = threats,
        focus = LatLng(46.48, 30.73),
        token = "Одеськ",
        enabledTypes = enabled,
        zoneThreats = zoneThreats,
        alertable = alertable,
        knownZones = knownZones,
        postedId = postedId,
        night = true,
        sirenOverride = false,
        fastVibrationLevel = 3,
        slowVibrationLevel = 2,
        now = now
    )

    @Test
    fun `sweep logs zone entry fired when the posted threat was armed and new`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val (entries, _) = computeSweep(
            ctx(mapOf("t1" to t), zoneThreats = mapOf("t1" to ThreatZone.INNER),
                alertable = mapOf("t1" to ThreatZone.INNER), postedId = "t1"),
            emptyMap()
        )
        assertEquals(1, entries.size)
        val e = entries.first()
        assertEquals(DebugLogKind.ZONE_ENTER, e.kind)
        assertEquals(ThreatZone.INNER, e.tier)
        assertEquals(true, e.notified)
        assertEquals(DebugLogReason.FIRED, e.reason)
    }

    @Test
    fun `sweep reports bell muted when the effective tier is null`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val (entries, _) = computeSweep(
            ctx(mapOf("t1" to t), zoneThreats = mapOf("t1" to ThreatZone.INNER), alertable = emptyMap()),
            emptyMap()
        )
        assertEquals(1, entries.size)
        val e = entries.first()
        assertEquals(false, e.notified)
        assertEquals(DebugLogReason.BELL_MUTED, e.reason)
        assertEquals(ThreatZone.INNER, e.tier)
    }

    @Test
    fun `sweep reports coalesced for a new armed threat that did not win the post`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val (entries, _) = computeSweep(
            ctx(mapOf("t1" to t), zoneThreats = mapOf("t1" to ThreatZone.INNER),
                alertable = mapOf("t1" to ThreatZone.INNER), postedId = "t2"),
            emptyMap()
        )
        assertEquals(1, entries.size)
        assertEquals(false, entries.first().notified)
        assertEquals(DebugLogReason.COALESCED, entries.first().reason)
    }

    @Test
    fun `just-posted siren is fired even though the service marked the id known this tick`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val (entries, _) = computeSweep(
            ctx(mapOf("t1" to t), zoneThreats = mapOf("t1" to ThreatZone.INNER),
                alertable = mapOf("t1" to ThreatZone.INNER),
                knownZones = mapOf("t1" to ThreatZone.INNER),
                postedId = "t1"),
            emptyMap()
        )
        assertEquals(1, entries.size)
        assertEquals(true, entries.first().notified)
        assertEquals(DebugLogReason.FIRED, entries.first().reason)
    }

    @Test
    fun `steady state produces no duplicate entries`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val base = ctx(
            mapOf("t1" to t),
            zoneThreats = mapOf("t1" to ThreatZone.INNER),
            alertable = mapOf("t1" to ThreatZone.INNER),
            postedId = "t1"
        )
        val (first, verdicts) = computeSweep(base, emptyMap())
        assertEquals(1, first.size)
        // Next tick: same threat, already known (dedup) — no new row, FIRED/ALREADY share "SIREN".
        val (second, _) = computeSweep(base.copy(knownZones = mapOf("t1" to ThreatZone.INNER), postedId = null), verdicts)
        assertEquals(0, second.size)
    }

    @Test
    fun `tier escalation from yellow to red logs a new entry`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val yellow = ctx(
            mapOf("t1" to t),
            zoneThreats = mapOf("t1" to ThreatZone.OUTER),
            alertable = mapOf("t1" to ThreatZone.OUTER),
            postedId = "t1"
        )
        val (first, verdicts) = computeSweep(yellow, emptyMap())
        assertEquals(ThreatZone.OUTER, first.first().tier)
        val red = yellow.copy(
            zoneThreats = mapOf("t1" to ThreatZone.INNER),
            alertable = mapOf("t1" to ThreatZone.INNER),
            knownZones = mapOf("t1" to ThreatZone.OUTER),
            postedId = "t1"
        )
        val (second, _) = computeSweep(red, verdicts)
        assertEquals(1, second.size)
        assertEquals(ThreatZone.INNER, second.first().tier)
        assertEquals(DebugLogReason.FIRED, second.first().reason)
    }

    @Test
    fun `leaving the region logs an exit`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val base = ctx(
            mapOf("t1" to t),
            zoneThreats = mapOf("t1" to ThreatZone.INNER),
            alertable = mapOf("t1" to ThreatZone.INNER),
            postedId = "t1"
        )
        val (_, verdicts) = computeSweep(base, emptyMap())
        // Threat resolves / vanishes: no longer in the candidate map.
        val (exits, nextVerdicts) = computeSweep(base.copy(threats = emptyMap()), verdicts)
        assertEquals(1, exits.size)
        assertEquals(DebugLogKind.ZONE_EXIT, exits.first().kind)
        assertEquals(DebugLogReason.LEFT, exits.first().reason)
        assertTrue(nextVerdicts.isEmpty())
    }

    @Test
    fun `region sweep logs stale and type-off threats with their why`() {
        val stale = threat(id = "stale", lat = 46.48, lon = 30.73, updatedAtMillis = 0)
        val typeOff = threat(id = "off", type = ThreatType.SHAHED, lat = 46.48, lon = 30.73)
        val (entries, _) = computeSweep(
            ctx(mapOf("stale" to stale, "off" to typeOff), enabled = emptySet()),
            emptyMap()
        )
        val staleEntry = entries.first { it.threatId == "stale" }
        assertEquals(DebugLogReason.STALE, staleEntry.reason)
        assertEquals(DebugLogKind.REGION_THREAT, staleEntry.kind)
        val offEntry = entries.first { it.threatId == "off" }
        assertEquals(DebugLogReason.TYPE_OFF, offEntry.reason)
    }
}