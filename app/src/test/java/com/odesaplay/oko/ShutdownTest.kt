package com.odesaplay.oko

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.odesaplay.oko.service.AlertWakeup
import com.odesaplay.oko.service.AlertWatchdog
import com.odesaplay.oko.service.EmergencyResurrectionWorker
import com.odesaplay.oko.service.IgnoreRetryReceiver
import com.odesaplay.oko.service.MonitoringStatus
import com.odesaplay.oko.service.OfflineIgnore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * "Stop & exit" must leave nothing scheduled: the service stops AND the wakeup alarm plus
 * both periodic workers are disarmed, so nothing revives monitoring minutes later with a
 * tap-to-resume prompt. And the shade's Ignore tap dismisses its rows without starting the
 * service at all.
 */
@RunWith(RobolectricTestRunner::class)
class ShutdownTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        MonitoringStatus.setRunning(false)
        OfflineIgnore.reset()
        runBlocking { UserPrefs(context).clearAll() }
    }

    @After
    fun tearDown() {
        MonitoringStatus.setRunning(false)
        OfflineIgnore.reset()
    }

    private fun alarmArmed(): Boolean =
        Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .peekNextScheduledAlarm() != null

    private fun workGone(name: String): Boolean {
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
        return infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED }
    }

    @Test
    fun `shutdown stops the service and disarms every resurrection path`() {
        MonitoringStatus.setRunning(true)
        AlertWakeup.schedule(context)
        AlertWatchdog.schedule(context)
        EmergencyResurrectionWorker.schedule(context)

        AlertService.shutdown(context)

        assertEquals(AlertService::class.java.name, Shadows.shadowOf(context).nextStoppedService?.component?.className)
        assertTrue(!alarmArmed())
        assertTrue(workGone("oko_alert_watchdog"))
        assertTrue(workGone(EmergencyResurrectionWorker.UNIQUE_WORK_NAME))
    }

    @Test
    fun `ignore tap dismisses the offline rows and mutes without starting the service`() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                AlertNotificationManager.CHANNEL_OFFLINE,
                "offline",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val placeholder = NotificationCompat.Builder(context, AlertNotificationManager.CHANNEL_OFFLINE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("offline")
            .build()
        nm.notify(AlertNotificationManager.NOTIF_MILESTONE, placeholder)
        nm.notify(AlertNotificationManager.NOTIF_OFFLINE_CRITICAL, placeholder)

        IgnoreRetryReceiver().onReceive(context, Intent())

        val shadowNm = Shadows.shadowOf(nm)
        assertEquals(null, shadowNm.getNotification(AlertNotificationManager.NOTIF_MILESTONE))
        assertEquals(null, shadowNm.getNotification(AlertNotificationManager.NOTIF_OFFLINE_CRITICAL))
        assertTrue(OfflineIgnore.isMuted())
        assertEquals(null, Shadows.shadowOf(context).nextStartedService?.component?.className)
    }
}
