package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LogBundleTest {

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
