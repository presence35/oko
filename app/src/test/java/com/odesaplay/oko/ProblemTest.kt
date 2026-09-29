package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemTest {

    /** Healthy defaults: everything armed, notifications on, a usable fix, nothing offline. */
    private fun derive(
        notificationsDisabled: Boolean = false,
        gpsUnreliable: Boolean = false,
        anyZoneArmed: Boolean = true,
        officialRedAlertsEnabled: Boolean = true,
        officialYellowAlertsEnabled: Boolean = true,
        allTypesSilenced: Boolean = false,
        criticalOfflineOverride: Boolean = true,
        sourceOffline: Boolean = false
    ) = deriveProblems(
        notificationsDisabled,
        gpsUnreliable,
        anyZoneArmed,
        officialRedAlertsEnabled,
        officialYellowAlertsEnabled,
        allTypesSilenced,
        criticalOfflineOverride,
        sourceOffline
    )

    @Test
    fun `a healthy app has no problems`() {
        assertTrue(derive().isEmpty())
    }

    @Test
    fun `blocked notifications are critical and fix at the OS page`() {
        val problem = derive(notificationsDisabled = true).single()
        assertEquals(ProblemId.NotificationsDisabled, problem.id)
        assertEquals(ProblemSeverity.Critical, problem.severity)
        assertEquals(ProblemFix.NotificationSettings, problem.fix)
    }

    @Test
    fun `an unusable position is critical and fix at the location permission`() {
        val problem = derive(gpsUnreliable = true).single()
        assertEquals(ProblemId.GpsUnreliable, problem.id)
        assertEquals(ProblemSeverity.Critical, problem.severity)
        assertEquals(ProblemFix.LocationPermission, problem.fix)
    }

    @Test
    fun `the two causes that stop warnings altogether outrank the narrower ones`() {
        val ids = derive(
            notificationsDisabled = true,
            gpsUnreliable = true,
            anyZoneArmed = false,
            officialRedAlertsEnabled = false,
            officialYellowAlertsEnabled = false,
            allTypesSilenced = true,
            sourceOffline = true,
            criticalOfflineOverride = false
        ).map { it.id }
        assertEquals(
            listOf(
                ProblemId.NotificationsDisabled,
                ProblemId.GpsUnreliable,
                ProblemId.AllChannelsOff,
                ProblemId.AllTypesSilenced,
                ProblemId.SourceOffline
            ),
            ids
        )
    }

    @Test
    fun `every silently-off channel is one problem, not one per channel`() {
        val problems = derive(
            anyZoneArmed = false,
            officialRedAlertsEnabled = false,
            officialYellowAlertsEnabled = false
        )
        assertEquals(listOf(ProblemId.AllChannelsOff), problems.map { it.id })
    }

    @Test
    fun `one channel still on is not a problem`() {
        assertTrue(derive(anyZoneArmed = false, officialYellowAlertsEnabled = false).isEmpty())
    }

    @Test
    fun `an offline source is only a problem when the override is off`() {
        assertTrue(derive(sourceOffline = true).isEmpty())
        assertEquals(
            listOf(ProblemId.SourceOffline),
            derive(sourceOffline = true, criticalOfflineOverride = false).map { it.id }
        )
    }

    @Test
    fun `no two problems share an id, so the guidance toast can dedup on it`() {
        val ids = derive(
            notificationsDisabled = true,
            gpsUnreliable = true,
            anyZoneArmed = false,
            officialRedAlertsEnabled = false,
            officialYellowAlertsEnabled = false,
            allTypesSilenced = true,
            sourceOffline = true,
            criticalOfflineOverride = false
        ).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
