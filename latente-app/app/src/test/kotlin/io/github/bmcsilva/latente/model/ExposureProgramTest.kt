package io.github.bmcsilva.latente.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A linha de programa.
 *
 * Os limites são os **medidos** no dispositivo de referência, não os declarados: tecto de exposição de
 * 1750 ms e não 100 ms, ISO analógico até 640. Testar com os declarados provaria a coisa errada.
 */
class ExposureProgramTest {

    private val limites = ExposureProgram.Limits(
        exposureMinNs = 15_000L,
        exposureMaxNs = 1_750_000_000L,
        minFrameDurationNs = 33_331_760L,
        isoMin = 50,
        isoMax = 3200,
        maxAnalogIso = 640)

    private val base = Exposure(
        exposureNs = 8_000_000L,      // 1/125 s
        iso = 50,
        frameDurationNs = 33_331_760L,
        focusDiopters = 0.5f,
        aperture = 1.8f)

    /** Uma leitura em que as luzes altas estão onde se quer: meio stop abaixo do corte. */
    private fun leitura(
        highlight: Double,
        mean: Double = highlight / 3,
        clipped: Double = 0.0,
    ) = Meter.Reading(1000, clipped, mean, mean, highlight)

    // -----------------------------------------------------------------------------------------
    // A correcção que o fotómetro pede
    // -----------------------------------------------------------------------------------------

    /** Com as luzes altas já a meio stop do corte, não há nada a corrigir. */
    @Test
    fun anAlreadyCorrectExposureNeedsNoCorrection() {
        val r = leitura(highlight = Math.pow(2.0, -0.5))
        assertEquals(0.0,
            ExposureProgram.correctionStops(r, ExposureProgram.Metering.HIGHLIGHTS), 0.01)
    }

    /** Luzes altas dois stops abaixo do alvo: pede-se dois stops a mais. */
    @Test
    fun darkHighlightsAskForMoreExposure() {
        val r = leitura(highlight = Math.pow(2.0, -2.5))
        assertEquals(2.0,
            ExposureProgram.correctionStops(r, ExposureProgram.Metering.HIGHLIGHTS), 0.01)
    }

    /** No corte, pede-se menos exposição. */
    @Test
    fun clippedHighlightsAskForLessExposure() {
        val r = leitura(highlight = 1.0)
        assertEquals(-0.5,
            ExposureProgram.correctionStops(r, ExposureProgram.Metering.HIGHLIGHTS), 0.01)
    }

    /** A média põe 18% do branco no sítio, que é outro critério e dá outro número. */
    @Test
    fun averageMeteringTargetsEighteenPercent() {
        val r = leitura(highlight = 0.9, mean = 0.09)
        assertEquals(1.0,
            ExposureProgram.correctionStops(r, ExposureProgram.Metering.AVERAGE), 0.01)
    }

