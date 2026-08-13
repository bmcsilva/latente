package io.github.bmcsilva.latente.export

import io.github.bmcsilva.latente.render.ColorScience
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * O escritor de TIFF, verificado **relendo o que escreve**.
 *
 * A máquina de leitura de TIFF já existe no `DngReader` — um DNG é um TIFF. Reutilizá-la para
 * validar a escrita fecha o ciclo: se o escritor puser uma etiqueta no sítio errado ou com o tipo
 * errado, o leitor não a encontra.
 */
class Tiff16WriterTest {

    private val largura = 7
    private val altura = 5

    private fun escrever(
        icc: ByteArray? = null,
        descricao: String? = null,
        pixels: ShortArray = ShortArray(largura * altura * 3) { (it * 700).toShort() },
    ): ByteArray {
        val o = ByteArrayOutputStream()
        Tiff16Writer.write(o, pixels, largura, altura, icc, descricao)
        return o.toByteArray()
    }

    private fun ler(bytes: ByteArray): Map<Int, DngReader.Entry> {
        val c = DngReader.Cursor(bytes, little = false)
        assertEquals(42, c.u16(2))
        return c.ifd(c.u32(4).toInt())
    }

    // -----------------------------------------------------------------------------------------

    @Test
    fun theStructureIsAValidBaselineTiff() {
        val b = escrever()
        assertEquals('M'.code.toByte(), b[0])
        assertEquals('M'.code.toByte(), b[1])

        val ifd = ler(b)
        val c = DngReader.Cursor(b, little = false)
        assertEquals(largura.toLong(), ifd[256]!!.longs(c)[0])
        assertEquals(altura.toLong(), ifd[257]!!.longs(c)[0])
        assertEquals(1L, ifd[259]!!.longs(c)[0])       // sem compressão
        assertEquals(2L, ifd[262]!!.longs(c)[0])       // RGB
        assertEquals(3L, ifd[277]!!.longs(c)[0])       // três amostras
        assertEquals(1L, ifd[284]!!.longs(c)[0])       // entrelaçado
    }

    @Test
    fun everyChannelIsSixteenBits() {
        val b = escrever()
        val c = DngReader.Cursor(b, little = false)
        val bits = ler(b)[258]!!.longs(c)
        assertEquals(3, bits.size)
        for (v in bits) assertEquals(16L, v)
    }

    @Test
    fun theSampleFormatSaysUnsignedInteger() {
        val b = escrever()
        val c = DngReader.Cursor(b, little = false)
        for (v in ler(b)[339]!!.longs(c)) assertEquals(1L, v)
    }

    /** O tamanho declarado tem de bater com o que lá está, ou os leitores lêem lixo. */
    @Test
    fun theDeclaredByteCountMatchesTheActualData() {
        val b = escrever()
        val c = DngReader.Cursor(b, little = false)
        val ifd = ler(b)
        val offset = ifd[273]!!.longs(c)[0].toInt()
        val contagem = ifd[279]!!.longs(c)[0].toInt()
        assertEquals(largura * altura * 3 * 2, contagem)
        assertEquals("o ficheiro devia acabar onde os dados acabam",
            b.size, offset + contagem)
    }

    /** As amostras têm de sobreviver à ida e volta, em big-endian e sem trocas de canal. */
    @Test
    fun thePixelsSurviveTheRoundTrip() {
        val pixels = ShortArray(largura * altura * 3) { ((it * 2311) % 65536).toShort() }
        val b = escrever(pixels = pixels)
        val c = DngReader.Cursor(b, little = false)
        val offset = ler(b)[273]!!.longs(c)[0].toInt()
        for (i in pixels.indices) {
            val hi = b[offset + i * 2].toInt() and 0xFF
            val lo = b[offset + i * 2 + 1].toInt() and 0xFF
            assertEquals("amostra $i", pixels[i].toInt() and 0xFFFF, (hi shl 8) or lo)
        }
    }

    // -----------------------------------------------------------------------------------------
    // O perfil ICC
    // -----------------------------------------------------------------------------------------

    @Test
    fun theIccProfileIsEmbeddedWhenGiven() {
        val icc = Tiff16Writer.iccFor(ColorScience.Output.DISPLAY_P3)
        val b = escrever(icc = icc)
        val c = DngReader.Cursor(b, little = false)
        val entrada = ler(b)[34675]
        assertTrue("a etiqueta ICC devia estar lá", entrada != null)
        assertEquals(icc.size, entrada!!.count)
        val lido = entrada.bytes(c)
        for (i in icc.indices) assertEquals("byte $i do perfil", icc[i], lido[i])
    }

