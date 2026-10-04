package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.odesaplay.oko.engine.LatLng
import com.odesaplay.oko.engine.NormalizedThreat
import com.odesaplay.oko.engine.ThreatEvaluationResult
import com.odesaplay.oko.engine.ThreatProps
import com.odesaplay.oko.engine.ThreatZone
import com.odesaplay.oko.engine.ZoneParams

class ThreatStagingTest {

    private val fast = ThreatProps(
        isFast = true, reachKm = 1500.0, alwaysInnerWithinReach = false,
        staleAfterMs = 180_000, ghostCapMs = 900_000,
        nominalSpeedMps = null, horizonSec = 180.0, maxGhostMeters = 30_000.0,
        maxPlausibleSpeedMps = 500.0
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
        assertEquals(ThreatZone.OUTER, out.eval.zoneThreats["s1"])
        assertTrue(out.eval.threatsInner.isEmpty())
        assertEquals(listOf(t), out.eval.threatsOuter)
        assertEquals(ThreatZone.OUTER, out.eval.activeZone)
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
        assertEquals(ThreatZone.INNER, out.eval.zoneThreats["f1"])
        assertEquals(listOf(t), out.eval.threatsInner)
    }

    @Test
    fun `an out-of-range inbound track is never staged`() {
        val t = threat("s2", "shahed")
        val out = stageInbound(ThreatEvaluationResult(), listOf(t), focus, params, { propsFor(it) }, now = 0L)
        assertTrue(out.eval.zoneThreats.isEmpty())
        assertNull(out.eval.activeZone)
        assertTrue(out.inboundIds.isEmpty())
    }

    @Test
    fun `the staged ids are published so no consumer re-derives the verdict`() {
        // The popup card used to run its own isInbound over the selected track — a second,
        // ungated evaluation that could disagree with the tier the service rang on.
        val t = threat("s1", "shahed")
        val eval = ThreatEvaluationResult(
            zoneThreats = mapOf("s1" to ThreatZone.INNER),
            activeZone = ThreatZone.INNER
        )
        val out = stageInbound(eval, listOf(t), focus, params, { propsFor(it) }, now = 0L)
        assertEquals(setOf("s1"), out.inboundIds)
        assertEquals(ThreatZone.OUTER, out.eval.zoneThreats["s1"])
    }

    @Test
    fun `a track the engine did not zone is never published as staged`() {
        val t = threat("s3", "shahed")
        val out = stageInbound(
            ThreatEvaluationResult(zoneThreats = mapOf("other" to ThreatZone.INNER)),
            listOf(t), focus, params, { propsFor(it) }, now = 0L
        )
        assertTrue(out.inboundIds.isEmpty())
    }
}
