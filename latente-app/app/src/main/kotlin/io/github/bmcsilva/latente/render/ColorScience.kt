package io.github.bmcsilva.latente.render

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceTransform

/**
 * A ciência da cor do revelador: do mosaico da câmara ao espaço de saída.
 *
 * Portado da implementação de referência em Python (`tools/develop.py`), que foi validada contra o
 * darktable com uma revelação neutra: 0,7% de desvio em R/G e 5,3% em B/G numa chapa plana. Os
 * testes desta classe fixam-na a esses valores, calculados a partir das matrizes reais do
 * dispositivo — qualquer divergência futura é regressão, não dúvida.
 *
 * O caminho é o da especificação DNG:
 *
 * ```
 * camWB   = camRGB / AsShotNeutral
 * XYZ_D50 = ForwardMatrix(cct) · camWB
 * XYZ_D65 = Bradford(D50 → D65) · XYZ_D50
 * saída   = M_saída · XYZ_D65
 * ```
 *
 * As quatro etapas colapsam numa só matriz 3×3, que é o que se envia ao shader. A adaptação de
 * Bradford **não é opcional**: sem ela o desvio salta para 15,9% e 34,4%.
 */
object ColorScience {

    /** Ponto branco D50, o espaço de conexão das `ForwardMatrix` da especificação DNG. */
    val D50 = doubleArrayOf(0.96422, 1.0, 0.82521)

    /** Ponto branco D65, o do sRGB e do Display P3. */
    val D65 = doubleArrayOf(0.95047, 1.0, 1.08883)

    val XYZ_TO_SRGB = doubleArrayOf(
        3.2404542, -1.5371385, -0.4985314,
        -0.9692660, 1.8760108, 0.0415560,
        0.0556434, -0.2040259, 1.0572252,
    )

    val XYZ_TO_DISPLAY_P3 = doubleArrayOf(
        2.4934969, -0.9313836, -0.4027108,
        -0.8294890, 1.7626641, 0.0236247,
        0.0358458, -0.0761724, 0.9568845,
    )

