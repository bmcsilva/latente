package io.github.bmcsilva.latente.diag

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.RggbChannelVector
import android.os.SystemClock
import android.util.Size
import io.github.bmcsilva.latente.camera.CameraSession
import io.github.bmcsilva.latente.camera.HalClamp
import io.github.bmcsilva.latente.camera.Planner
import io.github.bmcsilva.latente.camera.RawReader
import io.github.bmcsilva.latente.camera.RawStats
import io.github.bmcsilva.latente.export.Node
import io.github.bmcsilva.latente.model.Exposure
import io.github.bmcsilva.latente.model.LensProfile

/**
 * As nove perguntas que a F0 deixou em aberto.
 *
 * **Cada experiência tem a sua própria sessão.** Na primeira corrida usava-se uma sessão partilhada
 * e descobriu-se, da pior maneira, que pedir uma exposição acima do tecto declarado faz o HAL
 * fechar o dispositivo: a experiência 1 matava a câmara e as sete seguintes morriam com
 * `CameraDevice was already closed`. Isolar custa cerca de um segundo por experiência e vale cada
 * milissegundo.
 */
class Experiments(private val ctx: Context, private val profile: LensProfile) {

    fun runAll(progress: (String) -> Unit): Node {
        val root = Node("Latente · experiências da F1")
        root.put("objectiva", profile.label)
        root.put("id da câmara", profile.cameraId)
        root.put("tecto de exposição declarado ms", profile.exposureMaxNs / 1_000_000.0)
        root.put("bits úteis", profile.usefulBits)
        root.put("mosaico declarado", profile.cfaName)
        root.put("nível de branco", profile.whiteLevel)
        root.put("duração mínima de frame do stream RAW ms", profile.minFrameDurationNs / 1e6)

        progress("1/9 · tecto de exposição")
        root.children.add(exposureCeiling())

        progress("2/9 · shading OFF vs FAST")
        root.children.add(shadingEffect())

        progress("3/9 · RAW + preview")
        root.children.add(rawPlusPreview())

        progress("4/9 · nível de preto")
        root.children.add(blackLevel())

        progress("5/9 · linearidade")
        root.children.add(linearity())

        progress("6/9 · mosaico de cor")
        root.children.add(cfaCheck())

        progress("7/9 · ZSL desligado")
        root.children.add(zslOff())

        progress("8/9 · ponto neutro vs ganhos")
        root.children.add(neutralPoint())

        progress("9/9 · perfil de vinhetagem")
        root.children.add(shadingProfile())

        return root
    }

