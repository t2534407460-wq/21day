package com.twentyone.rhythm

import org.junit.Test
import org.junit.Assert.*

class OrbSelectionTest {
    @Test fun centreAndFarOutsideCancelWithoutSaving() {
        assertNull(OrbSelection.target(0f,0f,100f,4))
        assertNull(OrbSelection.target(30f,-10f,100f,4))
        assertNull(OrbSelection.target(0f,-200f,100f,4))
    }
    @Test fun fourDirectionsMatchVisibleChoices() {
        assertEquals(0,OrbSelection.target(0f,-100f,100f,4))
        assertEquals(1,OrbSelection.target(100f,0f,100f,4))
        assertEquals(2,OrbSelection.target(0f,100f,100f,4))
        assertEquals(3,OrbSelection.target(-100f,0f,100f,4))
    }
    @Test fun gapsDoNotAccidentallySelectAdjacentChoice() {
        assertNull(OrbSelection.target(90f,-90f,100f,4))
        assertEquals(1,OrbSelection.target(87f,50f,100f,3))
        assertEquals(5,OrbSelection.target(-87f,-50f,100f,6))
    }
}
