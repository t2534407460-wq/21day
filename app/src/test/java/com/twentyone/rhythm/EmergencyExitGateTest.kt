package com.twentyone.rhythm

import org.junit.Assert.*
import org.junit.Test

class EmergencyExitGateTest {
    @Test fun earlyPressAndWaitAloneCannotExit() {
        val gate=EmergencyExitGate(1000)
        assertFalse(gate.beginHold(60_999))
        assertFalse(gate.canExit(90_000))
        assertEquals(0L,gate.heldMillis(90_000))
    }
    @Test fun requiresFullWaitThenFiveContinuousSeconds() {
        val gate=EmergencyExitGate(1000)
        assertTrue(gate.beginHold(61_000))
        assertFalse(gate.canExit(65_999))
        assertTrue(gate.canExit(66_000))
        gate.cancelHold()
        assertFalse(gate.canExit(66_001))
    }
    @Test fun releasingDoesNotAccumulateShortHolds() {
        val gate=EmergencyExitGate(0)
        assertTrue(gate.beginHold(60_000));gate.cancelHold()
        assertTrue(gate.beginHold(64_000));gate.cancelHold()
        assertTrue(gate.beginHold(68_000))
        assertFalse(gate.canExit(72_999))
        assertTrue(gate.canExit(73_000))
        assertFalse(EmergencyExitGate(73_000).beginHold(73_001))
    }
}
