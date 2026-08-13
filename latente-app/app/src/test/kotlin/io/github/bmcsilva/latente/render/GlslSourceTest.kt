package io.github.bmcsilva.latente.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O texto dos shaders, na parte que se pode verificar sem GPU.
 *
 * Isto não corre GLSL — corre expressões sobre o texto. Parece pouco, mas cobre uma classe de bugs
 * que a GPU não denuncia: nomes de uniformes trocados, tabelas dimensionadas a menos, e a
 * correspondência entre o que o `GlDeveloper` envia e o que o shader declara. Um `glUniform*` para
 * uma localização inexistente **não dá erro de GL**, e o shader continua com zeros.
 */
class GlslSourceTest {

    private val fragmentos = listOf(GlslSource.DEVELOP_FRAGMENT, GlslSource.PREVIEW_FRAGMENT)

    // -----------------------------------------------------------------------------------------
    // A ponte entre o Kotlin e o GLSL
    // -----------------------------------------------------------------------------------------

    /**
     * Cada nome da lista tem de estar declarado nos dois fragmentos. É esta lista que o
     * `GlDeveloper` usa para pedir as localizações, portanto um nome a mais ou a menos aqui é um
     * uniforme que fica sem valor.
     */
    @Test
    fun everyDeclaredUniformNameExistsInBothShaders() {
        for (nome in GlslSource.UNIFORM_NAMES) {
            for (fonte in fragmentos) {
                assertTrue(
                    "o shader não declara o uniforme $nome",
                    Regex("uniform\\s+\\w+\\s+" + nome + "\\s*[;\\[]").containsMatchIn(fonte))
            }
        }
    }

    /** E o contrário: nenhum uniforme declarado no shader pode faltar na lista. */
    @Test
    fun noShaderUniformIsMissingFromTheList() {
        for (fonte in fragmentos) {
            val declarados = Regex("uniform\\s+\\w+\\s+(\\w+)\\s*[;\\[]")
                .findAll(fonte).map { it.groupValues[1] }.toList()
            assertTrue("o shader devia declarar uniformes", declarados.isNotEmpty())
            for (nome in declarados) {
                assertTrue(
                    "o shader declara $nome mas o GlDeveloper nunca lho envia",
                    GlslSource.UNIFORM_NAMES.contains(nome))
            }
        }
    }

    /**
     * O array de vinhetagem tem de ser declarado com o mesmo tamanho que o Kotlin verifica.
     *
     * Se o shader reservasse menos anéis do que o perfil tem, os últimos — que são precisamente os
     * cantos, onde a queda é maior — ficariam a zero. Daria uma imagem plausível e errada.
     */
    @Test
    fun theShadingArrayIsDeclaredWithTheCheckedSize() {
        for (fonte in fragmentos) {
            assertTrue("o tamanho declarado devia ser o MAX_RINGS",
                fonte.contains("uVinhetagem[" + GlslSource.MAX_RINGS + "]"))
        }
    }

    /** Os perfis medidos têm de caber no que o shader reserva. */
    @Test
    fun theMeasuredProfilesFitInTheShaderArray() {
        for (p in listOf(ShadingProfile.SM_S942B_ID0, ShadingProfile.SM_S942B_ID2)) {
            assertTrue("um perfil de " + p.rings + " anéis não cabe em " + GlslSource.MAX_RINGS,
                p.rings <= GlslSource.MAX_RINGS)
        }
    }

    // -----------------------------------------------------------------------------------------
    // A indexação do pixel
    // -----------------------------------------------------------------------------------------

    /**
     * Os fragmentos indexam o mosaico pelo `gl_FragCoord`, não por coordenadas interpoladas.
     *
     * Um `varying` com coordenadas de textura passa por interpolação e por arredondamento, e num
     * mosaico isso troca a paridade do CFA em píxeis isolados — verde onde devia ser vermelho, no
     * meio de uma imagem que no resto está certa. O `gl_FragCoord` dá o pixel exacto.
     */
    @Test
    fun theFragmentsIndexByFragCoordAndNotByVaryings() {
        for (fonte in fragmentos) {
            assertTrue("devia indexar pelo gl_FragCoord", fonte.contains("ivec2(gl_FragCoord.xy)"))
            assertFalse("não devia haver coordenadas interpoladas", fonte.contains("vUv"))
        }
        assertFalse("o vértice não devia emitir coordenadas", GlslSource.VERTEX.contains("out "))
    }

    /** O visor lê o mosaico de dois em dois, para agrupar cada quadrado do CFA num pixel. */
    @Test
    fun thePreviewSteppsTwoMosaicPixelsPerOutputPixel() {
        assertTrue("o visor devia avançar de dois em dois",
            GlslSource.PREVIEW_FRAGMENT.contains("ivec2(gl_FragCoord.xy) * 2"))
        assertFalse("a revelação completa não devia agrupar",
            GlslSource.DEVELOP_FRAGMENT.contains("gl_FragCoord.xy) * 2"))
    }

    // -----------------------------------------------------------------------------------------
    // Coerência interna
    // -----------------------------------------------------------------------------------------

    /** Os dois fragmentos escrevem num só alvo, e é o que o `GlDeveloper` liga ao FBO. */
    @Test
    fun eachFragmentWritesASingleOutput() {
        for (fonte in fragmentos) {
            assertEquals("devia haver uma só saída", 1,
                Regex("out\\s+vec4\\s+\\w+;").findAll(fonte).count())
        }
    }

    /** O alfa sai opaco: o `GpuCheck` compara três canais e ignora o quarto. */
    @Test
    fun theAlphaIsOpaque() {
        for (fonte in fragmentos) {
            assertTrue("o alfa devia ser 1.0", fonte.contains(", 1.0);"))
        }
    }
}
