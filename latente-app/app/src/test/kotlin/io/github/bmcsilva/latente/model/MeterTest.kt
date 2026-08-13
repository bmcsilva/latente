package io.github.bmcsilva.latente.model

import io.github.bmcsilva.latente.render.Demosaic
import io.github.bmcsilva.latente.render.ShadingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * O fotómetro, medido sobre planos RAW sintéticos.
 *
 * Um `ByteBuffer` é Java puro, portanto isto corre na JVM sobre exactamente o mesmo código que corre no
 * telefone — não há versão de teste nem simulação. Uma exposição mal medida não dá excepção nenhuma: dá
 * uma fotografia queimada, e ninguém repara até ser tarde.
 */
class MeterTest {

    /** O mosaico do dispositivo de referência. */
    private val gbrg = intArrayOf(Demosaic.G, Demosaic.B, Demosaic.R, Demosaic.G)
    private val semPreto = FloatArray(4)
    private val branco = 1023

    private val largura = 64
    private val altura = 48

    /**
     * Um plano onde cada posição do mosaico leva o valor que a função der.
     *
     * @param valor recebe (x, y, cor) e devolve o valor cru.
     */
    private fun plano(
        largura: Int = this.largura,
        altura: Int = this.altura,
        strideBytes: Int = largura * 2,
        valor: (Int, Int, Int) -> Int,
    ): ByteBuffer {
        val passo = strideBytes / 2
        val b = ByteBuffer.allocateDirect(passo * altura * 2).order(ByteOrder.LITTLE_ENDIAN)
        val s = b.asShortBuffer()
        for (y in 0 until altura) {
            for (x in 0 until largura) {
                val cor = gbrg[(y and 1) * 2 + (x and 1)]
                s.put(y * passo + x, valor(x, y, cor).toShort())
            }
        }
        b.position(0)
        return b
    }

    private fun medir(p: ByteBuffer, strideBytes: Int = largura * 2, step: Int = 1) =
        Meter.measure(p, largura, altura, strideBytes, gbrg, branco, semPreto, step)

    // -----------------------------------------------------------------------------------------
    // Só o verde
    // -----------------------------------------------------------------------------------------

    /**
     * O vermelho e o azul não entram na medição. Se entrassem, uma cena vermelha mediria diferente de
     * uma cena verde com a mesma luminância — e a exposição mudaria com a cor do assunto.
     */
    @Test
    fun onlyTheGreenChannelIsMeasured() {
        // Verde a meio, vermelho e azul no corte.
        val r = medir(plano { _, _, cor -> if (cor == Demosaic.G) 512 else 1023 })
        assertEquals("a média devia ser só do verde", 0.5, r.mean, 0.01)
        assertEquals("nada devia estar no corte", 0.0, r.clipped, 1e-9)
    }

    /** Metade das amostras de um mosaico são verdes: duas em cada quadrado de quatro. */
    @Test
    fun halfOfTheMosaicIsGreen() {
        val r = medir(plano { _, _, _ -> 100 })
        assertEquals(largura * altura / 2, r.samples)
    }

    // -----------------------------------------------------------------------------------------
    // Níveis
    // -----------------------------------------------------------------------------------------

    @Test
    fun aFlatPlaneAtHalfWhiteMeasuresHalf() {
        val r = medir(plano { _, _, _ -> 512 })
        assertEquals(0.5, r.mean, 0.01)
        assertEquals(0.5, r.median, 0.01)
        assertEquals(0.5, r.highlight, 0.01)
        assertEquals("meio caminho é um stop de margem", 1.0, r.headroomStops, 0.05)
    }

    @Test
    fun aClippedPlaneReportsFullClipping() {
        val r = medir(plano { _, _, _ -> branco })
        assertEquals(1.0, r.clipped, 1e-9)
        assertEquals(1.0, r.highlight, 1e-9)
        assertEquals("sem margem nenhuma", 0.0, r.headroomStops, 1e-6)
    }

    /** O nível de preto subtrai-se por posição do mosaico, como no revelador. */
    @Test
    fun theBlackLevelIsSubtractedPerMosaicPosition() {
        val preto = floatArrayOf(64f, 0f, 0f, 64f)
        val p = plano { _, _, _ -> 64 }
        val r = Meter.measure(p, largura, altura, largura * 2, gbrg, branco, preto, 1)
        assertEquals("com o pedestal subtraído, isto é preto", 0.0, r.mean, 1e-6)
    }

    // -----------------------------------------------------------------------------------------
    // Percentil e não máximo
    // -----------------------------------------------------------------------------------------

    /**
     * Uns poucos píxeis no corte não devem governar a exposição.
     *
     * Um sensor tem sempre píxeis quentes e reflexos especulares. Se o fotómetro usasse o máximo, meia
     * dúzia deles subexporia a fotografia toda — e é por isso que se usa o percentil 99,5.
     */
    @Test
    fun aFewHotPixelsDoNotGovernTheExposure() {
        // 0,2% das amostras de verde no corte; o resto a um quarto do branco.
        val verdesTotais = largura * altura / 2
        val quantosNoCorte = (verdesTotais * 0.002).toInt().coerceAtLeast(1)
        var postos = 0
        val p = plano { _, _, cor ->
            if (cor == Demosaic.G && postos < quantosNoCorte) {
                postos++
                branco
            } else {
                256
            }
        }
        val r = medir(p)
        assertEquals("o percentil devia ignorá-los", 0.25, r.highlight, 0.02)
        assertTrue("mas o corte tem de ser reportado", r.clipped > 0.0)
        assertTrue("e é pouco", r.clipped < 0.01)
    }

