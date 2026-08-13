package io.github.bmcsilva.latente.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Os dados que vão para o shader.
 *
 * O texto do shader não se pode correr sem GPU, mas isto pode — e é aqui que vivem os bugs de
 * porte. A matriz transposta é o clássico: dá cores plausíveis mas erradas, e ninguém repara até
 * comparar com uma referência.
 */
class GlUniformsTest {

    private val fm1 = doubleArrayOf(
        0.616210938, 0.130859375, 0.216796875,
        0.200195312, 0.757812500, 0.041992188,
        -0.000976562, -0.408203125, 1.233398438)

    // -----------------------------------------------------------------------------------------
    // A armadilha principal
    // -----------------------------------------------------------------------------------------

    /**
     * O `ColorScience` é em ordem de linhas; o OpenGL espera ordem de colunas. Se isto estiver
     * trocado, o `mat3 * vec3` do shader calcula a transposta e a cor sai errada sem nada avisar.
     */
    @Test
    fun theMatrixIsTransposedIntoColumnMajor() {
        val col = GlUniforms.toColumnMajor(fm1)
        for (linha in 0 until 3) {
            for (coluna in 0 until 3) {
                assertEquals(
                    "elemento ($linha,$coluna)",
                    fm1[linha * 3 + coluna].toFloat(),
                    col[coluna * 3 + linha],
                    1e-7f)
            }
        }
    }

    /**
     * A prova de que a transposição é a certa: o produto que o GLSL faz com a matriz em ordem de
     * colunas tem de dar o mesmo que o `matVec` em ordem de linhas.
     *
     * No GLSL, `m * v` calcula `soma_k coluna[k] * v[k]` — ou seja `out[i] = m[k*3+i] * v[k]`.
     */
    @Test
    fun theGlslProductMatchesTheRowMajorProduct() {
        val col = GlUniforms.toColumnMajor(fm1)
        val v = doubleArrayOf(0.30, 0.55, 0.22)

        val esperado = ColorScience.matVec(fm1, v)
        val obtido = FloatArray(3)
        for (i in 0 until 3) {
            var acc = 0f
            for (k in 0 until 3) acc += col[k * 3 + i] * v[k].toFloat()
            obtido[i] = acc
        }
        for (i in 0 until 3) {
            assertEquals("componente $i", esperado[i].toFloat(), obtido[i], 1e-6f)
        }
    }

    @Test
    fun transposingTwiceGivesBackTheOriginal( ) {
        val col = GlUniforms.toColumnMajor(fm1)
        val volta = DoubleArray(9)
        for (linha in 0 until 3) {
            for (coluna in 0 until 3) {
                volta[coluna * 3 + linha] = col[linha * 3 + coluna].toDouble()
            }
        }
        for (i in 0 until 9) assertEquals(fm1[i], volta[i], 1e-6)
    }

    // -----------------------------------------------------------------------------------------
    // A tabela de vinhetagem
    // -----------------------------------------------------------------------------------------

    /**
     * A tabela guarda a **queda** medida, não o ganho.
     *
     * O `ShadingProfile.gain` interpola a queda e só depois inverte. Se a tabela guardasse ganhos,
     * o shader interpolaria valores já invertidos e daria outro número — e a diferença entre CPU e
     * GPU deixaria de ser atribuível a um bug de canalização.
     */
    @Test
    fun theLutHoldsTheMeasuredFalloffNotTheGain() {
        val p = ShadingProfile.SM_S942B_ID0
        val (lut, aneis) = GlUniforms.shadingLut(p)
        assertEquals(p.rings, aneis)
        assertEquals(p.rings * 3, lut.size)

        for (k in 0 until aneis) {
            assertEquals("R no anel $k", p.red[k], lut[k * 3], 1e-6f)
            assertEquals("G no anel $k", p.green[k], lut[k * 3 + 1], 1e-6f)
            assertEquals("B no anel $k", p.blue[k], lut[k * 3 + 2], 1e-6f)
        }
        assertEquals("o centro é queda nenhuma", 1.0f, lut[1], 1e-6f)
        assertTrue("o canto devia ser uma queda grande", lut[(aneis - 1) * 3 + 1] < 0.25f)
    }

