package com.odesaplay.oko.service

import android.app.Application
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.odesaplay.oko.AlertService
import com.odesaplay.oko.UserPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class AlertWatchdogTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        MonitoringStatus.setRunning(false)
        runBlocking { UserPrefs(context).clearAll() }
    }

    @After
    fun tearDown() {
        MonitoringStatus.setRunning(false)
    }

    private fun runWorker() = runBlocking {
        TestListenableWorkerBuilder<AlertWatchdog>(context).build().doWork()
    }

    @Test
    fun `no-op while monitoring is already running`() {
        MonitoringStatus.setRunning(true)

        runWorker()

        assertNull(Shadows.shadowOf(context).nextStartedService)
    }

    @Test
    fun `no-op when auto-restart is off`() {
        runBlocking { UserPrefs(context).setBootRestartEnabled(false) }

        runWorker()

        assertNull(Shadows.shadowOf(context).nextStartedService)
    }

    @Test
    fun `starts the service when down and auto-restart is on`() {
        runWorker()

        assertEquals(AlertService::class.java.name, Shadows.shadowOf(context).nextStartedService?.component?.className)
    }
}
