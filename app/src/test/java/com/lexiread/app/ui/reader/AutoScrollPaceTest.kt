package com.lexiread.app.ui.reader

import org.junit.Assert.*
import org.junit.Test

class AutoScrollPaceTest {
    @Test fun speedsMatchTheVisibleTimer() {
        assertEquals(listOf(70_000, 50_000, 35_000, 22_000, 14_000), (1..5).map(AutoScrollPace::intervalMillis))
        assertEquals(listOf(9, 15, 24, 36, 52), (1..5).map(AutoScrollPace::pixelsPerSecond))
    }
    @Test fun staleSpeedValuesCannotCrashTheReader() {
        assertEquals(70_000, AutoScrollPace.intervalMillis(0))
        assertEquals(52, AutoScrollPace.pixelsPerSecond(6))
    }
}
