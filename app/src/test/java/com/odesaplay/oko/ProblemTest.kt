package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemTest {

    /** Healthy defaults: everything armed, notifications on, a usable fix, nothing offline. */
    private fun derive(
        notificationsDisabled: Boolean = false,
        locationBlocked: Boolean = false,
        anyZoneArmed: Boolean = true,
        officialRedAlertsEnabled: Boolean = true,
        officialYellowAlertsEnabled: Boolean = true,
        allTypesSilenced: Boolean = false,
        transportDown: Boolean = false
    ) = deriveProblems(
        notificationsDisabled,
        locationBlocked,
        anyZoneArmed,
        officialRedAlertsEnabled,
        officialYellowAlertsEnabled,
        allTypesSilenced,
        transportDown
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
    fun `a blocked location is critical and fixes at the permission`() {
        val problem = derive(locationBlocked = true).single()
        assertEquals(ProblemId.LocationBlocked, problem.id)
        assertEquals(ProblemSeverity.Critical, problem.severity)
        assertEquals(ProblemFix.LocationPermission, problem.fix)
    }

    /** Age is not an input: a fix that only went old is shown by the map dot and the GPS log,
     *  because there is no permission for the user to grant and nothing to tap. */
    @Test
    fun `an old fix is not a problem`() {
        assertTrue(derive().isEmpty())
    }

    @Test
    fun `the two causes that stop warnings altogether outrank the narrower ones`() {
        val ids = derive(
            notificationsDisabled = true,
            locationBlocked = true,
            anyZoneArmed = false,
            officialRedAlertsEnabled = false,
            officialYellowAlertsEnabled = false,
            allTypesSilenced = true,
            transportDown = true
        ).map { it.id }
        assertEquals(
            listOf(
                ProblemId.NotificationsDisabled,
                ProblemId.LocationBlocked,
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
    fun `a dead transport is a problem on its own, with the offline alarm on`() {
        assertEquals(
            listOf(ProblemId.SourceOffline),
            derive(transportDown = true).map { it.id }
        )
    }

    @Test
    fun `a degraded feed is not a transport problem, so the glyph stays off`() {
        assertTrue(derive(transportDown = false).isEmpty())
    }

    @Test
    fun `no two problems share an id, so the guidance toast can dedup on it`() {
        val ids = derive(
            notificationsDisabled = true,
            locationBlocked = true,
            anyZoneArmed = false,
            officialRedAlertsEnabled = false,
            officialYellowAlertsEnabled = false,
            allTypesSilenced = true,
            transportDown = true
        ).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
