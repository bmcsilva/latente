package io.github.bmcsilva.latente.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixa a `ColorScience` aos valores da implementação de referência em Python.
 *
 * As matrizes são as reais do dispositivo de referência (samsung SM-S942B, câmara principal), lidas
 * do DNG em `dados-de-teste/chapa-plana.dng`. Os resultados esperados foram calculados pelo
 * `tools/develop.py`, que por sua vez foi validado contra uma revelação neutra do darktable.
 *
 * Se algum destes testes falhar, é regressão no porte — não é dúvida sobre a cor.
 */
class ColorScienceTest {

    private val fm1 = doubleArrayOf(
        0.616210938, 0.130859375, 0.216796875,
        0.200195312, 0.757812500, 0.041992188,
        -0.000976562, -0.408203125, 1.233398438,
    )

    private val fm2 = doubleArrayOf(
        0.450195312, 0.176757812, 0.337890625,
        0.055664062, 0.753906250, 0.189453125,
        -0.137695312, -0.831054688, 1.793945312,
    )

    private val cm1 = doubleArrayOf(
        0.838867188, -0.193359375, -0.137695312,
        -0.440429688, 1.393554688, 0.023437500,
        -0.098632812, 0.307617188, 0.401367188,
    )

    /** D65 e Standard A: os iluminantes que este dispositivo declara. */
    private val kelvin1 = 6504.0
    private val kelvin2 = 2856.0

    private val asShotNeutral = doubleArrayOf(0.487304688, 1.0, 0.593750000)

    // -----------------------------------------------------------------------------------------
    // A propriedade que confirma a convenção da especificação DNG
    // -----------------------------------------------------------------------------------------

    /**
     * A `ForwardMatrix` mapeia o neutro (1,1,1) para o branco D50. Somando cada linha tem de dar
     * o D50 — é isto que prova que o espaço de chegada é D50 e que a adaptação é necessária.
     */
    @Test
    fun forwardMatrixMapsNeutralToD50() {
        val branco = ColorScience.matVec(fm1, doubleArrayOf(1.0, 1.0, 1.0))
        assertEquals(ColorScience.D50[0], branco[0], 0.002)
        assertEquals(ColorScience.D50[1], branco[1], 0.002)
        assertEquals(ColorScience.D50[2], branco[2], 0.002)
    }

    @Test
    fun theSecondForwardMatrixAlsoMapsToD50() {
        val branco = ColorScience.matVec(fm2, doubleArrayOf(1.0, 1.0, 1.0))
        assertEquals(ColorScience.D50[0], branco[0], 0.002)
        assertEquals(ColorScience.D50[1], branco[1], 0.002)
        assertEquals(ColorScience.D50[2], branco[2], 0.002)
    }

    // -----------------------------------------------------------------------------------------
    // Álgebra
    // -----------------------------------------------------------------------------------------

