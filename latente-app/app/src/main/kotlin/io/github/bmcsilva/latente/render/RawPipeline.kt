package io.github.bmcsilva.latente.render

import io.github.bmcsilva.latente.export.DngReader

/**
 * O que o fotógrafo decide na revelação.
 *
 * Todos os valores por omissão são neutros ou fisicamente correctos. Nenhum é um *look*: o
 * `contrast` não existe, a saturação não existe, e o `rolloff` a 1,0 é a identidade.
 */
class DevelopSettings(
    /** Compensação de exposição, em stops. */
    val exposureEv: Float = 0f,
    /**
     * Temperatura a que as matrizes calibradas são interpoladas.
     *
     * **Não** é o balanço de brancos — esse vem do `AsShotNeutral` do ficheiro, que é o que o
     * fotógrafo escolheu no disparo. Isto afina apenas a matriz de cor entre os dois iluminantes
     * de referência, e o efeito é pequeno.
     */
    val kelvin: Int = 5500,
    /** De 0 a 1. Aplica-se em stops: ver `LensShading`. */
    val shadingStrength: Float = LensShading.FULL,
    /**
     * Ponto branco do *rolloff* de altas luzes. **1,0 é linear puro**, sem compressão nenhuma.
     *
     * Acima de 1 comprime o topo e deixa o resto praticamente intacto. É a única não-linearidade
     * do pipeline, e existe porque o alcance da cena não cabe no do ecrã — não para dar «carácter».
     */
    val rolloff: Float = 1.0f,
    val output: ColorScience.Output = ColorScience.Output.SRGB,
    /**
     * *Binning* 2×2 em vez de Malvar-He-Cutler: metade da resolução em cada eixo, quatro vezes
     * mais barato. É o caminho do visor (§6.4), e a única diferença permitida entre o que se vê e
     * o que se grava.
     */
    val halfResolution: Boolean = false,
)

/**
 * O pipeline de revelação, do mosaico ao espaço de saída.
 *
 * Etapas da §6.1, por esta ordem:
 *
 * 1. **níveis** — preto a 0, branco a 1 (faz-se no `DngReader`);
 * 2. **vinhetagem** — antes de tudo o resto, porque é propriedade da óptica e do sensor e tem de
 *    sair antes de os píxeis se misturarem;
 * 3. **balanço de brancos** — por posição do CFA, ainda no mosaico: torna os canais comparáveis e
 *    melhora a interpolação;
 * 4. **demosaicing**;
 * 5. **cor** — câmara → XYZ D50 → D65 → espaço de saída, numa só matriz;
 * 6. **exposição e rolloff**;
 * 7. **codificação**.
 *
 * Tudo em vírgula flutuante até ao fim. A saída de `develop` é **linear**: a codificação é um passo
 * separado, para que quem escreve TIFF de 16 bits e quem desenha no ecrã partam do mesmo sítio.
 */
object RawPipeline {

    /**
     * Revela e devolve a imagem **linear** no espaço de saída.
     *
     * @param profile perfil de vinhetagem, ou nulo se não houver calibração para esta câmara. Não
     *   se inventa: sem perfil, não se corrige.
     */
    fun develop(
        reader: DngReader,
        profile: ShadingProfile?,
        settings: DevelopSettings = DevelopSettings(),
        /**
         * Um quadrado do mosaico em cada `reducao`, para miniaturas. Um é o ficheiro inteiro.
         *
         * O resto do pipeline não sabe que houve redução, e é de propósito: a vinhetagem mede o raio
         * pelo tamanho do mosaico que recebe, o `demosaicing` só olha para vizinhos, e a cor é por
         * píxel. Uma miniatura sai assim da **mesma revelação** que o TIFF, e não de outra parecida.
         */
        reducao: Int = 1,
    ): Rgb {
        val mosaic = reader.readMosaicReduced(reducao)

        if (profile != null && settings.shadingStrength > 0f) {
            LensShading.correct(mosaic, profile, settings.shadingStrength)
        }

        applyWhiteBalance(mosaic, reader.asShotNeutral, settings.exposureEv)

        val rgb = if (settings.halfResolution) Demosaic.bin2x2(mosaic) else Demosaic.malvar(mosaic)

        val forward = forwardMatrix(reader, settings.kelvin)
        if (forward != null) {
            applyMatrix(rgb, ColorScience.cameraToOutput(forward, settings.output))
        }

        if (settings.rolloff > 1.0f) applyRolloff(rgb, settings.rolloff.toDouble())
        return rgb
    }

