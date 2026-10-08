package com.blainemiller.scripturealone.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomStepsTest {
    @Test fun anUltraWideAddsHalf() {
        assertEquals(listOf(0.5f, 1f, 2f, 3f, 5f), zoomSteps(minZoom = 0.5f, maxZoom = 10f))
        // A camera that goes wider than 0.5× still offers 0.5×.
        assertEquals(listOf(0.5f, 1f, 2f), zoomSteps(minZoom = 0.4f, maxZoom = 2f))
    }

    @Test fun aNarrowerUltraWideIsOfferedAtItsOwnWidest() {
        assertEquals(listOf(0.6f, 1f, 2f), zoomSteps(minZoom = 0.6f, maxZoom = 2.5f))
    }

    @Test fun noUltraWideNoHalf() {
        assertEquals(listOf(1f, 2f, 3f), zoomSteps(minZoom = 1f, maxZoom = 4f))
        assertEquals(listOf(1f), zoomSteps(minZoom = 1f, maxZoom = 1f))
    }
}