    /**
     * A ordem das operações importa, e é medível: interpolar-depois-inverter dá um valor diferente
     * de inverter-depois-interpolar. Este teste fixa o valor certo, que é o do Kotlin.
     */
    @Test
    fun interpolatingFalloffDiffersFromInterpolatingGain() {
        val p = ShadingProfile.SM_S942B_ID0
        val n = p.rings
        // A meio caminho entre os dois últimos anéis, onde a curva é mais acentuada.
        val k = n - 2
        val r = (k + 1.0f) / n

        val certo = p.gain(Demosaic.G, r)
        val errado = 0.5f * (1f / p.green[k] + 1f / p.green[k + 1])

        // Medido neste perfil: 4,650 contra 4,696, ou seja 0,046 de diferença — cerca de 1%.
        // Pequeno, mas sistemático e crescente com a curvatura, e impossível de atribuir depois.
        assertEquals(4.650f, certo, 0.01f)
        assertEquals(4.696f, errado, 0.01f)
        assertTrue("os dois caminhos deviam divergir de forma mensurável",
            Math.abs(certo - errado) > 0.02f)
        // E o caminho certo é o que interpola a queda.
        val quedaInterpolada = 0.5f * (p.green[k] + p.green[k + 1])
        assertEquals(1f / quedaInterpolada, certo, 1e-4f)
    }

    @Test
    fun withoutAProfileTheLutIsNeutral() {
        val (lut, aneis) = GlUniforms.shadingLut(null)
        assertEquals(1, aneis)
        for (v in lut) assertEquals(1f, v, 1e-9f)
    }

    // -----------------------------------------------------------------------------------------
    // O mosaico
    // -----------------------------------------------------------------------------------------

    /**
     * A ordem do `ivec4` do shader tem de ser a mesma do `cfa` do Kotlin: (0,0), (1,0), (0,1),
     * (1,1). Trocar as duas do meio dá cor certa em metade dos píxeis, que é o pior sintoma
     * possível porque parece quase bem.
     */
    @Test
    fun theCfaOrderMatchesTheShaderIndexing() {
        val gbrg = intArrayOf(Demosaic.G, Demosaic.B, Demosaic.R, Demosaic.G)
        val m = Mosaic(FloatArray(16), 4, 4, gbrg)
        for (y in 0 until 2) {
            for (x in 0 until 2) {
                val indiceShader = (y and 1) * 2 + (x and 1)
                assertEquals("posição ($x,$y)", m.colourAt(x, y), gbrg[indiceShader])
            }
        }
    }

    /**
     * O mosaico vindo do enum do Camera2 tem de dar o mesmo padrão que o DNG escreve.
     *
     * Os valores do `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` coincidem com os do `CFAPattern` do DNG, e
     * é essa coincidência que permite o visor e o ficheiro partilharem o shader. Se a tabela estivesse
     * trocada, o visor mostraria cor errada em metade dos píxeis e o ficheiro sairia bem — ou o
     * contrário, que é pior.
     */
    @Test
    fun theArrangementEnumMapsToTheDngPattern() {
        // O dispositivo de referência: a principal é GBRG, valor 2.
        val gbrg = Demosaic.cfaFromArrangement(2)!!
        assertEquals(listOf(Demosaic.G, Demosaic.B, Demosaic.R, Demosaic.G), gbrg.toList())

        assertEquals(listOf(Demosaic.R, Demosaic.G, Demosaic.G, Demosaic.B),
            Demosaic.cfaFromArrangement(0)!!.toList())
        assertEquals(listOf(Demosaic.G, Demosaic.R, Demosaic.B, Demosaic.G),
            Demosaic.cfaFromArrangement(1)!!.toList())
        assertEquals(listOf(Demosaic.B, Demosaic.G, Demosaic.G, Demosaic.R),
            Demosaic.cfaFromArrangement(3)!!.toList())
    }

    /** Cada padrão tem de ter dois verdes, um vermelho e um azul. Um mosaico não é uma lista livre. */
    @Test
    fun everyPatternHasTwoGreensOneRedOneBlue() {
        for (arranjo in 0 until 4) {
            val cfa = Demosaic.cfaFromArrangement(arranjo)!!
            assertEquals("arranjo $arranjo: verdes", 2, cfa.count { it == Demosaic.G })
            assertEquals("arranjo $arranjo: vermelhos", 1, cfa.count { it == Demosaic.R })
            assertEquals("arranjo $arranjo: azuis", 1, cfa.count { it == Demosaic.B })
        }
    }

