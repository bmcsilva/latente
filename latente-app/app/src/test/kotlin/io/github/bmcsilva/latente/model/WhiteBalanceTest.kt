package io.github.bmcsilva.latente.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A parte de `WhiteBalance` que não depende do Android: a cromaticidade.
 *
 * Os valores de referência são os do locus de Planck e do locus da luz do dia, tabelados.
 */
class WhiteBalanceTest {

    @Test
    fun d65IsWhereItShouldBe() {
        val c = WhiteBalance.chromaticity(6504)
        assertEquals(0.3127, c[0], 0.002)
        assertEquals(0.3290, c[1], 0.002)
    }

    @Test
    fun d50IsWhereItShouldBe() {
        val c = WhiteBalance.chromaticity(5003)
        assertEquals(0.3457, c[0], 0.003)
        assertEquals(0.3585, c[1], 0.003)
    }

    @Test
    fun tungstenIsWarm() {
        val c = WhiteBalance.chromaticity(2856)
        assertEquals(0.4476, c[0], 0.004)
        assertEquals(0.4074, c[1], 0.004)
    }

    @Test
    fun chromaticityMovesTowardsBlueAsTemperatureRises() {
        var previousX = 1.0
        for (k in intArrayOf(2000, 2856, 4000, 5000, 6504, 9000, 15000)) {
            val x = WhiteBalance.chromaticity(k)[0]
            assertTrue("x devia descer com a temperatura (falhou em $k K)", x < previousX)
            previousX = x
        }
    }

    @Test
    fun extremesAreClamped() {
        val cold = WhiteBalance.chromaticity(500)
        val hot = WhiteBalance.chromaticity(100000)
        assertTrue(cold[0] > 0.0 && cold[1] > 0.0)
        assertTrue(hot[0] > 0.0 && hot[1] > 0.0)
        assertEquals(WhiteBalance.chromaticity(1667)[0], cold[0], 1e-9)
        assertEquals(WhiteBalance.chromaticity(25000)[0], hot[0], 1e-9)
    }

    /**
     * A tinta medida em doze chapas sob LED de interior: R e B a 0,90 do previsto pelo corpo
     * negro. O eixo tem de conseguir chegar lá.
     */
    @Test
    fun tintReachesTheMeasuredLedOffset() {
        assertEquals(1.0, WhiteBalance.tintMultiplier(0f), 1e-9)
        val alvo = 0.90
        val necessario = Math.log(alvo) / Math.log(2.0) / 0.5
        assertTrue("a tinta necessária devia caber no intervalo", necessario > -1.0 && necessario < 0.0)
        assertEquals(alvo, WhiteBalance.tintMultiplier(necessario.toFloat()), 0.001)
    }

    @Test
    fun tintIsMonotonicAndClamped() {
        assertTrue(WhiteBalance.tintMultiplier(-1f) < WhiteBalance.tintMultiplier(0f))
        assertTrue(WhiteBalance.tintMultiplier(0f) < WhiteBalance.tintMultiplier(1f))
        assertEquals(WhiteBalance.tintMultiplier(-1f), WhiteBalance.tintMultiplier(-5f), 1e-9)
        assertEquals(WhiteBalance.tintMultiplier(1f), WhiteBalance.tintMultiplier(5f), 1e-9)
        // Meio stop por unidade: ±41% nos extremos.
        assertEquals(0.7071, WhiteBalance.tintMultiplier(-1f), 0.001)
        assertEquals(1.4142, WhiteBalance.tintMultiplier(1f), 0.001)
    }

    @Test
    fun chromaticityStaysInsideTheHorseshoe() {
        for (k in 1667..25000 step 250) {
            val c = WhiteBalance.chromaticity(k)
            assertTrue("x fora do intervalo em $k K", c[0] > 0.2 && c[0] < 0.6)
            assertTrue("y fora do intervalo em $k K", c[1] > 0.2 && c[1] < 0.5)
            assertTrue("x+y > 1 em $k K", c[0] + c[1] < 1.0)
        }
    }
}
