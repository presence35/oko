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

    /** One id, one posting role. The monitoring family is ungrouped (no key at all), the tally
     *  is the group itself — a summary is the only shape the platform's app-level bundle cannot
     *  collect — and everything else is a child. */
    @Test
    fun `postingFor maps each id to exactly one role`() {
        val a = AlertNotificationManager
        assertEquals(AlertNotificationManager.Posting.Standalone, a.postingFor(a.NOTIF_MONITOR))
        assertEquals(AlertNotificationManager.Posting.Standalone, a.postingFor(a.NOTIF_MONITORING_PAUSED))
        assertEquals(AlertNotificationManager.Posting.Standalone, a.postingFor(a.NOTIF_SLEEP))
        assertEquals(AlertNotificationManager.Posting.Child(a.GROUP_ALERT), a.postingFor(a.NOTIF_ALERT))
        assertEquals(AlertNotificationManager.Posting.Child(a.GROUP_ALERT), a.postingFor(a.NOTIF_ALLCLEAR))
        assertEquals(AlertNotificationManager.Posting.Child(a.GROUP_OFFLINE), a.postingFor(a.NOTIF_MILESTONE))
        assertEquals(AlertNotificationManager.Posting.Child(a.GROUP_OFFLINE), a.postingFor(a.NOTIF_OFFLINE_CRITICAL))
        assertEquals(AlertNotificationManager.Posting.Child(a.GROUP_UPDATE), a.postingFor(a.NOTIF_UPDATE))
        assertEquals(AlertNotificationManager.Posting.Summary(a.GROUP_TALLY), a.postingFor(a.NOTIF_NEUTRALIZED))
        assertEquals(AlertNotificationManager.Posting.Child(a.GROUP_EPISODE), a.postingFor(a.NOTIF_ALARM_EPISODE))
    }

    /** An unmapped id used to inherit the tally's group through a catch-all `else`, so a new
     *  notification silently rode into a bundle nobody chose. It must fail loudly instead. */
    @Test(expected = IllegalStateException::class)
    fun `postingFor rejects an unmapped id`() {
        AlertNotificationManager.postingFor(4242)
    }

    /** The trident's row must arrive with no group key: nothing the app names can claim it. It is
     *  not proof of standing alone — a summary-less row can still be swept into the platform's
     *  app bundle — which is why the tally is a summary instead. */
    @Test
    fun `monitor notification is posted ungrouped`() {
        val mgr = AlertNotificationManager(context)
        mgr.ensureMonitorChannel()
        mgr.safeNotify(AlertNotificationManager.NOTIF_MONITOR, mgr.buildMonitorNotification("t", "b"))

        val n = notification(AlertNotificationManager.NOTIF_MONITOR)
        assertEquals(null, n.group)
        assertEquals(AlertNotificationManager.CHANNEL_MONITOR, n.channelId)
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

    /**
     * The tally's fix, pinned. As a summary-less child of `oko_tally` it rode the platform's
     * app-level bundle, and the always-on trident row was in that same bundle — the "1 Drone
     * resolved" row appeared welded to the monitor. The key was never what separated it: the
     * summary flag is, because a group summary is never collected as a bundle child.
     */
    @Test
    fun `the tally is posted as its own group summary`() {
        val mgr = AlertNotificationManager(context)
        val n = mgr.buildNotification(
            AlertNotificationManager.NOTIF_NEUTRALIZED,
            NeutralizedTally.CHANNEL_NEUTRALIZED
        ).build()

        assertEquals(AlertNotificationManager.GROUP_TALLY, n.group)
        assertTrue((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0)
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
        mgr.postOfflineNotification("t", "b")
        mgr.postCriticalOfflineNotification("t", "b")

        assertEquals(
            AlertNotificationManager.CHANNEL_OFFLINE,
            notification(AlertNotificationManager.NOTIF_MILESTONE).channelId
        )
        assertEquals(
            AlertNotificationManager.CHANNEL_OFFLINE_CRITICAL,
            notification(AlertNotificationManager.NOTIF_OFFLINE_CRITICAL).channelId
        )
    }

    /** The single-action-owner rule: the escalations escalate, the ongoing row is where the user
     *  acts. A Retry button on a one-shot would give one outage two owners and two tap targets, and
     *  the tap could not say which row it came from. */
    @Test
    fun `offline escalations carry no actions, the monitor row owns them`() {
        val mgr = AlertNotificationManager(context)
        mgr.postOfflineNotification("t", "b")
        mgr.postCriticalOfflineNotification("t", "b")

        assertTrue(notification(AlertNotificationManager.NOTIF_MILESTONE).actions.isNullOrEmpty())
        assertTrue(notification(AlertNotificationManager.NOTIF_OFFLINE_CRITICAL).actions.isNullOrEmpty())
        assertTrue(mgr.buildMonitorNotification("t", "b", "retry").actions?.isNotEmpty() == true)
    }

    /**
     * The sleep notice is the one place these two keys deliberately disagree, so pin both.
     *
     * The CHANNEL is about loudness and is the critical-offline one: same message to the user
     * ("Oko could not warn you"), already IMPORTANCE_HIGH, so the notice is actually seen rather
     * than sitting mute in the shade.
     *
     * The GROUP is about bundling and stays unset, with the rest of the monitoring family
     * (`NOTIF_MONITOR`, `NOTIF_MONITORING_PAUSED`): the only role the platform's app bundle
     * cannot collect is a summary, and this row has no children to summarise, so its key stays
     * off. It keeps a single id, so a night of Doze windows rewrites one row instead of stacking
     * thirty.
     */
    @Test
    fun `sleep notice reuses one id on the loud channel, ungrouped`() = runTest {
        val mgr = AlertNotificationManager(context)
        mgr.createChannels()
        mgr.postSleepNotice("t", "b", "allow")

        val n = notification(AlertNotificationManager.NOTIF_SLEEP)
        assertEquals(AlertNotificationManager.CHANNEL_OFFLINE_CRITICAL, n.channelId)
        assertEquals(null, n.group)
    }
}
