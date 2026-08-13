package io.github.bmcsilva.latente.export

import io.github.bmcsilva.latente.render.ColorScience
import io.github.bmcsilva.latente.render.RawPipeline
import io.github.bmcsilva.latente.render.Rgb
import java.io.OutputStream

/**
 * TIFF de 16 bits por canal, sem compressão, com perfil ICC.
 *
 * O Android não tem escritor de TIFF, mas um TIFF *baseline* é trivial: cabeçalho, tabela de
 * etiquetas, dados. São as mesmas estruturas que o `DngReader` já sabe ler — e é isso que permite
 * verificar o que se escreve relendo-o.
 *
 * Evita-se de propósito o caminho do `Bitmap`: o `RGBA_F16` existe, mas a compressão do Android
 * reduz a oito bits e perderia metade da razão de ser deste ficheiro.
 *
 * Dezasseis bits não guardam mais alcance do que os dez que o sensor entrega — guardam **margem de
 * edição**. Um gradiente que sobreviva a curvas e a ajustes de exposição sem quebrar em degraus é
 * o que justifica o dobro do tamanho.
 */
object Tiff16Writer {

    private const val BYTE = 1
    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val UNDEFINED = 7

    private class Campo(val tag: Int, val type: Int, val count: Int, val valores: LongArray?,
                        val bytes: ByteArray?)

    /**
     * @param pixels RGB entrelaçado, três amostras por pixel, já codificado no espaço de saída.
     * @param icc perfil a embeber, ou nulo. Sem perfil, um leitor assume sRGB — o que é correcto
     *   só quando a saída é mesmo sRGB.
     */
    fun write(
        out: OutputStream,
        pixels: ShortArray,
        width: Int,
        height: Int,
        icc: ByteArray? = null,
        description: String? = null,
        orientation: Int = 1,
    ) {
        require(pixels.size >= width * height * 3) { "faltam amostras para $width x $height" }
        out.write(cabecalho(width, height, icc, description, orientation))

        // Os dados vão linha a linha, para não duplicar a imagem inteira em memória.
        val linha = ByteArray(width * 3 * 2)
        for (y in 0 until height) {
            var p = y * width * 3
            var i = 0
            while (i < linha.size) {
                val v = pixels[p].toInt() and 0xFFFF
                linha[i] = ((v shr 8) and 0xFF).toByte()
                linha[i + 1] = (v and 0xFF).toByte()
                i += 2
                p++
            }
            out.write(linha)
        }
        out.flush()
    }

    /**
     * Escreve directamente da imagem **linear**, codificando linha a linha.
     *
     * A alternativa — codificar tudo para um `ShortArray` e depois escrevê-lo — custa 75 MB numa
     * imagem de 12 Mpx, ao lado dos 150 MB que a imagem em vírgula flutuante já ocupa. Num telefone
     * com 256 MB de tecto isso é a diferença entre gravar e morrer sem memória, e foi exactamente
     * assim que morreu à primeira.
     *
     * A codificação é a mesma: `RawPipeline.encodeSample16`, amostra a amostra.
     */
    fun write(
        out: OutputStream,
        rgb: Rgb,
        icc: ByteArray? = null,
        description: String? = null,
        /**
         * Orientação, do `Present.exifOrientation`. A imagem sai na orientação do sensor — deitada —
         * exactamente como o DNG, portanto leva a mesma etiqueta.
         */
        orientation: Int = 1,
    ) {
        out.write(cabecalho(rgb.width, rgb.height, icc, description, orientation))

        val linha = ByteArray(rgb.width * 3 * 2)
        for (y in 0 until rgb.height) {
            var p = y * rgb.width * 3
            var i = 0
            while (i < linha.size) {
                val v = RawPipeline.encodeSample16(rgb.data[p])
                linha[i] = ((v shr 8) and 0xFF).toByte()
                linha[i + 1] = (v and 0xFF).toByte()
                i += 2
                p++
            }
            out.write(linha)
        }
        out.flush()
    }

