package io.github.bmcsilva.latente.export

import io.github.bmcsilva.latente.render.DevelopSettings

/**
 * A leitura do sidecar, ao lado de quem o escreve.
 *
 * Fica no mesmo pacote que o `Sidecar` de propósito: os nomes dos campos são um contrato entre os dois,
 * e um contrato com as duas pontas em ficheiros distantes muda numa ponta só. Havia duas cópias desta
 * leitura — uma na verificação CPU/GPU e outra no ecrã dos negativos — com a mesma expressão copiada
 * palavra por palavra.
 *
 * É **extracção dirigida e não um leitor de JSON**. Não vale a pena um parser para meia dúzia de
 * campos, e a alternativa disponível — o `org.json` do Android — não existe nos testes de JVM, o que
 * tiraria a esta leitura a única cobertura que ela tem. Se o formato mudar, os campos não são
 * encontrados e usam-se as omissões: o comportamento seguro é não corrigir, não é adivinhar.
 */
object SidecarRead {

    /** O id da câmara, que decide o perfil de vinhetagem. Nulo quando o campo não está lá. */
    fun cameraId(texto: String?): String? = cadeia(texto, SidecarKeys.CAMERA_ID)

    /** A rotação que se etiquetou no negativo, em graus. Zero quando o campo não está lá. */
    fun rotationDegrees(texto: String?): Int =
        numero(texto, SidecarKeys.ROTATION_DEGREES)?.toInt() ?: 0

    /**
     * A receita, para revelar o negativo como ele estava no visor.
     *
     * Sem sidecar, as omissões do `DevelopSettings` — que é o mesmo que dizer «revela sem receita».
     */
    fun develop(texto: String?): DevelopSettings = DevelopSettings(
        exposureEv = numero(texto, SidecarKeys.DEVELOP_EV)?.toFloat() ?: 0f,
        kelvin = numero(texto, SidecarKeys.KELVIN)?.toInt() ?: 5500,
        shadingStrength = numero(texto, SidecarKeys.SHADING_STRENGTH)?.toFloat() ?: 1f,
        rolloff = numero(texto, SidecarKeys.ROLLOFF)?.toFloat() ?: 1f)

    // -----------------------------------------------------------------------------------------

    private fun cadeia(texto: String?, chave: String): String? {
        if (texto == null) return null
        return Regex(chaveEDoisPontos(chave) + "\"([^\"]*)\"")
            .find(texto)?.groupValues?.get(1)
    }

    private fun numero(texto: String?, chave: String): Double? {
        if (texto == null) return null
        return Regex(chaveEDoisPontos(chave) + "(-?[0-9.eE+-]+)")
            .find(texto)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * A chave entre aspas, com os acentos tolerantes ao encoding.
     *
     * Um caracter fora do ASCII pode chegar em UTF-8 directo — que é o que o nosso `Json.write` produz
     * — ou escapado em `\uXXXX`, se o ficheiro passar por uma ferramenta que o reescreva; o
     * `tools/develop.py` fá-lo. As três cópias anteriores desta leitura resolviam-no cada uma à sua
     * maneira, e uma delas não o resolvia: a receita não era encontrada num sidecar reescrito, porque
     * casava «exposição» à letra. Aqui o mecanismo é um só e vale para todos os campos.
     */
    private fun chaveEDoisPontos(chave: String): String {
        val sb = StringBuilder("\"")
        for (c in chave) {
            if (c.code < 128) sb.append(Regex.escape(c.toString())) else sb.append(".{1,6}")
        }
        sb.append("\"\\s*:\\s*")
        return sb.toString()
    }
}
