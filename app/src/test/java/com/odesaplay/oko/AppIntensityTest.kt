package com.odesaplay.oko

import org.junit.Assert.*
import org.junit.Test

/**
 * The intensity presets are a one-shot writer: whatever [UserPrefs.applyIntensity] stores is what
 * the user is left with. These tests pin the mapping so a careless edit to one persona cannot
 * quietly disarm a bell or arm a background wakelock for everybody who picked it.
 */
class AppIntensityTest {

    private val all = AppIntensity.values().toSet()

    @Test
    fun `every intensity defines a full patch`() {
        for (intensity in all) {
            val patch = intensity.patch()
            assertTrue(intensity.name, patch.alertTypes.isNotEmpty())
            assertTrue(intensity.name, patch.alertTypes.all { it in ThreatType.values() })
            assertTrue(intensity.name, patch.digestMax in 1..10)
            assertTrue(intensity.name, patch.fastYellowMin >= patch.fastRedMin)
        }
    }

    /**
     * "Normal" must be exactly what the app ships as its default — the whole point of a medium
     * preset is that it is what you get without thinking. It was the shipped default for every
     * field it owns, including the repeat-sound master, and that equality is the invariant.
     */
    @Test
    fun `balanced is the shipped default, field for field`() {
        assertNull("Normal must not touch the night window", AppIntensity.BALANCED.patch().night)
        val d = UserPreferences.DEFAULT
        val patch = AppIntensity.BALANCED.patch()
        assertEquals(d.fastRedMin, patch.fastRedMin)
        assertEquals(d.fastYellowMin, patch.fastYellowMin)
        assertEquals(d.slowRedArmed, patch.slowRedArmed)
        assertEquals(d.slowYellowArmed, patch.slowYellowArmed)
        assertEquals(d.fastRedArmed, patch.fastRedArmed)
        assertEquals(d.fastYellowArmed, patch.fastYellowArmed)
        assertEquals(d.sirenOverride, patch.sirenOverride)
        assertEquals(d.criticalOfflineOverride, patch.criticalOfflineOverride)
        assertEquals(d.criticalOfflineBypassSilent, patch.criticalOfflineBypassSilent)
        assertEquals(d.bootRestartEnabled, patch.bootRestartEnabled)
        assertEquals(d.periodicGps, patch.periodicGps)
        assertEquals(d.notifyPolicyEnabled, patch.notifyPolicyEnabled)
        assertEquals(d.zonePolicy, patch.zonePolicy)
        assertEquals(d.digestWindow, patch.digestWindow)
        assertEquals(d.digestPerType, patch.digestPerType)
        assertEquals(d.digestMax, patch.digestMax)
        assertEquals(d.alertEnabledTypes, patch.alertTypes)
        assertEquals(d.followBullet, patch.followBullet)
        assertEquals(d.highQualityExplosions, patch.highQualityExplosions)
        assertEquals(d.deathAnimationStyle, patch.deathAnimationStyle)
        assertEquals(d.sheltersEnabled, patch.sheltersEnabled)
        assertTrue(patch.batteryAskEnabled)
    }

    /**
     * The bug this preset work surfaced, pinned where it can bite again: the shipped default's
     * (master, policy) pair must actually mean the policy. With the master off, [NotifyPrefs.from]
     * silently substitutes EVERY_CHANGE, so a default of ONCE_PER_THREAT was a claim the app never
     * kept — it rang on every single update.
     */
    @Test
    fun `the shipped default really does sound once per threat`() {
        val d = UserPreferences.DEFAULT
        val resolved = NotifyPrefs.from(
            d.notifyPolicyEnabled, d.zonePolicy, d.digestMax, d.digestWindow, d.digestPerType
        )
        assertEquals(d.zonePolicy, resolved.preset)
        assertEquals(ZonePolicy.ONCE_PER_THREAT, resolved.preset)
    }

    /** The furthest-from-the-front persona must be the only one that stops sounding on stale data. */
    @Test
    fun `only just-watching drops the critical-offline override`() {
        for (intensity in all) {
            val expected = intensity != AppIntensity.WATCHING
            assertEquals(intensity.name, expected, intensity.patch().criticalOfflineOverride)
        }
    }

    @Test
    fun `just-watching is the only persona that stops waking the phone up`() {
        val watching = AppIntensity.WATCHING.patch()
        assertFalse(watching.bootRestartEnabled)
        assertFalse(watching.periodicGps)
        assertFalse(watching.batteryAskEnabled)
        assertFalse(watching.slowRedArmed)
        assertFalse(watching.slowYellowArmed)
        assertFalse(watching.fastRedArmed)
        assertFalse(watching.fastYellowArmed)
        for (intensity in all - setOf(AppIntensity.WATCHING)) {
            assertTrue(intensity.name, intensity.patch().bootRestartEnabled)
            assertTrue(intensity.name, intensity.patch().batteryAskEnabled)
        }
    }

