package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminHierarchyTest {

    @Test
    fun `catalog holds all 25 regions`() {
        assertEquals(25, AdminHierarchy.ALL_OBLASTS.size)
    }

    @Test
    fun `cities catalog is populated`() {
        assertTrue(AdminHierarchy.ALL_CITIES.size > 100)
    }

    @Test
    fun `odesa resolves to the odeska oblast`() {
        val odesa = AdminHierarchy.getCity("Одеса")
        assertNotNull(odesa)
        assertEquals("odeska", odesa!!.oblastId)
    }

    @Test
    fun `city lookup matches the other language names`() {
        val ua = AdminHierarchy.getCity("Одеса")!!
        if (ua.nameEn.isNotBlank()) assertEquals(ua.nameUa, AdminHierarchy.getCity(ua.nameEn)?.nameUa)
        if (ua.nameRu.isNotBlank()) assertEquals(ua.nameUa, AdminHierarchy.getCity(ua.nameRu)?.nameUa)
    }

    @Test
    fun `oblast resolves by id and by full name`() {
        assertEquals("odeska", AdminHierarchy.getOblast("odeska")?.id)
        assertEquals("odeska", AdminHierarchy.getOblast("Одеська область")?.id)
    }

    @Test
    fun `every city region resolves to an oblast except the special-status sevastopol`() {
        val unresolved = AdminHierarchy.ALL_CITIES
            .map { it.oblastId }
            .distinct()
            .filter { AdminHierarchy.getOblast(it) == null }
            .toSet()

        assertEquals(setOf("sevastopol"), unresolved)
    }

    @Test
    fun `oblast raions are scoped to that oblast`() {
        val raions = AdminHierarchy.getOblast("odeska")!!.raions()
        assertTrue(raions.isNotEmpty())
        assertTrue(raions.all { it.oblastId == "odeska" })
    }

    @Test
    fun `getRaion respects the oblast scope`() {
        val raion = AdminHierarchy.getRaion("Одеський", "odeska")
        assertNotNull(raion)
        assertEquals("odeska", raion!!.oblastId)
    }

    @Test
    fun `cityToOblast agrees with the resolved city`() {
        val odesa = AdminHierarchy.getCity("Одеса")!!
        assertEquals(odesa.oblastId, AdminHierarchy.cityToOblast("Одеса"))
    }

    @Test
    fun `admin city location keeps lat then lon`() {
        val odesa = AdminHierarchy.getCity("Одеса")!!
        assertEquals(odesa.lat, odesa.location.lat, 0.0)
        assertEquals(odesa.lon, odesa.location.lon, 0.0)
    }
}
