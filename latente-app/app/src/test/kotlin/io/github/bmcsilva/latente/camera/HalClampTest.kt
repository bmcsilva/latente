package io.github.bmcsilva.latente.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Limites do dispositivo de referência (SM-S942B, câmara principal):
 * exposição 85 000 ns … 100 000 000 ns, ISO 25…3200, foco até 10 dioptrias, f/1,8 fixo.
 */
class HalClampTest {

    private val minExp = 85_000L
    private val maxExp = 100_000_000L
    private val maxFrame = 142_857_142L

    @Test
    fun exposureWithinRangeIsUntouched() {
        val a = HalClamp.exposure(4_000_000L, minExp, maxExp)
        assertEquals(4_000_000L, a.applied)
        assertFalse(a.clamped)
    }

    @Test
    fun exposureAboveCeilingIsClamped() {
        // pedir 1 s no dispositivo de referência: o tecto é 1/10 s
        val a = HalClamp.exposure(1_000_000_000L, minExp, maxExp)
        assertEquals(maxExp, a.applied)
        assertTrue(a.clamped)
        assertEquals(1_000_000_000L, a.requested)
    }

    @Test
    fun exposureBelowFloorIsClamped() {
        val a = HalClamp.exposure(1_000L, minExp, maxExp)
        assertEquals(minExp, a.applied)
        assertTrue(a.clamped)
    }

    @Test
    fun isoIsClampedBothWays() {
        assertEquals(25, HalClamp.iso(10, 25, 3200).applied)
        assertEquals(3200, HalClamp.iso(12800, 25, 3200).applied)
        assertFalse(HalClamp.iso(640, 25, 3200).clamped)
    }

    @Test
    fun focusIsClampedToMinimumDistance() {
        assertEquals(0f, HalClamp.focus(-1f, 10f).applied)
        assertEquals(10f, HalClamp.focus(25f, 10f).applied)
        assertEquals(2.5f, HalClamp.focus(2.5f, 10f).applied)
        assertTrue(HalClamp.focus(25f, 10f).clamped)
    }

    @Test
    fun apertureSnapsToNearestAvailable() {
        val fixed = floatArrayOf(1.8f)
        assertEquals(1.8f, HalClamp.aperture(4.0f, fixed)!!.applied)
        assertTrue(HalClamp.aperture(4.0f, fixed)!!.clamped)

        val variable = floatArrayOf(1.63f, 2.0f, 2.8f, 4.0f)
        assertEquals(2.8f, HalClamp.aperture(2.7f, variable)!!.applied)
        assertEquals(1.63f, HalClamp.aperture(1.0f, variable)!!.applied)
    }

    @Test
    fun apertureIsNullWhenLensDeclaresNone() {
        assertNull(HalClamp.aperture(2.0f, null))
        assertNull(HalClamp.aperture(2.0f, floatArrayOf()))
    }

    @Test
    fun apertureDefaultsToFirstWhenNothingRequested() {
        val a = HalClamp.aperture(null, floatArrayOf(1.8f))!!
        assertEquals(1.8f, a.applied)
        assertFalse(a.clamped)
    }

    /**
     * O stream RAW de 12,5 MP corre a 30 fps: a duração mínima de frame é 33,3 ms. Pedir menos
     * fazia o HAL descartar a captura, e foi o que estragou cinco experiências da F1.
     */
    @Test
    fun frameDurationNeverGoesBelowStreamMinimum() {
        val minFrame = 33_333_333L
        assertEquals(minFrame, HalClamp.frameDuration(1_000_000L, minFrame))
        assertEquals(minFrame, HalClamp.frameDuration(8_000_000L, minFrame))
        assertEquals(minFrame, HalClamp.frameDuration(minFrame, minFrame))
    }

    @Test
    fun frameDurationFollowsLongExposures() {
        val minFrame = 33_333_333L
        assertEquals(100_000_000L, HalClamp.frameDuration(100_000_000L, minFrame))
        // Uma exposição de 1 s foi honrada no dispositivo de referência, apesar de o
        // SENSOR_INFO_MAX_FRAME_DURATION declarar 142,9 ms. Não se limita ao declarado.
        assertEquals(1_000_000_000L, HalClamp.frameDuration(1_000_000_000L, minFrame))
        assertTrue(HalClamp.frameDuration(1_000_000_000L, minFrame) > maxFrame)
    }

    @Test
    fun frameDurationToleratesUnknownMinimum() {
        assertEquals(8_000_000L, HalClamp.frameDuration(8_000_000L, 0L))
    }

    @Test
    fun usefulBitsComeFromWhiteLevel() {
        assertEquals(10, HalClamp.usefulBits(1023))
        assertEquals(12, HalClamp.usefulBits(4095))
        assertEquals(14, HalClamp.usefulBits(16383))
        assertEquals(16, HalClamp.usefulBits(65535))
        assertEquals(0, HalClamp.usefulBits(0))
    }
}
