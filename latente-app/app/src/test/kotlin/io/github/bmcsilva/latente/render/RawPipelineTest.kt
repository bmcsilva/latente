package io.github.bmcsilva.latente.render

import io.github.bmcsilva.latente.export.DngReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * O pipeline montado, verificado ponta a ponta.
 *
 * Dois testes carregam o peso:
 *
 * - **`aNeutralSceneComesOutNeutral`** — se o mosaico contiver exactamente o `AsShotNeutral`,
 *   o resultado tem de ser cinzento com R = G = B. É o critério do cartão de cinza da §9, e não é
 *   circular: passa pelo balanço, pelo demosaico e pela matriz, e qualquer erro em qualquer um
 *   deles desvia a cor.
 * - **`theWholePipelineMatchesThePythonReference`** — uma cor conhecida atravessa tudo e tem de
 *   sair no valor que o `tools/develop.py` produziu, e esse foi validado contra o darktable.
 */
class RawPipelineTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private var contador = 0

    private val largura = 32
    private val altura = 24
    private val branco = 1023

    /** GBRG, como a câmara principal. */
    private val cfa = byteArrayOf(1, 2, 0, 1)
    private val neutro = doubleArrayOf(0.487304688, 1.0, 0.593750000)

    private val fm1 = doubleArrayOf(
        0.616210938, 0.130859375, 0.216796875,
        0.200195312, 0.757812500, 0.041992188,
        -0.000976562, -0.408203125, 1.233398438)

    /**
     * A segunda matriz calibrada, do iluminante A. Sem ela o pipeline usaria a de D65 pura em vez
     * de interpolar a 5500 K — e a comparação com a referência falharia por 1,2% no verde, que foi
     * exactamente o que aconteceu quando o fixture estava incompleto.
     */
    private val fm2 = doubleArrayOf(
        0.450195312, 0.176757812, 0.337890625,
        0.055664062, 0.753906250, 0.189453125,
        -0.137695312, -0.831054688, 1.793945312)

    // -----------------------------------------------------------------------------------------

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun u32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    private fun rac(n: Long, d: Long) = u32(n) + u32(d)

    /** Escreve um DNG mínimo com um valor por cor do CFA. */
    private fun dngComCores(r: Double, g: Double, b: Double): File {
        val pixels = IntArray(largura * altura)
        for (y in 0 until altura) {
            for (x in 0 until largura) {
                val cor = cfa[(y and 1) * 2 + (x and 1)].toInt()
                val v = when (cor) {
                    0 -> r
                    2 -> b
                    else -> g
                }
                pixels[y * largura + x] = Math.round(v * branco).toInt()
            }
        }
        return escrever(pixels)
    }

    private fun escrever(pixels: IntArray): File {
        val linhas = altura
        val bpl = largura * 2

        class C(val tag: Int, val type: Int, val count: Int, val inline: ByteArray, val ext: ByteArray)

        fun matriz(m: DoubleArray) = m.fold(ByteArray(0)) { a, v ->
            a + u32((v * 1000000).toLong().let { if (it < 0) it + 0x100000000L else it }) + u32(1000000L)
        }
        val fmBytes = matriz(fm1)
        val fm2Bytes = matriz(fm2)
        val neutroBytes = neutro.fold(ByteArray(0)) { a, v -> a + rac((v * 1000000).toLong(), 1000000L) }
        val pretoBytes = (0 until 4).fold(ByteArray(0)) { a, _ -> a + rac(0L, 1L) }

        val campos = listOf(
            C(256, 3, 1, u16(largura), ByteArray(0)),
            C(257, 3, 1, u16(altura), ByteArray(0)),
            C(258, 3, 1, u16(16), ByteArray(0)),
            C(259, 3, 1, u16(1), ByteArray(0)),
            C(262, 3, 1, u16(32803), ByteArray(0)),
            C(273, 4, linhas, ByteArray(0), ByteArray(4 * linhas)),
            C(277, 3, 1, u16(1), ByteArray(0)),
            C(278, 3, 1, u16(1), ByteArray(0)),
            C(279, 4, linhas, ByteArray(0), ByteArray(4 * linhas)),
            C(33422, 1, 4, cfa, ByteArray(0)),
            C(50714, 5, 4, ByteArray(0), pretoBytes),
            C(50717, 3, 1, u16(branco), ByteArray(0)),
            C(50728, 5, 3, ByteArray(0), neutroBytes),
            C(50778, 3, 1, u16(21), ByteArray(0)),
            C(50779, 3, 1, u16(17), ByteArray(0)),
            C(50964, 10, 9, ByteArray(0), fmBytes),
            C(50965, 10, 9, ByteArray(0), fm2Bytes),
        ).sortedBy { it.tag }

        val ifdBytes = 2 + 12 * campos.size + 4
        val valorAt = 8 + ifdBytes
        val offsets = HashMap<Int, Int>()
        var corrente = valorAt
        for (c in campos) {
            if (c.ext.isNotEmpty()) {
                offsets[c.tag] = corrente
                corrente += c.ext.size
            }
        }
        val dadosAt = corrente

        val ext = HashMap<Int, ByteArray>()
        for (c in campos) if (c.ext.isNotEmpty()) ext[c.tag] = c.ext.copyOf()
        ext[273] = (0 until linhas).fold(ByteArray(0)) { a, i -> a + u32((dadosAt + i * bpl).toLong()) }
        ext[279] = (0 until linhas).fold(ByteArray(0)) { a, _ -> a + u32(bpl.toLong()) }

        val o = ByteArrayOutputStream()
        o.write("II".toByteArray())
        o.write(u16(42))
        o.write(u32(8L))
        o.write(u16(campos.size))
        for (c in campos) {
            o.write(u16(c.tag))
            o.write(u16(c.type))
            o.write(u32(c.count.toLong()))
            if (c.ext.isNotEmpty()) o.write(u32(offsets[c.tag]!!.toLong()))
            else o.write(c.inline.copyOf(4))
        }
        o.write(u32(0L))
        for (c in campos) if (c.ext.isNotEmpty()) o.write(ext[c.tag]!!)
        for (v in pixels) o.write(u16(v))

        val f = pasta.newFile("pipe-${contador++}.dng")
        f.writeBytes(o.toByteArray())
        return f
    }

    private fun revelar(f: File, s: DevelopSettings = DevelopSettings()): Rgb =
        RawPipeline.develop(DngReader.open(f), null, s)

    // -----------------------------------------------------------------------------------------
    // Os dois que carregam o peso
    // -----------------------------------------------------------------------------------------

    @Test
    fun aNeutralSceneComesOutNeutral() {
        // Um mosaico que contém exactamente o AsShotNeutral: é um cinzento sob a luz do disparo.
        val f = dngComCores(neutro[0] * 0.5, neutro[1] * 0.5, neutro[2] * 0.5)
        val rgb = revelar(f)
        val p = rgb.pixel(largura / 2, altura / 2)
        assertEquals("R e G deviam coincidir", p[1], p[0], 0.01f)
        assertEquals("B e G deviam coincidir", p[1], p[2], 0.01f)
        assertTrue("devia haver sinal", p[1] > 0.1f)
    }

    /**
     * A cor de referência do `tools/develop.py`: câmara (0,30 · 0,55 · 0,22) dá, depois do balanço
     * e da matriz a 5500 K, sRGB linear (0,655745 · 0,545974 · 0,215855).
     */
    @Test
    fun theWholePipelineMatchesThePythonReference() {
        val f = dngComCores(0.30, 0.55, 0.22)
        val rgb = revelar(f, DevelopSettings(rolloff = 1.0f))
        val p = rgb.pixel(largura / 2, altura / 2)
        assertEquals("R", 0.655745f, p[0], 2e-3f)
        assertEquals("G", 0.545974f, p[1], 2e-3f)
        assertEquals("B", 0.215855f, p[2], 2e-3f)
    }

    // -----------------------------------------------------------------------------------------
    // Propriedades
    // -----------------------------------------------------------------------------------------

    @Test
    fun exposureIsAPureScalarInLinearSpace() {
        val f = dngComCores(0.20, 0.30, 0.15)
        val a = revelar(f, DevelopSettings(rolloff = 1.0f)).pixel(16, 12)
        val b = revelar(f, DevelopSettings(exposureEv = 1f, rolloff = 1.0f)).pixel(16, 12)
        for (c in 0 until 3) {
            assertEquals("um stop devia dobrar o canal $c", a[c] * 2f, b[c], 1e-3f)
        }
    }

    @Test
    fun rolloffOfOneIsPurelyLinear() {
        val f = dngComCores(0.20, 0.30, 0.15)
        val a = revelar(f, DevelopSettings(rolloff = 1.0f)).pixel(16, 12)
        val b = revelar(f, DevelopSettings(rolloff = 1.6f)).pixel(16, 12)
        // Com rolloff activo os valores descem; sem ele ficam como a matriz os deixou.
        for (c in 0 until 3) assertTrue("o rolloff devia comprimir", b[c] <= a[c] + 1e-6f)
        assertTrue("e devia comprimir mesmo alguma coisa", b[1] < a[1])
    }

    @Test
    fun halfResolutionHalvesTheDimensionsAndKeepsTheColour() {
        val f = dngComCores(0.30, 0.55, 0.22)
        val cheia = revelar(f, DevelopSettings(rolloff = 1.0f))
        val meia = revelar(f, DevelopSettings(rolloff = 1.0f, halfResolution = true))

        assertEquals(largura / 2, meia.width)
        assertEquals(altura / 2, meia.height)

        // O visor pode ter metade da resolução, mas não pode ter outra cor.
        val a = cheia.pixel(16, 12)
        val b = meia.pixel(8, 6)
        for (c in 0 until 3) {
            assertEquals("o canal $c devia coincidir entre visor e ficheiro", a[c], b[c], 2e-3f)
        }
    }

    @Test
    fun displayP3GivesDifferentNumbersThanSrgb() {
        val f = dngComCores(0.30, 0.55, 0.22)
        val srgb = revelar(f, DevelopSettings(rolloff = 1.0f)).pixel(16, 12)
        val p3 = revelar(f, DevelopSettings(
            rolloff = 1.0f, output = ColorScience.Output.DISPLAY_P3)).pixel(16, 12)
        var diferente = false
        for (c in 0 until 3) if (Math.abs(srgb[c] - p3[c]) > 1e-3f) diferente = true
        assertTrue("o P3 devia dar números diferentes do sRGB", diferente)
    }

    // -----------------------------------------------------------------------------------------
    // Codificação
    // -----------------------------------------------------------------------------------------

    @Test
    fun encodingIsConsistentBetweenEightAndSixteenBits() {
        val f = dngComCores(0.30, 0.55, 0.22)
        val rgb = revelar(f, DevelopSettings(rolloff = 1.0f))
        val oito = RawPipeline.encode8(rgb)
        val dezasseis = RawPipeline.encode16(rgb)
        assertEquals(rgb.data.size, oito.size)
        assertEquals(rgb.data.size, dezasseis.size)
        for (i in 0 until 30) {
            val a = (oito[i].toInt() and 0xFF) / 255.0
            val b = (dezasseis[i].toInt() and 0xFFFF) / 65535.0
            assertEquals("amostra $i", a, b, 0.004)
        }
    }

    @Test
    fun encodingClampsInsteadOfWrapping() {
        // Uma cena muito acima do branco não pode dar valores baixos por transbordo.
        val f = dngComCores(0.9, 0.9, 0.9)
        val rgb = revelar(f, DevelopSettings(exposureEv = 6f, rolloff = 1.0f))
        val oito = RawPipeline.encode8(rgb)
        for (i in 0 until 30) {
            assertEquals("devia estar no tecto", 255, oito[i].toInt() and 0xFF)
        }
        val dezasseis = RawPipeline.encode16(rgb)
        for (i in 0 until 30) {
            assertEquals("devia estar no tecto", 65535, dezasseis[i].toInt() and 0xFFFF)
        }
    }

    @Test
    fun shadingIsSkippedWhenThereIsNoProfile() {
        // Sem calibração não se corrige nada, e não se inventa perfil nenhum.
        val f = dngComCores(0.30, 0.55, 0.22)
        val reader = DngReader.open(f)
        val sem = RawPipeline.develop(reader, null, DevelopSettings(rolloff = 1.0f))
        val zero = RawPipeline.develop(
            reader, ShadingProfile.SM_S942B_ID0, DevelopSettings(rolloff = 1.0f, shadingStrength = 0f))
        for (i in sem.data.indices) {
            assertEquals(sem.data[i], zero.data[i], 1e-6f)
        }
    }
}
