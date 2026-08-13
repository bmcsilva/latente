package io.github.bmcsilva.latente.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A leitura do sidecar, contra o que o próprio `Json.write` produz.
 *
 * O teste que interessa não é «a expressão casa com esta cadeia» — é **o que escrevemos volta a ler-se**.
 * Por isso o texto de partida é gerado aqui pelo escritor a sério, com as chaves partilhadas do
 * `SidecarKeys`: se alguém renomear um campo numa das pontas, isto deixa de encontrar o valor.
 */
class SidecarReadTest {

    /** Um sidecar como a aplicação o escreve: acentos em UTF-8 directo. */
    private fun comoAAppEscreve(): String {
        val raiz = Node("Latente · negativo LTNT_0042")
        raiz.put(SidecarKeys.ROTATION_DEGREES, 90)
        val objectiva = Node("Objectiva")
        objectiva.put(SidecarKeys.CAMERA_ID, "2")
        raiz.children.add(objectiva)
        val revelacao = Node("Revelação")
        revelacao.put(SidecarKeys.DEVELOP_EV, -1.25)
        revelacao.put(SidecarKeys.KELVIN, 4100)
        revelacao.put(SidecarKeys.SHADING_STRENGTH, 0.75)
        revelacao.put(SidecarKeys.ROLLOFF, 0.9)
        raiz.children.add(revelacao)
        return Json.write(raiz)
    }

    /** O mesmo depois de passar por uma ferramenta que escapa o não-ASCII — o `tools/develop.py`. */
    private fun comoOPythonReescreve(): String {
        val sb = StringBuilder()
        for (c in comoAAppEscreve()) {
            if (c.code < 128) sb.append(c) else sb.append(String.format("\\u%04x", c.code))
        }
        return sb.toString()
    }

    @Test
    fun theRecipeSurvivesTheRoundTripThroughTheRealWriter() {
        val r = SidecarRead.develop(comoAAppEscreve())
        assertEquals(-1.25f, r.exposureEv, 1e-4f)
        assertEquals(4100, r.kelvin)
        assertEquals(0.75f, r.shadingStrength, 1e-4f)
        assertEquals(0.9f, r.rolloff, 1e-4f)
    }

    @Test
    fun theCameraIdAndTheRotationSurviveToo() {
        val texto = comoAAppEscreve()
        assertEquals("2", SidecarRead.cameraId(texto))
        assertEquals(90, SidecarRead.rotationDegrees(texto))
    }

    /**
     * O que estava mal antes desta unificação.
     *
     * O leitor da biblioteca casava as chaves da receita à letra, com os acentos e tudo. Num sidecar
     * reescrito por uma ferramenta que escapa o não-ASCII, «exposição de revelação EV» deixava de ser
     * encontrado e a revelação seguia com as omissões — 0 EV e 5500 K em vez da receita, sem aviso
     * nenhum. O id da câmara escapava a isto por acaso, por ter a sua própria expressão tolerante.
     */
    @Test
    fun everyFieldIsFoundEvenWhenTheAccentsAreEscaped() {
        val texto = comoOPythonReescreve()
        val r = SidecarRead.develop(texto)
        assertEquals(-1.25f, r.exposureEv, 1e-4f)
        assertEquals(4100, r.kelvin)
        assertEquals(0.75f, r.shadingStrength, 1e-4f)
        assertEquals("2", SidecarRead.cameraId(texto))
        assertEquals(90, SidecarRead.rotationDegrees(texto))
    }

    /**
     * Sem campo não se inventa: ficam as omissões, que é revelar sem receita. A consequência é visível
     * — a imagem sai como o pipeline neutro a dá — e não uma correcção adivinhada.
     */
    @Test
    fun withoutTheFieldsTheDefaultsStand() {
        val r = SidecarRead.develop("""{"nome": "outra coisa qualquer"}""")
        assertEquals(0f, r.exposureEv, 1e-6f)
        assertEquals(5500, r.kelvin)
        assertEquals(1f, r.shadingStrength, 1e-6f)
        assertEquals(1f, r.rolloff, 1e-6f)
        assertNull(SidecarRead.cameraId(""))
        assertEquals(0, SidecarRead.rotationDegrees(""))
        assertNull(SidecarRead.cameraId(null))
    }

    /**
     * Uma chave não pode casar dentro de outra maior.
     *
     * «rolloff» é curta e sem acentos, e é o caso onde uma expressão frouxa se enganaria. Aqui o campo
     * pedido tem de aparecer entre aspas e seguido de dois pontos.
     */
    @Test
    fun aShortKeyDoesNotMatchInsideALongerOne() {
        val texto = """{"rolloff do sensor": 7, "rolloff": 0.5}"""
        assertEquals(0.5f, SidecarRead.develop(texto).rolloff, 1e-4f)
    }
}
