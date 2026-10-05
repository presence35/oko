package com.odesaplay.oko

import android.content.Context
import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
class AlertWakeLockManagerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun latestWakeLock(): PowerManager.WakeLock = ShadowPowerManager.getLatestWakeLock()

    @Test
    fun `acquireForAlert holds and release frees`() {
        val mgr = AlertWakeLockManager(context)

        mgr.acquireForAlert()
        assertTrue(latestWakeLock().isHeld)

        mgr.release()
        assertFalse(latestWakeLock().isHeld)
    }

    @Test
    fun `double acquire is idempotent and one release clears it`() {
        val mgr = AlertWakeLockManager(context)

        mgr.acquireForAlert()
        mgr.acquireForAlert()
        assertTrue(latestWakeLock().isHeld)

        mgr.release()
        assertFalse(latestWakeLock().isHeld)
    }

    @Test
    fun `release without acquire is a no-op`() {
        val mgr = AlertWakeLockManager(context)

        mgr.release()

        assertFalse(latestWakeLock().isHeld)
    }
}