    /**
     * Balanço de brancos e exposição, no mosaico.
     *
     * Dividir pelo `AsShotNeutral` leva o neutro da cena a (1,1,1), que é o que a `ForwardMatrix`
     * espera à entrada. A exposição é um escalar e comuta com tudo o resto, por isso vem junto.
     */
    private fun applyWhiteBalance(m: Mosaic, neutral: DoubleArray, exposureEv: Float) {
        val ganhos = ColorScience.whiteBalanceGains(neutral)
        val exposicao = Math.pow(2.0, exposureEv.toDouble())
        val porCor = FloatArray(3) { (ganhos[it] * exposicao).toFloat() }

        for (y in 0 until m.height) {
            val base = y * m.width
            for (x in 0 until m.width) {
                m.data[base + x] *= porCor[m.colourAt(x, y)]
            }
        }
    }

    private fun applyMatrix(rgb: Rgb, matrix: DoubleArray) {
        val m = FloatArray(9) { matrix[it].toFloat() }
        val d = rgb.data
        var i = 0
        while (i < d.size) {
            val r = d[i]
            val g = d[i + 1]
            val b = d[i + 2]
            d[i] = m[0] * r + m[1] * g + m[2] * b
            d[i + 1] = m[3] * r + m[4] * g + m[5] * b
            d[i + 2] = m[6] * r + m[7] * g + m[8] * b
            i += 3
        }
    }

    private fun applyRolloff(rgb: Rgb, whitePoint: Double) {
        val d = rgb.data
        for (i in d.indices) {
            d[i] = ColorScience.rolloff(d[i].toDouble(), whitePoint).toFloat()
        }
    }

    private fun forwardMatrix(reader: DngReader, kelvin: Int): DoubleArray? {
        val fm1 = reader.forwardMatrix1
        val fm2 = reader.forwardMatrix2
        if (fm1 == null) return fm2
        if (fm2 == null) return fm1
        return ColorScience.interpolateByCct(
            fm1, ColorScience.illuminantKelvin(reader.illuminant1),
            fm2, ColorScience.illuminantKelvin(reader.illuminant2),
            kelvin)
    }

    // -----------------------------------------------------------------------------------------
    // Codificação
    // -----------------------------------------------------------------------------------------

    /**
     * Uma amostra linear em oito bits.
     *
     * Existe à parte para poder ser chamada sem materializar a imagem inteira — 12 Mpx em oito bits
     * são 37 MB que não cabem folgadamente ao lado dos 150 MB da imagem em vírgula flutuante. E é
     * esta a função que a comparação com a GPU usa, de modo que compara contra a codificação real e
     * não contra uma segunda versão dela.
     */
    fun encodeSample8(linear: Float): Int {
        val v = ColorScience.srgbEncode(linear.toDouble())
        return Math.round(v * 255.0).toInt().coerceIn(0, 255)
    }

    fun encodeSample16(linear: Float): Int {
        val v = ColorScience.srgbEncode(linear.toDouble())
        return Math.round(v * 65535.0).toInt().coerceIn(0, 65535)
    }

    /** Para o ecrã: oito bits por canal, com a curva do espaço de saída. */
    fun encode8(rgb: Rgb): ByteArray {
        val out = ByteArray(rgb.data.size)
        for (i in rgb.data.indices) out[i] = encodeSample8(rgb.data[i]).toByte()
        return out
    }

    /**
     * Para o ficheiro: dezasseis bits por canal.
     *
     * Continua a levar a curva do espaço de saída, porque um TIFF sem perfil linear seria lido
     * como sRGB por toda a gente e sairia escuro. Os 16 bits estão lá para a edição não partir os
     * gradientes, não para guardar linear.
     */
    fun encode16(rgb: Rgb): ShortArray {
        val out = ShortArray(rgb.data.size)
        for (i in rgb.data.indices) out[i] = encodeSample16(rgb.data[i]).toShort()
        return out
    }
}
