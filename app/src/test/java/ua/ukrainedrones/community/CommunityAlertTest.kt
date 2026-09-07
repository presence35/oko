package ua.ukrainedrones.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityAlertTest {

    @Test
    fun testScaledRingCoordinateExtraction() {
        val ring = ScaledRing(
            intArrayOf(
                47659, 34044, // lat: 47.659, lon: 34.044
                47666, 33979
            )
        )
        assertEquals(2, ring.pointCount)
        assertEquals(47.659, ring.getLat(0), 0.0001)
        assertEquals(34.044, ring.getLon(0), 0.0001)
        assertEquals(47.666, ring.getLat(1), 0.0001)
        assertEquals(33.979, ring.getLon(1), 0.0001)

        val points = ring.toPoints()
        assertEquals(2, points.size)
        assertEquals(47.659, points[0].lat, 0.0001)
        assertEquals(34.044, points[0].lon, 0.0001)
    }

    @Test
    fun testScaledRingPointInPolygon() {
        // Square polygon around lat 50.0..51.0, lon 30.0..31.0
        val ring = ScaledRing(
            intArrayOf(
                50000, 30000,
                51000, 30000,
                51000, 31000,
                50000, 31000,
                50000, 30000
            )
        )
        // Inside point
        assertTrue(ring.contains(50.5, 30.5))
        // Outside points
        assertFalse(ring.contains(49.9, 30.5))
        assertFalse(ring.contains(50.5, 31.5))
    }

    @Test
    fun testCompactOblastBoundariesLookup() {
        // Should find Odesa by raw id or stem
        val odesaById = CompactOblastBoundaries.get("odeska")
        assertNotNull(odesaById)
        assertTrue(odesaById!!.pointCount > 50)

        val odesaByStem = CompactOblastBoundaries.get("одеськ")
        assertNotNull(odesaByStem)
        assertEquals(odesaById.pointCount, odesaByStem!!.pointCount)

        // Point inside Odesa city center (approx 46.48, 30.72)
        assertTrue(odesaById.contains(46.48, 30.72))
    }

    @Test
    fun testCompactRaionBoundariesLookup() {
        val nikopol = CompactRaionBoundaries.forKey("Дніпропетровськ", "нікопольський")
        assertNotNull(nikopol)
        assertTrue(nikopol!!.pointCount > 50)

        // Point inside Nikopol city (47.57, 34.40)
        assertTrue(nikopol.contains(47.57, 34.40))

        // All map should contain raions
        assertTrue(CompactRaionBoundaries.all.isNotEmpty())
    }

    @Test
    fun testGateSkipsWhenNoActiveAlerts() {
        val decision = CommunityAlertGate.evaluate(
            activeAlertKeysOrNames = emptyList(),
            userLocation = LatLon(47.57, 34.40), // Nikopol
            userCityName = "Нікополь"
        )
        assertFalse(decision.shouldPoll)
        assertEquals("No active alerts", decision.reason)
    }

    @Test
    fun testGateSkipsWhenAlertIsNotFrontline() {
        val decision = CommunityAlertGate.evaluate(
            activeAlertKeysOrNames = listOf("Одеський район", "Київська область"),
            userLocation = LatLon(47.57, 34.40), // User is in Nikopol, but alert is in Odesa
            userCityName = "Нікополь"
        )
        assertFalse(decision.shouldPoll)
        assertTrue(decision.reason.contains("No frontline community-capable raions"))
    }

    @Test
    fun testGateSkipsWhenUserIsNotNearAlertedFrontlineRaion() {
        val decision = CommunityAlertGate.evaluate(
            activeAlertKeysOrNames = listOf("Нікопольський район"),
            userLocation = LatLon(46.48, 30.72), // User is in Odesa
            userCityName = "Одеса"
        )
        assertFalse(decision.shouldPoll)
        assertTrue(decision.reason.contains("not in or near the affected area"))
    }

    @Test
    fun testGateTriggersWhenUserIsInAlertedFrontlineCommunity() {
        val decision = CommunityAlertGate.evaluate(
            activeAlertKeysOrNames = listOf("Нікопольський район"),
            userLocation = null,
            userCityName = "Нікополь"
        )
        assertTrue(decision.shouldPoll)
        assertNotNull(decision.targetRaion)
        assertEquals("nikopolskyi", decision.targetRaion?.id)
        assertNotNull(decision.matchedCommunity)
        assertEquals("nikopolska", decision.matchedCommunity?.id)
    }

    @Test
    fun testGateTriggersWhenUserGpsIsInAlertedFrontlineCommunity() {
        val decision = CommunityAlertGate.evaluate(
            activeAlertKeysOrNames = listOf("nikopolskyi"),
            userLocation = LatLon(47.66, 34.61), // Marhanets coords
            userCityName = null
        )
        assertTrue(decision.shouldPoll)
        assertNotNull(decision.targetRaion)
        assertEquals("nikopolskyi", decision.targetRaion?.id)
        assertNotNull(decision.matchedCommunity)
        assertEquals("marhanetska", decision.matchedCommunity?.id)
    }

    @Test
    fun testSkogRawJsonParsing() {
        val json = """
            {
              "raw": {
                "3": {
                  "name": "Дніпропетровська область",
                  "alert": true,
                  "community": [
                    { "name": "Марганецька територіальна громада", "alert": true, "changed": "2026-09-07 08:36:03" },
                    { "name": "Нікопольська територіальна громада", "alert": true, "changed": "2026-09-07 07:31:26" },
                    { "name": "Покровська територіальна громада", "alert": false, "changed": "2026-09-05 23:53:45" }
                  ]
                }
              }
            }
        """.trimIndent()

        val client = UbillingCommunityAlertClient()
        val communities = client.parseSkogRawJson(json)

        assertEquals(3, communities.size)
        val marhanets = communities.first { it.name.contains("Марганецька") }
        assertTrue(marhanets.isActive)
        val pokrov = communities.first { it.name.contains("Покровська") }
        assertFalse(pokrov.isActive)
    }

    @Test
    fun testResolveEffectiveAlertForSafeCommunityInAlertedRaion() {
        val enricher = CommunityAlertEnricher()
        // Broad raion has an alert (e.g. Nikopol Raion), but Pokrov community is NOT alerted
        val enrichment = CommunityEnrichmentResult(
            isFrontlineCommunityArea = true,
            specificCommunityAlert = false,
            communityName = "Покровська територіальна громада",
            raionName = "Нікопольський район"
        )
        val effective = enricher.resolveEffectiveAlert(isStandardAlertActive = true, enrichment = enrichment)
        // Should be false! Pokrov is safe!
        assertFalse(effective)
    }
}
