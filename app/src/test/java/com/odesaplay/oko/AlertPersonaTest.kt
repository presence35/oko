package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alert personas are a one-shot writer over the switches that decide whether a phone makes a
 * sound. These tests pin the mapping so a careless edit cannot quietly silence a bell for everyone
 * who picked a setup — the same contract [AppIntensityTest] holds for the paranoia dial.
 */
class AlertPersonaTest {

    private val all = AlertPersona.values().toSet()

    @Test
    fun `every persona states a full patch`() {
        for (persona in all) {
            val p = persona.patch()
            assertTrue(persona.name, p.alertTypes.isNotEmpty())
            assertTrue(persona.name, p.alertTypes.all { it in ThreatType.values() })
            assertTrue(persona.name, p.digestMax in 1..10)
            assertTrue(persona.name, p.fastYellowMin >= p.fastRedMin)
            assertTrue(persona.name, p.fallingDebrisDelaySec in 0..600)
            // The night window is claimed by every persona, always: half of these people sleep
            // through the war and a null here would leave them on whatever was there before.
            assertTrue(persona.name, p.officialRed || p.officialYellow)
        }
    }

    /** No persona may turn off red official alerts: that is the one thing that must never be off. */
    @Test
    fun `every persona keeps red official alerts on`() {
        for (persona in all) assertTrue(persona.name, persona.patch().officialRed)
    }

    /** A teen's phone rings for red only — day and night. Yellow noise is how the phone gets ignored. */
    @Test
    fun `the teen setup never rings yellow`() {
        val teen = AlertPersona.TEEN.patch()
        assertFalse(teen.officialYellow)
        assertFalse(teen.night.officialYellow)
    }

    /**
     * Nobody may drop the stale-feed override: for every one of these people, "the feed went quiet,
     * so it must be fine" is the wrong answer — the last known red still sounds.
     */
    @Test
    fun `every persona keeps the critical-offline override`() {
        for (persona in all) {
            assertTrue(persona.name, persona.patch().criticalOfflineOverride)
        }
    }

    /** The parents are the only ones who keep a countdown running after the all-clear. */
    @Test
    fun `only the parent setup keeps a debris countdown`() {
        assertEquals(120, AlertPersona.PARENT.patch().fallingDebrisDelaySec)
        for (persona in all - AlertPersona.PARENT) {
            assertEquals(persona.name, 0, persona.patch().fallingDebrisDelaySec)
        }
    }

    @Test
    fun `only abroad stops restarting and screaming`() {
        val abroad = AlertPersona.ABROAD.patch()
        assertFalse(abroad.bootRestartEnabled)
        assertFalse(abroad.sirenOverride)
        assertFalse(abroad.criticalOfflineBypassSilent)
        for (persona in all - AlertPersona.ABROAD) {
            assertTrue(persona.name, persona.patch().bootRestartEnabled)
        }
    }

    /** The loud end sounds on every change; a filter there would swallow the earliest warning. */
    @Test
    fun `grandma and frontline sound on every change`() {
        for (persona in setOf(AlertPersona.GRANDMA, AlertPersona.FRONTLINE)) {
            val p = persona.patch()
            assertFalse(persona.name, p.notifyPolicyEnabled)
            assertEquals(persona.name, ZonePolicy.EVERY_CHANGE, p.zonePolicy)
        }
        for (persona in setOf(AlertPersona.PARENT, AlertPersona.TEEN, AlertPersona.AT_WORK, AlertPersona.ON_ROAD)) {
            val p = persona.patch()
            assertTrue(persona.name, p.notifyPolicyEnabled)
            assertEquals(persona.name, ZonePolicy.ONCE_PER_THREAT, p.zonePolicy)
        }
    }

    /** The desk and the departure lounge are the two places an alarm tone is wrong. */
    @Test
    fun `offices and airports get no alarm tone`() {
        for (persona in setOf(AlertPersona.AT_WORK, AlertPersona.ABROAD)) {
            val p = persona.patch()
            assertFalse(persona.name, p.sirenOverride)
            assertFalse(persona.name, p.criticalOfflineBypassSilent)
        }
    }

    /**
     * Distance rings are either a whole policy or none of it. The one exception is the teen setup,
     * which arms exactly the in-zone red bell — one rule they can hold in their head.
     */
    @Test
    fun `rings are all armed, all disarmed, or the teen single bell`() {
        for (persona in all) {
            val p = persona.patch()
            val arms = listOf(p.slowRedArmed, p.slowYellowArmed, p.fastRedArmed, p.fastYellowArmed)
            val nightArms = listOf(
                p.night.slowRedArmed, p.night.slowYellowArmed,
                p.night.fastRedArmed, p.night.fastYellowArmed
            )
            val consistent = arms.all { it } || arms.none { it } || persona == AlertPersona.TEEN
            assertTrue("${persona.name} has a half-armed ring set", consistent)
            val nightConsistent = nightArms.all { it } || nightArms.none { it } || persona == AlertPersona.TEEN
            assertTrue("${persona.name} has a half-armed night ring set", nightConsistent)
        }
        val teen = AlertPersona.TEEN.patch()
        assertTrue(teen.fastRedArmed)
        assertFalse(teen.fastYellowArmed)
    }

    /**
     * Structural guard, not a value test: a persona has no business deciding map performance, the
     * morale engine, GPS, shelters or identity. Those belong to the person, and a persona tap that
     * reset them would undo hand-tuning nobody asked it to touch.
     */
    @Test
    fun `no persona can reach the map, morale, location or identity`() {
        val forbidden = listOf(
            "morale", "tally", "Neutralized", "Alarm", "language", "pinnedCity",
            "followMe", "Gps", "shelters", "calmMessages", "deathAnimation",
            "highQuality", "followBullet", "batteryAsk", "cardSize", "iconSet"
        )
        for (field in AlertPersonaPatch::class.java.declaredFields) {
            for (banned in forbidden) {
                assertFalse("patch must not own '${field.name}'", field.name.contains(banned))
            }
        }
    }

    /** Every language has a tile name and a one-liner for every persona, and none is a duplicate. */
    @Test
    fun `every language names and explains every persona`() {
        for (language in AppLanguage.values()) {
            val s = Strings.get(language)
            val titles = all.map { it.title(s) }
            val descs = all.map { it.desc(s) }
            for (title in titles) assertTrue("$language has a blank persona name", title.isNotBlank())
            for (desc in descs) assertTrue("$language has a blank persona line", desc.isNotBlank())
            assertEquals("$language repeats a persona name", titles.size, titles.distinct().size)
            assertEquals("$language repeats a persona line", descs.size, descs.distinct().size)
            assertTrue("$language has no restore label", s.alertPersonaRestore.isNotBlank())
        }
    }

    /**
     * The tile's "selected" mark is derived, not remembered: a persona is lit only while the live
     * switches equal what it writes. These pin the two halves of that contract — a persona always
     * recognises its own state, and a single hand edit stops it from doing so.
     */
    @Test
    fun `every persona is recognised by the state it writes`() {
        for (persona in all) {
            assertEquals(persona.name, persona, matchingAlertPersona(persona.patch()))
        }
    }

    @Test
    fun `a hand edit leaves no persona selected`() {
        val snapshot = AlertPersona.PARENT.patch()
        assertNull(matchingAlertPersona(snapshot.copy(officialYellow = !snapshot.officialYellow)))
        assertNull(matchingAlertPersona(snapshot.copy(alertTypes = snapshot.alertTypes - ThreatType.SHAHED)))
        assertNull(matchingAlertPersona(snapshot.copy(night = snapshot.night.copy(fastRedArmed = false))))
    }
}
