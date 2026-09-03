package ua.ukrainedrones

import ua.ukrainedrones.connection.ConnectionState
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.ZoneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSnapshotTest {

    private val params = ZoneParams(slowRedKm = 20, slowYellowKm = 50, fastRedMin = 5, fastYellowMin = 20)
    private val allTypes = ThreatTypeCatalog.INFO.keys
    private val now = 1_700_000_000_000L
    private val focus = LatLng(46.48, 30.73) // Odesa

    private fun state(vararg threats: NormalizedThreat) =
        threats.associateBy { it.id }

    @Test
    fun `empty state yields zero threats and no zone`() {
        val snap = computeWidgetSnapshot(ConnectionState.Disconnected, state(), emptyList(), focus, "odesa", params, allTypes, now)
        assertEquals(0, snap.threatCount)
        assertNull(snap.activeZone)
        assertNull(snap.nearestKm)
        assertFalse(snap.officialAlert)
    }

    @Test
    fun `counts map-enabled non-stale non-resolved threats`() {
        val s = state(
            threat(id = "a", updatedAtMillis = now),
            threat(id = "b", status = "resolved", updatedAtMillis = now),
            threat(id = "c", updatedAtMillis = now - 301_000L),
            threat(id = "d", areaOnly = true, updatedAtMillis = now),
            threat(id = "e", type = ThreatType.RECON, updatedAtMillis = now)
        )
        val snap = computeWidgetSnapshot(ConnectionState.Disconnected, s, emptyList(), focus, "Одеськ", params, allTypes, now)
        assertEquals(3, snap.threatCount)
        assertEquals(2, snap.typeCounts[ThreatType.SHAHED])
        assertEquals(1, snap.typeCounts[ThreatType.RECON])
    }

    @Test
    fun `typeCounts aggregates same-type threats`() {
        val s = state(
            threat(id = "a1", updatedAtMillis = now),
            threat(id = "a2", updatedAtMillis = now),
            threat(id = "a3", type = ThreatType.CRUISE_MISSILE, updatedAtMillis = now)
        )
        val snap = computeWidgetSnapshot(ConnectionState.Disconnected, s, emptyList(), focus, "Одеськ", params, allTypes, now)
        assertEquals(2, snap.typeCounts[ThreatType.SHAHED])
        assertEquals(1, snap.typeCounts[ThreatType.CRUISE_MISSILE])
        assertEquals(3, snap.threatCount)
    }

    @Test
    fun `nearest distance is the closest live threat to the focus`() {
        val s = state(
            threat(id = "near", lat = 46.48, lon = 30.80, updatedAtMillis = now),
            threat(id = "far", lat = 47.0, lon = 31.0, updatedAtMillis = now)
        )
        val snap = computeWidgetSnapshot(ConnectionState.Disconnected, s, emptyList(), focus, "odesa", params, allTypes, now)
        assertTrue(snap.nearestKm!! < 10.0)
        assertTrue(snap.nearestKm!! > 0.0)
    }

    @Test
    fun `inner threat yields inner zone`() {
        val s = state(threat(id = "a", lat = 46.49, lon = 30.74, updatedAtMillis = now))
        val snap = computeWidgetSnapshot(ConnectionState.Disconnected, s, emptyList(), focus, "odesa", params, allTypes, now)
        assertEquals(ThreatZone.INNER, snap.activeZone)
    }

    @Test
    fun `official alert matches the focus oblast token`() {
        val alert = OblastAlert(key = "k", name = "Одеська область", oblast = "Одеська", since = "x")
        assertTrue(computeWidgetSnapshot(ConnectionState.Disconnected, state(), listOf(alert), focus, "Одеськ", params, allTypes, now).officialAlert)
        assertFalse(computeWidgetSnapshot(ConnectionState.Disconnected, state(), listOf(alert), focus, "Київськ", params, allTypes, now).officialAlert)
    }

    @Test
    fun `source flag reflects connectivity`() {
        val online = computeWidgetSnapshot(ConnectionState.Connected(generation = 1, openedAtMs = now, lastFrameAtMs = now), state(), emptyList(), focus, null, params, allTypes, now)
        assertTrue(online.sourceOnline)

        val offline = computeWidgetSnapshot(ConnectionState.Disconnected, state(), emptyList(), focus, null, params, allTypes, now)
        assertFalse(offline.sourceOnline)
    }

    @Test
    fun `primary threat is the nearest live threat`() {
        val s = state(
            threat(id = "near", lat = 46.48, lon = 30.80, updatedAtMillis = now),
            threat(id = "far", lat = 47.0, lon = 31.0, updatedAtMillis = now)
        )
        val snap = computeWidgetSnapshot(ConnectionState.Disconnected, s, emptyList(), focus, "odesa", params, allTypes, now)
        assertEquals("near", snap.primaryThreat?.id)
        assertEquals(ThreatType.SHAHED, snap.primaryThreat?.type)
    }

    @Test
    fun `primary threat is null when nothing is live`() {
        val snap = computeWidgetSnapshot(ConnectionState.Disconnected, state(), emptyList(), focus, "odesa", params, allTypes, now)
        assertNull(snap.primaryThreat)
    }
}
