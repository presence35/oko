package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LogBundleTest {

    private fun sample(
        atMillis: Long,
        start: Long? = 1_000L,
        battery: Int? = 50,
        interactive: Boolean? = false,
        zones: Int? = 0
    ) = BatterySample(
        atMillis = atMillis,
        batteryPct = battery,
        charging = false,
        temperatureTenthsC = 250,
        powerSave = false,
        deviceIdle = false,
        interactive = interactive,
        thermal = 0,
        gpsEnabled = true,
        batteryUnoptimized = false,
        processStartMonoMs = start,
        feedThreats = 10,
        activeZones = zones
    )

    /** A new process generation inside a gap is a kill/restart; the gap bounds the blind window. */
    @Test
    fun `power summary counts process generations and restarts`() {
        val summary = LogBundle.summarisePower(
            listOf(
                sample(0L, start = 100L),
                sample(25_000L, start = 100L),
                sample(50_000L, start = 100L),
                sample(90_000L, start = 700L),
                sample(115_000L, start = 700L)
            )
        )
        assertEquals(2, summary.getInt("processGenerations"))
        assertEquals(1, summary.getInt("restartsObserved"))
        assertEquals(40, summary.getInt("maxBlindWindowSec"))
        assertEquals(5, summary.getInt("samples"))
    }

    /** No restart means a clean run: one generation, zero observed restarts, null blind window. */
    @Test
    fun `power summary reports a single generation when nothing was killed`() {
        val summary = LogBundle.summarisePower(
            listOf(sample(0L, start = 100L), sample(25_000L, start = 100L))
        )
        assertEquals(1, summary.getInt("processGenerations"))
        assertEquals(0, summary.getInt("restartsObserved"))
        assertEquals(0, summary.getInt("maxBlindWindowSec"))
    }

    /**
     * A gap with no generation change is the service being paused (Doze, an OS kill of the
     * service inside a live process), NOT a process restart — the two must not be conflated.
     */
    @Test
    fun `a service pause without a new process is not counted as a restart`() {
        val summary = LogBundle.summarisePower(
            listOf(
                sample(0L, start = 100L),
                sample(25_000L, start = 100L),
                sample(700_000L, start = 100L)
            )
        )
        assertEquals(1, summary.getInt("processGenerations"))
        assertEquals(0, summary.getInt("restartsObserved"))
        assertEquals(675, summary.getInt("longestGapSec"))
    }

    /**
     * Samples written before this field existed carry null. "Cannot tell" must be null, not 0 —
     * a zero would read as "definitely never restarted".
     */
    @Test
    fun `samples predating the field report null rather than zero generations`() {
        val summary = LogBundle.summarisePower(
            listOf(sample(0L, start = null), sample(25_000L, start = null))
        )
        assertTrue(summary.isNull("processGenerations"))
        assertTrue(summary.isNull("restartsObserved"))
    }

    /** The first sample that DOES carry the field must not look like a restart. */
    @Test
    fun `the first sample with the field does not register as a restart`() {
        val summary = LogBundle.summarisePower(
            listOf(
                sample(0L, start = null),
                sample(25_000L, start = null),
                sample(50_000L, start = 900L)
            )
        )
        assertEquals(1, summary.getInt("processGenerations"))
        assertEquals(0, summary.getInt("restartsObserved"))
    }

    @Test
    fun `an empty timeline says zero samples rather than throwing`() {
        val summary = LogBundle.summarisePower(emptyList())
        assertEquals(0, summary.getInt("samples"))
    }

    @Test
    fun `summary aggregates the drain-shape figures`() {
        val summary = LogBundle.summarisePower(
            listOf(
                sample(0L, battery = 80, interactive = true, zones = 3),
                sample(25_000L, battery = 76, interactive = false, zones = 0)
            )
        )
        assertEquals(50, summary.getInt("screenOnPct"))
        assertEquals(50, summary.getInt("zonesActivePct"))
        assertEquals(76, summary.getInt("batteryPctMin"))
        assertEquals(80, summary.getInt("batteryPctMax"))
    }


    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")

    /** 2026-10-04 14:32:22 Kyiv. */
    private val at = ZonedDateTime.of(2026, 10, 4, 14, 32, 22, 0, kyiv).toInstant().toEpochMilli()

    @Test
    fun `file name carries device, build and a local timestamp`() {
        assertEquals(
            "samsung_SM-A505FN_742_20261004-143222.json",
            LogBundle.bundleFileName("samsung", "SM-A505FN", 742, at, kyiv)
        )
    }

    /**
     * The server rebuilds this name from a header and keeps only [A-Za-z0-9._-], so anything
     * outside that set would be silently truncated and two devices could collide.
     */
    @Test
    fun `file name is safe for the endpoint's sanitiser`() {
        val name = LogBundle.bundleFileName("Xiaomi/Redmi", "Redmi Note 12 (5G)", 743, at, kyiv)
        assertTrue(name.matches(Regex("[A-Za-z0-9._-]+\\.json")))
    }

    @Test
    fun `a brand or model that sanitises to nothing still yields a usable name`() {
        val name = LogBundle.bundleFileName("华为", "", 743, at, kyiv)
        assertEquals("unknown_model_743_20261004-143222.json", name)
    }

    /** Two bundles must never share a name, or the endpoint overwrite returns. */
    @Test
    fun `the stamp distinguishes taps within the same build`() {
        val later = ZonedDateTime.of(2026, 10, 4, 14, 33, 5, 0, kyiv).toInstant().toEpochMilli()
        assertTrue(
            LogBundle.bundleFileName("google", "Pixel 7", 742, at, kyiv) !=
                LogBundle.bundleFileName("google", "Pixel 7", 742, later, kyiv)
        )
    }
}
