package io.github.bmcsilva.latente.model

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.RggbChannelVector
import io.github.bmcsilva.latente.render.ColorScience

/**
 * Balanço de brancos: da temperatura escolhida para os ganhos do sensor.
 *
 * Serve um objectivo concreto descoberto na F1: o `DngCreator` não deixa definir o `AsShotNeutral`
 * — deriva-o de `SENSOR_NEUTRAL_COLOR_POINT`. Mas a experiência 8 provou que
 * `COLOR_CORRECTION_GAINS` **determina** esse ponto neutro, e é exactamente o seu recíproco.
 * Logo, definindo os ganhos certos, a escolha do fotógrafo chega ao DNG.
 *
 * E chega sem tocar no RAW: com o AWB desligado, os ganhos aplicam-se às saídas processadas, não ao
 * mosaico. O negativo fica intocado e são só os metadados a declarar a intenção.
 *
 * O caminho é o da especificação DNG: Kelvin → cromaticidade → XYZ → espaço da câmara pela
 * `ColorMatrix`, interpolada entre os dois iluminantes de referência.
 */
object WhiteBalance {

    /** Cromaticidade xy do corpo negro / luz do dia à temperatura dada. */
    fun chromaticity(kelvin: Int): DoubleArray {
        val t = kelvin.toDouble().coerceIn(1667.0, 25000.0)
        val x: Double
        if (t < 4000.0) {
            // Locus de Planck
            x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else if (t <= 7000.0) {
            x = 0.244063 + 0.09911e3 / t + 2.9678e6 / (t * t) - 4.6070e9 / (t * t * t)
        } else {
            x = 0.237040 + 0.24748e3 / t + 1.9018e6 / (t * t) - 2.0064e9 / (t * t * t)
        }

        val y: Double
        if (t < 2222.0) {
            y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
        } else if (t < 4000.0) {
            y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
        } else {
            y = -3.000 * x * x + 2.870 * x - 0.275
        }
        return doubleArrayOf(x, y)
    }

    private fun xyz(kelvin: Int): DoubleArray {
        val c = chromaticity(kelvin)
        val x = c[0]
        val y = c[1]
        if (y <= 0.0) return doubleArrayOf(1.0, 1.0, 1.0)
        return doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)
    }

    /**
     * Multiplicador de R e B correspondente à tinta.
     *
     * `tint` em [−1, +1]; zero é o locus de Planck. Positivo levanta R e B no ponto neutro, o que
     * baixa os seus ganhos e deixa a revelação mais verde; negativo faz o contrário.
     *
     * A escala é de meio stop por unidade, o que dá ±41% em cada extremo — folgado para as luzes
     * fora do locus que se encontram em interiores.
     */
    fun tintMultiplier(tint: Float): Double =
        Math.pow(2.0, tint.coerceIn(-1f, 1f).toDouble() * 0.5)

    /**
     * O ponto neutro no espaço da câmara: o que o sensor lê perante um cinzento sob esta luz.
     *
     * Devolve normalizado com o maior valor a 1, que é a convenção do `AsShotNeutral`.
     */
    fun neutral(ch: CameraCharacteristics, kelvin: Int, tint: Float = 0f): DoubleArray? {
        val cm1 = ColorScience.toDoubles(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)) ?: return null
        val cm2 = ColorScience.toDoubles(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))

        // Interpolação em 1/T entre os dois iluminantes: a mesma da ColorScience, para que as
        // duas não possam divergir.
        val m = if (cm2 == null) {
            cm1
        } else {
            ColorScience.interpolateByCct(
                cm1,
                ColorScience.illuminantKelvin(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)),
                cm2,
                ColorScience.illuminantKelvin(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()),
                kelvin)
        }

        val cam = ColorScience.matVec(m, xyz(kelvin))

        val t = tintMultiplier(tint)
        cam[0] *= t
        cam[2] *= t

        var max = 0.0
        for (v in cam) if (v > max) max = v
        if (max <= 0.0) return null
        for (i in 0 until 3) cam[i] = Math.max(cam[i] / max, 1e-4)
        return cam
    }

    /**
     * Ganhos a enviar em `COLOR_CORRECTION_GAINS`.
     *
     * São o recíproco do ponto neutro, normalizados para que o menor seja 1 — o HAL não aceita
     * ganhos abaixo de 1.
     */
    fun gains(ch: CameraCharacteristics, kelvin: Int, tint: Float = 0f): RggbChannelVector? {
        val n = neutral(ch, kelvin, tint) ?: return null
        val g = doubleArrayOf(1.0 / n[0], 1.0 / n[1], 1.0 / n[2])
        var min = Double.MAX_VALUE
        for (v in g) if (v < min) min = v
        if (min <= 0.0) return null
        for (i in 0 until 3) g[i] /= min
        return RggbChannelVector(
            g[0].toFloat(), g[1].toFloat(), g[1].toFloat(), g[2].toFloat())
    }
}