    /** Matriz de Bradford, para adaptação cromática. */
    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )

    enum class Output { SRGB, DISPLAY_P3 }

    // -----------------------------------------------------------------------------------------
    // álgebra
    // -----------------------------------------------------------------------------------------

    fun matMul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var acc = 0.0
                for (k in 0 until 3) acc += a[i * 3 + k] * b[k * 3 + j]
                out[i * 3 + j] = acc
            }
        }
        return out
    }

    fun matVec(m: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    fun matInv(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
                m[1] * (m[3] * m[8] - m[5] * m[6]) +
                m[2] * (m[3] * m[7] - m[4] * m[6])
        if (Math.abs(det) < 1e-12) return null
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) / det,
            (m[2] * m[7] - m[1] * m[8]) / det,
            (m[1] * m[5] - m[2] * m[4]) / det,
            (m[5] * m[6] - m[3] * m[8]) / det,
            (m[0] * m[8] - m[2] * m[6]) / det,
            (m[2] * m[3] - m[0] * m[5]) / det,
            (m[3] * m[7] - m[4] * m[6]) / det,
            (m[1] * m[6] - m[0] * m[7]) / det,
            (m[0] * m[4] - m[1] * m[3]) / det,
        )
    }

    /** Adaptação cromática de Bradford entre dois pontos brancos, em XYZ. */
    fun bradford(srcWhite: DoubleArray, dstWhite: DoubleArray): DoubleArray {
        val inv = matInv(BRADFORD) ?: return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val s = matVec(BRADFORD, srcWhite)
        val d = matVec(BRADFORD, dstWhite)
        val diag = doubleArrayOf(
            d[0] / s[0], 0.0, 0.0,
            0.0, d[1] / s[1], 0.0,
            0.0, 0.0, d[2] / s[2],
        )
        return matMul(inv, matMul(diag, BRADFORD))
    }

    // -----------------------------------------------------------------------------------------
    // interpolação entre iluminantes
    // -----------------------------------------------------------------------------------------

    /** Temperatura aproximada de um iluminante de referência do DNG. */
    fun illuminantKelvin(code: Int?): Double = when (code) {
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT -> 5503.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65 -> 6504.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50 -> 5003.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55 -> 5503.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75 -> 7504.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A -> 2856.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> 2856.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT -> 4150.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER -> 6504.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE -> 7504.0
        else -> 6504.0
    }

    /**
     * Interpola duas matrizes calibradas em 1/T, como manda a especificação DNG.
     *
     * Nos extremos devolve exactamente a matriz correspondente; a temperatura é limitada ao
     * intervalo entre os dois iluminantes.
     */
    fun interpolateByCct(
        m1: DoubleArray,
        kelvin1: Double,
        m2: DoubleArray,
        kelvin2: Double,
        kelvin: Int,
    ): DoubleArray {
        val hot = Math.max(kelvin1, kelvin2)
        val cold = Math.min(kelvin1, kelvin2)
        val mHot = if (kelvin1 >= kelvin2) m1 else m2
        val mCold = if (kelvin1 >= kelvin2) m2 else m1
        if (hot == cold) return mHot.copyOf()

        val t = Math.min(Math.max(kelvin.toDouble(), cold), hot)
        val g = (1.0 / t - 1.0 / hot) / (1.0 / cold - 1.0 / hot)
        val out = DoubleArray(9)
        for (i in 0 until 9) out[i] = mHot[i] * (1.0 - g) + mCold[i] * g
        return out
    }

    // -----------------------------------------------------------------------------------------
    // a cadeia completa
    // -----------------------------------------------------------------------------------------

    /**
     * A matriz única que leva o mosaico já balanceado ao espaço de saída, em linear.
     *
     * @param forward `ForwardMatrix` já interpolada para a temperatura escolhida.
     */
    fun cameraToOutput(forward: DoubleArray, output: Output): DoubleArray {
        val toDisplay = if (output == Output.SRGB) XYZ_TO_SRGB else XYZ_TO_DISPLAY_P3
        return matMul(toDisplay, matMul(bradford(D50, D65), forward))
    }

    /** Para o uniforme do shader. Ordem de linhas, como o resto desta classe. */
    fun toFloats(m: DoubleArray): FloatArray {
        val out = FloatArray(m.size)
        for (i in m.indices) out[i] = m[i].toFloat()
        return out
    }

    /**
     * Ganhos de balanço de brancos a partir do ponto neutro.
     *
     * Dividir por `AsShotNeutral` leva o neutro da cena a (1,1,1), que é o que a `ForwardMatrix`
     * espera à entrada.
     */
    fun whiteBalanceGains(neutral: DoubleArray): DoubleArray = doubleArrayOf(
        1.0 / Math.max(neutral[0], 1e-6),
        1.0 / Math.max(neutral[1], 1e-6),
        1.0 / Math.max(neutral[2], 1e-6),
    )

    // -----------------------------------------------------------------------------------------
    // codificação de saída
    // -----------------------------------------------------------------------------------------

    fun srgbEncode(x: Double): Double = when {
        x <= 0.0 -> 0.0
        x >= 1.0 -> 1.0
        x <= 0.0031308 -> x * 12.92
        else -> 1.055 * Math.pow(x, 1.0 / 2.4) - 0.055
    }

    fun srgbDecode(x: Double): Double = when {
        x <= 0.0 -> 0.0
        x <= 0.04045 -> x / 12.92
        else -> Math.pow((x + 0.055) / 1.055, 2.4)
    }

    /**
     * *Rolloff* de altas luzes, Reinhard estendido.
     *
     * Com `whitePoint = 1.0` é a identidade, e é assim que se obtém uma revelação puramente linear.
     * Acima disso comprime o topo e deixa o resto praticamente intacto.
     */
    fun rolloff(x: Double, whitePoint: Double): Double {
        if (x <= 0.0) return 0.0
        val w2 = whitePoint * whitePoint
        return x * (1.0 + x / w2) / (1.0 + x)
    }

    // -----------------------------------------------------------------------------------------
    // leitura do dispositivo
    // -----------------------------------------------------------------------------------------

    fun toDoubles(t: ColorSpaceTransform?): DoubleArray? {
        if (t == null) return null
        val v = DoubleArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                v[row * 3 + col] = t.getElement(col, row).toDouble()
            }
        }
        return v
    }

    /** `ForwardMatrix` do dispositivo, interpolada para a temperatura escolhida. */
    fun forwardMatrix(ch: CameraCharacteristics, kelvin: Int): DoubleArray? {
        val fm1 = toDoubles(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))
        val fm2 = toDoubles(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))
        if (fm1 == null) return fm2
        if (fm2 == null) return fm1
        val t1 = illuminantKelvin(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1))
        val t2 = illuminantKelvin(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt())
        return interpolateByCct(fm1, t1, fm2, t2, kelvin)
    }
}
