package io.github.bmcsilva.latente.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Os escritores de relatório são o único código feito à mão que pode produzir saída inválida
 * em silêncio. Estes testes correm na JVM, sem dispositivo.
 */
class ReportFormatTest {

    private fun sample(): Node {
        val root = Node("LATENTE · Sonda")
        root.put("modelo", "SM-S938B")
        root.put("API", 36)
        root.put("factor de recorte", 3.5412)
        root.put("nulo", null)
        root.put("lista vazia", emptyList<String>())
        root.put("com \"aspas\" e\nlinha nova", "valor com \\ barra")

        val cam = root.child("Câmara 0")
        cam.put("VEREDICTO", "SERVE")
        cam.put("capabilities", listOf("RAW", "MANUAL_SENSOR"))
        cam.put("shading JÁ APLICADO ao RAW", false)

        val cor = cam.child("Cor")
        cor.put("forwardMatrix1", Mat3(doubleArrayOf(
            0.9, -0.1, 0.2,
            0.3, 0.8, -0.05,
            -0.02, 0.1, 0.75)))
        cor.put("colorTransform2", null)

        cam.child("Vazia")
        return root
    }

    // -----------------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------------

    @Test
    fun jsonDelimitersAreBalanced() {
        val json = Json.write(sample())
        var braces = 0
        var brackets = 0
        var inString = false
        var escaped = false
        for (c in json) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> braces++
                '}' -> braces--
                '[' -> brackets++
                ']' -> brackets--
            }
            assertTrue("chaves negativas em $json", braces >= 0)
            assertTrue("parênteses negativos", brackets >= 0)
        }
        assertFalse("string não fechada", inString)
        assertEquals("chaves desequilibradas", 0, braces)
        assertEquals("parênteses desequilibrados", 0, brackets)
    }

    @Test
    fun jsonHasNoDanglingCommas() {
        val json = Json.write(sample()).replace(Regex("\\s+"), "")
        assertFalse("vírgula antes de }", json.contains(",}"))
        assertFalse("vírgula antes de ]", json.contains(",]"))
        assertFalse("vírgula dupla", json.contains(",,"))
        assertFalse("abre e vírgula", json.contains("{,"))
        assertFalse("abre lista e vírgula", json.contains("[,"))
    }

    @Test
    fun numbersAreNotQuoted() {
        val json = Json.write(sample()).replace(Regex("[ \\t]+"), "")
        assertTrue("inteiro citado", json.contains("\"API\":36"))
        assertTrue("booleano citado", json.contains("\"shadingJÁAPLICADOaoRAW\":false")
                || json.contains("\"shading JÁ APLICADO ao RAW\":false"))
        assertTrue("nulo mal escrito", json.contains("\"nulo\":null"))
        assertTrue("decimal citado", Regex("\"factor de recorte\":\\s*3\\.54").containsMatchIn(Json.write(sample())))
    }

    @Test
    fun mat3BecomesNineNumbers() {
        val json = Json.write(sample())
        val at = json.indexOf("\"forwardMatrix1\"")
        assertTrue("forwardMatrix1 ausente", at > 0)
        val open = json.indexOf('[', at)
        val close = json.indexOf(']', open)
        assertTrue("lista da matriz ausente", open in 1 until close)
        val body = json.substring(open + 1, close)
        assertEquals("a matriz não tem 9 elementos", 9, body.split(',').size)
    }

    @Test
    fun stringsAreEscaped() {
        val json = Json.write(sample())
        assertTrue("aspas não escapadas", json.contains("\\\"aspas\\\""))
        assertTrue("nova linha não escapada", json.contains("\\n"))
        assertTrue("barra não escapada", json.contains("\\\\ barra"))
    }

    @Test
    fun childrenAreNested() {
        val json = Json.write(sample())
        assertTrue("filhos ausentes", json.contains("\"filhos\""))
        assertTrue("secção Cor ausente", json.contains("\"Câmara 0\""))
        assertTrue("nó folha vazio ausente", json.contains("\"Vazia\""))
    }

    // -----------------------------------------------------------------------
    // Texto
    // -----------------------------------------------------------------------

    @Test
    fun textContainsSectionsAndValues() {
        val txt = Txt.write(sample())
        assertTrue("título ausente", txt.startsWith("LATENTE · Sonda"))
        assertTrue("secção ausente", txt.contains("── Câmara 0"))
        assertTrue("subsecção ausente", txt.contains("── Cor"))
        assertTrue("veredicto ausente", txt.contains("SERVE"))
        assertTrue("lista não unida", txt.contains("RAW, MANUAL_SENSOR"))
    }

    @Test
    fun textShowsDashForNullAndEmpty() {
        val txt = Txt.write(sample())
        assertTrue("nulo sem travessão", Regex("nulo\\s+: —").containsMatchIn(txt))
        assertTrue("lista vazia sem travessão", Regex("lista vazia\\s+: —").containsMatchIn(txt))
    }

    @Test
    fun textPrintsMatrixInThreeRows() {
        val txt = Txt.write(sample())
        val at = txt.indexOf("forwardMatrix1")
        assertTrue(at > 0)
        val chunk = txt.substring(at, minOf(txt.length, at + 400))
        assertEquals("a matriz devia sair em 3 linhas", 3, chunk.split('[').size - 1)
    }
}
