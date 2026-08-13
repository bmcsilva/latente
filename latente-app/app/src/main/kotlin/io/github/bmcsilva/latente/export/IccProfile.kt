package io.github.bmcsilva.latente.export

import io.github.bmcsilva.latente.render.ColorScience
import java.io.ByteArrayOutputStream

/**
 * Gerador de perfis ICC mínimos, do tipo matriz + curva.
 *
 * Um TIFF de 16 bits sem perfil é lido como sRGB por toda a gente. Isso é inofensivo quando a saída
 * **é** sRGB, e é um erro silencioso quando é Display P3 — as cores saturadas saem lavadas e nada
 * avisa. Daí valer a pena escrever a etiqueta, mesmo que custe gerar o perfil.
 *
 * Perfil v2, com os nove tags obrigatórios para um perfil de matriz: descrição, ponto branco, os
 * três corantes, as três curvas e o aviso de direitos. Os corantes vão adaptados a **D50**, que é o
 * espaço de conexão do ICC — não ao branco do espaço de saída.
 */
object IccProfile {

    /** Primárias xy e branco de cada espaço. */
    private val SRGB_PRIMARIES = doubleArrayOf(0.640, 0.330, 0.300, 0.600, 0.150, 0.060)
    private val P3_PRIMARIES = doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060)

    private val D50 = ColorScience.D50
    private val D65 = ColorScience.D65

    fun forOutput(output: ColorScience.Output): ByteArray = when (output) {
        ColorScience.Output.SRGB -> build(SRGB_PRIMARIES, "Latente sRGB")
        ColorScience.Output.DISPLAY_P3 -> build(P3_PRIMARIES, "Latente Display P3")
    }

    /**
     * Matriz RGB → XYZ a partir das primárias e do branco.
     *
     * Cada primária dá uma direcção em XYZ; os três factores de escala saem de exigir que
     * (1,1,1) dê exactamente o branco.
     */
    fun rgbToXyz(primaries: DoubleArray, white: DoubleArray): DoubleArray {
        val m = DoubleArray(9)
        for (i in 0 until 3) {
            val x = primaries[i * 2]
            val y = primaries[i * 2 + 1]
            m[i] = x / y
            m[3 + i] = 1.0
            m[6 + i] = (1.0 - x - y) / y
        }
        val inv = ColorScience.matInv(m) ?: return m
        val escala = ColorScience.matVec(inv, white)
        val out = DoubleArray(9)
        for (linha in 0 until 3) {
            for (col in 0 until 3) {
                out[linha * 3 + col] = m[linha * 3 + col] * escala[col]
            }
        }
        return out
    }

    /** Os três corantes do perfil, já adaptados a D50. */
    fun colorants(primaries: DoubleArray): Array<DoubleArray> {
        val d65 = rgbToXyz(primaries, D65)
        val adaptada = ColorScience.matMul(ColorScience.bradford(D65, D50), d65)
        return Array(3) { col -> doubleArrayOf(adaptada[col], adaptada[3 + col], adaptada[6 + col]) }
    }

    // -----------------------------------------------------------------------------------------

    private fun u8(o: ByteArrayOutputStream, v: Int) = o.write(v and 0xFF)

    private fun u16(o: ByteArrayOutputStream, v: Int) {
        o.write((v shr 8) and 0xFF); o.write(v and 0xFF)
    }

    private fun u32(o: ByteArrayOutputStream, v: Long) {
        o.write(((v shr 24) and 0xFF).toInt()); o.write(((v shr 16) and 0xFF).toInt())
        o.write(((v shr 8) and 0xFF).toInt()); o.write((v and 0xFF).toInt())
    }

    /** s15Fixed16: o formato de vírgula fixa do ICC. */
    private fun s15(o: ByteArrayOutputStream, v: Double) =
        u32(o, (Math.round(v * 65536.0)).toLong() and 0xFFFFFFFFL)

    private fun xyzType(v: DoubleArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("XYZ ".toByteArray()); u32(o, 0)
        for (c in v) s15(o, c)
        return o.toByteArray()
    }

    /** Curva como tabela de 1024 entradas: exacta, e dispensa aproximar por um gama. */
    private fun curveType(): ByteArray {
        val n = 1024
        val o = ByteArrayOutputStream()
        o.write("curv".toByteArray()); u32(o, 0); u32(o, n.toLong())
        for (i in 0 until n) {
            val linear = i / (n - 1.0)
            u16(o, Math.round(ColorScience.srgbEncode(linear) * 65535.0).toInt())
        }
        return o.toByteArray()
    }

    /** `textDescriptionType` do ICC v2: ASCII, mais campos Unicode e ScriptCode vazios. */
    private fun descType(texto: String): ByteArray {
        val ascii = texto.toByteArray(Charsets.US_ASCII)
        val o = ByteArrayOutputStream()
        o.write("desc".toByteArray()); u32(o, 0)
        u32(o, (ascii.size + 1).toLong())
        o.write(ascii); u8(o, 0)
        u32(o, 0); u32(o, 0)          // Unicode: código de língua e contagem
        u16(o, 0); u8(o, 0)           // ScriptCode: código e contagem
        o.write(ByteArray(67))        // ScriptCode: 67 bytes reservados
        return o.toByteArray()
    }

    private fun textType(texto: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("text".toByteArray()); u32(o, 0)
        o.write(texto.toByteArray(Charsets.US_ASCII)); u8(o, 0)
        return o.toByteArray()
    }

    fun build(primaries: DoubleArray, nome: String): ByteArray {
        val c = colorants(primaries)
        val curva = curveType()

        // A curva é a mesma nos três canais: escreve-se uma vez e os três tags apontam para lá.
        val tags = listOf(
            "desc" to descType(nome),
            "wtpt" to xyzType(D50),
            "rXYZ" to xyzType(c[0]),
            "gXYZ" to xyzType(c[1]),
            "bXYZ" to xyzType(c[2]),
            "rTRC" to curva,
            "gTRC" to curva,
            "bTRC" to curva,
            "cprt" to textType("Latente"),
        )

        val tabelaAt = 128
        val tabelaBytes = 4 + tags.size * 12
        var corrente = tabelaAt + tabelaBytes
        val offsets = ArrayList<Pair<Int, Int>>()
        val escritos = HashMap<String, Int>()
        val corpo = ByteArrayOutputStream()
        for ((sig, dados) in tags) {
            val chave = sig + dados.size
            val jaEscrito = if (sig.endsWith("TRC")) escritos["TRC"] else null
            if (jaEscrito != null) {
                offsets.add(jaEscrito to dados.size)
                continue
            }
            offsets.add(corrente to dados.size)
            if (sig.endsWith("TRC")) escritos["TRC"] = corrente
            corpo.write(dados)
            corrente += dados.size
            // Cada tag alinha a quatro bytes.
            while (corrente % 4 != 0) {
                corpo.write(0); corrente++
            }
        }
        val total = corrente

        val o = ByteArrayOutputStream()
        u32(o, total.toLong())
        o.write("LTNT".toByteArray())         // fabricante do perfil
        u32(o, 0x02100000)                    // versão 2.1
        o.write("mntr".toByteArray())
        o.write("RGB ".toByteArray())
        o.write("XYZ ".toByteArray())
        o.write(ByteArray(12))                // data e hora: zeros
        o.write("acsp".toByteArray())
        o.write(ByteArray(4))                 // plataforma
        u32(o, 0)                             // bandeiras
        o.write(ByteArray(8))                 // fabricante e modelo do dispositivo
        o.write(ByteArray(8))                 // atributos
        u32(o, 0)                             // intenção de reprodução: perceptual
        for (v in D50) s15(o, v)              // ponto branco do espaço de conexão
        o.write("LTNT".toByteArray())         // criador
        o.write(ByteArray(44))                // reservado, até aos 128 bytes

        u32(o, tags.size.toLong())
        for (i in tags.indices) {
            o.write(tags[i].first.toByteArray(Charsets.US_ASCII))
            u32(o, offsets[i].first.toLong())
            u32(o, offsets[i].second.toLong())
        }
        o.write(corpo.toByteArray())
        return o.toByteArray()
    }
}