    /** Com muitas amostras no corte, o percentil já as conta — e tem de contar. */
    @Test
    fun realClippingDoesReachThePercentile() {
        var postos = 0
        val limite = largura * altura / 2 / 10   // 10% dos verdes
        val p = plano { _, _, cor ->
            if (cor == Demosaic.G && postos < limite) {
                postos++
                branco
            } else {
                256
            }
        }
        val r = medir(p)
        assertEquals(1.0, r.highlight, 1e-9)
        assertTrue(r.clipped > 0.09)
    }

    // -----------------------------------------------------------------------------------------
    // Robustez
    // -----------------------------------------------------------------------------------------

    /**
     * O passo de linha não é necessariamente `largura * 2`.
     *
     * Ignorá-lo lia a imagem enviesada, e as estatísticas ficariam de uma imagem que não existe. Neste
     * telefone o passo calha alinhado, o que faria o erro passar em claro até um dispositivo onde não
     * calhe.
     */
    @Test
    fun aPaddedRowStrideDoesNotSkewTheReading() {
        val stride = (largura + 17) * 2
        val p = plano(strideBytes = stride) { _, _, cor ->
            if (cor == Demosaic.G) 512 else 0
        }
        val r = medir(p, strideBytes = stride)
        assertEquals(0.5, r.mean, 0.01)
        assertEquals(largura * altura / 2, r.samples)
    }

    /**
     * Sub-amostrar não muda a leitura de uma cena uniforme, só o número de amostras.
     *
     * A conta fica fixada aqui porque é dela que depende o custo do fotómetro. Num plano de 64×48 há
     * 32×24 quadrados de mosaico; com passo 4 visitam-se 8×6 deles, e cada um dá dois verdes: 96
     * amostras contra 1536. No frame real, 4080×3060 com passo 8 dá cerca de 195 mil — menos de 2% do
     * frame, e é por isso que isto corre na CPU sem custar nada.
     */
    @Test
    fun subsamplingKeepsTheReadingAndCutsTheWork() {
        val p = plano { _, _, _ -> 512 }
        val todos = medir(p, step = 1)
        val poucos = medir(p, step = 4)
        assertEquals(todos.mean, poucos.mean, 1e-6)
        assertEquals("todos os verdes", 64 * 48 / 2, todos.samples)
        assertEquals("8 x 6 quadrados, dois verdes cada", 96, poucos.samples)
    }

    // -----------------------------------------------------------------------------------------
    // A vinhetagem entra na medição
    // -----------------------------------------------------------------------------------------

    /**
     * O fotómetro tem de contar com o ganho que a revelação vai aplicar.
     *
     * Medido num ensaio real: RAW com máximo 787 de 1023, folgado, e a revelação com **50,8% dos
     * píxeis no corte** — a correcção de vinhetagem multiplica até 6× nas bordas. O ficheiro ficava
     * bom e o visor mostrava queimado, o que é o contrário da promessa.
     */
    @Test
    fun theShadingGainIsCountedBecauseTheDevelopmentWillApplyIt() {
        val p = plano { _, _, _ -> 200 }
        val sem = Meter.measure(p, largura, altura, largura * 2, gbrg, branco, semPreto, 1)
        val com = Meter.measure(p, largura, altura, largura * 2, gbrg, branco, semPreto, 1,
            ShadingProfile.SM_S942B_ID0, 1f)

        assertTrue("com o perfil, o campo plano mede mais brilhante nas bordas",
            com.mean > sem.mean * 1.5)
        assertTrue("e as luzes altas sobem muito", com.highlight > sem.highlight * 2)
        assertEquals("sem perfil não há ganho nenhum", 200.0 / branco, sem.mean, 0.01)
    }

    /** Força zero é o mesmo que não haver perfil: quem desliga a correcção desliga-a em todo o lado. */
    @Test
    fun zeroStrengthMeasuresTheSameAsNoProfile() {
        val p = plano { _, _, _ -> 300 }
        val a = Meter.measure(p, largura, altura, largura * 2, gbrg, branco, semPreto, 1)
        val b = Meter.measure(p, largura, altura, largura * 2, gbrg, branco, semPreto, 1,
            ShadingProfile.SM_S942B_ID0, 0f)
        assertEquals(a.mean, b.mean, 1e-9)
        assertEquals(a.highlight, b.highlight, 1e-9)
    }

    @Test
    fun anImpossiblePlaneGivesAnInvalidReadingInsteadOfThrowing() {
        val r = Meter.measure(
            ByteBuffer.allocateDirect(8), 0, 0, 0, gbrg, branco, semPreto, 1)
        assertFalse(r.valid)
        assertEquals(0, r.samples)
    }
}
