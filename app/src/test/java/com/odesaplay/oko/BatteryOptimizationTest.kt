package com.odesaplay.oko

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class BatteryOptimizationTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `xiaomi maps to aggressive with an auto-start intent`() {
        BatteryOptimization.setSimulatedOem(context, "Xiaomi")
        val info = BatteryOptimization.getOemInfo(context)

        assertTrue(info.isAggressive)
        assertTrue(info.hasAutoStartIntent)
        assertEquals("Xiaomi", info.manufacturer)
    }

    @Test
    fun `samsung is aggressive without an auto-start intent`() {
        BatteryOptimization.setSimulatedOem(context, "samsung")
        val info = BatteryOptimization.getOemInfo(context)

        assertTrue(info.isAggressive)
        assertFalse(info.hasAutoStartIntent)
    }

    @Test
    fun `unlisted vendor defaults to aggressive without auto-start`() {
        BatteryOptimization.setSimulatedOem(context, "Google")
        val info = BatteryOptimization.getOemInfo(context)

        assertTrue(info.isAggressive)
        assertFalse(info.hasAutoStartIntent)
    }

    @Test
    fun `clearing the simulation reverts to automatic detection`() {
        BatteryOptimization.setSimulatedOem(context, "Xiaomi")
        assertEquals("Xiaomi", BatteryOptimization.getSimulatedOem(context))

        BatteryOptimization.setSimulatedOem(context, null)
        assertNull(BatteryOptimization.getSimulatedOem(context))
    }

    @Test
    fun `ignoring battery optimizations follows the power manager`() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val shadow = Shadows.shadowOf(pm)

        shadow.setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(BatteryOptimization.isIgnoringBatteryOptimizations(context))

        shadow.setIgnoringBatteryOptimizations(context.packageName, false)
        assertFalse(BatteryOptimization.isIgnoringBatteryOptimizations(context))
    }

    @Test
    fun `oem-aware request launches the vendor page for xiaomi`() {
        BatteryOptimization.setSimulatedOem(context, "Xiaomi")

        val launched = BatteryOptimization.requestExemptionOemAware(context)

        assertTrue(launched)
        val started = Shadows.shadowOf(context as Application).nextStartedActivity
        assertEquals("com.miui.securitycenter", started?.component?.packageName)
    }

    @Test
    fun `oem-aware request falls back to the standard dialog for an unknown vendor`() {
        BatteryOptimization.setSimulatedOem(context, "Google")

        val launched = BatteryOptimization.requestExemptionOemAware(context)

        assertFalse(launched)
        val started = Shadows.shadowOf(context as Application).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, started?.action)
    }
}
