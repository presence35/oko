package com.odesaplay.oko

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UserPrefsTest {

    private lateinit var prefs: UserPrefs

    @Before
    fun setUp() {
        resetStores()
        prefs = UserPrefs(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `fresh store returns the documented defaults`() = runTest {
        prefs.clearAll()
        val p = prefs.preferences.first()

        assertEquals(UserPreferences.DEFAULT.slowRedKm, p.slowRedKm)
        assertEquals(UserPreferences.DEFAULT.slowYellowKm, p.slowYellowKm)
        assertEquals(UserPreferences.DEFAULT.wizardCompleted, p.wizardCompleted)
        assertEquals(UserPreferences.DEFAULT.threatCardSize, p.threatCardSize)
        assertEquals(UserPreferences.DEFAULT.alertRegionMode, p.alertRegionMode)
        assertEquals(UserPreferences.DEFAULT.mapVisibleTypes, p.mapVisibleTypes)
        assertEquals(UserPreferences.DEFAULT.alertEnabledTypes, p.alertEnabledTypes)
        assertTrue(p.notifyPolicyEnabled)
    }

    @Test
    fun `setters round trip through the preferences flow`() = runTest {
        prefs.clearAll()
        prefs.setSlowRedKm(12)
        prefs.setThreatCardSize(ThreatCardSize.SMALL)
        prefs.setLanguage(AppLanguage.EN)
        prefs.setSheltersEnabled(false)
        prefs.setNotifyPolicyEnabled(false)

        val p = prefs.preferences.first()
        assertEquals(12, p.slowRedKm)
        assertEquals(ThreatCardSize.SMALL, p.threatCardSize)
        assertEquals(AppLanguage.EN, p.language)
        assertFalse(p.sheltersEnabled)
        assertFalse(p.notifyPolicyEnabled)
    }

    @Test
    fun `yellow radius is clamped above the red radius`() = runTest {
        prefs.clearAll()
        prefs.setSlowRedKm(20)
        prefs.setSlowYellowKm(10) // below red+2, must clamp up

        assertEquals(22, prefs.preferences.first().slowYellowKm)
    }

    @Test
    fun `digest max is clamped to one through ten`() = runTest {
        prefs.clearAll()
        prefs.setDigestMax(99)
        assertEquals(10, prefs.preferences.first().digestMax)

        prefs.setDigestMax(0)
        assertEquals(1, prefs.preferences.first().digestMax)
    }

    @Test
    fun `pinning a city turns follow-me off`() = runTest {
        prefs.clearAll()
        prefs.setFollowMe(true)
        prefs.setPinnedCityWithFollow("Одеса")

        val p = prefs.preferences.first()
        assertEquals("Одеса", p.pinnedCity)
        assertFalse(p.followMe)
    }

    @Test
    fun `enabling a threat alert also enables its map visibility`() = runTest {
        prefs.clearAll()
        prefs.setThreatAlertsEnabled(ThreatType.SHAHED, true)

        val p = prefs.preferences.first()
        assertTrue(ThreatType.SHAHED in p.alertEnabledTypes)
        assertTrue(ThreatType.SHAHED in p.mapVisibleTypes)
    }

    @Test
    fun `sleep preset mutes the night bells then restores them`() = runTest {
        prefs.clearAll()
        val before = prefs.preferences.first().nightOfficialRedEnabled

        prefs.setNightSleep(true)
        assertFalse(prefs.preferences.first().nightOfficialRedEnabled)

        prefs.setNightSleep(false)
        assertEquals(before, prefs.preferences.first().nightOfficialRedEnabled)
    }

    @Test
    fun `touching a night bell drops the sleep snapshot`() = runTest {
        prefs.clearAll()
        prefs.setNightSleep(true)
        prefs.setNightOfficialRedEnabled(true) // a direct edit ends the preset

        prefs.setNightSleep(false)
        assertTrue(prefs.preferences.first().nightOfficialRedEnabled)
    }

    /**
     * The intensity presets write two dozen keys in one transaction, so the risk is a key spelled
     * wrong or a field inverted: `batteryAskEnabled` is stored as `batteryOnboardShown`, and the
     * patch's "the user wants the battery prompt" is the store's "we already asked". Assert the
     * patch lands field for field rather than spot-checking the obvious ones.
     */
    @Test
    fun `an intensity patch lands every field it owns`() = runTest {
        prefs.clearAll()
        val patch = AppIntensity.QUIET.patch()
        prefs.applyIntensity(patch)

        val p = prefs.preferences.first()
        assertEquals(patch.sirenOverride, p.sirenOverride)
        assertEquals(patch.criticalOfflineOverride, p.criticalOfflineOverride)
        assertEquals(patch.criticalOfflineBypassSilent, p.criticalOfflineBypassSilent)
        assertEquals(patch.bootRestartEnabled, p.bootRestartEnabled)
        assertEquals(patch.periodicGps, p.periodicGps)
        assertEquals(patch.notifyPolicyEnabled, p.notifyPolicyEnabled)
        assertEquals(patch.zonePolicy, p.zonePolicy)
        assertEquals(patch.digestWindow, p.digestWindow)
        assertEquals(patch.digestPerType, p.digestPerType)
        assertEquals(patch.digestMax, p.digestMax)
        assertEquals(patch.slowRedKm, p.slowRedKm)
        assertEquals(patch.slowYellowKm, p.slowYellowKm)
        assertEquals(patch.fastRedMin, p.fastRedMin)
        assertEquals(patch.fastYellowMin, p.fastYellowMin)
        assertEquals(patch.slowRedArmed, p.slowRedArmed)
        assertEquals(patch.slowYellowArmed, p.slowYellowArmed)
        assertEquals(patch.fastRedArmed, p.fastRedArmed)
        assertEquals(patch.fastYellowArmed, p.fastYellowArmed)
        assertEquals(patch.alertTypes, p.alertEnabledTypes)
        assertEquals(patch.followBullet, p.followBullet)
        assertEquals(patch.highQualityExplosions, p.highQualityExplosions)
        assertEquals(patch.deathAnimationStyle, p.deathAnimationStyle)
        assertEquals(patch.sheltersEnabled, p.sheltersEnabled)
        // Inverted on purpose in the patch: `false` here means "still ask about the battery".
        assertFalse(p.batteryOnboardShown)
    }

    /** Map visibility is not a preset's business — a preset decides what rings, not what is drawn. */
    @Test
    fun `an intensity patch leaves the map alone`() = runTest {
        prefs.clearAll()
        prefs.setThreatMapVisible(ThreatType.RECON, false)

        prefs.applyIntensity(AppIntensity.QUIET.patch())

        val p = prefs.preferences.first()
        assertFalse(p.mapVisibleTypes.contains(ThreatType.RECON))
        // …while still not letting that type ring.
        assertFalse(p.alertEnabledTypes.contains(ThreatType.RECON))
    }

    @Test
    fun `re-applying an intensity restores it exactly`() = runTest {
        prefs.clearAll()
        val quiet = AppIntensity.QUIET.patch()
        prefs.applyIntensity(quiet)
        prefs.setSlowRedKm(3)
        prefs.setFollowBullet(true)

        prefs.applyIntensity(quiet)

        val p = prefs.preferences.first()
        assertEquals(quiet.slowRedKm, p.slowRedKm)
        assertEquals(quiet.followBullet, p.followBullet)
    }

    /**
     * A patch that claims the night window ends "Just let me sleep!", exactly as a hand edit does —
     * otherwise the restore fires at the end of the night and silently re-mutes what was just armed.
     */
    @Test
    fun `a patch that claims the night drops the sleep snapshot`() = runTest {
        prefs.clearAll()
        prefs.setNightSleep(true)
        assertNotNull(prefs.preferences.first().nightSleepRestore)

        prefs.applyIntensity(AppIntensity.ALWAYS_READY.patch())

        val p = prefs.preferences.first()
        assertNull("the restore must not outlive the choice that replaced it", p.nightSleepRestore)
        assertTrue(p.nightOfficialRedEnabled)
        assertTrue(p.nightZoneSirenOverride)
        assertTrue(p.nightSlowYellowArmed)
        assertFalse("every night bell on means the window is no longer a mute", p.nightSleepActive)
    }

    @Test
    fun `a patch that leaves the night alone keeps the sleep snapshot`() = runTest {
        prefs.clearAll()
        prefs.setNightSleep(true)

        prefs.applyIntensity(AppIntensity.QUIET.patch())

        val p = prefs.preferences.first()
        assertNotNull("applying a setup must not silently cancel the user's night mute", p.nightSleepRestore)
        assertTrue(p.nightSleepActive)
    }
}
