package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AirPlayHid
import org.junit.Assert.*
import org.junit.Test

class CarLifeInputMappingTest {
    @Test fun ordinarySwipesUseRelativeFocusReportsInBothDirections() {
        for ((x, y) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val state = CarLifeInputMapping.knobState(0, x, y)
            val report = AirPlayHid.knobReport(state)
            assertEquals(0, report[0].toInt())
            assertEquals(0, report[1].toInt())
            assertEquals(0, report[2].toInt())
            assertEquals(if (x != 0) x else y, report[3].toInt())
            assertFalse(state.select)
        }
    }

    @Test fun consecutiveStepsDoNotBecomeAnAbsolutePositionOrASelection() {
        repeat(4) {
            assertArrayEquals(byteArrayOf(0, 0, 0, 1),
                AirPlayHid.knobReport(CarLifeInputMapping.knobState(0, 1, 0)))
        }
    }

    @Test fun confirmationAndNavigationButtonsRemainMomentaryStates() {
        val select = CarLifeInputMapping.knobState(1, 0, 0)
        assertTrue(select.select)
        assertEquals(0, select.wheel)
        assertTrue(CarLifeInputMapping.knobState(2, 0, 0).home)
        assertTrue(CarLifeInputMapping.knobState(4, 0, 0).back)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0),
            AirPlayHid.knobReport(CarLifeInputMapping.knobState(0, 0, 0)))
    }

    @Test fun dominantAxisSuppliesTheFocusDirectionWithoutDiagonalDoubleSteps() {
        assertEquals(-2, CarLifeInputMapping.knobState(0, 1, -2).wheel)
        assertEquals(3, CarLifeInputMapping.knobState(0, 3, -1).wheel)
    }
}
