package com.presaince.oko

import com.presaince.oko.engine.LatLng
import com.presaince.oko.engine.NormalizedThreat
import com.presaince.oko.engine.ThreatZone
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
        verdicts: Map<String, PluginVerdict> = emptyMap(),
        winnerId: String? = null,
        enabled: Set<ThreatType> = ThreatTypeCatalog.INFO.keys,
        now: Long = 1_000_000L
    ) = DebugLogContext(
        threats = threats,
        focus = LatLng(46.48, 30.73),
        token = "Одеськ",
        enabledTypes = enabled,
        zoneThreats = zoneThreats,
        alertable = alertable,
        verdicts = verdicts,
        winnerId = winnerId,
        night = true,
        sirenOverride = false,
        fastVibrationLevel = 3,
        slowVibrationLevel = 2,
        now = now
    )

    private fun soundVerdict() = PluginVerdict(VerdictKind.SOUND)
    private fun silentVerdict() = PluginVerdict(VerdictKind.SILENT)
    private fun suppressVerdict(reason: PolicyReason) = PluginVerdict(VerdictKind.SUPPRESS, reason)

    @Test
    fun `recordZoneFired writes a notified row and seeds the sweep verdict`() {
        DebugLog.clear()
        val now = System.currentTimeMillis()
        DebugLog.recordZoneFired(
            threatId = "t1", threatType = ThreatType.SHAHED, tier = ThreatZone.INNER,
            night = true, sirenOverride = false, vibrationLevel = 3,
            distanceKm = 12.5, locality = "Одеса", now = now
        )
        val recorded = DebugLog.entries.value.last()
        assertEquals(DebugLogKind.ZONE_ENTER, recorded.kind)
        assertEquals(ThreatZone.INNER, recorded.tier)
        assertEquals(true, recorded.notified)
        assertEquals(DebugLogReason.FIRED, recorded.reason)
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val (entries, _) = computeSweep(
            ctx(mapOf("t1" to t), zoneThreats = mapOf("t1" to ThreatZone.INNER),
                alertable = mapOf("t1" to ThreatZone.INNER),
                verdicts = mapOf("t1" to soundVerdict())),
            mapOf("t1" to DebugLog.fingerprintOf(recorded))
        )
        assertTrue(entries.isEmpty())
        DebugLog.clear()
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
    fun `sweep reports coalesced for a new armed threat (fires are recorded at post time)`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val (entries, _) = computeSweep(
            ctx(mapOf("t1" to t), zoneThreats = mapOf("t1" to ThreatZone.INNER),
                alertable = mapOf("t1" to ThreatZone.INNER)),
            emptyMap()
        )
        assertEquals(1, entries.size)
        assertEquals(false, entries.first().notified)
        assertEquals(DebugLogReason.COALESCED, entries.first().reason)
    }

    @Test
    fun `steady state produces no duplicate entries`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val base = ctx(
            mapOf("t1" to t),
            zoneThreats = mapOf("t1" to ThreatZone.INNER),
            alertable = mapOf("t1" to ThreatZone.INNER),
            verdicts = mapOf("t1" to silentVerdict()),
            winnerId = "t1"
        )
        val (first, verdicts) = computeSweep(base, emptyMap())
        assertEquals(1, first.size)
        assertEquals(DebugLogReason.ALREADY_NOTIFIED, first.first().reason)
        // Next tick: same verdict, same fingerprint — no new row.
        val (second, _) = computeSweep(base, verdicts)
        assertEquals(0, second.size)
    }

    @Test
    fun `sound verdict is logged fired and dedups against the post-time row`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val (entries, fp) = computeSweep(
            ctx(mapOf("t1" to t), zoneThreats = mapOf("t1" to ThreatZone.INNER),
                alertable = mapOf("t1" to ThreatZone.INNER),
                verdicts = mapOf("t1" to soundVerdict()), winnerId = "t1"),
            emptyMap()
        )
        assertEquals(1, entries.size)
        assertEquals(true, entries.first().notified)
        assertEquals(DebugLogReason.FIRED, entries.first().reason)
        // Same tick's sweep after the synchronous record: silent.
        val (second, _) = computeSweep(
            ctx(mapOf("t1" to t), zoneThreats = mapOf("t1" to ThreatZone.INNER),
                alertable = mapOf("t1" to ThreatZone.INNER),
                verdicts = mapOf("t1" to soundVerdict()), winnerId = "t1"),
            fp
        )
        assertTrue(second.isEmpty())
    }

    @Test
    fun `suppressed verdicts log their policy reason without notified`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val cases = mapOf(
            PolicyReason.RATE_LIMITED to DebugLogReason.RATE_LIMITED,
            PolicyReason.ONCE_PER_THREAT to DebugLogReason.ONCE_PER_THREAT,
            PolicyReason.ONCE_PER_TYPE to DebugLogReason.ONCE_PER_TYPE
        )
        for ((policy, expected) in cases) {
            val (entries, _) = computeSweep(
                ctx(mapOf("t1" to t), zoneThreats = mapOf("t1" to ThreatZone.INNER),
                    alertable = mapOf("t1" to ThreatZone.INNER),
                    verdicts = mapOf("t1" to suppressVerdict(policy)), winnerId = "t1"),
                emptyMap()
            )
            assertEquals(1, entries.size)
            assertEquals(false, entries.first().notified)
            assertEquals(expected, entries.first().reason)
        }
    }

    @Test
    fun `non-winning steady threat is coalesced`() {
        val a = threat(id = "a", lat = 46.48, lon = 30.73)
        val b = threat(id = "b", lat = 46.49, lon = 30.74)
        val (entries, _) = computeSweep(
            ctx(mapOf("a" to a, "b" to b),
                zoneThreats = mapOf("a" to ThreatZone.INNER, "b" to ThreatZone.INNER),
                alertable = mapOf("a" to ThreatZone.INNER, "b" to ThreatZone.INNER),
                verdicts = mapOf("a" to soundVerdict(), "b" to silentVerdict()), winnerId = "a"),
            emptyMap()
        )
        assertEquals(DebugLogReason.FIRED, entries.first { it.threatId == "a" }.reason)
        assertEquals(DebugLogReason.COALESCED, entries.first { it.threatId == "b" }.reason)
    }

    @Test
    fun `tier escalation from yellow to red logs a transition entry`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val yellow = ctx(
            mapOf("t1" to t),
            zoneThreats = mapOf("t1" to ThreatZone.OUTER),
            alertable = mapOf("t1" to ThreatZone.OUTER)
        )
        val (first, verdicts) = computeSweep(yellow, emptyMap())
        assertEquals(ThreatZone.OUTER, first.first().tier)
        val red = yellow.copy(
            zoneThreats = mapOf("t1" to ThreatZone.INNER),
            alertable = mapOf("t1" to ThreatZone.INNER),
            verdicts = mapOf("t1" to soundVerdict()),
            winnerId = "t1"
        )
        val (second, _) = computeSweep(red, verdicts)
        assertEquals(1, second.size)
        assertEquals(ThreatZone.INNER, second.first().tier)
        assertEquals(DebugLogReason.FIRED, second.first().reason)
    }

    @Test
    fun `leaving the region clears verdicts silently`() {
        val t = threat(id = "t1", lat = 46.48, lon = 30.73)
        val base = ctx(
            mapOf("t1" to t),
            zoneThreats = mapOf("t1" to ThreatZone.INNER),
            alertable = mapOf("t1" to ThreatZone.INNER)
        )
        val (_, verdicts) = computeSweep(base, emptyMap())
        // Threat resolves / vanishes: no longer in the candidate map.
        val (entries, nextVerdicts) = computeSweep(base.copy(threats = emptyMap()), verdicts)
        assertTrue(entries.isEmpty())
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

    @Test
    fun `parallel sweep and clear does not throw ConcurrentModificationException`() {
        val t1 = threat(id = "t1", lat = 46.48, lon = 30.73)
        val t2 = threat(id = "t2", lat = 46.49, lon = 30.74)
        val context = ctx(mapOf("t1" to t1, "t2" to t2))

        val threads = (1..16).map { idx ->
            Thread {
                for (i in 0 until 100) {
                    if (idx % 2 == 0) {
                        DebugLog.sweep(context)
                    } else {
                        DebugLog.clear()
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }
}