    /** Abre, configura, corre e fecha. Uma sessão por experiência, sem excepções. */
    private fun withSession(n: Node, body: (CameraSession) -> Unit) {
        var session: CameraSession? = null
        try {
            session = CameraSession(ctx, profile.openId, profile.physicalId)
            val openError = session.open()
            if (openError != null) {
                n.put("ERRO", "abertura falhou: $openError")
                return
            }
            val configError = session.configure(profile.rawSize)
            if (configError != null) {
                n.put("ERRO", "configuração falhou: $configError")
                return
            }
            body(session)
            session.deathReason?.let { n.put("aviso", "a câmara morreu durante a experiência: $it") }
        } catch (t: Throwable) {
            n.put("EXCEPÇÃO", t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            session?.close()
        }
    }

    /**
     * Exposição de sondagem, com a duração de frame sempre acima do mínimo do stream.
     *
     * Punha-se a duração igual à exposição e isso descartava as capturas curtas (ver `HalClamp`).
     */
    private fun probeExposure(iso: Int, exposureNs: Long = profile.exposureMinNs): Exposure = Exposure(
        exposureNs = exposureNs,
        iso = iso,
        frameDurationNs = HalClamp.frameDuration(exposureNs, profile.minFrameDurationNs),
        focusDiopters = 0f,
        aperture = profile.apertures.firstOrNull(),
        oisMode = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
    )

    private fun fmtMs(ns: Long): String =
        String.format(java.util.Locale.US, "%.1f ms", ns / 1e6)

    private class ProbeOutcome(
        val applied: Long?,
        val status: String,
        val died: Boolean,
        val timedOut: Boolean,
    )

    /** Uma sonda de exposição, em sessão própria e descartável. */
    private fun probeCeiling(t: Long): ProbeOutcome {
        val e = probeExposure(profile.isoMin.coerceAtLeast(50), t)
        val timeout = t / 1_000_000L * 3L + 8000L
        var out = ProbeOutcome(null, "não corrido", false, false)
        val sub = Node("sonda")
        withSession(sub) { session ->
            val settle = session.settle(Planner.raw(e), frames = 1, timeoutMs = timeout)
            val applied = settle.last?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            session.stopRepeating()
            out = when {
                applied != null -> {
                    val note = if (applied != t) " (pedido " + fmtMs(t) + " — cortado)" else ""
                    ProbeOutcome(applied, "aplicado " + fmtMs(applied) + note, false, false)
                }
                session.deathReason != null -> ProbeOutcome(
                    null, "RECUSADO — o HAL fechou o dispositivo: " + session.deathReason, true, false)
                settle.error != null -> ProbeOutcome(
                    null, "SEM RESPOSTA em $timeout ms — " + settle.error, false, true)
                else -> ProbeOutcome(null, "FALHOU sem razão declarada", false, false)
            }
        }
        sub.fields["ERRO"]?.let { out = ProbeOutcome(null, "sessão: $it", false, false) }
        return out
    }

    // =========================================================================================
    // 1. Tecto de exposição
    // =========================================================================================

    private fun exposureCeiling(): Node {
        val n = Node("1. Tecto de exposição")
        n.put("pergunta", "o HAL aceita mais do que os " +
                (profile.exposureMaxNs / 1_000_000) + " ms que SENSOR_INFO_EXPOSURE_TIME_RANGE declara?")
        n.put("método", "pedir sem o nosso corte, uma sessão nova por valor, um frame só, e ler " +
                "SENSOR_EXPOSURE_TIME do resultado")
        n.put("nota", "um frame por sonda e espera proporcional ao tempo pedido. Na segunda corrida " +
                "os 2 s falharam por causa do meu limite de espera, não do HAL — o erro estava aqui")
        n.put("aviso de duração", "a escada vai até 30 s; a experiência pode levar minutos")

        val wanted = longArrayOf(
            50_000_000L, 100_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L,
            2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 15_000_000_000L, 30_000_000_000L)

        var maxHonoured = 0L
        var firstFailure = 0L
        var consecutiveFailures = 0
        var killedTheDevice = false
        var timedOut = false

        for (t in wanted) {
            val label = "pedido " + (t / 1_000_000) + " ms"
            if (consecutiveFailures >= 2) {
                n.put(label, "não tentado — duas falhas seguidas, o limite já ficou provado")
                continue
            }
            val r = probeCeiling(t)
            n.put(label, r.status)
            if (r.applied != null) {
                if (r.applied > maxHonoured) maxHonoured = r.applied
                consecutiveFailures = 0
            } else {
                if (firstFailure == 0L) firstFailure = t
                consecutiveFailures++
                if (r.died) killedTheDevice = true
                if (r.timedOut) timedOut = true
            }
        }

        // Afinação: o limite verdadeiro está entre o maior honrado e a primeira falha.
        if (maxHonoured > 0 && firstFailure > maxHonoured) {
            val gap = firstFailure - maxHonoured
            if (gap > 100_000_000L) {
                n.put("afinação", "o limite está entre " + fmtMs(maxHonoured) + " e " +
                        fmtMs(firstFailure) + "; a afinar em três passos")
                for (k in 1..3) {
                    val t = maxHonoured + gap * k / 4
                    val r = probeCeiling(t)
                    n.put("afinação " + fmtMs(t), r.status)
                    if (r.applied != null && r.applied > maxHonoured) maxHonoured = r.applied
                }
            }
        }

        n.put("declarado como máximo ms", profile.exposureMaxNs / 1e6)
        n.put("duração mínima de frame do stream ms", profile.minFrameDurationNs / 1e6)
        n.put("máximo realmente honrado ms", maxHonoured / 1e6)
        n.put("pedir acima do declarado fecha o dispositivo", killedTheDevice)
        n.put("alguma sonda expirou por tempo de espera", timedOut)

        val factor = if (profile.exposureMaxNs > 0) maxHonoured.toDouble() / profile.exposureMaxNs else 0.0
        n.put("factor entre o real e o declarado", factor)
        n.put("VEREDICTO", if (maxHonoured > profile.exposureMaxNs) {
            "SENSOR_INFO_EXPOSURE_TIME_RANGE MENTE: o HAL honra até " +
                    String.format(java.util.Locale.US, "%.1f", maxHonoured / 1e6) + " ms, ou seja " +
                    String.format(java.util.Locale.US, "%.0f", factor) +
                    "× o declarado. A exposição longa existe — mas o limite tem de ser descoberto " +
                    "por sondagem, não lido"
        } else {
            "o tecto declarado é real: sem exposições longas por Camera2"
        })
        return n
    }

    // =========================================================================================
    // 2. SHADING_MODE tem efeito no RAW?
    // =========================================================================================

    private fun shadingEffect(): Node {
        val n = Node("2. Shading: OFF tem efeito no RAW?")
        n.put("pergunta", "SENSOR_INFO_LENS_SHADING_APPLIED diz " + profile.shadingApplied +
                "; a chave SHADING_MODE muda algo?")
        n.put("método", "campo uniforme; razão entre a média dos cantos e a do centro")
        n.put("instrução", "apontar a uma superfície lisa e uniformemente iluminada, a preencher o quadro")

        val ratios = HashMap<String, Double>()

        for (mode in intArrayOf(CameraMetadata.SHADING_MODE_OFF, CameraMetadata.SHADING_MODE_FAST)) {
            val name = if (mode == CameraMetadata.SHADING_MODE_OFF) "OFF" else "FAST"
            withSession(n) { session ->
                val e = profile.defaultExposure()
                session.settle(e, frames = 3) { b -> b.set(CaptureRequest.SHADING_MODE, mode) }
                val (frame, error) = session.captureOne(e) { b ->
                    b.set(CaptureRequest.SHADING_MODE, mode)
                }
                if (frame == null) {
                    n.put("SHADING_MODE $name", "falhou: $error")
                    return@withSession
                }
                try {
                    val ratio = RawReader.cornerToCentreRatio(frame.image)
                    ratios[name] = ratio
                    n.put("SHADING_MODE $name · razão cantos/centro", ratio)
                    n.put("SHADING_MODE $name · confirmado no resultado",
                        frame.result.get(CaptureResult.SHADING_MODE))
                    n.put("SHADING_MODE $name · frame emparelhado por timestamp",
                        frame.matchedByTimestamp)
                } finally {
                    frame.close()
                }
            }
        }

        val off = ratios["OFF"]
        val fast = ratios["FAST"]
        if (off != null && fast != null) {
            val delta = Math.abs(off - fast)
            n.put("diferença", delta)
            n.put("VEREDICTO", if (delta < 0.02) {
                "a chave é decorativa: o RAW vem igual nos dois casos, logo o shading do HAL manda"
            } else {
                "a chave TEM efeito no RAW — diferença de " +
                        String.format(java.util.Locale.US, "%.3f", delta)
            })
        }
        return n
    }

    // =========================================================================================
    // 3. RAW + preview na mesma sessão
    // =========================================================================================

    private fun rawPlusPreview(): Node {
        val n = Node("3. RAW + preview na mesma sessão")
        n.put("pergunta", "com REQUEST_MAX_NUM_OUTPUT_RAW = 1, a combinação RAW + YUV é aceite?")
        n.put("porque importa", "sem ela não há visor WYSIWYG (F3)")

        var session: CameraSession? = null
        try {
            session = CameraSession(ctx, profile.openId, profile.physicalId)
            val openError = session.open()
            if (openError != null) {
                n.put("ERRO", "abertura falhou: $openError")
                return n
            }
            val preview = Size(1920, 1080)
            n.put("tamanho de preview tentado", preview.width.toString() + "x" + preview.height)
            val configError = session.configure(profile.rawSize, preview)
            if (configError == null) {
                // Não basta configurar: tem de entregar frames.
                val settle = session.settle(profile.defaultExposure(), frames = 3)
                n.put("frames entregues com as duas saídas", settle.frames)
                n.put("VEREDICTO", if (settle.error == null) {
                    "ACEITE e a funcionar — RAW e preview coexistem; o visor WYSIWYG é possível"
                } else {
                    "configurada mas com problemas: " + settle.error
                })
                session.stopRepeating()
            } else {
                n.put("VEREDICTO", "RECUSADA — $configError")
            }
        } catch (t: Throwable) {
            n.put("EXCEPÇÃO", t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            session?.close()
        }
        return n
    }

    // =========================================================================================
    // 4. Nível de preto real
    // =========================================================================================

    private fun blackLevel(): Node {
        val n = Node("4. Nível de preto com a lente tapada")
        n.put("pergunta", "o padrão declarado é " + profile.blackLevelPattern.toList() +
                "; o sinal real com luz zero confirma?")
        n.put("instrução", "tapar completamente a lente antes de correr")

        withSession(n) { session ->
            val e = probeExposure(profile.isoMin.coerceAtLeast(50),
                profile.exposureMinNs.coerceAtLeast(1_000_000L))
            session.settle(e, frames = 3)
            val (frame, error) = session.captureOne(e)
            session.stopRepeating()

            if (frame == null) {
                n.put("ERRO", error)
                return@withSession
            }
            try {
                val stats = RawReader.stats(frame.image, null, profile.whiteLevel)
                n.put("média", stats.mean)
                n.put("mínimo", stats.min)
                n.put("máximo", stats.max)
                n.put("médias por posição do mosaico", stats.cfaMeans.toList())
                n.put("nível de preto dinâmico do resultado",
                    frame.result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.toList())
                n.put("frame emparelhado por timestamp", frame.matchedByTimestamp)

                // Num frame verdadeiramente escuro as quatro posições do mosaico são iguais.
                // Se houver estrutura, entrou luz e a medição não vale — foi o que aconteceu
                // numa das corridas, que deu um "pedestal" de 4,1 que era imagem.
                val spread = stats.cfaSpread()
                n.put("espalhamento entre posições do mosaico", spread)
                if (spread > 0.25 || stats.max > 64) {
                    n.put("VEREDICTO", "MEDIÇÃO INVÁLIDA — entrou luz. As posições do mosaico têm " +
                            "estrutura (espalhamento " + String.format(java.util.Locale.US, "%.2f", spread) +
                            ", máximo " + stats.max + "), logo isto é imagem e não pedestal. " +
                            "Repetir com a lente completamente tapada")
                } else {
                    n.put("VEREDICTO", if (stats.mean < 4.0) {
                        "pedestal já subtraído: o preto está no zero. Não subtrair na revelação"
                    } else {
                        "há pedestal de cerca de " +
                                String.format(java.util.Locale.US, "%.1f", stats.mean) +
                                " — subtrair na revelação"
                    })
                }
            } finally {
                frame.close()
            }
        }
        return n
    }

    // =========================================================================================
    // 5. Linearidade
    // =========================================================================================

    private fun linearity(): Node {
        val n = Node("5. Linearidade")
        n.put("pergunta", "o sinal cresce em proporção com o tempo de exposição?")
        n.put("método", "uma sessão por medição; média de um quadrado central de 400x400")
        n.put("instrução", "telefone imóvel, cena de luz constante, sem sombras a mexer")
        n.put("nota", "o tecto é " + profile.whiteLevel + ", não 65535 — o corte aparece muito antes")

        val iso = profile.isoMin.coerceAtLeast(50)
        val w = profile.rawSize.width
        val h = profile.rawSize.height
        val patch = Rect(w / 2 - 200, h / 2 - 200, w / 2 + 200, h / 2 + 200)

        // De 1 ms a 512 ms: com luz de interior chega à saturação, que é onde a linearidade
        // deixa de valer e isso tem de aparecer na tabela.
        val rows = ArrayList<String>()
        var previous: Double? = null
        var previousP20: Double? = null
        var previousTime = 0L
        var t = 1_000_000L

        while (t <= 512_000_000L) {
            val exposure = t
            val e = probeExposure(iso, exposure)
            var st: RawStats? = null
            var failure: String? = null

            // Sessão nova por medição: mudar a exposição de um pedido repetido a meio da sessão
            // fazia o HAL descartar as capturas seguintes.
            val sub = Node("medição")
            withSession(sub) { session ->
                val settle = session.settle(e, frames = 2, timeoutMs = exposure / 1_000_000L * 4 + 8000)
                if (!settle.settledAtRequested) {
                    failure = settle.error ?: "não assentou nos valores pedidos"
                    return@withSession
                }
                val (frame, error) = session.captureOne(e, timeoutMs = exposure / 1_000_000L * 4 + 8000)
                session.stopRepeating()
                if (frame == null) {
                    failure = error
                    return@withSession
                }
                try {
                    st = RawReader.stats(frame.image, patch, profile.whiteLevel)
                    if (!frame.matchedByTimestamp) failure = "frame não emparelhado"
                } finally {
                    frame.close()
                }
            }
            sub.fields["ERRO"]?.let { failure = "sessão: $it" }

            val stats = st
            if (stats == null) {
                rows.add("${exposure / 1000} µs → falhou: $failure")
            } else {
                val m = stats.mean
                val p20 = stats.percentile(0.20).toDouble()
                val clip = stats.clippedFraction(profile.whiteLevel) * 100.0
                val ratioMean = if (previous != null && previous!! > 0.5) m / previous!! else Double.NaN
                val ratioP20 = if (previousP20 != null && previousP20!! > 0.5) p20 / previousP20!! else Double.NaN
                rows.add(String.format(java.util.Locale.US,
                    "%d µs → média %.1f (%.1f%%) · p20 %.0f · p99 %d · cortado %.2f%%%s%s",
                    exposure / 1000, m, m / profile.whiteLevel * 100,
                    p20, stats.percentile(0.99), clip,
                    if (ratioMean.isNaN()) "" else String.format(java.util.Locale.US, " · média ×%.2f", ratioMean),
                    if (ratioP20.isNaN()) "" else String.format(java.util.Locale.US, " · p20 ×%.2f", ratioP20)))
                previous = m
                previousP20 = p20
                previousTime = exposure
            }
            t *= 2
        }

        n.put("medições", rows)
        n.put("como ler", "ao dobrar o tempo, os valores devem dobrar. **O p20 é a prova**: são os " +
                "píxeis escuros, que não saturam. Se o p20 continuar a dobrar enquanto a média " +
                "abranda, o desvio é das altas luzes da cena a cortar — e o sensor é linear. Se o " +
                "p20 também abrandar longe do tecto, aí sim há um joelho no caminho do RAW, e a " +
                "premissa do projeto cai")
        return n
    }

    // =========================================================================================
    // 6. Mosaico de cor
    // =========================================================================================

    private fun cfaCheck(): Node {
        val n = Node("6. Mosaico de cor")
        n.put("declarado", profile.cfaName)
        n.put("método", "médias por posição do mosaico numa cena de cor forte")
        n.put("instrução", "apontar a algo saturado, de preferência vermelho ou azul")

        withSession(n) { session ->
            val e = profile.defaultExposure()
            session.settle(e, frames = 3)
            val (frame, error) = session.captureOne(e)
            session.stopRepeating()

            if (frame == null) {
                n.put("ERRO", error)
                return@withSession
            }
            try {
                val stats = RawReader.stats(frame.image, null, profile.whiteLevel)
                n.put("posição (0,0)", stats.cfaMeans[0])
                n.put("posição (1,0)", stats.cfaMeans[1])
                n.put("posição (0,1)", stats.cfaMeans[2])
                n.put("posição (1,1)", stats.cfaMeans[3])
                n.put("frame emparelhado por timestamp", frame.matchedByTimestamp)
            } finally {
                frame.close()
            }
        }
        n.put("como ler", "em " + profile.cfaName + " as duas posições verdes devem ter valores " +
                "próximos entre si e o canal da cor dominante da cena deve destacar-se")
        return n
    }

    // =========================================================================================
    // 7. ZSL desligado
    // =========================================================================================

    private fun zslOff(): Node {
        val n = Node("7. ZSL desligado")
        n.put("pergunta", "o frame gravado é o do disparo, ou um anterior guardado em cache?")
        n.put("método", "comparar SENSOR_TIMESTAMP com o relógio no instante do disparo")
        n.put("nota", "a fonte de timestamp é REALTIME, logo comparável com elapsedRealtimeNanos()")

        withSession(n) { session ->
            val e = profile.defaultExposure()
            session.settle(e, frames = 3)

            val before = SystemClock.elapsedRealtimeNanos()
            val (frame, error) = session.captureOne(e)
            session.stopRepeating()

            if (frame == null) {
                n.put("ERRO", error)
                return@withSession
            }
            try {
                val ts = frame.result.get(CaptureResult.SENSOR_TIMESTAMP)
                if (ts == null) {
                    n.put("ERRO", "sem SENSOR_TIMESTAMP")
                    return@withSession
                }
                val deltaMs = (ts - before) / 1e6
                n.put("frame emparelhado por timestamp", frame.matchedByTimestamp)
                if (!frame.matchedByTimestamp) {
                    n.put("VEREDICTO", "INCONCLUSIVO — a imagem não foi emparelhada com o " +
                            "resultado, logo o timestamp não prova nada sobre ZSL")
                    return@withSession
                }
                n.put("relógio antes do disparo ns", before)
                n.put("timestamp do sensor ns", ts)
                n.put("atraso do frame ms", deltaMs)
                n.put("VEREDICTO", if (deltaMs < -20.0) {
                    "SUSPEITO: o frame é anterior ao pedido em " +
                            String.format(java.util.Locale.US, "%.1f", -deltaMs) + " ms — parece ZSL"
                } else {
                    "o frame é posterior ao pedido: ZSL respeitado"
                })
            } finally {
                frame.close()
            }
        }
        return n
    }

    // =========================================================================================
    // 9. Perfil de vinhetagem, lido do HAL
    // =========================================================================================

    private fun shadingProfile(): Node {
        val n = Node("9. Perfil de vinhetagem do HAL")
        n.put("pergunta", "qual é a vinhetagem real desta objectiva?")
        n.put("método", "ler STATISTICS_LENS_SHADING_CORRECTION_MAP do resultado — é o mapa de " +
                "correcção que o fabricante mediu. Não precisa de fotografar nada uniforme")
        n.put("nota", "a F0 dava esta chave como ausente da lista declarada, mas ela vem no " +
                "resultado. Verificar, não confiar — desta vez a favor")

        withSession(n) { session ->
            val e = profile.defaultExposure()
            val settle = session.settle(e, frames = 3)
            session.stopRepeating()

            val map = settle.last?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            if (map == null) {
                n.put("ERRO", "o mapa não veio no resultado" +
                        (settle.error?.let { " ($it)" } ?: ""))
                return@withSession
            }

            val cols = map.columnCount
            val rows = map.rowCount
            n.put("malha", cols.toString() + "x" + rows)
            n.put("canais", "R · G(par) · G(ímpar) · B")

            var maxGain = 1.0f
            val nomes = arrayOf("R", "G par", "G ímpar", "B")
            for (c in 0 until 4) {
                val centro = map.getGainFactor(c, cols / 2, rows / 2)
                val cantos = floatArrayOf(
                    map.getGainFactor(c, 0, 0),
                    map.getGainFactor(c, cols - 1, 0),
                    map.getGainFactor(c, 0, rows - 1),
                    map.getGainFactor(c, cols - 1, rows - 1))
                var maxCanto = 0f
                for (v in cantos) if (v > maxCanto) maxCanto = v
                if (maxCanto > maxGain) maxGain = maxCanto
                n.put("canal " + nomes[c], String.format(java.util.Locale.US,
                    "centro %.4f · cantos %.3f %.3f %.3f %.3f · queda nos cantos %.0f%%",
                    centro, cantos[0], cantos[1], cantos[2], cantos[3],
                    if (maxCanto > 0f) (1.0 - centro / maxCanto) * 100 else 0.0))
            }

            n.put("ganho máximo do mapa", maxGain)
            n.put("VEREDICTO", if (maxGain > 1.05f) {
                "a objectiva perde " + String.format(java.util.Locale.US, "%.0f%%", 
                    (1.0 - 1.0 / maxGain) * 100) + " de luz nos cantos, e é isto que o HAL corrige " +
                        "antes de entregar o RAW. Perfil medido sem fotografar nada"
            } else {
                "o mapa é praticamente identidade: o HAL não declara vinhetagem por corrigir. " +
                        "O perfil verdadeiro não é observável por esta via"
            })
        }
        return n
    }

    // =========================================================================================
    // 8. Ponto neutro contra ganhos de cor
    // =========================================================================================

    private fun neutralPoint(): Node {
        val n = Node("8. Ponto neutro contra ganhos de cor")
        n.put("pergunta", "definir COLOR_CORRECTION_GAINS altera SENSOR_NEUTRAL_COLOR_POINT?")
        n.put("porque importa", "o DngCreator deriva AsShotNeutral do ponto neutro e não deixa " +
                "defini-lo. Se os ganhos o influenciarem, o balanço de brancos do utilizador pode " +
                "chegar ao DNG; se não, fica só no sidecar")

        val gains = arrayOf(
            RggbChannelVector(1.0f, 1.0f, 1.0f, 1.0f),
            RggbChannelVector(2.0f, 1.0f, 1.0f, 0.5f),
        )

        withSession(n) { session ->
            val e = profile.defaultExposure()
            for (g in gains) {
                val label = "ganhos " + String.format(java.util.Locale.US, "%.1f/%.1f/%.1f/%.1f",
                    g.red, g.greenEven, g.greenOdd, g.blue)
                val settle = session.settle(e, frames = 3) { b ->
                    b.set(CaptureRequest.COLOR_CORRECTION_MODE,
                        CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    b.set(CaptureRequest.COLOR_CORRECTION_GAINS, g)
                }
                val np = settle.last?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
                n.put(label, np?.map { it.toDouble() }
                    ?: ("sem resultado" + (settle.error?.let { " ($it)" } ?: "")))
            }
            session.stopRepeating()
        }
        n.put("como ler", "se os dois pontos neutros forem iguais, os ganhos não o influenciam e o " +
                "balanço do utilizador não chega ao DNG por esta via")
        return n
    }
}
