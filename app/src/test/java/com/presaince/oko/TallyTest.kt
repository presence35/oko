package com.presaince.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TallyTest {

    private fun encode(records: List<FlourishRecord>): Array<Any?> {
        return arrayOf(
            records.map { it.lat }.toDoubleArray(),
            records.map { it.lon }.toDoubleArray(),
            records.map { it.type.name }.toTypedArray(),
            records.map { it.region }.toTypedArray()
        )
    }

    @Test
    fun `parseFlourishRecords round-trips baked tap extras`() {
        val records = listOf(
            FlourishRecord(46.48, 30.73, ThreatType.SHAHED, "Одеська область"),
            FlourishRecord(50.45, 30.52, ThreatType.KAB, null)
        )
        val e = encode(records)
        @Suppress("UNCHECKED_CAST")
        val parsed = parseFlourishRecords(
            e[0] as DoubleArray, e[1] as DoubleArray,
            e[2] as Array<String>, e[3] as Array<out String?>
        )
        assertEquals(records, parsed)
    }

    @Test
    fun `parseFlourishRecords drops garbage coords and unknown types`() {
        val parsed = parseFlourishRecords(
            doubleArrayOf(46.48, 999.0, 47.0, Double.NaN),
            doubleArrayOf(30.73, 30.0, 31.0, 32.0),
            arrayOf("SHAHED", "SHAHED", "NOPE", "KAB"),
            arrayOf("A", "B", "C", "D")
        )
        assertEquals(
            listOf(FlourishRecord(46.48, 30.73, ThreatType.SHAHED, "A")),
            parsed
        )
    }

    @Test
    fun `parseFlourishRecords stops at the shortest array`() {
        val parsed = parseFlourishRecords(
            doubleArrayOf(46.48, 47.0),
            doubleArrayOf(30.73),
            arrayOf("SHAHED", "KAB"),
            null
        )
        assertEquals(listOf(FlourishRecord(46.48, 30.73, ThreatType.SHAHED, null)), parsed)
    }

    @Test
    fun `parseFlourishRecords empty in means empty out`() {
        assertTrue(parseFlourishRecords(doubleArrayOf(), doubleArrayOf(), arrayOf(), null).isEmpty())
    }

    @Test
    fun `reset routing clears only the watched store`() {
        assertTrue(flourishResetTally(null))
        assertTrue(flourishResetEpisode(null))
        assertTrue(flourishResetTally(NeutralizedTally.SOURCE_TALLY))
        assertFalse(flourishResetEpisode(NeutralizedTally.SOURCE_TALLY))
        assertFalse(flourishResetTally(NeutralizedTally.SOURCE_EPISODE))
        assertTrue(flourishResetEpisode(NeutralizedTally.SOURCE_EPISODE))
        assertFalse(flourishResetTally(NeutralizedTally.SOURCE_ALLCLEAR))
        assertTrue(flourishResetEpisode(NeutralizedTally.SOURCE_ALLCLEAR))
    }
}
