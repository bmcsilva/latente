package io.github.bmcsilva.latente.diag

import io.github.bmcsilva.latente.export.SidecarRead
import io.github.bmcsilva.latente.render.ShadingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A parte da verificação CPU/GPU que se pode correr sem telefone.
 *
 * A escolha do perfil de vinhetagem depende de saber **que objectiva tirou a fotografia**, e isso
 * vem do sidecar. Aplicar o perfil da principal a uma foto da ultra-grande-angular corrigiria a
 * queda errada — 4,7× no canto contra 8,5× — e o resultado pareceria plausível.
 */
class GpuCheckTest {

    /** Como o `Json.write` escreve mesmo: acento em UTF-8 directo. */
    private val comoOJsonWriteEscreve = """
        {
          "nome": "Latente · negativo LTNT_0015",
          "filhos": [
            {
              "nome": "Objectiva",
              "id da câmara": "2",
              "distância focal mm": 2.2
            }
          ]
        }
    """.trimIndent()

    /** Como fica depois de passar por uma ferramenta que escapa o que não é ASCII. */
    private val comoOPythonReescreve =
        """{"nome": "Latente", "id da câmara": "0", "abertura": 1.8}"""

    @Test
    fun theCameraIdIsFoundInASidecarWrittenByTheApp() {
        assertEquals("2", SidecarRead.cameraId(comoOJsonWriteEscreve))
    }

    @Test
    fun theCameraIdIsAlsoFoundWhenTheAccentIsEscaped() {
        assertEquals("0", SidecarRead.cameraId(comoOPythonReescreve))
    }

    /**
     * Sem o campo não se inventa. A consequência a jusante é não haver correcção de vinhetagem, que
     * é o comportamento seguro — e o relatório di-lo.
     */
    @Test
    fun withoutTheFieldNothingIsGuessed() {
        assertNull(SidecarRead.cameraId("""{"nome": "outra coisa qualquer"}"""))
        assertNull(SidecarRead.cameraId(""))
    }

    /** O valor lido tem de servir mesmo para escolher o perfil. */
    @Test
    fun theIdFromTheSidecarSelectsADifferentProfilePerLens() {
        val principal = ShadingProfile.forDevice("SM-S942B",
            SidecarRead.cameraId(comoOPythonReescreve)!!)
        val ultraLarga = ShadingProfile.forDevice("SM-S942B",
            SidecarRead.cameraId(comoOJsonWriteEscreve)!!)

        assertEquals(ShadingProfile.SM_S942B_ID0, principal)
        assertEquals(ShadingProfile.SM_S942B_ID2, ultraLarga)
    }

    /**
     * E a diferença entre os dois perfis é grande o suficiente para o erro importar: no canto, a
     * ultra-grande-angular precisa de quase o dobro do ganho.
     */
    @Test
    fun theTwoProfilesDifferEnoughForTheChoiceToMatter() {
        val principal = ShadingProfile.SM_S942B_ID0.gain(1, 1.0f)
        val ultraLarga = ShadingProfile.SM_S942B_ID2.gain(1, 1.0f)
        assertEquals(5.16f, principal, 0.05f)
        assertEquals(7.32f, ultraLarga, 0.05f)
    }
}
