package com.odesaplay.oko

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.odesaplay.oko.service.AlertWakeup
import com.odesaplay.oko.service.AlertWatchdog
import com.odesaplay.oko.service.MonitoringStatus
import com.odesaplay.oko.service.SuspendReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * Pausing the app from the OS is the user's strongest "stop". Suspension stops our activities and
 * hides our notifications, but it leaves a foreground service running — which is how a paused Oko
 * kept monitoring from "Active apps" while its watchdog kept resurrecting it. These tests pin both
 * halves: nothing automatic brings a paused monitor back, and un-pausing puts it back.
 *
 * The OS read itself (`SuspendState.isSuspended`) is not simulated — Robolectric cannot put a
 * package in the suspended state through any public API — so the rule it feeds is tested as the
 * pure decision [AlertService.mayStartMonitor], and the receiver is tested on its observable
 * effects.
 */
@RunWith(RobolectricTestRunner::class)
class SuspendTest {

    private lateinit var context: Application
    private val receiver = SuspendReceiver()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        MonitoringStatus.reset()
        runBlocking { UserPrefs(context).clearAll() }
    }

    @After
    fun tearDown() {
        MonitoringStatus.reset()
    }

    private fun receive(action: String) = receiver.onReceive(context, Intent(action))

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun alarmArmed() = Shadows.shadowOf(alarmManager()).peekNextScheduledAlarm() != null

    private fun startedService() =
        Shadows.shadowOf(context).nextStartedService?.component?.className

    private fun stoppedService() =
        Shadows.shadowOf(context).nextStoppedService?.component?.className

    @Test
    fun `monitoring may start whenever the package is not paused`() {
        assertTrue(AlertService.mayStartMonitor(suspended = false, fromUnpause = false))
    }

    @Test
    fun `a paused package is never started automatically`() {
        assertFalse(AlertService.mayStartMonitor(suspended = true, fromUnpause = false))
    }

    @Test
    fun `the un-pause broadcast is the authority that the pause is over`() {
        assertTrue(AlertService.mayStartMonitor(suspended = true, fromUnpause = true))
        assertTrue(AlertService.mayStartMonitor(suspended = false, fromUnpause = true))
    }

    @Test
    fun `pause stops the service and cancels the wakeup chain`() {
        AlertWakeup.schedule(context)
        assertTrue(alarmArmed())

        receive(Intent.ACTION_MY_PACKAGE_SUSPENDED)

        assertEquals(AlertService::class.java.name, stoppedService())
        assertFalse(alarmArmed())
    }

    @Test
    fun `un-pause restores the wakeup chain, the watchdog and monitoring`() {
        runBlocking { UserPrefs(context).setBootRestartEnabled(true) }

        receive(Intent.ACTION_MY_PACKAGE_UNSUSPENDED)

        assertEquals(AlertService::class.java.name, startedService())
        assertTrue(alarmArmed())
        val watchdog = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("oko_alert_watchdog").get()
        assertTrue(watchdog.isNotEmpty())
    }

    @Test
    fun `un-pause leaves monitoring stopped when auto-restart is off`() {
        runBlocking { UserPrefs(context).setBootRestartEnabled(false) }

        receive(Intent.ACTION_MY_PACKAGE_UNSUSPENDED)

        assertNull(startedService())
        assertFalse(alarmArmed())
    }

    @Test
    fun `the watchdog still resurrects a dead monitor`() {
        runBlocking { UserPrefs(context).setBootRestartEnabled(true) }

        runBlocking { TestListenableWorkerBuilder<AlertWatchdog>(context).build().doWork() }

        assertEquals(AlertService::class.java.name, startedService())
    }
}
