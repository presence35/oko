package com.odesaplay.oko

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoConstantsTest {

    @Test
    fun `odesa centre is inside Ukraine`() {
        assertTrue(isInsideUkraine(ODESA_LAT, ODESA_LON))
    }

    @Test
    fun `kyiv is inside Ukraine`() {
        assertTrue(isInsideUkraine(50.45, 30.52))
    }

    @Test
    fun `points beyond the wide bounds are outside Ukraine`() {
        assertFalse(isInsideUkraine(52.52, 13.40)) // Berlin — west of the wide lon bound
        assertFalse(isInsideUkraine(55.75, 37.62)) // Moscow — north of the wide lat bound
    }

    @Test
    fun `non-finite coordinates are rejected`() {
        assertFalse(isInsideUkraine(Double.NaN, 30.0))
        assertFalse(isInsideUkraine(50.0, Double.NaN))
        assertFalse(isInsideUkraine(Double.POSITIVE_INFINITY, 30.0))
        assertFalse(isInsideUkraine(50.0, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `tight bounds sit inside the wide bounds`() {
        assertTrue(UA_TIGHT_MIN_LAT >= UA_WIDE_MIN_LAT)
        assertTrue(UA_TIGHT_MAX_LAT <= UA_WIDE_MAX_LAT)
        assertTrue(UA_TIGHT_MIN_LON >= UA_WIDE_MIN_LON)
        assertTrue(UA_TIGHT_MAX_LON <= UA_WIDE_MAX_LON)
    }

    @Test
    fun `pan bounds contain the wide bounds`() {
        assertTrue(UA_PAN_MIN_LAT <= UA_WIDE_MIN_LAT)
        assertTrue(UA_PAN_MAX_LAT >= UA_WIDE_MAX_LAT)
        assertTrue(UA_PAN_MIN_LON <= UA_WIDE_MIN_LON)
        assertTrue(UA_PAN_MAX_LON >= UA_WIDE_MAX_LON)
    }

    @Test
    fun `odesa centre is inside the tight bounds`() {
        assertTrue(ODESA_LAT in UA_TIGHT_MIN_LAT..UA_TIGHT_MAX_LAT)
        assertTrue(ODESA_LON in UA_TIGHT_MIN_LON..UA_TIGHT_MAX_LON)
    }
}
