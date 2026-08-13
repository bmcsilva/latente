package io.github.bmcsilva.latente.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A correcção de vinhetagem, verificada por ida e volta.
 *
 * O teste que vale é o `aVignettedFieldIsFlattened`: sintetiza-se um mosaico com **exactamente** a
 * queda que o perfil descreve, aplica-se a correcção, e o resultado tem de ficar plano. Se a
 * interpolação, o mapeamento de raio ou a atribuição de canais estiverem errados, esse teste falha.
 */
class LensShadingTest {

    private val gbrg = intArrayOf(Demosaic.G, Demosaic.B, Demosaic.R, Demosaic.G)

    private val id0 = ShadingProfile.SM_S942B_ID0
    private val id2 = ShadingProfile.SM_S942B_ID2

    /** Constrói um mosaico com a queda do perfil aplicada a um campo uniforme. */
    private fun vignetted(w: Int, h: Int, valor: Float, p: ShadingProfile, cfa: IntArray): Mosaic {
        val m = Mosaic(FloatArray(w * h), w, h, cfa)
        val cx = w / 2.0f
        val cy = h / 2.0f
        val rmax = Math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val r = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / rmax
                // A queda é o recíproco do ganho: assim a ida e a volta são exactamente inversas.
                m.data[y * w + x] = valor / p.gain(m.colourAt(x, y), r)
            }
        }
        return m
    }

    // -----------------------------------------------------------------------------------------
    // O teste que apanha quase tudo
    // -----------------------------------------------------------------------------------------

    @Test
    fun aVignettedFieldIsFlattened() {
        for (p in listOf(id0, id2)) {
            val m = vignetted(200, 150, 0.5f, p, gbrg)
            LensShading.correct(m, p)
            for (y in 0 until m.height) {
                for (x in 0 until m.width) {
                    assertEquals("em ($x,$y)", 0.5f, m.data[y * m.width + x], 1e-4f)
                }
            }
        }
    }

    @Test
    fun theCentreIsBarelyTouched() {
        val m = Mosaic(FloatArray(101 * 101) { 1.0f }, 101, 101, gbrg)
        LensShading.correct(m, id0)
        val centro = m.data[50 * 101 + 50]
        assertEquals("o centro devia ficar praticamente igual", 1.0f, centro, 0.01f)
    }

    @Test
    fun theCornersAreLiftedByTheMeasuredAmount() {
        val m = Mosaic(FloatArray(200 * 150) { 1.0f }, 200, 150, gbrg)
        LensShading.correct(m, id0)
        // Canto superior esquerdo: em GBRG a posição (0,0) é verde.
        val ganhoCanto = m.data[0]
        assertEquals("o ganho no canto devia ser ~1/0,194", 1f / 0.19382f, ganhoCanto, 0.35f)
        assertTrue("o canto tem de subir muito", ganhoCanto > 4.0f)
    }

    // -----------------------------------------------------------------------------------------
    // A correcção é por canal, e tem de ser
    // -----------------------------------------------------------------------------------------

    /**
     * O vermelho cai ~15% mais que o verde no canto, nas duas objectivas. Corrigir com um ganho
     * único deixaria desvio de cor visível nas bordas.
     */
    @Test
    fun redNeedsMoreGainThanGreenAtTheCorner() {
        for (p in listOf(id0, id2)) {
            val gr = p.gain(Demosaic.R, 1.0f)
            val gg = p.gain(Demosaic.G, 1.0f)
            assertTrue("o vermelho devia precisar de mais ganho", gr > gg)
            val excesso = gr / gg - 1f
            assertTrue("o excesso devia rondar os 15%%, deu %.0f%%".format(excesso * 100),
                excesso > 0.10f && excesso < 0.25f)
        }
    }

    @Test
    fun theTwoLensesAgreeOnTheColourShading() {
        // Ser quase igual em duas ópticas diferentes é o que aponta para o filtro do sensor.
        val a = id0.red[id0.rings - 1] / id0.green[id0.rings - 1]
        val b = id2.red[id2.rings - 1] / id2.green[id2.rings - 1]
        assertEquals("as duas objectivas deviam concordar no desvio de cor", a, b, 0.02f)
    }

    // -----------------------------------------------------------------------------------------
    // Propriedades do perfil
    // -----------------------------------------------------------------------------------------

    @Test
    fun theGainGrowsMonotonicallyFromCentreToCorner() {
        for (p in listOf(id0, id2)) {
            for (c in intArrayOf(Demosaic.R, Demosaic.G, Demosaic.B)) {
                var anterior = 0f
                var r = 0f
                while (r <= 1.0f) {
                    val g = p.gain(c, r)
                    assertTrue("o ganho baixou em r=$r", g >= anterior - 1e-4f)
                    anterior = g
                    r += 0.02f
                }
            }
        }
    }

    @Test
    fun theGainAtTheCentreIsOne() {
        for (p in listOf(id0, id2)) {
            for (c in intArrayOf(Demosaic.R, Demosaic.G, Demosaic.B)) {
                assertEquals(1.0f, p.gain(c, 0f), 1e-3f)
            }
        }
    }

    @Test
    fun theWideLensLosesMoreLightThanTheMain() {
        assertTrue("a ultra-grande-angular devia perder mais",
            LensShading.maxGain(id2) > LensShading.maxGain(id0))
        // `maxGain` é o máximo **entre canais**, e o canal crítico é o vermelho — que cai mais.
        // No verde os ganhos são 5,16x e 7,32x; no vermelho, 6,04x e 8,52x.
        assertEquals(6.04f, LensShading.maxGain(id0), 0.2f)
        assertEquals(8.52f, LensShading.maxGain(id2), 0.3f)
        assertEquals(5.16f, id0.gain(Demosaic.G, 1.0f), 0.2f)
        assertEquals(7.32f, id2.gain(Demosaic.G, 1.0f), 0.3f)
    }

    @Test
    fun radiusIsClampedOutsideTheProfile() {
        val dentro = id0.gain(Demosaic.G, 1.0f)
        val fora = id0.gain(Demosaic.G, 3.0f)
        assertEquals("fora do perfil devia manter o último valor", dentro, fora, 1e-4f)
        assertEquals(1.0f, id0.gain(Demosaic.G, -1.0f), 1e-3f)
    }

    // -----------------------------------------------------------------------------------------
    // Selecção por dispositivo
    // -----------------------------------------------------------------------------------------

    @Test
    fun profilesAreOnlyOfferedForTheCalibratedDevice() {
        assertNotNull(ShadingProfile.forDevice("SM-S942B", "0"))
        assertNotNull(ShadingProfile.forDevice("SM-S942B", "2"))
        // As frontais não foram calibradas: não se inventa perfil.
        assertNull(ShadingProfile.forDevice("SM-S942B", "1"))
        assertNull(ShadingProfile.forDevice("SM-S942B", "3"))
        // Outro telefone não herda a calibração deste.
        assertNull(ShadingProfile.forDevice("Pixel 9", "0"))
    }

    // -----------------------------------------------------------------------------------------
    // A força da correcção é do utilizador
    // -----------------------------------------------------------------------------------------

    @Test
    fun zeroStrengthLeavesTheMosaicUntouched() {
        val m = Mosaic(FloatArray(64 * 48) { 0.3f }, 64, 48, gbrg)
        LensShading.correct(m, id0, 0f)
        for (v in m.data) assertEquals(0.3f, v, 1e-6f)
    }

    @Test
    fun fullStrengthIsTheDefault() {
        val a = Mosaic(FloatArray(64 * 48) { 0.3f }, 64, 48, gbrg)
        val b = Mosaic(FloatArray(64 * 48) { 0.3f }, 64, 48, gbrg)
        LensShading.correct(a, id0)
        LensShading.correct(b, id0, LensShading.FULL)
        for (i in a.data.indices) assertEquals(a.data[i], b.data[i], 1e-6f)
    }

    /** Metade da força é metade dos **stops**, não metade do factor. */
    @Test
    fun halfStrengthIsHalfTheStops() {
        val cheio = id0.gain(Demosaic.R, 1.0f)
        val metade = LensShading.applyStrength(cheio, 0.5f)
        assertEquals("devia ser a raiz quadrada do ganho",
            Math.sqrt(cheio.toDouble()).toFloat(), metade, 1e-4f)
        // E não a média linear, que é o erro fácil.
        val linear = 1f + (cheio - 1f) * 0.5f
        assertTrue("metade em stops não é metade linear", Math.abs(metade - linear) > 0.5f)

        val stopsCheio = Math.log(cheio.toDouble()) / Math.log(2.0)
        val stopsMetade = Math.log(metade.toDouble()) / Math.log(2.0)
        assertEquals(stopsCheio / 2.0, stopsMetade, 1e-6)
    }

    @Test
    fun strengthIsMonotonicAndClamped() {
        val g = id2.gain(Demosaic.R, 1.0f)
        var anterior = 0f
        var s = 0f
        while (s <= 1.0f) {
            val v = LensShading.applyStrength(g, s)
            assertTrue("não é monótona em s=$s", v >= anterior)
            anterior = v
            s += 0.05f
        }
        assertEquals(1f, LensShading.applyStrength(g, -1f), 1e-6f)
        assertEquals(g, LensShading.applyStrength(g, 5f), 1e-4f)
    }

    @Test
    fun theCornerStopsAreReportedForTheInterface() {
        // A principal amplifica o canto em ~2,6 stops à força total.
        assertEquals(2.59f, LensShading.stopsAtCorner(id0), 0.1f)
        // A ultra-grande-angular, ~3,1.
        assertEquals(3.09f, LensShading.stopsAtCorner(id2), 0.1f)
        // A meia força, metade dos stops.
        assertEquals(LensShading.stopsAtCorner(id0) / 2f,
            LensShading.stopsAtCorner(id0, 0.5f), 0.01f)
        assertEquals(0f, LensShading.stopsAtCorner(id0, 0f), 1e-6f)
    }

    @Test
    fun aProfileNeedsThreeChannelsOfTheSameLength() {
        var falhou = false
        try {
            ShadingProfile(floatArrayOf(1f, 0.5f), floatArrayOf(1f), floatArrayOf(1f, 0.5f))
        } catch (e: IllegalArgumentException) {
            falhou = true
        }
        assertTrue("canais de tamanhos diferentes deviam ser recusados", falhou)
    }
}
