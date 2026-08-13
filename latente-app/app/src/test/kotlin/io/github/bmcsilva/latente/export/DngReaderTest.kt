package io.github.bmcsilva.latente.export

import io.github.bmcsilva.latente.render.Demosaic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * O leitor de DNG, verificado contra um DNG **construído no próprio teste**.
 *
 * Escrever o ficheiro aqui em vez de depender de um DNG de 24 MB no disco torna o teste rápido,
 * determinístico e independente de dados externos — e obriga-me a saber exactamente o que o formato
 * diz, em vez de o inferir de um exemplo.
 *
 * O formato-alvo é o que o `DngCreator` do Android produz: mosaico no próprio IFD0, sem compressão,
 * uma tira por linha, 16 bits.
 */
class DngReaderTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private val largura = 8
    private val altura = 6

    /** Os testes que escrevem mais do que um DNG precisam de nomes distintos. */
    private var contador = 0

    /** GBRG, como a câmara principal do dispositivo de referência. */
    private val cfa = byteArrayOf(1, 2, 0, 1)

    // -----------------------------------------------------------------------------------------
    // Construção de um DNG mínimo mas válido
    // -----------------------------------------------------------------------------------------

    private class Campo(val tag: Int, val type: Int, val count: Int, val payload: ByteArray)

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun u32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    private fun racional(num: Long, den: Long) = u32(num) + u32(den)

    private fun escreverDng(
        pixels: IntArray,
        white: Int = 1023,
        black: IntArray = intArrayOf(0, 0, 0, 0),
        neutro: DoubleArray = doubleArrayOf(0.4873, 1.0, 0.5938),
    ): File {
        val linhas = altura
        val bytesPorLinha = largura * 2

        val campos = ArrayList<Campo>()
        campos.add(Campo(256, 3, 1, u16(largura)))
        campos.add(Campo(257, 3, 1, u16(altura)))
        campos.add(Campo(258, 3, 1, u16(16)))
        campos.add(Campo(259, 3, 1, u16(1)))
        campos.add(Campo(262, 3, 1, u16(32803)))
        campos.add(Campo(273, 4, linhas, ByteArray(0)))     // preenchido depois
        campos.add(Campo(277, 3, 1, u16(1)))
        campos.add(Campo(278, 3, 1, u16(1)))
        campos.add(Campo(279, 4, linhas, ByteArray(0)))     // idem
        campos.add(Campo(33422, 1, 4, cfa))
        campos.add(Campo(50714, 5, 4, black.fold(ByteArray(0)) { a, v -> a + racional(v.toLong(), 1L) }))
        campos.add(Campo(50717, 3, 1, u16(white)))
        campos.add(Campo(50728, 5, 3,
            neutro.fold(ByteArray(0)) { a, v -> a + racional((v * 1000000).toLong(), 1000000L) }))
        campos.add(Campo(50778, 3, 1, u16(21)))
        campos.add(Campo(50964, 10, 9, ByteArray(0)))       // ForwardMatrix1
        campos.sortBy { it.tag }

        val fm1 = doubleArrayOf(
            0.616210938, 0.130859375, 0.216796875,
            0.200195312, 0.757812500, 0.041992188,
            -0.000976562, -0.408203125, 1.233398438)

        val n = campos.size
        val ifdBytes = 2 + 12 * n + 4
        var valorAt = 8 + ifdBytes
        val valores = ByteArrayOutputStream()

        // Reservar espaço para os valores externos, na ordem em que os campos aparecem.
        val offsets = HashMap<Int, Int>()
        for (c in campos) {
            val tamanho = when (c.tag) {
                273, 279 -> 4 * linhas
                50964 -> 8 * 9
                else -> c.payload.size
            }
            if (tamanho > 4) {
                offsets[c.tag] = valorAt + valores.size()
                valores.write(ByteArray(tamanho))
            }
        }
        val dadosAt = valorAt + valores.size()

        // Agora que se sabe onde começam os dados, escrevem-se os valores a sério.
        val blocoValores = valores.toByteArray()
        fun porEm(tag: Int, conteudo: ByteArray) {
            val o = offsets[tag]!! - valorAt
            System.arraycopy(conteudo, 0, blocoValores, o, conteudo.size)
        }
        porEm(273, (0 until linhas).fold(ByteArray(0)) { a, i ->
            a + u32((dadosAt + i * bytesPorLinha).toLong())
        })
        porEm(279, (0 until linhas).fold(ByteArray(0)) { a, _ -> a + u32(bytesPorLinha.toLong()) })
        porEm(50964, fm1.fold(ByteArray(0)) { a, v ->
            a + u32((v * 1000000).toLong().let { if (it < 0) it + 0x100000000L else it }) + u32(1000000L)
        })
        for (c in campos) {
            val tamanho = when (c.tag) {
                273, 279, 50964 -> 5
                else -> c.payload.size
            }
            if (tamanho > 4 && c.payload.isNotEmpty()) porEm(c.tag, c.payload)
        }

        val saida = ByteArrayOutputStream()
        saida.write("II".toByteArray())
        saida.write(u16(42))
        saida.write(u32(8L))
        saida.write(u16(n))
        for (c in campos) {
            saida.write(u16(c.tag))
            saida.write(u16(c.type))
            saida.write(u32(c.count.toLong()))
            val tamanho = when (c.tag) {
                273, 279 -> 4 * linhas
                50964 -> 8 * 9
                else -> c.payload.size
            }
            if (tamanho > 4) {
                saida.write(u32(offsets[c.tag]!!.toLong()))
            } else {
                val p = c.payload.copyOf(4)
                saida.write(p)
            }
        }
        saida.write(u32(0L))
        saida.write(blocoValores)
        for (v in pixels) saida.write(u16(v))

        val f = pasta.newFile("teste-${contador++}.dng")
        f.writeBytes(saida.toByteArray())
        return f
    }

    // -----------------------------------------------------------------------------------------

    @Test
    fun readsTheBasicGeometryAndMetadata() {
        val f = escreverDng(IntArray(largura * altura) { 512 })
        val r = DngReader.open(f)

        assertEquals(largura, r.width)
        assertEquals(altura, r.height)
        assertEquals(1023, r.whiteLevel)
        assertEquals("GBRG", "" +
                "RGB"[r.cfa[0]] + "RGB"[r.cfa[1]] + "RGB"[r.cfa[2]] + "RGB"[r.cfa[3]])
        assertEquals(0.4873, r.asShotNeutral[0], 1e-4)
        assertEquals(1.0, r.asShotNeutral[1], 1e-4)
        assertEquals(0.5938, r.asShotNeutral[2], 1e-4)
        assertEquals(21, r.illuminant1)
    }

    @Test
    fun readsTheForwardMatrix() {
        val f = escreverDng(IntArray(largura * altura) { 100 })
        val fm = DngReader.open(f).forwardMatrix1
        assertTrue("a ForwardMatrix devia estar presente", fm != null)
        assertEquals(0.616210938, fm!![0], 1e-5)
        assertEquals(0.757812500, fm[4], 1e-5)
        assertEquals(1.233398438, fm[8], 1e-5)
        // A propriedade que define a convenção: as linhas somam o branco D50.
        assertEquals(0.96422, fm[0] + fm[1] + fm[2], 0.002)
    }

    /** Preto a 0 e branco a 1: é o primeiro passo da §6.1 e tem de sair exacto. */
    @Test
    fun normalisesLevels() {
        val f = escreverDng(IntArray(largura * altura) { 1023 })
        val m = DngReader.open(f).readMosaic()
        for (v in m.data) assertEquals(1.0f, v, 1e-5f)

        val g = escreverDng(IntArray(largura * altura) { 0 })
        for (v in DngReader.open(g).readMosaic().data) assertEquals(0.0f, v, 1e-5f)
    }

    @Test
    fun normalisationRespectsTheBlackLevelPerCfaPosition() {
        // Pedestal diferente por posição, como alguns sensores têm.
        val preto = intArrayOf(10, 20, 30, 40)
        val f = escreverDng(IntArray(largura * altura) { 1023 }, black = preto)
        val m = DngReader.open(f).readMosaic()
        for (y in 0 until altura) {
            for (x in 0 until largura) {
                val p = preto[(y and 1) * 2 + (x and 1)]
                val esperado = (1023f - p) / (1023f - p)
                assertEquals("em ($x,$y)", esperado, m.data[y * largura + x], 1e-5f)
            }
        }
        // E um valor a meio caminho não pode ficar igual nas quatro posições.
        val g = escreverDng(IntArray(largura * altura) { 500 }, black = preto)
        val n = DngReader.open(g).readMosaic()
        assertTrue("posições com pedestais diferentes deviam normalizar diferente",
            Math.abs(n.data[0] - n.data[1]) > 1e-4f)
    }

    @Test
    fun theMosaicKeepsThePixelOrder() {
        val pixels = IntArray(largura * altura) { it * 10 }
        val f = escreverDng(pixels)
        val m = DngReader.open(f).readMosaic()
        for (i in pixels.indices) {
            assertEquals("pixel $i", pixels[i] / 1023f, m.data[i], 1e-5f)
        }
    }

    @Test
    fun theMosaicCarriesTheCfaSoDemosaicCanUseIt() {
        val f = escreverDng(IntArray(largura * altura) { 300 })
        val m = DngReader.open(f).readMosaic()
        assertEquals(Demosaic.G, m.colourAt(0, 0))
        assertEquals(Demosaic.B, m.colourAt(1, 0))
        assertEquals(Demosaic.R, m.colourAt(0, 1))
        assertEquals(Demosaic.G, m.colourAt(1, 1))
    }

    @Test
    fun rejectsSomethingThatIsNotATiff() {
        val f = pasta.newFile("lixo.dng")
        f.writeBytes(ByteArray(64) { 7 })
        var falhou = false
        try {
            DngReader.open(f)
        } catch (e: IllegalArgumentException) {
            falhou = true
        }
        assertTrue("um ficheiro que não é TIFF devia ser recusado", falhou)
    }
}
