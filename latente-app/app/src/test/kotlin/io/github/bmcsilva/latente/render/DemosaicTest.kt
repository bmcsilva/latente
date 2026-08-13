package io.github.bmcsilva.latente.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O *demosaicing* verificado por invariantes.
 *
 * Não há GPU aqui, mas não é preciso: a matemática é a mesma que vai para o shader, e estes testes
 * apanham praticamente qualquer erro de implementação. O mais importante é o campo uniforme — os
 * filtros do Malvar-He-Cutler somam todos 8 e são divididos por 8, logo um campo uniforme tem de
 * atravessar o algoritmo **exactamente** inalterado, incluindo nas bordas. Se um coeficiente
 * estiver trocado ou um vizinho mal indexado, esse teste falha.
 */
class DemosaicTest {

    /** GBRG: o mosaico da câmara principal do dispositivo de referência. */
    private val gbrg = intArrayOf(Demosaic.G, Demosaic.B, Demosaic.R, Demosaic.G)

    /** RGGB: o das restantes câmaras. */
    private val rggb = intArrayOf(Demosaic.R, Demosaic.G, Demosaic.G, Demosaic.B)

    private fun uniform(w: Int, h: Int, v: Float, cfa: IntArray) =
        Mosaic(FloatArray(w * h) { v }, w, h, cfa)

    // -----------------------------------------------------------------------------------------
    // O teste que apanha quase tudo
    // -----------------------------------------------------------------------------------------

    @Test
    fun malvarLeavesAUniformFieldExactlyUnchanged() {
        for (cfa in listOf(gbrg, rggb)) {
            val m = uniform(32, 24, 0.42f, cfa)
            val out = Demosaic.malvar(m)
            for (y in 0 until out.height) {
                for (x in 0 until out.width) {
                    val p = out.pixel(x, y)
                    assertEquals("R em ($x,$y)", 0.42f, p[0], 1e-5f)
                    assertEquals("G em ($x,$y)", 0.42f, p[1], 1e-5f)
                    assertEquals("B em ($x,$y)", 0.42f, p[2], 1e-5f)
                }
            }
        }
    }

    /**
     * As bordas são onde os erros de indexação aparecem. Reflectir a coordenada preserva a paridade
     * do CFA; limitar trocaria as cores nas duas primeiras colunas e linhas.
     */
    @Test
    fun theBordersAreAlsoExactOnAUniformField() {
        val m = uniform(16, 16, 1.0f, gbrg)
        val out = Demosaic.malvar(m)
        val cantos = listOf(0 to 0, 15 to 0, 0 to 15, 15 to 15, 1 to 1, 14 to 14)
        for ((x, y) in cantos) {
            val p = out.pixel(x, y)
            assertEquals("R em ($x,$y)", 1.0f, p[0], 1e-5f)
            assertEquals("G em ($x,$y)", 1.0f, p[1], 1e-5f)
            assertEquals("B em ($x,$y)", 1.0f, p[2], 1e-5f)
        }
    }

    // -----------------------------------------------------------------------------------------
    // A cor nativa nunca é adivinhada
    // -----------------------------------------------------------------------------------------

    @Test
    fun theNativeSampleIsKeptExactly() {
        val w = 20
        val h = 20
        val data = FloatArray(w * h) { 0.3f + (it % 7) * 0.05f }
        val m = Mosaic(data, w, h, gbrg)
        val out = Demosaic.malvar(m)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val nativa = m.colourAt(x, y)
                val medida = data[y * w + x]
                assertEquals("a amostra nativa em ($x,$y) foi alterada",
                    medida, out.pixel(x, y)[nativa], 1e-6f)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // O CFA é lido, não presumido
    // -----------------------------------------------------------------------------------------

    /**
     * Os mesmos dados com CFA diferente têm de dar cores diferentes. Presumir RGGB daria cor
     * trocada exactamente na câmara mais importante deste telefone, que é GBRG.
     */
    @Test
    fun theSameDataWithADifferentCfaGivesDifferentColours() {
        val w = 16
        val h = 16
        // Só as posições (0,0) do quarteto ficam a 1; as outras a zero.
        val data = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x % 2 == 0 && y % 2 == 0) 1.0f else 0.0f
        }
        val comGbrg = Demosaic.malvar(Mosaic(data, w, h, gbrg)).pixel(8, 8)
        val comRggb = Demosaic.malvar(Mosaic(data, w, h, rggb)).pixel(8, 8)