    /** Sem leitura não se inventa correcção. */
    @Test
    fun withoutAReadingThereIsNoCorrection() {
        val nada = Meter.Reading(0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(0.0,
            ExposureProgram.correctionStops(nada, ExposureProgram.Metering.HIGHLIGHTS), 1e-9)
        assertEquals(0.0,
            ExposureProgram.correctionStops(nada, ExposureProgram.Metering.AVERAGE), 1e-9)
    }

    // -----------------------------------------------------------------------------------------
    // Compensação de exposição
    // -----------------------------------------------------------------------------------------

    /**
     * A compensação desloca o **alvo**, não o resultado.
     *
     * Pedir +1 EV é dizer ao fotómetro para apontar um stop mais claro. Somar ao fim daria o mesmo
     * número em automático, mas mentiria em manual — onde o conselho mostrado tem de ser o conselho
     * para o alvo escolhido, e não para um alvo que já ninguém quer.
     */
    @Test
    fun compensationShiftsTheTargetByExactlyThatMuch() {
        val r = leitura(highlight = Math.pow(2.0, -0.5))
        assertEquals("sem compensação, nada a corrigir",
            0.0, ExposureProgram.correctionStops(r, ExposureProgram.Metering.HIGHLIGHTS), 0.01)
        assertEquals("+1 EV pede um stop a mais",
            1.0, ExposureProgram.correctionStops(
                r, ExposureProgram.Metering.HIGHLIGHTS, compensationStops = 1.0), 0.01)
        assertEquals("−2 EV pede dois stops a menos",
            -2.0, ExposureProgram.correctionStops(
                r, ExposureProgram.Metering.HIGHLIGHTS, compensationStops = -2.0), 0.01)
    }

    /** Funciona igual no fotómetro de média, que tem outro alvo. */
    @Test
    fun compensationAlsoShiftsTheAverageTarget() {
        val r = leitura(highlight = 0.9, mean = 0.18)
        assertEquals(0.0, ExposureProgram.correctionStops(
            r, ExposureProgram.Metering.AVERAGE), 0.01)
        assertEquals(1.5, ExposureProgram.correctionStops(
            r, ExposureProgram.Metering.AVERAGE, compensationStops = 1.5), 0.01)
    }

    /** E chega ao tempo e ao ISO: um stop de compensação é um stop de luz a mais no ficheiro. */
    @Test
    fun compensationReachesTheTimeAndTheIso() {
        val leitura = leitura(highlight = Math.pow(2.0, -0.5))
        val neutro = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, base, limites, leitura)
        val maisClaro = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, base, limites, leitura,
            compensationStops = 1.0)
        val luzNeutra = neutro.exposureNs.toDouble() * neutro.iso
        val luzClara = maisClaro.exposureNs.toDouble() * maisClaro.iso
        assertEquals("o dobro da luz", 2.0, luzClara / luzNeutra, 0.05)
    }

    /** Pedidos absurdos são cortados em vez de rebentarem os limites do corpo. */
    @Test
    fun anAbsurdCompensationIsClamped() {
        val r = leitura(highlight = Math.pow(2.0, -0.5))
        val muito = ExposureProgram.correctionStops(
            r, ExposureProgram.Metering.HIGHLIGHTS, compensationStops = 99.0)
        assertEquals("cortado em 5 stops", 5.0, muito, 0.01)
    }

    // -----------------------------------------------------------------------------------------
    // Manual
    // -----------------------------------------------------------------------------------------

    /**
     * Em manual o fotómetro aconselha e **não mexe em nada**. É o ponto de haver um modo manual, e o
     * pedido explícito de quem usa isto.
     */
    @Test
    fun manualModeNeverChangesAnything() {
        val r = ExposureProgram.apply(
            ExposureProgram.Mode.M, ExposureProgram.Metering.HIGHLIGHTS, base, limites,
            leitura(highlight = 0.05))
        assertEquals(base.exposureNs, r.exposureNs)
        assertEquals(base.iso, r.iso)
        assertFalse("nada mudou", r.changed)
        assertTrue("mas o conselho está lá", r.correctionStops > 3.0)
    }

    // -----------------------------------------------------------------------------------------
    // Prioridade ao tempo
    // -----------------------------------------------------------------------------------------

    /** Em S o tempo é do utilizador: só o ISO se move. */
    @Test
    fun shutterPriorityMovesOnlyTheIso() {
        val r = ExposureProgram.apply(
            ExposureProgram.Mode.S, ExposureProgram.Metering.HIGHLIGHTS, base, limites,
            leitura(highlight = Math.pow(2.0, -2.5)))
        assertEquals("o tempo não se mexe", base.exposureNs, r.exposureNs)
        assertEquals("dois stops de ISO", 200, r.iso)
        assertEquals(0.0, r.residualStops, 0.05)
    }

    /** Quando o ISO chega ao tecto, o que falta é reportado em vez de fingido. */
    @Test
    fun whenTheIsoRunsOutTheShortfallIsReported() {
        val r = ExposureProgram.apply(
            ExposureProgram.Mode.S, ExposureProgram.Metering.HIGHLIGHTS, base, limites,
            leitura(highlight = Math.pow(2.0, -10.0)))
        assertEquals("ISO no tecto", limites.isoMax, r.iso)
        assertTrue("e faltam stops", r.residualStops > 3.0)
        assertTrue("e diz-se",
            r.notes.any { it.contains("limites do corpo") })
    }

    // -----------------------------------------------------------------------------------------
    // A linha de programa
    // -----------------------------------------------------------------------------------------

    /**
     * Precisando de luz, o programa usa **primeiro o tempo** e mantém o ISO na base. Um stop de tempo
     * não custa nada em qualidade; um stop de ISO custa ruído.
     */
    @Test
    fun theProgramSpendsTimeBeforeIso() {
        val r = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, base, limites,
            leitura(highlight = Math.pow(2.0, -2.5)))
        assertEquals("ISO fica na base", limites.isoMin, r.iso)
        assertEquals("o tempo quadruplica",
            (base.exposureNs * 4).toDouble(), r.exposureNs.toDouble(), 200_000.0)
        assertEquals(0.0, r.residualStops, 0.05)
    }

    /**
     * Passado o limite de mão livre, sobe-se o ISO — mas só até ao **analógico**. Acima de 640 neste
     * sensor o ganho é digital, e o programa não vai lá enquanto tiver alternativa.
     */
    @Test
    fun pastHandHeldTheProgramRaisesAnalogIsoOnly() {
        val r = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, base, limites,
            leitura(highlight = Math.pow(2.0, -6.5)))
        assertTrue("o tempo devia ter ido até ao limite de mão livre",
            r.exposureNs >= ExposureProgram.HAND_HELD_LIMIT_NS - 1)
        assertTrue("o ISO subiu", r.iso > limites.isoMin)
        assertTrue("mas não passou do analógico: " + r.iso, r.iso <= limites.maxAnalogIso)
        assertEquals(0.0, r.residualStops, 0.1)
    }

    /**
     * Só quando o tempo e o ISO analógico se esgotam se vai ao ganho digital — e nesse caso di-lo pelo
     * nome. «É volume, não sinal» é a conclusão medida na F1, não uma opinião.
     */
    @Test
    fun digitalGainIsTheLastResortAndIsNamed() {
        val r = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, base, limites,
            leitura(highlight = Math.pow(2.0, -14.0)))
        assertTrue("o tempo devia ter ido ao tecto",
            r.exposureNs >= limites.exposureMaxNs - 1_000_000L)
        assertTrue("e o ISO ao digital: " + r.iso, r.iso > limites.maxAnalogIso)
        assertTrue("e tem de ser dito", r.notes.any { it.contains("volume, não sinal") })
    }

    /**
     * Cena queimada: o programa reduz a luz e vai buscar o ponto **da sua linha**.
     *
     * Partindo de 100 ms a ISO 400 e precisando de meio stop a menos, a resposta é 44 ms a ISO 640 — mais
     * ISO do que estava, e menos de metade do tempo. Parece contra-intuitivo e é correcto: a luz total
     * é a pedida, e a linha prefere um tempo curto a ISO analógico do que 566 ms a ISO base.
     *
     * Foi por não ser assim que a primeira versão falhou. Fazia ajustes encadeados a partir do ponto de
     * partida e, com o tempo já longo, chegava a **tirar luz quando lhe pediam para pôr**.
     */
    @Test
    fun anOverexposedSceneLandsOnTheProgramLine() {
        val partida = base.copy(exposureNs = 100_000_000L, iso = 400)
        val r = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, partida, limites,
            leitura(highlight = 1.0, clipped = 0.2))
        assertEquals("a exposição pedida é dada por inteiro", 0.0, r.residualStops, 0.05)
        assertTrue("o tempo desceu muito: " + r.exposureNs, r.exposureNs < partida.exposureNs / 2)
        assertEquals("ISO no tecto analógico, não acima", limites.maxAnalogIso, r.iso)
        assertTrue("e o corte é reportado", r.notes.any { it.contains("corte") })
    }

    /**
     * A propriedade que a correcção trouxe: **em P o resultado depende só da luz pedida, não do ponto
     * de partida.** Dois pontos de partida com a mesma leitura relativa dão a mesma resposta.
     *
     * É isto que «a câmara escolhe» significa, e é o que impede o comportamento de derivar conforme o
     * caminho por onde se chegou.
     */
    @Test
    fun theProgramDependsOnlyOnTheLightAskedFor() {
        // Dois pontos de partida a dois stops de distância, com leituras que pedem a mesma luz total.
        val a = base.copy(exposureNs = 8_000_000L, iso = 200)
        val b = base.copy(exposureNs = 32_000_000L, iso = 50)
        val ra = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, a, limites,
            leitura(highlight = Math.pow(2.0, -2.5)))
        val rb = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, b, limites,
            leitura(highlight = Math.pow(2.0, -2.5)))
        assertEquals("mesmo ISO", ra.iso, rb.iso)
        assertEquals("mesmo tempo",
            ra.exposureNs.toDouble(), rb.exposureNs.toDouble(), 100_000.0)
    }

    /**
     * O A e o P coincidem neste corpo, porque há uma só abertura. Fica fixado para que ninguém tome a
     * coincidência por bug — nem por licença para eliminar o modo.
     */
    @Test
    fun apertureAndProgramCoincideOnAFixedApertureBody() {
        val leitura = leitura(highlight = Math.pow(2.0, -3.5))
        val a = ExposureProgram.apply(
            ExposureProgram.Mode.A, ExposureProgram.Metering.HIGHLIGHTS, base, limites, leitura)
        val p = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, base, limites, leitura)
        assertEquals(a.exposureNs, p.exposureNs)
        assertEquals(a.iso, p.iso)
    }

    // -----------------------------------------------------------------------------------------
    // O tecto declarado contra o tecto medido
    // -----------------------------------------------------------------------------------------

    /**
     * A descoberta de uma corrida real, fixada aqui.
     *
     * Com o tecto **declarado** de 100 ms, uma cena escura empurra a linha de programa para o ganho
     * digital: viu-se 100 ms a ISO 787. Com o tecto **medido** de 1750 ms — 17,5× mais — a mesma cena
     * fica no ISO analógico, porque há mais quatro stops de tempo para gastar antes de o ISO ter de
     * subir.
     *
     * Não é afinação: é a diferença entre um ficheiro com ruído digital e um ficheiro limpo, e vinha de
     * o perfil guardar o que o fabricante diz em vez do que este projeto mediu.
     */
    @Test
    fun theDeclaredCeilingPushesTheProgramIntoDigitalGain() {
        val declarado = ExposureProgram.Limits(
            exposureMinNs = 15_000L,
            exposureMaxNs = 100_000_000L,          // o que o HAL declara
            minFrameDurationNs = 33_331_760L,
            isoMin = 50, isoMax = 3200, maxAnalogIso = 640)

        val escura = leitura(highlight = Math.pow(2.0, -9.0))

        val comDeclarado = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, base, declarado, escura)
        assertEquals("com 100 ms, o tempo esgota-se", 100_000_000L, comDeclarado.exposureNs)
        assertTrue("e o ISO passa ao digital: " + comDeclarado.iso,
            comDeclarado.iso > declarado.maxAnalogIso)

        val comMedido = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, base, limites, escura)
        assertTrue("com 1750 ms, o ISO fica analógico: " + comMedido.iso,
            comMedido.iso <= limites.maxAnalogIso)
        assertEquals("e a exposição é dada por inteiro", 0.0, comMedido.residualStops, 0.05)
    }

    /** A calibração é por modelo, e sem medição fica-se com o declarado. Não se inventa. */
    @Test
    fun anUncalibratedBodyKeepsTheDeclaredCeiling() {
        assertEquals(1_750_000_000L,
            BodyCalibration.exposureCeilingNs("SM-S942B", "0", 100_000_000L))
        assertEquals("um corpo desconhecido fica com o que declara",
            100_000_000L, BodyCalibration.exposureCeilingNs("Pixel 9", "0", 100_000_000L))
        assertTrue(BodyCalibration.ceilingIsMeasured("SM-S942B", "0", 100_000_000L))
        assertFalse(BodyCalibration.ceilingIsMeasured("Pixel 9", "0", 100_000_000L))
    }

    // -----------------------------------------------------------------------------------------
    // Limites que não se violam
    // -----------------------------------------------------------------------------------------

    /**
     * O tecto de exposição **nunca** se excede. Acima dele o HAL não corta: **fecha o dispositivo**, e
     * tudo o que vinha depois rebentava com uma excepção opaca.
     */
    @Test
    fun theExposureCeilingIsNeverExceeded() {
        for (stops in intArrayOf(4, 8, 12, 20)) {
            for (modo in listOf(
                ExposureProgram.Mode.P, ExposureProgram.Mode.A, ExposureProgram.Mode.S)) {
                val r = ExposureProgram.apply(
                    modo, ExposureProgram.Metering.HIGHLIGHTS, base, limites,
                    leitura(highlight = Math.pow(2.0, -stops.toDouble())))
                assertTrue("$modo com $stops stops deu " + r.exposureNs,
                    r.exposureNs <= limites.exposureMaxNs)
                assertTrue("tempo mínimo", r.exposureNs >= limites.exposureMinNs)
                assertTrue("ISO dentro dos limites",
                    r.iso >= limites.isoMin && r.iso <= limites.isoMax)
            }
        }
    }

    /**
     * A duração de frame nunca fica abaixo do piso do stream. Pedir menos faz o HAL **descartar a
     * captura** — custou duas corridas da F1 a descobrir.
     */
    @Test
    fun theFrameDurationNeverGoesBelowTheStreamFloor() {
        for (stops in intArrayOf(-6, -2, 0, 3, 9)) {
            val r = ExposureProgram.apply(
                ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, base, limites,
                leitura(highlight = Math.pow(2.0, stops.toDouble() - 0.5)))
            assertTrue("com $stops stops a duração foi " + r.frameDurationNs,
                r.frameDurationNs >= limites.minFrameDurationNs)
            assertTrue("e nunca menor que o tempo", r.frameDurationNs >= r.exposureNs)
        }
    }

    /** Sem leitura válida, os modos automáticos deixam tudo como está. */
    @Test
    fun withoutAReadingAutomaticModesLeaveEverythingAlone() {
        val nada = Meter.Reading(0, 0.0, 0.0, 0.0, 0.0)
        val r = ExposureProgram.apply(
            ExposureProgram.Mode.P, ExposureProgram.Metering.HIGHLIGHTS, base, limites, nada)
        assertEquals(base.exposureNs, r.exposureNs)
        assertEquals(base.iso, r.iso)
        assertFalse(r.changed)
    }
}
