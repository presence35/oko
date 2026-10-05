package com.odesaplay.oko

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import com.odesaplay.oko.engine.ThreatZone
import com.odesaplay.oko.service.MonitoringStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
class AlertServiceTest {

    private lateinit var controller: ServiceController<AlertService>

    @Before
    fun setUp() {
        controller = Robolectric.buildService(AlertService::class.java).create()
    }

    @After
    fun tearDown() {
        controller.destroy()
        MonitoringStatus.setRunning(false)
    }

    private fun service() = controller.get()

    private fun notificationManager() =
        service().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `onCreate promotes the monitor notification to the foreground`() {
        assertNotNull(Shadows.shadowOf(service()).lastForegroundNotification)
    }

    @Test
    fun `onBind returns null`() {
        assertNull(service().onBind(Intent()))
    }

    @Test
    fun `onStartCommand returns sticky`() {
        assertEquals(Service.START_STICKY, service().onStartCommand(Intent(), 0, 1))
    }

    @Test
    fun `alert-ok action cancels the alert notification`() {
        runBlocking {
            AlertNotificationManager(service())
                .postAlertNotification(ThreatZone.OUTER, "t", "b", sirenOverride = false)
        }
        val nm = Shadows.shadowOf(notificationManager())
        assertNotNull(nm.getNotification(AlertNotificationManager.NOTIF_ALERT))

        service().onStartCommand(Intent(AlertService.ACTION_ALERT_OK), 0, 1)

        assertNull(nm.getNotification(AlertNotificationManager.NOTIF_ALERT))
    }

    @Test
    fun `ignore-retry action cancels the offline notifications`() {
        val mgr = AlertNotificationManager(service())
        mgr.postOfflineNotification("t", "b", "retry")
        mgr.postCriticalOfflineNotification("t", "b", "retry")
        val nm = Shadows.shadowOf(notificationManager())
        assertNotNull(nm.getNotification(AlertNotificationManager.NOTIF_MILESTONE))
        assertNotNull(nm.getNotification(AlertNotificationManager.NOTIF_OFFLINE_CRITICAL))

        service().onStartCommand(Intent(AlertService.ACTION_IGNORE_RETRY), 0, 1)

        assertNull(nm.getNotification(AlertNotificationManager.NOTIF_MILESTONE))
        assertNull(nm.getNotification(AlertNotificationManager.NOTIF_OFFLINE_CRITICAL))
    }
}
