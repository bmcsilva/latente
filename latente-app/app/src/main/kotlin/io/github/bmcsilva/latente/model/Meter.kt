package io.github.bmcsilva.latente.model

import io.github.bmcsilva.latente.render.Demosaic
import io.github.bmcsilva.latente.render.LensShading
import io.github.bmcsilva.latente.render.ShadingProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * O fotómetro, medido no mosaico do sensor.
 *
 * Mede-se o **RAW**, e não a saída do nosso pipeline nem o que o HAL diz. Três razões, por ordem de
 * importância: é onde o corte acontece — depois da matriz de cor já não se sabe o que se perdeu; é
 * anterior ao balanço de brancos, portanto não depende do Kelvin escolhido; e as estatísticas do HAL
 * vêm do ISP, que esta aplicação não usa.
 *
 * Mede-se o **verde**. Carrega a maior parte da luminância, tem o dobro das amostras de qualquer outro
 * canal, e é o canal que a exposição de uma câmara sempre governou.
 *
 * Corre na CPU, sobre o `ByteBuffer` que a câmara já entregou, com sub-amostragem: com o passo por
 * omissão são **97 920 leituras**, ou 0,78% dos 12,5 milhões de píxeis do frame. Evita ler de volta da
 * GPU, que custaria muito mais do que a medição vale.
 */
object Meter {

    /** Bins do histograma. 1024 dá resolução de sobra para os 10 bits úteis do sensor. */
    const val BINS = 1024

    /**
     * O que o sensor está a ver, em fracção do nível de branco.
     *
     * Tudo normalizado de 0 a 1, onde 1 é o corte. Não há unidades fotográficas aqui de propósito: a
     * conversão para stops é decisão de quem interpreta, e está no `ExposureProgram`.
     */
    class Reading(
        val samples: Int,
        /** Fracção das amostras no último bin, ou seja no corte. */
        val clipped: Double,
        val mean: Double,
        val median: Double,
        /** O 99,5.º percentil: onde estão as luzes altas sem contar com uns píxeis perdidos. */
        val highlight: Double,
        /** O histograma em bruto, para o visor o desenhar. Nulo quando não houve leitura. */
        val histogram: IntArray? = null,
    ) {
        val valid: Boolean get() = samples > 0

        /** Quantos stops faltam até o 99,5.º percentil cortar. Negativo já passou. */
        val headroomStops: Double
            get() = if (highlight <= 0.0) 20.0 else -log2(highlight)
    }

    /**
     * Mede um plano RAW, saltando de `step` em `step` quadrados do mosaico.
     *
     * @param rowStrideBytes o passo de linha do plano, que não é necessariamente `width * 2`.
     * @param step em quadrados 2×2. Com 8, um frame de 4080×3060 dá 192 × 255 quadrados a dois verdes
     *   cada: **97 920 amostras**, 0,78% do frame. Mais do que suficiente para um histograma de 1024
     *   bins — o percentil 99,5 assenta em cerca de 490 amostras.
     * @param shading o perfil de vinhetagem que a revelação vai aplicar. **Medir sem ele protege o
     *   ficheiro e queima o que se vê**: viu-se num ensaio real com o RAW folgado — máximo 787 de 1023
     *   — e a revelação com 50,8% dos píxeis no corte, porque a correcção multiplica até 6× nas bordas.
     *   O fotómetro não estava a medir mal; faltava-lhe este passo.
     */
    fun measure(
        plane: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        cfa: IntArray,
        whiteLevel: Int,
        blackLevel: FloatArray,
        step: Int = 8,
        shading: ShadingProfile? = null,
        shadingStrength: Float = 0f,
    ): Reading {
        if (width < 2 || height < 2 || whiteLevel <= 0) return Reading(0, 0.0, 0.0, 0.0, 0.0)

        plane.position(0)
        val s = plane.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val passo = if (rowStrideBytes > 0) rowStrideBytes / 2 else width
        val hist = IntArray(BINS)
        var soma = 0.0
        var contagem = 0

        val salto = if (step < 1) 1 else step
        val meiaLargura = width / 2.0
        val meiaAltura = height / 2.0
        val raioMaximo = Math.sqrt(meiaLargura * meiaLargura + meiaAltura * meiaAltura)
        var by = 0
        while (by * 2 + 1 < height) {
            var bx = 0
            while (bx * 2 + 1 < width) {
                for (i in 0 until 4) {
                    if (cfa[i] != Demosaic.G) continue
                    val x = bx * 2 + (i and 1)
                    val y = by * 2 + (i shr 1)
                    val indice = y * passo + x
                    if (indice >= s.limit()) continue
                    val cru = s.get(indice).toInt() and 0xFFFF
                    val preto = blackLevel[i].toDouble()
                    var v = (cru - preto) / (whiteLevel - preto)
                    if (shading != null && shadingStrength > 0f) {
                        // O mesmo ganho que a revelação vai aplicar neste ponto, pelo mesmo raio e
                        // pela mesma fórmula. Se divergissem, o fotómetro protegeria uma imagem que
                        // não é a que se grava.
                        val dx = x + 0.5 - meiaLargura
                        val dy = y + 0.5 - meiaAltura
                        val r = Math.sqrt(dx * dx + dy * dy) / raioMaximo
                        v *= LensShading.applyStrength(
                            shading.gain(Demosaic.G, r.toFloat()), shadingStrength).toDouble()
                    }
                    v = v.coerceIn(0.0, 1.0)
                    hist[(v * (BINS - 1)).toInt()]++
                    soma += v
                    contagem++
                }
                bx += salto
            }
            by += salto
        }

        if (contagem == 0) return Reading(0, 0.0, 0.0, 0.0, 0.0)
        return Reading(
            samples = contagem,
            clipped = hist[BINS - 1].toDouble() / contagem,
            mean = soma / contagem,
            median = percentil(hist, contagem, 0.50),
            highlight = percentil(hist, contagem, 0.995),
            histogram = hist)
    }

    /**
     * O valor no percentil pedido, em fracção do branco.
     *
     * Percentil e não máximo: um sensor tem sempre píxeis quentes e reflexos especulares, e deixar
     * meia dúzia deles governar a exposição subexpõe a fotografia toda.
     */
    private fun percentil(hist: IntArray, total: Int, fraccao: Double): Double {
        val alvo = (total * fraccao).toLong()
        var acumulado = 0L
        for (b in hist.indices) {
            acumulado += hist[b]
            if (acumulado >= alvo) return b.toDouble() / (BINS - 1)
        }
        return 1.0
    }

    fun log2(v: Double): Double = Math.log(v) / Math.log(2.0)
}
