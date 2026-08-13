package io.github.bmcsilva.latente.model

/**
 * Os modos de exposição, e a linha de programa que decide tempo e ISO.
 *
 * Tudo aqui são funções puras sobre primitivos, pela mesma razão que o `HalClamp`: é a parte onde um
 * erro dá uma fotografia mal exposta em vez de uma excepção, e portanto é a parte que precisa de
 * testes. Nada nesta classe toca no Camera2.
 *
 * A linha de programa não é arbitrária — segue o que se mediu neste projeto:
 *
 * - **ISO analógico primeiro.** Acima de 640 neste sensor o ganho é digital: multiplica o ruído com o
 *   sinal e não acrescenta informação. É volume, não sinal.
 * - **O tecto de exposição é 1750 ms**, não os 100 ms declarados, e acima dele o HAL **fecha o
 *   dispositivo** em vez de cortar.
 * - **Proteger as luzes altas** é o critério por omissão. Num ficheiro RAW, sombra recupera-se e luz
 *   cortada não existe — não há lá informação para recuperar.
 */
object ExposureProgram {

    /**
     * Quem decide o quê.
     *
     * O `A` e o `P` coincidem num corpo de abertura fixa, e este é: a principal tem uma só abertura,
     * f/1,8. Não é degenerescência do modo, é o que «prioridade à abertura» significa quando há uma
     * abertura — e diz-se em vez de se esconder.
     */
    enum class Mode { M, S, A, P }

    /** O que o fotómetro tenta colocar onde. */
    enum class Metering {
        /**
         * Põe o 99,5.º percentil abaixo do corte, com margem. É o modo por omissão, e é o que um
         * fotógrafo faz com RAW: expor para as luzes e levantar as sombras depois.
         */
        HIGHLIGHTS,

        /** Põe a média em 18% do branco. O fotómetro clássico de média ponderada. */
        AVERAGE,
    }

    /** Os limites do corpo, em primitivos para isto poder ser testado sem Android. */
    class Limits(
        val exposureMinNs: Long,
        val exposureMaxNs: Long,
        val minFrameDurationNs: Long,
        val isoMin: Int,
        val isoMax: Int,
        val maxAnalogIso: Int,
    )

    class Result(
        val exposureNs: Long,
        val iso: Int,
        val frameDurationNs: Long,
        /** Correcção que o fotómetro pediu, em stops. */
        val correctionStops: Double,
        /** O que não se conseguiu aplicar por causa dos limites. Zero é o esperado. */
        val residualStops: Double,
        val notes: List<String>,
    ) {
        val changed: Boolean get() = Math.abs(correctionStops - residualStops) > 0.01
    }

    /**
     * Margem por omissão até ao corte, em stops.
     *
     * Meio stop. Suficiente para um reflexo especular inesperado não queimar, e pouco o bastante para
     * não desperdiçar alcance — que num sensor de 10 bits é escasso.
     */
    const val DEFAULT_HEADROOM_STOPS = 0.5

    /** O tempo mais longo que se usa antes de subir o ISO. Um trigésimo, à mão livre. */
    const val HAND_HELD_LIMIT_NS = 33_333_333L

    /**
     * Tecto de exposição **do visor**, que não é o do disparo.
     *
     * Um oitavo de segundo. Medido no telefone porque não o ter custou caro: com a objectiva tapada, a
     * linha de programa foi ao tecto real de 1,8 s e o visor caiu para **0,6 fps**. Pior do que a taxa
     * era a latência — a seis frames de arrefecimento, reagir a destapar a objectiva levava onze
     * segundos. Um visor congelado não é um visor.
     *
     * A fotografia continua a poder levar 1,8 s: o disparo usa o tecto todo. O que o visor perde em luz
     * recupera-se **em ganho de apresentação**, e por isso o visor continua a mostrar o brilho que o
     * ficheiro vai ter — com mais ruído, que é o preço honesto de o mostrar depressa.
     */
    const val VIEWFINDER_MAX_NS = 125_000_000L

    // -----------------------------------------------------------------------------------------

    /**
     * Quantos stops a exposição precisa de mudar, segundo o fotómetro.
     *
     * Positivo é expor mais. Devolve zero quando não há leitura válida: sem medição não se inventa
     * correcção nenhuma.
     */
    fun correctionStops(
        reading: Meter.Reading,
        metering: Metering,
        headroomStops: Double = DEFAULT_HEADROOM_STOPS,
        compensationStops: Double = 0.0,
    ): Double {
        if (!reading.valid) return 0.0
        // A compensação desloca o **alvo**, não o resultado: pedir +1 EV é dizer ao fotómetro para
        // apontar um stop mais claro, e a partir daí a linha de programa faz o que sempre fez. Somar
        // ao fim daria o mesmo número em modo automático mas mentiria em manual, onde o conselho
        // mostrado tem de ser o conselho para o alvo escolhido.
        val c = compensationStops.coerceIn(-5.0, 5.0)
        return when (metering) {
            Metering.HIGHLIGHTS -> {
                if (reading.highlight <= 0.0) return 0.0
                val alvo = Math.pow(2.0, -headroomStops + c)
                Meter.log2(alvo / reading.highlight)
            }
            Metering.AVERAGE -> {
                if (reading.mean <= 0.0) return 0.0
                Meter.log2(0.18 * Math.pow(2.0, c) / reading.mean)
            }
        }
    }

