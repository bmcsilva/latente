package io.github.bmcsilva.latente.camera

import android.graphics.Rect
import android.media.Image

class RawStats(
    val mean: Double,
    val min: Int,
    val max: Int,
    val count: Int,
    /** Médias por posição no mosaico, indexadas por `(y % 2) * 2 + (x % 2)`. */
    val cfaMeans: DoubleArray,
    /** Histograma dos valores, do zero ao nível de branco. */
    val histogram: IntArray,
) {

    fun percentile(p: Double): Int {
        if (count <= 0) return 0
        val target = (count * p).toLong()
        var acc = 0L
        for (v in histogram.indices) {
            acc += histogram[v]
            if (acc >= target) return v
        }
        return histogram.size - 1
    }

    /** Fracção de píxeis a 95% ou mais do nível de branco: altas luzes cortadas. */
    fun clippedFraction(whiteLevel: Int): Double {
        if (count <= 0 || whiteLevel <= 0) return 0.0
        val from = (whiteLevel * 0.95).toInt()
        var n = 0L
        for (v in from until histogram.size) n += histogram[v]
        return n.toDouble() / count
    }

    /**
     * Espalhamento entre as quatro posições do mosaico, relativo à média.
     *
     * Num frame verdadeiramente escuro as quatro são iguais. Se houver estrutura, entrou luz — e a
     * medição de nível de preto não vale.
     */
    fun cfaSpread(): Double {
        if (mean <= 0.0) return 0.0
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (v in cfaMeans) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return (hi - lo) / mean
    }
}

/**
 * Estatísticas sobre o mosaico, sem *demosaicing*.
 *
 * `RAW_SENSOR` é um plano único de 16 bits, mas os bits úteis são os que o nível de branco diz —
 * 10 no dispositivo de referência. Lê-se byte a byte porque o `rowStride` não tem de coincidir com
 * a largura.
 */
object RawReader {

    fun stats(image: Image, region: Rect? = null, whiteLevel: Int = 1023): RawStats {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        // Origem par, para que as posições do mosaico não fiquem trocadas entre regiões.
        val r = region ?: Rect(0, 0, image.width, image.height)
        val x0 = (r.left / 2) * 2
        val y0 = (r.top / 2) * 2
        val x1 = Math.min(r.right, image.width)
        val y1 = Math.min(r.bottom, image.height)

        var sum = 0.0
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var n = 0
        val cfaSum = DoubleArray(4)
        val cfaN = IntArray(4)
        val hist = IntArray(if (whiteLevel > 0) whiteLevel + 1 else 1024)

        var y = y0
        while (y < y1) {
            val rowBase = y * rowStride
            var x = x0
            while (x < x1) {
                val o = rowBase + x * pixelStride
                if (o + 1 >= buf.limit()) {
                    x++
                    continue
                }
                val lo = buf.get(o).toInt() and 0xFF
                val hi = buf.get(o + 1).toInt() and 0xFF
                val v = (hi shl 8) or lo

                sum += v
                if (v < min) min = v
                if (v > max) max = v
                n++

                val idx = (y and 1) * 2 + (x and 1)
                cfaSum[idx] += v
                cfaN[idx]++

                hist[if (v >= hist.size) hist.size - 1 else if (v < 0) 0 else v]++

                x++
            }
            y++
        }

        val means = DoubleArray(4)
        for (i in 0 until 4) means[i] = if (cfaN[i] > 0) cfaSum[i] / cfaN[i] else 0.0

        return RawStats(
            mean = if (n > 0) sum / n else 0.0,
            min = if (n > 0) min else 0,
            max = if (n > 0) max else 0,
            count = n,
            cfaMeans = means,
            histogram = hist,
        )
    }

    /**
     * Razão entre os quatro cantos e o centro.
     *
     * Num campo uniforme, um valor próximo de 1 significa que a vinhetagem já foi corrigida antes
     * de o RAW ser entregue.
     */
    fun cornerToCentreRatio(image: Image, patch: Int = 200): Double {
        val w = image.width
        val h = image.height
        val p = Math.min(patch, Math.min(w, h) / 6)

        val centre = stats(image, Rect(w / 2 - p / 2, h / 2 - p / 2, w / 2 + p / 2, h / 2 + p / 2)).mean
        if (centre <= 0.0) return 0.0

        var cornerSum = 0.0
        cornerSum += stats(image, Rect(0, 0, p, p)).mean
        cornerSum += stats(image, Rect(w - p, 0, w, p)).mean
        cornerSum += stats(image, Rect(0, h - p, p, h)).mean
        cornerSum += stats(image, Rect(w - p, h - p, w, h)).mean

        return (cornerSum / 4.0) / centre
    }

}
