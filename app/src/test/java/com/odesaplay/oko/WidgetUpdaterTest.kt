package com.odesaplay.oko

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WidgetUpdaterTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `fresh snapshot store returns empty defaults`() = runTest {
        val snap = WidgetUpdater.readSnapshot(context)

        assertEquals(0, snap.threatCount)
        assertTrue(snap.typeCounts.isEmpty())
        assertNull(snap.activeZone)
        assertNull(snap.nearestKm)
        assertFalse(snap.officialAlert)
        assertFalse(snap.officialYellowAlert)
        assertFalse(snap.sourceOnline)
        assertFalse(snap.sourceDegraded)
        assertNull(snap.primaryThreat)
        assertEquals(0L, snap.updatedAtMs)
    }

    @Test
    fun `readLang defaults to english`() = runTest {
        assertEquals(AppLanguage.EN, WidgetUpdater.readLang(context))
    }

    @Test
    fun `readIconSet defaults to photo`() = runTest {
        assertEquals(ThreatIconSet.PHOTO, WidgetUpdater.readIconSet(context))
    }
}