    /** Monocromático, infravermelho e ausente não se convertem: não se inventa um mosaico. */
    @Test
    fun unknownArrangementsGiveNothingInsteadOfAGuess() {
        assertNull(Demosaic.cfaFromArrangement(null))
        assertNull(Demosaic.cfaFromArrangement(4))
        assertNull(Demosaic.cfaFromArrangement(5))
        assertNull(Demosaic.cfaFromArrangement(-1))
    }

    // -----------------------------------------------------------------------------------------
    // Coerência com o shader
    // -----------------------------------------------------------------------------------------

    /**
     * Os filtros do Malvar-He-Cutler têm de estar iguais nos dois sítios. Isto não corre o shader,
     * mas garante que ninguém mexe num sem mexer no outro: os coeficientes estão no texto e são
     * procurados aqui.
     */
    /** A força tem de ser aplicada no shader, e como expoente. */
    @Test
    fun theShaderAppliesStrengthAsAnExponent() {
        for (fonte in listOf(GlslSource.DEVELOP_FRAGMENT, GlslSource.PREVIEW_FRAGMENT)) {
            assertTrue("falta a função de ganho", fonte.contains("vec3 ganhoVinhetagem(float r)"))
            assertTrue("a queda devia ser interpolada antes de invertida",
                fonte.contains("vec3 queda = mix(uVinhetagem[a], uVinhetagem[b], t)"))
            assertTrue("devia inverter depois de interpolar",
                fonte.contains("1.0 / max(queda"))
            assertTrue("a força devia ser um expoente", fonte.contains("pow(ganho, vec3(uForca))"))
        }
    }

    @Test
    fun theShaderCarriesTheSameMalvarCoefficients() {
        val fonte = GlslSource.DEVELOP_FRAGMENT
        assertTrue("falta o filtro do verde", fonte.contains("4.0 * amostra(p)"))
        assertTrue("falta o filtro da mesma linha", fonte.contains("5.0 * amostra(p)"))
        assertTrue("falta o filtro da diagonal", fonte.contains("6.0 * amostra(p)"))
        assertTrue("falta o coeficiente 1.5 da diagonal", fonte.contains("1.5 * ("))
        assertTrue("falta o meio-coeficiente do filtro em verde", fonte.contains("0.5 * ("))
        // Todos os filtros dividem por 8.
        assertEquals("deviam ser quatro divisões por 8", 4,
            Regex("acc / 8\\.0").findAll(fonte).count())
    }

    @Test
    fun theShaderReflectsAtTheBordersInsteadOfClamping() {
        val fonte = GlslSource.DEVELOP_FRAGMENT
        assertTrue("falta a reflexão", fonte.contains("2 * uTamanho.x - p.x - 2"))
        assertTrue("falta a reflexão vertical", fonte.contains("2 * uTamanho.y - p.y - 2"))
    }

    @Test
    fun bothShadersUseTheSameEncodingConstants() {
        for (fonte in listOf(GlslSource.DEVELOP_FRAGMENT, GlslSource.PREVIEW_FRAGMENT)) {
            assertTrue("falta o limiar do sRGB", fonte.contains("0.0031308"))
            assertTrue("falta o declive linear", fonte.contains("12.92"))
            assertTrue("falta o expoente", fonte.contains("1.0 / 2.4"))
            assertTrue("falta o desvio", fonte.contains("1.055"))
        }
    }

    @Test
    fun theShadersDeclareTheEs31Version() {
        for (fonte in listOf(
            GlslSource.VERTEX, GlslSource.DEVELOP_FRAGMENT, GlslSource.PREVIEW_FRAGMENT)) {
            assertTrue("devia declarar #version 310 es", fonte.startsWith("#version 310 es"))
        }
    }

    /** O mosaico entra como inteiros de 16 bits: um formato de vírgula flutuante perderia sombras. */
    @Test
    fun theMosaicIsSampledAsUnsignedIntegers() {
        for (fonte in listOf(GlslSource.DEVELOP_FRAGMENT, GlslSource.PREVIEW_FRAGMENT)) {
            assertTrue("o mosaico devia ser usampler2D", fonte.contains("uniform usampler2D uMosaico"))
            assertTrue("devia usar texelFetch, sem interpolação", fonte.contains("texelFetch(uMosaico"))
        }
    }
}
