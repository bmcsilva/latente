package io.github.bmcsilva.latente.render

/**
 * O mosaico já normalizado e balanceado, pronto para *demosaicing*.
 *
 * `data` é linear, com o nível de preto subtraído e o branco a 1,0, e já com os ganhos de balanço
 * aplicados por posição do CFA — é a ordem da §6.1 da especificação, e é a ordem certa: aplicar o
 * balanço antes de interpolar torna os canais comparáveis e melhora a interpolação.
 *
 * `cfa` são as quatro cores nas posições (0,0), (1,0), (0,1), (1,1), com 0=R, 1=G, 2=B — a mesma
 * convenção do `CFAPattern` do DNG. No dispositivo de referência a principal é **GBRG**, ou seja
 * `[1, 2, 0, 1]`, e as outras câmaras são RGGB: nunca presumir.
 */
class Mosaic(
    val data: FloatArray,
    val width: Int,
    val height: Int,
    val cfa: IntArray,
) {
    init {
        require(data.size >= width * height) { "mosaico menor que $width x $height" }
        require(cfa.size == 4) { "o CFA tem de ter quatro posições" }
    }

    /** Cor nativa da posição. */
    fun colourAt(x: Int, y: Int): Int = cfa[(y and 1) * 2 + (x and 1)]

    fun at(x: Int, y: Int): Float {
        val cx = if (x < 0) -x else if (x >= width) 2 * width - x - 2 else x
        val cy = if (y < 0) -y else if (y >= height) 2 * height - y - 2 else y
        return data[cy * width + cx]
    }
}

/**
 * *Demosaicing*.
 *
 * Duas vias, como a §6.1 manda: **Malvar-He-Cutler 5×5** para a exportação, e ***binning* 2×2**
 * para o visor, que é quatro vezes mais barato e não inventa nada.
 *
 * Os filtros do MHC vêm de «High-quality linear interpolation for demosaicing of Bayer-patterned
 * color images» (Malvar, He, Cutler, 2004). Todos somam 8 e são divididos por 8 — e é por isso que
 * um campo uniforme atravessa o algoritmo **exactamente** inalterado. Esse é o teste que apanha
 * quase todos os erros de implementação, e está em `DemosaicTest`.
 *
 * Nas bordas reflecte-se a coordenada. Reflectir preserva a paridade do CFA, ao contrário de
 * limitar: limitar trocaria as cores nas duas primeiras colunas e linhas.
 */
object Demosaic {

    const val R = 0
    const val G = 1
    const val B = 2

    /**
     * O padrão do mosaico a partir do enum do Camera2.
     *
     * Existe aqui, e não na câmara, porque é uma tabela de quatro cores e não uma consulta ao
     * dispositivo — e assim testa-se na JVM. Os valores do enum são os mesmos do `CFAPattern` do DNG:
     * a posição 0 é (0,0), a 1 é (1,0), a 2 é (0,1) e a 3 é (1,1).
     *
     * Trocar duas destas posições dá cor certa em metade dos píxeis, que é o pior sintoma possível
     * porque parece quase bem.
     *
     * @param arrangement `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`. Nulo ou monocromático dá nulo: não se
     *   inventa um mosaico que não existe.
     */
    fun cfaFromArrangement(arrangement: Int?): IntArray? = when (arrangement) {
        0 -> intArrayOf(R, G, G, B)   // RGGB
        1 -> intArrayOf(G, R, B, G)   // GRBG
        2 -> intArrayOf(G, B, R, G)   // GBRG
        3 -> intArrayOf(B, G, G, R)   // BGGR
        else -> null
    }

    /**
     * *Binning* 2×2: cada quarteto do CFA dá um pixel.
     *
     * Um R, dois G que se somam, um B. Metade da resolução em cada eixo, sem interpolação nenhuma —
     * é média de amostras verdadeiras, e por isso não pode introduzir artefactos de cor.
     */
    fun bin2x2(m: Mosaic): Rgb {
        val ow = m.width / 2
        val oh = m.height / 2
        val out = FloatArray(ow * oh * 3)

        var o = 0
        for (oy in 0 until oh) {
            val y0 = oy * 2
            for (ox in 0 until ow) {
                val x0 = ox * 2
                var r = 0f
                var g = 0f
                var b = 0f
                var gn = 0
                for (dy in 0 until 2) {
                    for (dx in 0 until 2) {
                        val v = m.data[(y0 + dy) * m.width + (x0 + dx)]
                        when (m.cfa[dy * 2 + dx]) {
                            R -> r = v
                            B -> b = v
                            else -> {
                                g += v
                                gn++
                            }
                        }
                    }
                }
                out[o] = r
                out[o + 1] = if (gn > 0) g / gn else 0f
                out[o + 2] = b
                o += 3
            }
        }
        return Rgb(out, ow, oh)
    }

