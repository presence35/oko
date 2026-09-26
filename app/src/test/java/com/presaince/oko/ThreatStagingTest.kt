package com.presaince.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.presaince.oko.engine.LatLng
import com.presaince.oko.engine.NormalizedThreat
import com.presaince.oko.engine.ThreatEvaluationResult
import com.presaince.oko.engine.ThreatProps
import com.presaince.oko.engine.ThreatZone
import com.presaince.oko.engine.ZoneParams

class ThreatStagingTest {

    private val fast = ThreatProps(
        isFast = true, reachKm = 1500.0, alwaysInnerWithinReach = false,
        staleAfterMs = 180_000, ghostCapMs = 900_000,
        nominalSpeedMps = null, horizonSec = 180.0, maxGhostMeters = 30_000.0
    )
    private val slow = fast.copy(isFast = false)
    private val params = ZoneParams(slowRedKm = 20, slowYellowKm = 50, fastRedMin = 5, fastYellowMin = 20)
    private val focus = LatLng(46.3, 30.65)
    private val dest = LatLng(46.3, 30.65)

    private fun threat(id: String, type: String) = NormalizedThreat(
        id = id, type = type, title = "t", region = null, district = null, locality = null,
        lat = 46.3, lon = 30.65, heading = null, bearingDeg = 0.0, status = "active",
        advisory = false, areaOnly = false, confirmations = 1, reliability = "high", count = 1,
        explanationShort = null, speedKmh = null, uncertaintyKm = null, positionQuality = "approx",
        confirmedAtMillis = 0L, updatedAtMillis = 0L, trail = emptyList(), destination = dest
    )

    private fun propsFor(type: String) = if (type == "cruise") fast else slow

    @Test
    fun `a slow inbound track in the red zone is shown approaching instead`() {
        val t = threat("s1", "shahed")
        val eval = ThreatEvaluationResult(
            threatsInner = listOf(t),
            zoneThreats = mapOf("s1" to ThreatZone.INNER),
            activeZone = ThreatZone.INNER
        )
        val out = stageInbound(eval, listOf(t), focus, params, { propsFor(it) }, now = 0L)
        assertEquals(ThreatZone.OUTER, out.zoneThreats["s1"])
        assertTrue(out.threatsInner.isEmpty())
        assertEquals(listOf(t), out.threatsOuter)
        assertEquals(ThreatZone.OUTER, out.activeZone)
    }

    @Test
    fun `a fast inbound track stays red`() {
        val t = threat("f1", "cruise")
        val eval = ThreatEvaluationResult(
            threatsInner = listOf(t),
            zoneThreats = mapOf("f1" to ThreatZone.INNER),
            activeZone = ThreatZone.INNER
        )
        val out = stageInbound(eval, listOf(t), focus, params, { propsFor(it) }, now = 0L)
        assertEquals(ThreatZone.INNER, out.zoneThreats["f1"])
        assertEquals(listOf(t), out.threatsInner)
    }

    @Test
    fun `an out-of-range inbound track is never staged`() {
        val t = threat("s2", "shahed")
        val out = stageInbound(ThreatEvaluationResult(), listOf(t), focus, params, { propsFor(it) }, now = 0L)
        assertTrue(out.zoneThreats.isEmpty())
        assertNull(out.activeZone)
    }
}
