package io.github.bmcsilva.latente.camera

/**
 * O que se pediu, o que se levou, e se foi cortado.
 *
 * Isto não é uma nota de rodapé: na especificação é funcionalidade de primeira linha. O
 * utilizador tem direito a saber quando o HAL não lhe deu o que pediu.
 */
data class Applied<T>(val requested: T, val applied: T, val clamped: Boolean) {

    companion object {
        fun <T> of(requested: T, applied: T): Applied<T> =
            Applied(requested, applied, requested != applied)
    }
}

/**
 * Limites que se conhecem antes de falar com o HAL.
 *
 * Funções puras sobre primitivos, de propósito: `android.util.Range` não existe em testes de
 * unidade na JVM, e esta lógica tem de ser testável sem dispositivo.
 *
 * Não se tenta quantizar ao *line time* do sensor — o Camera2 não o expõe. Pede-se o valor
 * limitado ao intervalo e lê-se no `CaptureResult` o que o HAL realmente usou.
 */
object HalClamp {

    fun exposure(requestedNs: Long, minNs: Long, maxNs: Long): Applied<Long> {
        val applied = when {
            requestedNs < minNs -> minNs
            requestedNs > maxNs -> maxNs
            else -> requestedNs
        }
        return Applied.of(requestedNs, applied)
    }

    fun iso(requested: Int, min: Int, max: Int): Applied<Int> {
        val applied = when {
            requested < min -> min
            requested > max -> max
            else -> requested
        }
        return Applied.of(requested, applied)
    }

    /** Foco em dioptrias: 0 é infinito, `maxDiopters` é a distância mínima de foco. */
    fun focus(requestedDiopters: Float, maxDiopters: Float): Applied<Float> {
        val applied = when {
            requestedDiopters < 0f -> 0f
            requestedDiopters > maxDiopters -> maxDiopters
            else -> requestedDiopters
        }
        return Applied.of(requestedDiopters, applied)
    }

    /** A abertura não é contínua: força-se ao conjunto que a objectiva declara. */
    fun aperture(requested: Float?, available: FloatArray?): Applied<Float>? {
        if (available == null || available.isEmpty()) return null
        if (requested == null) return Applied.of(available[0], available[0])
        var best = available[0]
        var bestDelta = Math.abs(available[0] - requested)
        for (a in available) {
            val d = Math.abs(a - requested)
            if (d < bestDelta) {
                bestDelta = d
                best = a
            }
        }
        return Applied.of(requested, best)
    }

    /**
     * Duração do frame: nunca abaixo do mínimo do stream, nunca abaixo da exposição.
     *
     * Isto custou uma corrida inteira da F1. Punha-se a duração igual à exposição, e com exposições
     * curtas — 8 ms, 1 ms — ficava **abaixo da duração mínima do stream RAW** (33,3 ms para
     * 12,5 MP a 30 fps). O HAL descartava o pedido: umas vezes `onCaptureFailed` com razão 0,
     * outras um resultado sem imagem nenhuma. O padrão das falhas era inequívoco — passavam só as
     * capturas com 50 ms ou mais.
     *
     * `minFrameDurationNs` vem de `getOutputMinFrameDuration(RAW_SENSOR, tamanho)`.
     *
     * Não se limita ao `SENSOR_INFO_MAX_FRAME_DURATION` declarado porque a F1 provou que também
     * esse é subdeclarado: uma exposição de 1 s foi honrada com o máximo declarado a 142,9 ms.
     */
    fun frameDuration(exposureNs: Long, minFrameDurationNs: Long): Long {
        val floor = if (minFrameDurationNs > 0) minFrameDurationNs else 0L
        return if (exposureNs > floor) exposureNs else floor
    }

    /** Bits úteis por pixel a partir do nível de branco. 1023 → 10 bits, não 16. */
    fun usefulBits(whiteLevel: Int): Int {
        if (whiteLevel <= 0) return 0
        return Math.ceil(Math.log((whiteLevel + 1).toDouble()) / Math.log(2.0)).toInt()
    }
}