    /** Malvar-He-Cutler 5×5, resolução total. */
    fun malvar(m: Mosaic): Rgb {
        val out = FloatArray(m.width * m.height * 3)
        var o = 0
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                val native = m.colourAt(x, y)
                val centre = m.at(x, y)
                when (native) {
                    G -> {
                        // A cor que acompanha este verde na mesma linha decide qual dos dois
                        // filtros vai para R e qual vai para B.
                        val rowColour = m.colourAt(x + 1, y)
                        val sameRow = rbAtGreenSameRow(m, x, y)
                        val otherRow = rbAtGreenOtherRow(m, x, y)
                        out[o] = if (rowColour == R) sameRow else otherRow
                        out[o + 1] = centre
                        out[o + 2] = if (rowColour == R) otherRow else sameRow
                    }
                    R -> {
                        out[o] = centre
                        out[o + 1] = greenAtRedOrBlue(m, x, y)
                        out[o + 2] = rbAtDiagonal(m, x, y)
                    }
                    else -> {
                        out[o] = rbAtDiagonal(m, x, y)
                        out[o + 1] = greenAtRedOrBlue(m, x, y)
                        out[o + 2] = centre
                    }
                }
                o += 3
            }
        }
        return Rgb(out, m.width, m.height)
    }

    // -----------------------------------------------------------------------------------------
    // Os quatro filtros. Todos somam 8 antes da divisão — daí um campo uniforme sair intacto.
    // -----------------------------------------------------------------------------------------

    /**
     * Verde numa posição de R ou de B.
     *
     * ```
     *        ·   ·  -1   ·   ·
     *        ·   ·   2   ·   ·
     *       -1   2   4   2  -1
     *        ·   ·   2   ·   ·
     *        ·   ·  -1   ·   ·
     * ```
     */
    private fun greenAtRedOrBlue(m: Mosaic, x: Int, y: Int): Float {
        val acc = 4f * m.at(x, y) +
                2f * (m.at(x - 1, y) + m.at(x + 1, y) + m.at(x, y - 1) + m.at(x, y + 1)) -
                (m.at(x - 2, y) + m.at(x + 2, y) + m.at(x, y - 2) + m.at(x, y + 2))
        return acc / 8f
    }

    /**
     * R (ou B) numa posição de verde, para a cor que está **na mesma linha**.
     *
     * ```
     *        ·   ·  0.5  ·   ·
     *        ·  -1   ·  -1   ·
     *       -1   4   5   4  -1
     *        ·  -1   ·  -1   ·
     *        ·   ·  0.5  ·   ·
     * ```
     */
    private fun rbAtGreenSameRow(m: Mosaic, x: Int, y: Int): Float {
        val acc = 5f * m.at(x, y) +
                4f * (m.at(x - 1, y) + m.at(x + 1, y)) -
                (m.at(x - 2, y) + m.at(x + 2, y)) -
                (m.at(x - 1, y - 1) + m.at(x + 1, y - 1) + m.at(x - 1, y + 1) + m.at(x + 1, y + 1)) +
                0.5f * (m.at(x, y - 2) + m.at(x, y + 2))
        return acc / 8f
    }

    /** R (ou B) numa posição de verde, para a cor que está **na outra linha**: o filtro transposto. */
    private fun rbAtGreenOtherRow(m: Mosaic, x: Int, y: Int): Float {
        val acc = 5f * m.at(x, y) +
                4f * (m.at(x, y - 1) + m.at(x, y + 1)) -
                (m.at(x, y - 2) + m.at(x, y + 2)) -
                (m.at(x - 1, y - 1) + m.at(x + 1, y - 1) + m.at(x - 1, y + 1) + m.at(x + 1, y + 1)) +
                0.5f * (m.at(x - 2, y) + m.at(x + 2, y))
        return acc / 8f
    }

    /**
     * R numa posição de B, ou B numa posição de R: a cor está na diagonal.
     *
     * ```
     *        ·   ·  -1.5  ·   ·
     *        ·   2   ·    2   ·
     *     -1.5   ·   6    ·  -1.5
     *        ·   2   ·    2   ·
     *        ·   ·  -1.5  ·   ·
     * ```
     */
    private fun rbAtDiagonal(m: Mosaic, x: Int, y: Int): Float {
        val acc = 6f * m.at(x, y) +
                2f * (m.at(x - 1, y - 1) + m.at(x + 1, y - 1) +
                        m.at(x - 1, y + 1) + m.at(x + 1, y + 1)) -
                1.5f * (m.at(x - 2, y) + m.at(x + 2, y) + m.at(x, y - 2) + m.at(x, y + 2))
        return acc / 8f
    }
}

/** Imagem em três canais entrelaçados, linear. */
class Rgb(val data: FloatArray, val width: Int, val height: Int) {

    fun pixel(x: Int, y: Int): FloatArray {
        val o = (y * width + x) * 3
        return floatArrayOf(data[o], data[o + 1], data[o + 2])
    }
}