    /** O cabeçalho e a tabela de etiquetas, tudo o que vem antes dos dados. */
    private fun cabecalho(
        width: Int,
        height: Int,
        icc: ByteArray?,
        description: String?,
        orientation: Int = 1,
    ): ByteArray {
        val campos = ArrayList<Campo>()
        fun curto(tag: Int, v: Int) = campos.add(Campo(tag, SHORT, 1, longArrayOf(v.toLong()), null))
        fun longo(tag: Int, v: Long) = campos.add(Campo(tag, LONG, 1, longArrayOf(v), null))

        curto(256, width)                                   // ImageWidth
        curto(257, height)                                  // ImageLength
        campos.add(Campo(258, SHORT, 3, longArrayOf(16, 16, 16), null))   // BitsPerSample
        curto(259, 1)                                       // Compression: nenhuma
        curto(262, 2)                                       // Photometric: RGB
        description?.let {
            val b = it.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
            campos.add(Campo(270, ASCII, b.size, null, b))
        }
        curto(274, orientation)                             // Orientation
        longo(273, 0L)                                      // StripOffsets: preenchido depois
        curto(277, 3)                                       // SamplesPerPixel
        longo(278, height.toLong())                         // RowsPerStrip: uma tira só
        longo(279, (width.toLong() * height * 3 * 2))       // StripByteCounts
        campos.add(Campo(282, RATIONAL, 1, longArrayOf(72, 1), null))     // XResolution
        campos.add(Campo(283, RATIONAL, 1, longArrayOf(72, 1), null))     // YResolution
        curto(284, 1)                                       // PlanarConfiguration: entrelaçado
        curto(296, 2)                                       // ResolutionUnit: polegada
        campos.add(Campo(339, SHORT, 3, longArrayOf(1, 1, 1), null))      // SampleFormat: inteiro
        icc?.let { campos.add(Campo(34675, UNDEFINED, it.size, null, it)) }

        campos.sortBy { it.tag }

        // Duas passagens: a primeira para saber onde ficam os valores longos e os dados.
        val ifdAt = 8
        val ifdBytes = 2 + campos.size * 12 + 4
        var corrente = ifdAt + ifdBytes
        val externos = LinkedHashMap<Int, Pair<Int, ByteArray>>()
        for (c in campos) {
            val dados = materializar(c)
            if (dados.size > 4) {
                externos[c.tag] = corrente to dados
                corrente += dados.size
                if (corrente % 2 != 0) corrente++
            }
        }
        val dadosAt = corrente

        val cabecalho = java.io.ByteArrayOutputStream()
        cabecalho.write("MM".toByteArray())                 // big-endian, para não haver dúvidas
        u16(cabecalho, 42)
        u32(cabecalho, ifdAt.toLong())
        u16(cabecalho, campos.size)
        for (c in campos) {
            u16(cabecalho, c.tag)
            u16(cabecalho, c.type)
            u32(cabecalho, c.count.toLong())
            val dados = if (c.tag == 273) materializar(
                Campo(273, LONG, 1, longArrayOf(dadosAt.toLong()), null)) else materializar(c)
            if (dados.size > 4) {
                u32(cabecalho, externos[c.tag]!!.first.toLong())
            } else {
                cabecalho.write(dados.copyOf(4))
            }
        }
        u32(cabecalho, 0L)
        for ((_, par) in externos) {
            cabecalho.write(par.second)
            if (par.second.size % 2 != 0) cabecalho.write(0)
        }

        return cabecalho.toByteArray()
    }

    /** O perfil que corresponde ao espaço de saída da revelação. */
    fun iccFor(output: ColorScience.Output): ByteArray = IccProfile.forOutput(output)

    // -----------------------------------------------------------------------------------------

    private fun materializar(c: Campo): ByteArray {
        c.bytes?.let { return it }
        val v = c.valores ?: return ByteArray(0)
        val o = java.io.ByteArrayOutputStream()
        when (c.type) {
            SHORT -> for (x in v) u16(o, x.toInt())
            LONG -> for (x in v) u32(o, x)
            RATIONAL -> {
                var i = 0
                while (i + 1 < v.size) {
                    u32(o, v[i]); u32(o, v[i + 1]); i += 2
                }
            }
            BYTE, ASCII, UNDEFINED -> for (x in v) o.write(x.toInt())
            else -> for (x in v) u32(o, x)
        }
        return o.toByteArray()
    }

    private fun u16(o: java.io.ByteArrayOutputStream, v: Int) {
        o.write((v shr 8) and 0xFF); o.write(v and 0xFF)
    }

    private fun u32(o: java.io.ByteArrayOutputStream, v: Long) {
        o.write(((v shr 24) and 0xFF).toInt()); o.write(((v shr 16) and 0xFF).toInt())
        o.write(((v shr 8) and 0xFF).toInt()); o.write((v and 0xFF).toInt())
    }
}