    @Test
    fun inverseTimesOriginalIsIdentity() {
        val inv = ColorScience.matInv(cm1)
        assertNotNull(inv)
        val id = ColorScience.matMul(inv!!, cm1)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                assertEquals(if (i == j) 1.0 else 0.0, id[i * 3 + j], 1e-9)
            }
        }
    }

    @Test
    fun singularMatrixHasNoInverse() {
        val singular = doubleArrayOf(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 1.0, 1.0, 1.0)
        assertEquals(null, ColorScience.matInv(singular))
    }

    @Test
    fun bradfordMovesD50WhiteOntoD65White() {
        val m = ColorScience.bradford(ColorScience.D50, ColorScience.D65)
        val out = ColorScience.matVec(m, ColorScience.D50)
        assertEquals(ColorScience.D65[0], out[0], 1e-9)
        assertEquals(ColorScience.D65[1], out[1], 1e-9)
        assertEquals(ColorScience.D65[2], out[2], 1e-9)
    }

    @Test
    fun bradfordToTheSameWhiteIsIdentity() {
        val m = ColorScience.bradford(ColorScience.D65, ColorScience.D65)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                assertEquals(if (i == j) 1.0 else 0.0, m[i * 3 + j], 1e-9)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Interpolação entre iluminantes
    // -----------------------------------------------------------------------------------------

    @Test
    fun interpolationReturnsTheExactMatrixAtEachEndpoint() {
        val quente = ColorScience.interpolateByCct(fm1, kelvin1, fm2, kelvin2, 6504)
        val frio = ColorScience.interpolateByCct(fm1, kelvin1, fm2, kelvin2, 2856)
        for (i in 0 until 9) {
            assertEquals("posição $i no quente", fm1[i], quente[i], 1e-12)
            assertEquals("posição $i no frio", fm2[i], frio[i], 1e-12)
        }
    }

    @Test
    fun interpolationIsClampedOutsideTheRange() {
        val acima = ColorScience.interpolateByCct(fm1, kelvin1, fm2, kelvin2, 25000)
        val abaixo = ColorScience.interpolateByCct(fm1, kelvin1, fm2, kelvin2, 1500)
        for (i in 0 until 9) {
            assertEquals(fm1[i], acima[i], 1e-12)
            assertEquals(fm2[i], abaixo[i], 1e-12)
        }
    }

    /** Valores do `tools/develop.py` a 5500 K. Interpolação em 1/T, não em T. */
    @Test
    fun interpolationAt5500MatchesThePythonReference() {
        val esperado = doubleArrayOf(
            0.592485001, 0.137418899, 0.234102852,
            0.179539791, 0.757254243, 0.063066402,
            -0.020515569, -0.468634481, 1.313508364,
        )
        val fm = ColorScience.interpolateByCct(fm1, kelvin1, fm2, kelvin2, 5500)
        for (i in 0 until 9) {
            assertEquals("posição $i", esperado[i], fm[i], 1e-8)
        }
    }

    @Test
    fun interpolationInOneOverTemperatureIsNotLinearInTemperature() {
        // A meio caminho em T (4680 K) não é a meio caminho nas matrizes; se fosse, a
        // interpolação estaria a ser feita em T e não em 1/T.
        val meio = ColorScience.interpolateByCct(fm1, kelvin1, fm2, kelvin2, 4680)
        val mediaSimples = (fm1[0] + fm2[0]) / 2.0
        assertTrue("a interpolação parece linear em T", Math.abs(meio[0] - mediaSimples) > 0.01)
    }

    // -----------------------------------------------------------------------------------------
    // A cadeia completa, contra o oráculo
    // -----------------------------------------------------------------------------------------

    @Test
    fun theFullChainMatchesThePythonReference() {
        val esperado = doubleArrayOf(
            1.576536072, -0.563808961, -0.012751938,
            -0.236568217, 1.300827192, -0.064346093,
            -0.027315912, -0.822063218, 1.848198945,
        )
        val fm = ColorScience.interpolateByCct(fm1, kelvin1, fm2, kelvin2, 5500)
        val m = ColorScience.cameraToOutput(fm, ColorScience.Output.SRGB)
        // Tolerância de 1e-6, não menos: a referência em Python usa a matriz de Bradford
        // D50→D65 **publicada**, enquanto esta classe a **deriva** dos pontos brancos. As duas
        // vias concordam a cerca de 2e-7, o que é em si uma validação — mas não são idênticas
        // ao bit.
        for (i in 0 until 9) {
            assertEquals("posição $i", esperado[i], m[i], 1e-6)
        }
    }

    /** Uma conversão inteira, ponta a ponta, contra o valor que o Python devolveu. */
    @Test
    fun oneWholeConversionMatchesThePythonReference() {
        val fm = ColorScience.interpolateByCct(fm1, kelvin1, fm2, kelvin2, 5500)
        val m = ColorScience.cameraToOutput(fm, ColorScience.Output.SRGB)
        val ganhos = ColorScience.whiteBalanceGains(asShotNeutral)

        val cam = doubleArrayOf(0.30, 0.55, 0.22)
        val balanceado = doubleArrayOf(
            cam[0] * ganhos[0], cam[1] * ganhos[1], cam[2] * ganhos[2])
        assertEquals(0.615631263, balanceado[0], 1e-8)
        assertEquals(0.550000000, balanceado[1], 1e-8)
        assertEquals(0.370526316, balanceado[2], 1e-8)

        val out = ColorScience.matVec(m, balanceado)
        assertEquals(0.655745036, out[0], 1e-7)
        assertEquals(0.545974245, out[1], 1e-7)
        assertEquals(0.215855047, out[2], 1e-7)
    }

    /**
     * O neutro da cena tem de sair neutro. É o critério do cartão de cinza da §9, aqui em forma
     * de teste: um pixel igual ao `AsShotNeutral` deve dar R = G = B na saída.
     */
    @Test
    fun theSceneNeutralComesOutNeutral() {
        val fm = ColorScience.interpolateByCct(fm1, kelvin1, fm2, kelvin2, 5500)
        val m = ColorScience.cameraToOutput(fm, ColorScience.Output.SRGB)
        val ganhos = ColorScience.whiteBalanceGains(asShotNeutral)
        val cinza = doubleArrayOf(
            asShotNeutral[0] * ganhos[0],
            asShotNeutral[1] * ganhos[1],
            asShotNeutral[2] * ganhos[2])
        val out = ColorScience.matVec(m, cinza)
        assertEquals("R e G divergem", out[0], out[1], 0.02)
        assertEquals("B e G divergem", out[2], out[1], 0.02)
    }

    @Test
    fun displayP3IsWiderThanSrgb() {
        val fm = ColorScience.interpolateByCct(fm1, kelvin1, fm2, kelvin2, 5500)
        val srgb = ColorScience.cameraToOutput(fm, ColorScience.Output.SRGB)
        val p3 = ColorScience.cameraToOutput(fm, ColorScience.Output.DISPLAY_P3)
        // Num espaço mais largo os coeficientes fora da diagonal são menores em módulo: é preciso
        // menos correcção para chegar às primárias.
        val foraSrgb = Math.abs(srgb[1]) + Math.abs(srgb[2]) + Math.abs(srgb[3])
        val foraP3 = Math.abs(p3[1]) + Math.abs(p3[2]) + Math.abs(p3[3])
        assertTrue("o P3 devia exigir menos correcção que o sRGB", foraP3 < foraSrgb)
    }

    // -----------------------------------------------------------------------------------------
    // Codificação e rolloff
    // -----------------------------------------------------------------------------------------

    @Test
    fun srgbRoundTrips() {
        for (i in 0..100) {
            val v = i / 100.0
            assertEquals(v, ColorScience.srgbDecode(ColorScience.srgbEncode(v)), 1e-9)
        }
    }

    @Test
    fun srgbHasTheKnownAnchors() {
        assertEquals(0.0, ColorScience.srgbEncode(0.0), 1e-12)
        assertEquals(1.0, ColorScience.srgbEncode(1.0), 1e-12)
        // 18% de cinzento linear dá cerca de 46% codificado.
        assertEquals(0.4613, ColorScience.srgbEncode(0.18), 0.001)
    }

    @Test
    fun rolloffWithWhitePointOneIsIdentity() {
        for (i in 0..100) {
            val v = i / 100.0
            assertEquals(v, ColorScience.rolloff(v, 1.0), 1e-12)
        }
    }

    @Test
    fun rolloffCompressesOnlyTheTop() {
        // Em baixo é praticamente linear.
        assertEquals(0.01, ColorScience.rolloff(0.01, 1.6), 0.001)
        // No topo comprime: com ponto branco 1,6, uma entrada de 1,0 sai a
        // (1 + 1/2,56) / 2 = 0,6953.
        assertEquals(0.6953125, ColorScience.rolloff(1.0, 1.6), 1e-7)
        assertTrue(ColorScience.rolloff(1.0, 1.6) < 1.0)
        // E o ponto branco é onde a curva chega a 1.
        assertEquals(1.0, ColorScience.rolloff(1.6, 1.6), 1e-9)
        // Monótono.
        var anterior = -1.0
        for (i in 0..200) {
            val v = ColorScience.rolloff(i / 100.0, 1.6)
            assertTrue("não é monótono em $i", v > anterior)
            anterior = v
        }
    }

    @Test
    fun floatConversionKeepsRowOrder() {
        val f = ColorScience.toFloats(fm1)
        assertEquals(9, f.size)
        for (i in 0 until 9) {
            assertEquals(fm1[i].toFloat(), f[i], 1e-7f)
        }
    }
}