    /**
     * Aplica o modo: devolve o tempo e o ISO a pedir.
     *
     * Em `M` não se mexe em nada — o fotómetro aconselha e o utilizador manda, que é o ponto de haver
     * um modo manual. Nos outros distribui-se a correcção pela linha de programa.
     */
    fun apply(
        mode: Mode,
        metering: Metering,
        current: Exposure,
        limits: Limits,
        reading: Meter.Reading,
        headroomStops: Double = DEFAULT_HEADROOM_STOPS,
        compensationStops: Double = 0.0,
    ): Result {
        val correccao = correctionStops(reading, metering, headroomStops, compensationStops)
        val notas = ArrayList<String>()

        if (mode == Mode.M) {
            notas.add("manual: o fotómetro aconselha " + stops(correccao) + " e não mexe em nada")
            return Result(current.exposureNs, current.iso,
                frameDuration(current.exposureNs, limits), correccao, correccao, notas)
        }
        if (!reading.valid) {
            notas.add("sem leitura do fotómetro; mantém-se o que estava")
            return Result(current.exposureNs, current.iso,
                frameDuration(current.exposureNs, limits), 0.0, 0.0, notas)
        }

        // Trabalha-se sobre a **luz total** pedida, e não por ajustes encadeados.
        //
        // A primeira versão fazia o encadeado — punha o ISO na base, tentava o tempo, depois subia o
        // ISO — e produzia exposições **piores do que a de partida**: com o tempo já acima do limite de
        // mão livre, descer o ISO três stops e cortar o tempo ao limite tirava luz em vez de a pôr. Os
        // testes apanharam-no. Com a luz total como alvo, o resultado não depende do caminho.
        val alvo = current.exposureNs.toDouble() * current.iso * Math.pow(2.0, correccao)

        val tempo: Long
        val iso: Int
        if (mode == Mode.S) {
            // Tempo é do utilizador: só o ISO se move.
            tempo = current.exposureNs
            iso = arredondarIso(alvo / tempo, limits.isoMax, limits)
        } else {
            val escolha = linhaDePrograma(alvo, limits)
            tempo = escolha.first
            iso = arredondarIso(escolha.second, limits.isoMax, limits)
        }

        val conseguida = tempo.toDouble() * iso
        val restam = Meter.log2(alvo / conseguida)

        if (iso > limits.maxAnalogIso) {
            notas.add("ISO " + iso + " é ganho digital, acima dos " + limits.maxAnalogIso +
                    " analógicos: é volume, não sinal")
        }
        if (tempo > HAND_HELD_LIMIT_NS) {
            notas.add("tempo acima de 1/30 s: apoiar a câmara")
        }
        if (Math.abs(restam) > 0.05) {
            notas.add("faltam " + stops(restam) + " que os limites do corpo não deixam dar")
        }
        if (reading.clipped > 0.01) {
            notas.add(String.format(java.util.Locale.US,
                "%.1f%% das amostras estão no corte", reading.clipped * 100))
        }

        return Result(tempo, iso, frameDuration(tempo, limits), correccao, restam, notas)
    }

    // -----------------------------------------------------------------------------------------

    /**
     * A linha de programa: dada a luz total pedida, que tempo e que ISO.
     *
     * A ordem das preferências é a que os factos medidos ditam, e cada passo só entra quando o anterior
     * se esgota:
     *
     * 1. **ISO na base**, com o tempo a fazer o trabalho, até 1/30 s.
     * 2. **ISO analógico** até 640, mantendo o tempo em 1/30 s.
     * 3. **Tempo até 1750 ms**, que é o tecto real deste HAL.
     * 4. **ISO digital**, só porque não há mais nada — e a nota di-lo.
     *
     * Nota sobre o que isto significa: em `P` e `A` a câmara **recalcula da linha**, não empurra o que
     * lá estava. Partindo de um ponto lento e sensível, a resposta pode ser mais ISO e muito menos
     * tempo — é o que «a câmara escolhe» quer dizer, e é o que faz uma máquina de verdade.
     *
     * @return tempo em ns e ISO.
     */
    private fun linhaDePrograma(alvo: Double, limits: Limits): Pair<Long, Double> {
        val maoLivre = Math.min(HAND_HELD_LIMIT_NS, limits.exposureMaxNs)
        val analogico = Math.min(limits.maxAnalogIso, limits.isoMax)

        // 1. ISO na base.
        val tNaBase = alvo / limits.isoMin
        if (tNaBase <= maoLivre) {
            return arredondarTempo(tNaBase, limits) to limits.isoMin.toDouble()
        }

        // 2. ISO analógico, tempo em mão livre.
        val isoEmMaoLivre = alvo / maoLivre
        if (isoEmMaoLivre <= analogico) {
            return maoLivre to isoEmMaoLivre
        }

        // 3. Esticar o tempo, com o ISO analógico no máximo.
        val tNoAnalogico = alvo / analogico
        if (tNoAnalogico <= limits.exposureMaxNs) {
            return arredondarTempo(tNoAnalogico, limits) to analogico.toDouble()
        }

        // 4. Tempo no tecto e ISO digital.
        return limits.exposureMaxNs to (alvo / limits.exposureMaxNs)
    }

    private fun arredondarTempo(ns: Double, limits: Limits): Long =
        Math.round(ns.coerceIn(limits.exposureMinNs.toDouble(), limits.exposureMaxNs.toDouble()))

    private fun arredondarIso(ideal: Double, teto: Int, limits: Limits): Int =
        Math.round(ideal.coerceIn(limits.isoMin.toDouble(), teto.toDouble()))
            .toInt().coerceIn(limits.isoMin, limits.isoMax)

    /**
     * A duração de frame que acompanha o tempo.
     *
     * Nunca abaixo do piso do stream: pedir menos faz o HAL **descartar a captura**. Custou duas
     * corridas da F1 a descobrir.
     */
    private fun frameDuration(exposureNs: Long, limits: Limits): Long =
        if (exposureNs > limits.minFrameDurationNs) exposureNs else limits.minFrameDurationNs

    fun stops(v: Double): String = String.format(java.util.Locale.US, "%+.2f EV", v)
}
