package top.m7a.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGeometryTest {
    @Test fun fullHdLandscapeShrinksWithoutDistortion() {
        assertEquals(CaptureDimensions(1280, 720), captureDimensions(2560, 1440))
        assertEquals(CaptureDimensions(1280, 720), captureDimensions(1920, 1080))
    }

    @Test fun portraitAndSmallDisplay() {
        assertEquals(CaptureDimensions(576, 1280), captureDimensions(1080, 2400))
        assertEquals(CaptureDimensions(720, 480), captureDimensions(720, 480))
        assertEquals(CaptureDimensions(1, 1280), captureDimensions(1, 3000))
    }

    @Test fun rejectNonsensicalSizes() {
        assertTrue(runCatching { captureDimensions(0, 1080) }.isFailure)
        assertTrue(runCatching { captureDimensions(1920, -2) }.isFailure)
        assertTrue(runCatching { captureDimensions(1920, 1080, 0) }.isFailure)
    }
}