    /** Nothing wakes up for someone who is not in the war zone — that is the whole persona. */
    @Test
    fun `just-watching stops caring about personal rings`() {
        val watching = AppIntensity.WATCHING.patch()
        assertEquals(ZonePolicy.DIGEST, watching.zonePolicy)
        assertEquals(DigestWindow.MIN_60, watching.digestWindow)
        assertFalse(watching.sirenOverride)
        assertFalse(watching.sheltersEnabled)
        assertTrue(AppIntensity.ALWAYS_READY.patch().slowRedArmed)
    }

    /** The loud end arms everything and sounds on every change. */
    @Test
    fun `always-ready is the most persistent setup available`() {
        val ready = AppIntensity.ALWAYS_READY.patch()
        assertTrue(ready.sirenOverride)
        assertTrue(ready.criticalOfflineBypassSilent)
        assertTrue(ready.periodicGps)
        assertTrue(ready.fastYellowArmed)
        assertTrue(ready.followBullet)
        assertTrue(ready.highQualityExplosions)
        assertEquals(ZonePolicy.EVERY_CHANGE, ready.zonePolicy)
        // Master off means EVERY_CHANGE anyway; asserting the policy keeps the two in step.
        assertFalse(ready.notifyPolicyEnabled)
        assertEquals(ThreatType.values().toSet(), ready.alertTypes)
    }

    /**
     * The quiet persona mutes the noise, not the news: bells become a digest, the loudest flourishes
     * go, and the neutralized counter the user watches for stress release is not a preset's to take.
     */
    @Test
    fun `quiet house digests, softens effects, and keeps every type on the map`() {
        val quiet = AppIntensity.QUIET.patch()
        assertEquals(ZonePolicy.DIGEST, quiet.zonePolicy)
        assertEquals(DigestWindow.MIN_10, quiet.digestWindow)
        assertTrue(quiet.digestPerType)
        assertTrue(quiet.criticalOfflineOverride)
        assertFalse(quiet.sirenOverride)
        assertFalse(quiet.followBullet)
        assertFalse(quiet.highQualityExplosions)
        assertEquals(DeathAnimationStyle.SIMPLE, quiet.deathAnimationStyle)
        assertEquals(setOf(ThreatType.AVIATION, ThreatType.RECON, ThreatType.UNKNOWN), ThreatType.values().toSet() - quiet.alertTypes)
    }

    /**
     * The night window belongs to exactly one persona. Everyone else must leave it alone, or
     * applying a setup would silently cancel the user's own "Just let me sleep!".
     */
    @Test
    fun `only always-ready claims the night window`() {
        for (intensity in all) {
            val night = intensity.patch().night
            if (intensity == AppIntensity.ALWAYS_READY) {
                assertNotNull(intensity.name, night)
                assertNotNull(intensity.name, night!!.zoneSirenOverride)
                assertTrue(intensity.name, night!!.officialSirenOverride)
                assertTrue(intensity.name, night!!.officialRed)
                assertTrue(intensity.name, night!!.officialYellow)
                assertTrue(intensity.name, night!!.slowRedArmed)
                assertTrue(intensity.name, night!!.slowYellowArmed)
                assertTrue(intensity.name, night!!.fastRedArmed)
                assertTrue(intensity.name, night!!.fastYellowArmed)
                // Every night bell on means the window is no longer a mute, whatever it was.
                assertFalse(intensity.name, NightSleepPreset(
                    useCustomZones = true,
                    slowRedArmed = night.slowRedArmed,
                    slowYellowArmed = night.slowYellowArmed,
                    fastRedArmed = night.fastRedArmed,
                    fastYellowArmed = night.fastYellowArmed,
                    zoneSirenOverride = night.zoneSirenOverride,
                    officialSirenOverride = night.officialSirenOverride,
                    officialRed = night.officialRed,
                    officialYellow = night.officialYellow
                ).isMuted)
            } else {
                assertNull(intensity.name, night)
            }
        }
    }

    /**
     * Structural guard, not a value test: the patch must stay unable to touch the things a persona
     * has no business deciding. Someone who picks the quiet setup still gets the counter.
     */
    @Test
    fun `no preset can reach morale, tallies or identity`() {
        val owned = IntensityPatch::class.java.declaredFields.map { it.name }
        val forbidden = listOf(
            "morale", "tally", "Neutralized", "Alarm",
            "language", "pinnedCity", "followMe", "sheltersWithKids", "calmMessages",
            "deathAnimationEnabled", "autoDismissAllClear", "official"
        )
        for (name in owned) {
            for (banned in forbidden) {
                assertFalse("patch must not own '$name'", name.contains(banned))
            }
        }
    }
}