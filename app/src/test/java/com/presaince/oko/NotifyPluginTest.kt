package com.presaince.oko

import com.presaince.oko.engine.ThreatZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyPluginTest {

    private val now = 1_000_000L

    private fun inp(
        id: String,
        tier: ThreatZone?,
        alertTier: ThreatZone? = tier,
        type: ThreatType = ThreatType.SHAHED,
        live: Boolean = true,
        shotGrace: Boolean = false
    ) = PluginInput(id, tier, alertTier, type, live, shotGrace)

    private fun prefs(
        preset: ZonePolicy,
        max: Int = 10,
        window: DigestWindow = DigestWindow.EPISODE,
        perType: Boolean = false
    ) = NotifyPrefs(preset, max, window, perType)

    @Test
    fun `every change sounds entries reentries and escalations, never steady`() {
        val p = NotifyPlugin()
        val pr = prefs(ZonePolicy.EVERY_CHANGE)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.OUTER)), pr, now)["a"]!!.kind)
        assertEquals(VerdictKind.SILENT, p.tick(listOf(inp("a", ThreatZone.OUTER)), pr, now)["a"]!!.kind)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)["a"]!!.kind)
        assertEquals(VerdictKind.SILENT, p.tick(listOf(inp("a", ThreatZone.OUTER)), pr, now)["a"]!!.kind)
        // Out of zones one tick (live), back in: re-entry sounds.
        assertTrue(p.tick(listOf(inp("a", null, live = true)), pr, now).isEmpty())
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.OUTER)), pr, now)["a"]!!.kind)
    }

    @Test
    fun `once per threat suppresses reentry but sounds new episodes`() {
        val p = NotifyPlugin()
        val pr = prefs(ZonePolicy.ONCE_PER_THREAT)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)["a"]!!.kind)
        p.tick(listOf(inp("a", null, live = true)), pr, now)
        val re = p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)["a"]!!
        assertEquals(VerdictKind.SUPPRESS, re.kind)
        assertEquals(PolicyReason.ONCE_PER_THREAT, re.reason)
        // Track dies (stale): episode closes; return is a new episode and sounds.
        p.tick(listOf(inp("a", null, live = false)), pr, now)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)["a"]!!.kind)
    }

    @Test
    fun `floor first red sounds past digest and type gates`() {
        val p = NotifyPlugin()
        val pr = prefs(ZonePolicy.DIGEST, max = 1, window = DigestWindow.EPISODE)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.OUTER)), pr, now)["a"]!!.kind)
        // Bucket full, but first INNER always sounds.
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("b", ThreatZone.INNER)), pr, now)["b"]!!.kind)
        // Second OUTER now overflows.
        val over = p.tick(listOf(inp("c", ThreatZone.OUTER)), pr, now)["c"]!!
        assertEquals(VerdictKind.SUPPRESS, over.kind)
        assertEquals(PolicyReason.RATE_LIMITED, over.reason)
    }

    @Test
    fun `once per type gates on open sounded episodes of the same type`() {
        val p = NotifyPlugin()
        val pr = prefs(ZonePolicy.ONCE_PER_TYPE)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.OUTER)), pr, now)["a"]!!.kind)
        val gated = p.tick(
            listOf(inp("a", ThreatZone.OUTER), inp("b", ThreatZone.OUTER)), pr, now
        )["b"]!!
        assertEquals(VerdictKind.SUPPRESS, gated.kind)
        assertEquals(PolicyReason.ONCE_PER_TYPE, gated.reason)
        // Different type is unaffected.
        assertEquals(
            VerdictKind.SOUND,
            p.tick(
                listOf(
                    inp("a", ThreatZone.OUTER),
                    inp("b", ThreatZone.OUTER),
                    inp("c", ThreatZone.OUTER, type = ThreatType.CRUISE_MISSILE)
                ), pr, now
            )["c"]!!.kind
        )
    }

    @Test
    fun `digest minute window slides`() {
        val p = NotifyPlugin()
        val pr = prefs(ZonePolicy.DIGEST, max = 2, window = DigestWindow.MIN_2)
        val first = p.tick(
            listOf(inp("a", ThreatZone.OUTER), inp("b", ThreatZone.OUTER), inp("c", ThreatZone.OUTER)),
            pr, now
        )
        assertEquals(VerdictKind.SOUND, first["a"]!!.kind)
        assertEquals(VerdictKind.SOUND, first["b"]!!.kind)
        assertEquals(VerdictKind.SUPPRESS, first["c"]!!.kind)
        // 3 minutes later the window slid: room again.
        assertEquals(
            VerdictKind.SOUND,
            p.tick(
                listOf(
                    inp("a", ThreatZone.OUTER), inp("b", ThreatZone.OUTER),
                    inp("c", ThreatZone.OUTER), inp("d", ThreatZone.OUTER)
                ), pr, now + 180_000
            )["d"]!!.kind
        )
    }

    @Test
    fun `digest episode bucket resets when the sky empties`() {
        val p = NotifyPlugin()
        val pr = prefs(ZonePolicy.DIGEST, max = 1, window = DigestWindow.EPISODE)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.OUTER)), pr, now)["a"]!!.kind)
        val over = p.tick(
            listOf(inp("a", ThreatZone.OUTER), inp("b", ThreatZone.OUTER)), pr, now
        )["b"]!!
        assertEquals(VerdictKind.SUPPRESS, over.kind)
        // Everyone dies: buckets reset; the next sitting sounds again.
        p.tick(listOf(inp("a", null, live = false), inp("b", null, live = false)), pr, now)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("c", ThreatZone.OUTER)), pr, now)["c"]!!.kind)
    }

    @Test
    fun `escalation to red always sounds, downgrade stays silent`() {
        val p = NotifyPlugin()
        val pr = prefs(ZonePolicy.ONCE_PER_THREAT)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.OUTER)), pr, now)["a"]!!.kind)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)["a"]!!.kind)
        assertEquals(VerdictKind.SILENT, p.tick(listOf(inp("a", ThreatZone.OUTER)), pr, now)["a"]!!.kind)
        // Re-escalation past the accepted downgrade sounds again.
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)["a"]!!.kind)
    }

    @Test
    fun `shot grace freezes the episode so the respawn never re-sounds`() {
        val p = NotifyPlugin()
        val pr = prefs(ZonePolicy.EVERY_CHANGE)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)["a"]!!.kind)
        // Shot down: track gone, grace holds the episode frozen (no verdict).
        assertTrue(p.tick(listOf(inp("a", null, live = true, shotGrace = true)), pr, now).isEmpty())
        // Same-id respawn reads as steady, even under EVERY_CHANGE.
        assertEquals(VerdictKind.SILENT, p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)["a"]!!.kind)
    }

    @Test
    fun `plain removal closes the episode and the return sounds`() {
        val p = NotifyPlugin()
        val pr = prefs(ZonePolicy.EVERY_CHANGE)
        p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)
        p.tick(listOf(inp("a", null, live = false)), pr, now)
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)["a"]!!.kind)
    }

    @Test
    fun `muted threats get no verdict and arm later as a fresh candidate`() {
        val p = NotifyPlugin()
        val pr = prefs(ZonePolicy.ONCE_PER_THREAT)
        assertTrue(p.tick(listOf(inp("a", ThreatZone.INNER, alertTier = null)), pr, now).isEmpty())
        // Bell armed while still in zones: sounds (never sounded before).
        assertEquals(
            VerdictKind.SOUND,
            p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)["a"]!!.kind
        )
    }

    @Test
    fun `seedKnown restores handled episodes and snapshot round-trips`() {
        val p = NotifyPlugin()
        p.seedKnown(mapOf("a" to ThreatZone.INNER))
        // First tick reads as steady: no re-siren after restart.
        assertEquals(
            VerdictKind.SILENT,
            p.tick(listOf(inp("a", ThreatZone.INNER)), prefs(ZonePolicy.EVERY_CHANGE), now)["a"]!!.kind
        )
        assertEquals(mapOf("a" to ThreatZone.INNER), p.snapshot())
        // Genuinely new id still sounds.
        assertEquals(
            VerdictKind.SOUND,
            p.tick(
                listOf(inp("a", ThreatZone.INNER), inp("b", ThreatZone.OUTER)),
                prefs(ZonePolicy.EVERY_CHANGE), now
            )["b"]!!.kind
        )
    }

    @Test
    fun `reset is a fresh start`() {
        val p = NotifyPlugin()
        val pr = prefs(ZonePolicy.ONCE_PER_THREAT)
        p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)
        p.reset()
        assertEquals(VerdictKind.SOUND, p.tick(listOf(inp("a", ThreatZone.INNER)), pr, now)["a"]!!.kind)
    }

    @Test
    fun `whatIf counts actual and estimated sounds`() {
        fun fired(id: String, type: ThreatType, at: Long) = DebugLogEntry(
            at, DebugLogKind.ZONE_ENTER, false, false, 3, true,
            DebugLogReason.FIRED, id, type, ThreatZone.INNER, 10.0, null
        )
        val rows = listOf(
            fired("a", ThreatType.SHAHED, 1_000L),
            fired("a", ThreatType.SHAHED, 2_000L),
            fired("b", ThreatType.SHAHED, 3_000L),
            fired("c", ThreatType.CRUISE_MISSILE, 4_000L)
        )
        val counts = NotifyPlugin.whatIf(rows, NotifyPrefs())
        assertEquals(4, counts[ZonePolicy.EVERY_CHANGE])
        assertEquals(3, counts[ZonePolicy.ONCE_PER_THREAT])
        assertEquals(2, counts[ZonePolicy.ONCE_PER_TYPE])
    }
}
