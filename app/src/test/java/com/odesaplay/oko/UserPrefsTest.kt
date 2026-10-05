package com.odesaplay.oko

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }

    @Test
    fun `setters round trip through the preferences flow`() = runTest {
        prefs.clearAll()
        prefs.setSlowRedKm(12)
        prefs.setThreatCardSize(ThreatCardSize.SMALL)
        prefs.setLanguage(AppLanguage.EN)
        prefs.setSheltersEnabled(false)
        prefs.setNotifyPolicyEnabled(true)

        val p = prefs.preferences.first()
        assertEquals(12, p.slowRedKm)
        assertEquals(ThreatCardSize.SMALL, p.threatCardSize)
        assertEquals(AppLanguage.EN, p.language)
        assertFalse(p.sheltersEnabled)
        assertTrue(p.notifyPolicyEnabled)
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
}
