package com.odesaplay.oko

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.odesaplay.oko.engine.MonitorCoreImpl
import com.odesaplay.oko.service.EmergencyResurrectionWorker
import com.odesaplay.oko.service.MonitoringStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        MonitoringStatus.reset()
        context.getSharedPreferences(MonitorCoreImpl.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        runBlocking { UserPrefs(context).clearAll() }
    }

    @After
    fun tearDown() {
        MonitoringStatus.reset()
    }

    private fun boot(action: String = Intent.ACTION_BOOT_COMPLETED) =
        BootReceiver().onReceive(context, Intent(action))

    @Test
    fun `boot with an active alert starts the service`() {
        context.getSharedPreferences(MonitorCoreImpl.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(MonitorCoreImpl.KEY_HAD_ACTIVE_ALERT, true).commit()

        boot()

        assertEquals(AlertService::class.java.name, Shadows.shadowOf(context).nextStartedService?.component?.className)
    }

    @Test
    fun `boot without an active alert and auto-restart off posts the paused prompt`() {
        runBlocking { UserPrefs(context).setBootRestartEnabled(false) }

        boot()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(Shadows.shadowOf(nm).getNotification(AlertNotificationManager.NOTIF_MONITORING_PAUSED))
    }

    @Test
    fun `package replaced is treated like a boot`() {
        context.getSharedPreferences(MonitorCoreImpl.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(MonitorCoreImpl.KEY_HAD_ACTIVE_ALERT, true).commit()

        boot(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertEquals(AlertService::class.java.name, Shadows.shadowOf(context).nextStartedService?.component?.className)
    }

    @Test
    fun `boot schedules both watchdogs`() {
        boot()

        val wm = WorkManager.getInstance(context)
        assertTrue(wm.getWorkInfosForUniqueWork("oko_alert_watchdog").get().isNotEmpty())
        assertTrue(wm.getWorkInfosForUniqueWork(EmergencyResurrectionWorker.UNIQUE_WORK_NAME).get().isNotEmpty())
    }
}
