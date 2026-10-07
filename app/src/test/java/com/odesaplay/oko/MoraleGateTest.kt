package com.odesaplay.oko

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MoraleGateTest {

    private val source = MutableStateFlow(UserPreferences.DEFAULT)

    /**
     * The gate is the single mirror of the master pref. Every flourish engine now gates on
     * this one value, so "off" has to propagate — a gate that lagged the pref would leave the
     * flourish running while the settings row already read off.
     */
    @Test
    fun `the gate mirrors the master pref`() = runTest {
        val gate = MoraleGate()
        gate.bind(source, TestScope(testScheduler))
        advanceUntilIdle()

        assertTrue("default master is on", gate.isOn)

        source.value = source.value.copy(moraleMasterEnabled = false)
        advanceUntilIdle()
        assertFalse(gate.isOn)

        source.value = source.value.copy(moraleMasterEnabled = true)
        advanceUntilIdle()
        assertTrue(gate.isOn)
    }

    /**
     * The reaction side is what makes the master a kill-switch rather than a mute: flipping it
     * off has to tear down whatever is in flight, and it has to do so exactly once per flip.
     */
    @Test
    fun `an engine tears down once per flip to off`() = runTest {
        val gate = MoraleGate()
        gate.bind(source, TestScope(testScheduler))
        gate.ejectWhenOff(TestScope(testScheduler)) { ejects++ }
        advanceUntilIdle()

        assertEquals("starting on must not eject", 0, ejects)

        source.value = source.value.copy(moraleMasterEnabled = false)
        advanceUntilIdle()
        assertEquals(1, ejects)

        // Staying off is not a new decision — no second teardown.
        advanceUntilIdle()
        assertEquals(1, ejects)

        source.value = source.value.copy(moraleMasterEnabled = true)
        advanceUntilIdle()
        source.value = source.value.copy(moraleMasterEnabled = false)
        advanceUntilIdle()
        assertEquals("a real flip back on and off ejects again", 2, ejects)
    }

    /**
     * An engine that subscribes while the master is already off (the ViewModel outliving a
     * settings change, a tally built after the flip) must tear down immediately rather than
     * sit armed until the next transition.
     */
    @Test
    fun `subscribing while already off tears down at once`() = runTest {
        source.value = source.value.copy(moraleMasterEnabled = false)
        val gate = MoraleGate()
        gate.bind(source, TestScope(testScheduler))
        advanceUntilIdle()

        var late = 0
        gate.ejectWhenOff(TestScope(testScheduler)) { late++ }
        advanceUntilIdle()

        assertEquals(1, late)
    }

    private var ejects = 0
}