    @Test
    fun withoutAProfileThereIsNoIccTag() {
        assertTrue("sem perfil não devia haver etiqueta", ler(escrever())[34675] == null)
    }

    @Test
    fun theProfileHeaderIsWellFormed() {
        for (saida in ColorScience.Output.values()) {
            val icc = IccProfile.forOutput(saida)
            // O tamanho declarado no cabeçalho tem de ser o tamanho real.
            val declarado = ((icc[0].toInt() and 0xFF) shl 24) or ((icc[1].toInt() and 0xFF) shl 16) or
                    ((icc[2].toInt() and 0xFF) shl 8) or (icc[3].toInt() and 0xFF)
            assertEquals("tamanho declarado em $saida", icc.size, declarado)
            // A assinatura 'acsp' está sempre no mesmo sítio.
            assertEquals("acsp", String(icc, 36, 4, Charsets.US_ASCII))
            assertEquals("RGB ", String(icc, 16, 4, Charsets.US_ASCII))
            assertEquals("XYZ ", String(icc, 20, 4, Charsets.US_ASCII))
        }
    }

    /**
     * A invariante que diz se um perfil de matriz está certo: os três corantes somados dão o
     * ponto branco do espaço de conexão, que é D50. Se a adaptação cromática estiver errada ou em
     * falta, esta soma dá o branco errado.
     */
    @Test
    fun theColorantsSumToTheD50WhitePoint() {
        for (primarias in listOf(
            doubleArrayOf(0.640, 0.330, 0.300, 0.600, 0.150, 0.060),
            doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060))) {
            val c = IccProfile.colorants(primarias)
            for (eixo in 0 until 3) {
                val soma = c[0][eixo] + c[1][eixo] + c[2][eixo]
                assertEquals("eixo $eixo", ColorScience.D50[eixo], soma, 1e-4)
            }
        }
    }

    @Test
    fun theMatrixTakesWhiteToWhite() {
        val m = IccProfile.rgbToXyz(
            doubleArrayOf(0.640, 0.330, 0.300, 0.600, 0.150, 0.060), ColorScience.D65)
        val branco = ColorScience.matVec(m, doubleArrayOf(1.0, 1.0, 1.0))
        for (i in 0 until 3) assertEquals(ColorScience.D65[i], branco[i], 1e-9)
    }

    @Test
    fun theTwoProfilesAreNotTheSame() {
        val srgb = IccProfile.forOutput(ColorScience.Output.SRGB)
        val p3 = IccProfile.forOutput(ColorScience.Output.DISPLAY_P3)
        var diferente = false
        for (i in 0 until minOf(srgb.size, p3.size)) if (srgb[i] != p3[i]) diferente = true
        assertTrue("os dois espaços deviam dar perfis diferentes", diferente)
    }

    /**
     * A escrita a partir da imagem linear tem de dar o **ficheiro exactamente igual** à escrita a
     * partir do `ShortArray` já codificado.
     *
     * O caminho linear existe só para não materializar 75 MB de amostras num telefone que morreu por
     * falta de memória. Uma optimização de memória que mude o ficheiro não é uma optimização — é um
     * bug. Este teste compara byte a byte.
     */
    @Test
    fun writingFromTheLinearImageGivesTheSameFileAsWritingFromSamples() {
        val rgb = io.github.bmcsilva.latente.render.Rgb(
            FloatArray(largura * altura * 3) { (it % 37) / 36f }, largura, altura)
        val icc = IccProfile.forOutput(ColorScience.Output.SRGB)

        val a = ByteArrayOutputStream()
        Tiff16Writer.write(a, io.github.bmcsilva.latente.render.RawPipeline.encode16(rgb),
            largura, altura, icc, "igual")
        val b = ByteArrayOutputStream()
        Tiff16Writer.write(b, rgb, icc, "igual")

        val pa = a.toByteArray()
        val pb = b.toByteArray()
        assertEquals("os dois ficheiros deviam ter o mesmo tamanho", pa.size, pb.size)
        for (i in pa.indices) {
            assertEquals("byte $i", pa[i].toInt() and 0xFF, pb[i].toInt() and 0xFF)
        }
    }

    @Test
    fun theDescriptionIsWrittenWhenGiven() {
        val b = escrever(descricao = "Latente F2")
        val c = DngReader.Cursor(b, little = false)
        val entrada = ler(b)[270]
        assertTrue("devia haver descrição", entrada != null)
        val texto = String(entrada!!.bytes(c), Charsets.US_ASCII).trimEnd(' ')
        assertEquals("Latente F2", texto)
    }
}
