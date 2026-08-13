package io.github.bmcsilva.latente.render

import android.hardware.camera2.CameraCharacteristics
import io.github.bmcsilva.latente.export.DngReader
import io.github.bmcsilva.latente.model.WhiteBalance

/**
 * Os dados que vão para o shader, preparados e verificáveis.
 *
 * O texto de um shader não se pode correr sem GPU, mas **os dados que lhe são entregues sim** — e é
 * aí que vivem os bugs de porte: matrizes transpostas, tabelas fora de ordem, o padrão do mosaico
 * trocado. Esta classe existe para essa parte ser testada na JVM, ficando o shader reduzido a
 * aritmética simples sobre valores já provados.
 *
 * A armadilha maior é a orientação das matrizes. O `ColorScience` trabalha em **ordem de linhas**,
 * como toda a matemática deste projeto; o OpenGL espera **ordem de colunas** em
 * `glUniformMatrix3fv` com `transpose = false`. Trocar isto dá cores plausíveis mas erradas, que é
 * o pior tipo de erro.
 */
class GlUniforms(
    /** Matriz câmara → saída, **em ordem de colunas**, pronta para `glUniformMatrix3fv`. */
    val colourMatrix: FloatArray,
    /** Ganhos de balanço multiplicados pela exposição, na ordem R, G, B. */
    val whiteBalance: FloatArray,
    /** Cores do mosaico nas posições (0,0), (1,0), (0,1), (1,1). 0=R, 1=G, 2=B. */
    val cfa: IntArray,
    /**
     * Tabela de vinhetagem: `rings` entradas de RGB com a **queda**, do centro ao canto.
     *
     * Guarda-se a queda e não o ganho de propósito. O `ShadingProfile.gain` interpola a queda e
     * **depois** inverte; interpolar ganhos já invertidos dá outro número. Guardar a queda é o que
     * torna o shader e o Kotlin comparáveis ao bit.
     */
    val shadingLut: FloatArray,
    val shadingRings: Int,
    /** Força da correcção, aplicada no shader como expoente. Ver `LensShading.applyStrength`. */
    val shadingStrength: Float,
    val whiteLevel: Float,
    /** Nível de preto por posição do mosaico, na mesma ordem do `cfa`. */
    val blackLevel: FloatArray,
    val rolloffWhitePoint: Float,
) {

    companion object {

        /**
         * De ordem de linhas para ordem de colunas.
         *
         * `m[linha * 3 + coluna]` passa a `out[coluna * 3 + linha]`.
         */
        fun toColumnMajor(rowMajor: DoubleArray): FloatArray {
            val out = FloatArray(9)
            for (linha in 0 until 3) {
                for (coluna in 0 until 3) {
                    out[coluna * 3 + linha] = rowMajor[linha * 3 + coluna].toFloat()
                }
            }
            return out
        }

        /**
         * Perfil de vinhetagem como tabela linear, para o shader interpolar por raio.
         *
         * Contém a **queda** medida, tal e qual: o shader interpola e só depois inverte e eleva à
         * força, exactamente como o `ShadingProfile.gain` seguido de `LensShading.applyStrength`.
         *
         * A ordem importa. Interpolar ganhos — isto é, valores já invertidos — daria um número
         * diferente do que o Kotlin calcula, e a diferença entre os dois caminhos deixaria de ser
         * atribuível a um bug de canalização.
         *
         * A entrada `k` corresponde ao raio `(k + 0,5) / rings`, que é onde os anéis foram medidos.
         */
        fun shadingLut(profile: ShadingProfile?): Pair<FloatArray, Int> {
            if (profile == null) return floatArrayOf(1f, 1f, 1f) to 1
            val n = profile.rings
            val out = FloatArray(n * 3)
            for (k in 0 until n) {
                out[k * 3] = profile.red[k]
                out[k * 3 + 1] = profile.green[k]
                out[k * 3 + 2] = profile.blue[k]
            }
            return out to n
        }

        fun from(
            reader: DngReader,
            profile: ShadingProfile?,
            settings: DevelopSettings,
        ): GlUniforms = montar(
            forwardMatrix(reader, settings.kelvin),
            reader.asShotNeutral,
            reader.cfa,
            reader.whiteLevel,
            reader.blackLevel,
            profile,
            settings)

        /**
         * Os mesmos uniformes, mas a partir da câmara em vez de um ficheiro.
         *
         * É isto que faz o visor mostrar o que o ficheiro vai ter, sem passar por disco: as matrizes,
         * o mosaico e os níveis vêm das `CameraCharacteristics`, que são a mesma fonte de onde o
         * `DngCreator` os tira para escrever o DNG.
         *
         * O ponto neutro é a excepção, e de propósito: vem do **Kelvin e da tinta que o utilizador
         * escolheu**, calculados pelo `WhiteBalance`. É a mesma conta que produz os
         * `COLOR_CORRECTION_GAINS` que determinam o `AsShotNeutral` do ficheiro — verificado a 0,07%
         * contra uma implementação independente. Não se lê o `SENSOR_NEUTRAL_COLOR_POINT` do resultado
         * porque isso seria perguntar ao HAL o que nós já decidimos.
         *
         * @return nulo se faltar metadado essencial. Não se inventa: sem matriz de cor ou sem mosaico
         *   não há revelação honesta possível.
         */
        fun fromCamera(
            ch: CameraCharacteristics,
            kelvin: Int,
            tint: Float,
            profile: ShadingProfile?,
            settings: DevelopSettings,
        ): GlUniforms? {
            val cfa = Demosaic.cfaFromArrangement(
                ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) ?: return null
            val branco = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: return null
            val neutro = WhiteBalance.neutral(ch, kelvin, tint) ?: return null

            val fm1 = ColorScience.toDoubles(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))
            val fm2 = ColorScience.toDoubles(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))
            val forward = when {
                fm1 == null -> fm2 ?: return null
                fm2 == null -> fm1
                else -> ColorScience.interpolateByCct(
                    fm1,
                    ColorScience.illuminantKelvin(
                        ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)),
                    fm2,
                    ColorScience.illuminantKelvin(
                        ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()),
                    kelvin)
            }

            // O padrão estático, e não o `SENSOR_DYNAMIC_BLACK_LEVEL`: este dispositivo não expõe a
            // chave dinâmica, e mediu-se no escuro que o pedestal já vem subtraído.
            val preto = FloatArray(4)
            ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
                val v = IntArray(4)
                p.copyTo(v, 0)
                for (i in 0 until 4) preto[i] = v[i].toFloat()
            }

            return montar(forward, neutro, cfa, branco, preto, profile, settings)
        }

        /**
         * A parte comum aos dois caminhos.
         *
         * Que o ficheiro e a câmara passem por aqui não é economia de linhas: é o que garante que os
         * dois produzem exactamente os mesmos uniformes a partir dos mesmos números. Se divergissem, o
         * visor deixava de mostrar o que o ficheiro vai ter.
         */
        private fun montar(
            forward: DoubleArray?,
            neutral: DoubleArray,
            cfa: IntArray,
            whiteLevel: Int,
            blackLevel: FloatArray,
            profile: ShadingProfile?,
            settings: DevelopSettings,
        ): GlUniforms {
            val matriz = if (forward != null) {
                toColumnMajor(ColorScience.cameraToOutput(forward, settings.output))
            } else {
                floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }

            val ganhos = ColorScience.whiteBalanceGains(neutral)
            val exposicao = Math.pow(2.0, settings.exposureEv.toDouble())
            val wb = FloatArray(3) { (ganhos[it] * exposicao).toFloat() }

            val (lut, aneis) = shadingLut(if (settings.shadingStrength > 0f) profile else null)

            return GlUniforms(
                colourMatrix = matriz,
                whiteBalance = wb,
                cfa = cfa.copyOf(),
                shadingLut = lut,
                shadingRings = aneis,
                shadingStrength = settings.shadingStrength,
                whiteLevel = whiteLevel.toFloat(),
                blackLevel = blackLevel.copyOf(),
                rolloffWhitePoint = settings.rolloff,
            )
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
    }
}
