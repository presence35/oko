package com.odesaplay.oko.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PendingAlarmTest {

    private fun snd(id: Int) = PendingAlarm.Sound(id, leftVol = 1f, rightVol = 1f, priority = 10, loopCount = 0, rate = 1f)

    @Test
    fun `loaded sample plays immediately`() {
        val p = PendingAlarm()
        assertEquals(PendingAlarm.Decision.PLAY, p.onRequest(snd(5), loaded = true))
        assertNull(p.onLoaded(5, ok = true))
    }

    @Test
    fun `unloaded sample queues then plays when loaded`() {
        val p = PendingAlarm()
        assertEquals(PendingAlarm.Decision.QUEUE, p.onRequest(snd(5), loaded = false))
        val played = p.onLoaded(5, ok = true)
        assertNotNull(played)
        assertEquals(5, played!!.sampleId)
    }

    @Test
    fun `newer request supersedes an older pending one`() {
        val p = PendingAlarm()
        p.onRequest(snd(5), loaded = false)
        p.onRequest(snd(6), loaded = false)
        assertNull(p.onLoaded(5, ok = true))
        assertEquals(6, p.onLoaded(6, ok = true)!!.sampleId)
    }

    @Test
    fun `zero id is dropped`() {
        val p = PendingAlarm()
        assertEquals(PendingAlarm.Decision.DROP, p.onRequest(snd(0), loaded = false))
    }

    @Test
    fun `failed load drops the pending sound`() {
        val p = PendingAlarm()
        p.onRequest(snd(5), loaded = false)
        assertNull(p.onLoaded(5, ok = false))
    }
}
