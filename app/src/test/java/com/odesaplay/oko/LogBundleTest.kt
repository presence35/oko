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
        zones: Int? = 0,
        cpu: Long? = null
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
        processCpuMs = cpu,
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

    /** 250ms of CPU per 25s wall = 1% of one core. */
    @Test
    fun `cpu is summarised as a share of one core`() {
        val summary = LogBundle.summarisePower(
            listOf(
                sample(0L, cpu = 1_000L),
                sample(25_000L, cpu = 1_250L),
                sample(50_000L, cpu = 1_500L)
            )
        )
        assertEquals(2, summary.getInt("cpuIntervals"))
        assertEquals(1.0, summary.getDouble("cpuPctMean"), 0.001)
        assertEquals(1.0, summary.getDouble("cpuPctMax"), 0.001)
    }

    /** A restart resets the counter; that pair must not read as a huge negative rate. */
    @Test
    fun `a cpu pair spanning a restart is skipped`() {
        val summary = LogBundle.summarisePower(
            listOf(
                sample(0L, cpu = 500_000L, start = 1_000L),
                sample(25_000L, cpu = 10L, start = 2_000L),
                sample(50_000L, cpu = 260L, start = 2_000L)
            )
        )
        // Only the second pair (250ms / 25s) is counted.
        assertEquals(1, summary.getInt("cpuIntervals"))
        assertEquals(1.0, summary.getDouble("cpuPctMean"), 0.001)
    }

    @Test
    fun `no cpu field means cannot tell, not zero`() {
        val summary = LogBundle.summarisePower(
            listOf(sample(0L, cpu = null), sample(25_000L, cpu = null))
        )
        assertTrue(summary.isNull("cpuPctMean"))
        assertTrue(summary.isNull("cpuPctMax"))
        assertTrue(summary.isNull("cpuIntervals"))
    }

    private fun exit(
        atMillis: Long,
        reason: Int = 3,
        reasonName: String = "REASON_LOW_MEMORY",
        importance: Int = 125
    ) = ProcessExit(
        timestampMs = atMillis,
        elapsedRealtimeMs = atMillis,
        reason = reason,
        reasonName = reasonName,
        description = null,
        status = 0,
        importance = importance,
        pssKb = 40_000L,
        rssKb = 90_000L
    )

    /**
     * The whole point of the block: a kill log that says only "3 exits" is useless. Reasons must
     * stay separate — a crash and a low-memory kill are different bugs — and the window bounds
     * must be real, because "when did this start" is what separates an overnight murder from an
     * install.
     */
    @Test
    fun `exit summary counts each reason separately and bounds the window`() {
        val summary = LogBundle.summariseExits(
            listOf(
                exit(1_000L, reason = 4, reasonName = "REASON_CRASH"),
                exit(2_000L),
                exit(9_000L, reason = 4, reasonName = "REASON_CRASH")
            )
        )
        assertEquals(3, summary.getInt("total"))
        assertEquals(2, summary.getJSONObject("byReason").getInt("REASON_CRASH"))
        assertEquals(1, summary.getJSONObject("byReason").getInt("REASON_LOW_MEMORY"))
        assertEquals(1_000L, summary.getLong("oldestTimestampMs"))
        assertEquals(9_000L, summary.getLong("newestTimestampMs"))
    }

    /**
     * The field report was seven deaths "while the screen was on". Foreground is the app's
     * importance, not the screen's, so the two are tracked apart on purpose — a summary that
     * blurred them would hide the one case worth chasing.
     */
    @Test
    fun `exit summary separates deaths while foreground from backgrounded ones`() {
        val summary = LogBundle.summariseExits(
            listOf(
                exit(1_000L, reason = 4, reasonName = "REASON_CRASH", importance = 130),
                exit(2_000L, importance = 125),
                exit(3_000L, importance = 40)
            )
        )
        assertEquals(2, summary.getInt("whileForeground"))
    }

    /**
     * A phone that never killed the app must not report a bogus death: zero entries, and null
     * bounds rather than 0 (epoch) or now, which would read as a real event at a real time.
     */
    @Test
    fun `an empty exit history reports zero and null bounds`() {
        val summary = LogBundle.summariseExits(emptyList())
        assertEquals(0, summary.getInt("total"))
        assertEquals(0, summary.getInt("whileForeground"))
        assertTrue(summary.isNull("oldestTimestampMs"))
        assertTrue(summary.isNull("newestTimestampMs"))
        assertEquals(0, summary.getJSONObject("byReason").length())
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
