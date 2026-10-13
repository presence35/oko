package com.odesaplay.oko

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.odesaplay.oko.engine.ThreatZone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class AlertNotificationManagerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun notificationManager() =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notification(id: Int): Notification =
        Shadows.shadowOf(notificationManager()).getNotification(id)!!

    @Test
    fun `groupFor maps each id to exactly one group`() {
        assertEquals(AlertNotificationManager.GROUP_MONITOR, AlertNotificationManager.groupFor(AlertNotificationManager.NOTIF_MONITOR))
        assertEquals(AlertNotificationManager.GROUP_MONITOR, AlertNotificationManager.groupFor(AlertNotificationManager.NOTIF_MONITORING_PAUSED))
        assertEquals(AlertNotificationManager.GROUP_MONITOR, AlertNotificationManager.groupFor(AlertNotificationManager.NOTIF_SLEEP))
        assertEquals(AlertNotificationManager.GROUP_ALERT, AlertNotificationManager.groupFor(AlertNotificationManager.NOTIF_ALERT))
        assertEquals(AlertNotificationManager.GROUP_ALERT, AlertNotificationManager.groupFor(AlertNotificationManager.NOTIF_ALLCLEAR))
        assertEquals(AlertNotificationManager.GROUP_OFFLINE, AlertNotificationManager.groupFor(AlertNotificationManager.NOTIF_MILESTONE))
        assertEquals(AlertNotificationManager.GROUP_OFFLINE, AlertNotificationManager.groupFor(AlertNotificationManager.NOTIF_OFFLINE_CRITICAL))
        assertEquals(AlertNotificationManager.GROUP_UPDATE, AlertNotificationManager.groupFor(AlertNotificationManager.NOTIF_UPDATE))
        assertEquals(AlertNotificationManager.GROUP_TALLY, AlertNotificationManager.groupFor(AlertNotificationManager.NOTIF_ALARM_EPISODE))
        assertEquals(AlertNotificationManager.GROUP_TALLY, AlertNotificationManager.groupFor(AlertNotificationManager.NOTIF_NEUTRALIZED))
    }

    /**
     * Two features sharing one id silently overwrite each other in the shade: the tally and the
     * critical-offline notice both sat on 6, so a reconnect that cancelled the offline row also
     * wiped the tally. One id, one owner — pinned here so it can never regress.
     */
    @Test
    fun `every notification id is unique`() {
        val ids = listOf(
            AlertNotificationManager.NOTIF_MONITOR,
            AlertNotificationManager.NOTIF_ALERT,
            AlertNotificationManager.NOTIF_ALLCLEAR,
            AlertNotificationManager.NOTIF_NEUTRALIZED,
            AlertNotificationManager.NOTIF_MILESTONE,
            AlertNotificationManager.NOTIF_OFFLINE_CRITICAL,
            AlertNotificationManager.NOTIF_UPDATE,
            AlertNotificationManager.NOTIF_ALARM_EPISODE,
            AlertNotificationManager.NOTIF_MONITORING_PAUSED,
            AlertNotificationManager.NOTIF_SLEEP
        )

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `buildNotification carries the id's group`() {
        val mgr = AlertNotificationManager(context)
        val n = mgr.buildNotification(
            AlertNotificationManager.NOTIF_ALERT,
            AlertNotificationManager.CHANNEL_ALERTS_INNER
        ).build()

        assertEquals(AlertNotificationManager.GROUP_ALERT, n.group)
    }

    @Test
    fun `createChannels defines every managed channel`() = runTest {
        AlertNotificationManager(context).createChannels()

        val ids = notificationManager().notificationChannels.map { it.id }.toSet()
        val expected = listOf(
            AlertNotificationManager.CHANNEL_MONITOR,
            AlertNotificationManager.CHANNEL_ALERTS_INNER,
            AlertNotificationManager.CHANNEL_ALERTS_OUTER,
            AlertNotificationManager.CHANNEL_ALL_CLEAR,
            AlertNotificationManager.CHANNEL_ALERTS_INNER_ALARM,
            AlertNotificationManager.CHANNEL_ALERTS_OUTER_ALARM,
            AlertNotificationManager.CHANNEL_OFFLINE,
            AlertNotificationManager.CHANNEL_OFFLINE_CRITICAL,
            AlertNotificationManager.CHANNEL_ALARM_EPISODE,
            AlertNotificationManager.CHANNEL_UPDATE,
            NeutralizedTally.CHANNEL_NEUTRALIZED
        )
        assertTrue("missing: ${expected - ids}", ids.containsAll(expected))
    }

    @Test
    fun `ensureMonitorChannel defines the monitor channel alone`() {
        AlertNotificationManager(context).ensureMonitorChannel()

        val ids = notificationManager().notificationChannels.map { it.id }
        assertTrue(AlertNotificationManager.CHANNEL_MONITOR in ids)
    }

    @Test
    fun `inner alert with siren override uses the inner alarm channel`() = runTest {
        AlertNotificationManager(context)
            .postAlertNotification(ThreatZone.INNER, "t", "b", sirenOverride = true)

        assertEquals(
            AlertNotificationManager.CHANNEL_ALERTS_INNER_ALARM,
            notification(AlertNotificationManager.NOTIF_ALERT).channelId
        )
    }

    @Test
    fun `outer alert without override uses the outer channel`() = runTest {
        AlertNotificationManager(context)
            .postAlertNotification(ThreatZone.OUTER, "t", "b", sirenOverride = false)

        assertEquals(
            AlertNotificationManager.CHANNEL_ALERTS_OUTER,
            notification(AlertNotificationManager.NOTIF_ALERT).channelId
        )
    }

    @Test
    fun `all-clear posts on its own channel and id`() {
        AlertNotificationManager(context).postAllClearNotification("t", "b")

        assertEquals(
            AlertNotificationManager.CHANNEL_ALL_CLEAR,
            notification(AlertNotificationManager.NOTIF_ALLCLEAR).channelId
        )
    }

    @Test
    fun `offline and critical-offline post on their channels`() {
        val mgr = AlertNotificationManager(context)
        mgr.postOfflineNotification("t", "b", "retry")
        mgr.postCriticalOfflineNotification("t", "b", "retry")

        assertEquals(
            AlertNotificationManager.CHANNEL_OFFLINE,
            notification(AlertNotificationManager.NOTIF_MILESTONE).channelId
        )
        assertEquals(
            AlertNotificationManager.CHANNEL_OFFLINE_CRITICAL,
            notification(AlertNotificationManager.NOTIF_OFFLINE_CRITICAL).channelId
        )
    }

    /**
     * The sleep notice is the one place these two keys deliberately disagree, so pin both.
     *
     * The CHANNEL is about loudness and is the critical-offline one: same message to the user
     * ("Oko could not warn you"), already IMPORTANCE_HIGH, so the notice is actually seen rather
     * than sitting mute in the shade.
     *
     * The GROUP is about bundling and stays the monitor one, alongside `NOTIF_MONITORING_PAUSED` —
     * the same "monitoring could not run" family. It keeps a single id, so a night of Doze windows
     * rewrites one row instead of stacking thirty.
     */
    @Test
    fun `sleep notice reuses one id on the loud channel, in the monitor group`() {
        val mgr = AlertNotificationManager(context)
        mgr.postSleepNotice("t", "b", "allow")

        val n = notification(AlertNotificationManager.NOTIF_SLEEP)
        assertEquals(AlertNotificationManager.CHANNEL_OFFLINE_CRITICAL, n.channelId)
        assertEquals(AlertNotificationManager.GROUP_MONITOR, n.group)
    }
}