        // Em GBRG a posição (0,0) é verde; em RGGB é vermelha. O canal aceso tem de mudar.
        assertTrue("em GBRG o verde devia dominar", comGbrg[1] > comGbrg[0])
        assertTrue("em RGGB o vermelho devia dominar", comRggb[0] > comRggb[1])
    }

    // -----------------------------------------------------------------------------------------
    // Suavidade: um gradiente não deve ganhar ondulação
    // -----------------------------------------------------------------------------------------

    @Test
    fun aHorizontalRampStaysSmooth() {
        val w = 64
        val h = 16
        val data = FloatArray(w * h) { i -> (i % w) / (w - 1).toFloat() }
        val out = Demosaic.malvar(Mosaic(data, w, h, gbrg))

        // Longe das bordas, a segunda derivada de uma rampa linear deve ser ~0 em todos os canais.
        for (c in 0 until 3) {
            for (y in 4 until h - 4) {
                for (x in 4 until w - 4) {
                    val d2 = out.pixel(x - 1, y)[c] - 2f * out.pixel(x, y)[c] + out.pixel(x + 1, y)[c]
                    assertTrue("ondulação no canal $c em ($x,$y): $d2", Math.abs(d2) < 0.02f)
                }
            }
        }
    }

    @Test
    fun aRampIsReproducedWithTheRightSlope() {
        val w = 64
        val h = 16
        val data = FloatArray(w * h) { i -> (i % w) / (w - 1).toFloat() }
        val out = Demosaic.malvar(Mosaic(data, w, h, gbrg))
        val esperado = 1.0f / (w - 1)
        for (c in 0 until 3) {
            val medido = out.pixel(40, 8)[c] - out.pixel(39, 8)[c]
            assertEquals("declive errado no canal $c", esperado, medido, 0.004f)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Binning 2x2
    // -----------------------------------------------------------------------------------------

    @Test
    fun binningAveragesTheTwoGreensAndKeepsRandB() {
        // Um só quarteto GBRG: G=0.2, B=0.4, R=0.6, G=0.8
        val data = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f)
        val out = Demosaic.bin2x2(Mosaic(data, 2, 2, gbrg))
        assertEquals(1, out.width)
        assertEquals(1, out.height)
        val p = out.pixel(0, 0)
        assertEquals("R", 0.6f, p[0], 1e-6f)
        assertEquals("G, média dos dois", 0.5f, p[1], 1e-6f)
        assertEquals("B", 0.4f, p[2], 1e-6f)
    }

    @Test
    fun binningHalvesTheResolution() {
        val out = Demosaic.bin2x2(uniform(4080, 3060, 0.5f, gbrg))
        assertEquals(2040, out.width)
        assertEquals(1530, out.height)
    }

    @Test
    fun binningLeavesAUniformFieldUnchanged() {
        val out = Demosaic.bin2x2(uniform(64, 64, 0.37f, rggb))
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val p = out.pixel(x, y)
                assertEquals(0.37f, p[0], 1e-6f)
                assertEquals(0.37f, p[1], 1e-6f)
                assertEquals(0.37f, p[2], 1e-6f)
            }
        }
    }

    /**
     * O *binning* não pode inventar nada: cada valor de saída tem de estar entre o mínimo e o
     * máximo das amostras que o originaram. É a diferença entre média e interpolação.
     */
    @Test
    fun binningNeverInventsValuesOutsideTheInput() {
        val w = 32
        val h = 32
        val data = FloatArray(w * h) { i -> ((i * 37) % 101) / 100.0f }
        val out = Demosaic.bin2x2(Mosaic(data, w, h, gbrg))
        val lo = data.min()
        val hi = data.max()
        for (v in out.data) {
            assertTrue("$v fora de [$lo, $hi]", v >= lo - 1e-6f && v <= hi + 1e-6f)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Dimensões e contratos
    // -----------------------------------------------------------------------------------------

    @Test
    fun malvarKeepsTheFullResolution() {
        val out = Demosaic.malvar(uniform(40, 30, 0.5f, gbrg))
        assertEquals(40, out.width)
        assertEquals(30, out.height)
        assertEquals(40 * 30 * 3, out.data.size)
    }

    @Test
    fun theMosaicRejectsAnIncoherentCfa() {
        var falhou = false
        try {
            Mosaic(FloatArray(16), 4, 4, intArrayOf(0, 1, 2))
        } catch (e: IllegalArgumentException) {
            falhou = true
        }
        assertTrue("um CFA com três posições devia ser recusado", falhou)
    }

    @Test
    fun reflectionAtTheBorderPreservesCfaParity() {
        val m = uniform(8, 8, 1.0f, gbrg)
        // Reflectir tem de devolver uma posição com a mesma cor nativa que a original.
        for (x in 0 until 4) {
            assertEquals("paridade em x=-$x", m.colourAt(x, 0), m.colourAt(-x, 0))
        }
        for (y in 0 until 4) {
            assertEquals("paridade em y=-$y", m.colourAt(0, y), m.colourAt(0, -y))
        }
    }
}
