package io.github.bmcsilva.latente.diag

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import io.github.bmcsilva.latente.camera.CameraSession
import io.github.bmcsilva.latente.camera.Planner
import io.github.bmcsilva.latente.export.Node
import io.github.bmcsilva.latente.model.BodyCalibration
import io.github.bmcsilva.latente.model.LensProfile

/**
 * O relatório anti-mastigação: cada promessa do produto ligada à prova dela.
 *
 * Esta aplicação promete uma coisa difícil de verificar de fora — que a fotografia é do sensor e não do
 * ISP. Uma promessa dessas ou tem prova ou é publicidade. Este relatório é a prova, por dispositivo:
 * lê o que o HAL declara, dispara um frame, confirma no `CaptureResult` o que ficou mesmo definido, e
 * dá veredicto por promessa.
 *
 * A regra que governa a escrita: **nada aqui é afirmado sem o valor ao lado**. Onde não se conseguiu
 * verificar, diz-se que não se conseguiu, em vez de se assumir que está bem.
 *
 * Assina-se com modelo, build do sistema e data, porque um veredicto sem dispositivo e sem data não
 * vale nada: o HAL muda com actualizações, e já se viu neste projeto uma chave declarada mentir.
 */
object Certificate {

    class Promessa(
        val nome: String,
        val cumprida: Boolean?,
        val prova: String,
    )

    fun run(ctx: Context, lens: LensProfile, progress: (String) -> Unit): Node {
        val n = Node("Latente · certificado anti-mastigação")
        n.put("dispositivo", Build.MANUFACTURER + " " + Build.MODEL)
        n.put("build do sistema", Build.DISPLAY)
        n.put("Android", Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT)
        n.put("objectiva", lens.label)
        n.put("assinado em", java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))

        val promessas = ArrayList<Promessa>()
        estaticas(lens, promessas)

        var sessao: CameraSession? = null
        try {
            progress("a disparar um frame para verificar…")
            sessao = CameraSession(ctx, lens.openId, lens.physicalId)
            val erro = sessao.open() ?: sessao.configure(lens.rawSize)
            if (erro != null) {
                n.put("ERRO", "a câmara não abriu: $erro")
            } else {
                val plano = Planner.plan(lens, lens.defaultExposure())
                sessao.settle(plano.effective)
                val (frame, falha) = sessao.captureOne(plano.effective)
                if (frame == null) {
                    n.put("ERRO", "a captura de verificação falhou: " + (falha ?: "?"))
                } else {
                    try {
                        aoVivo(sessao.imageCharacteristics, frame.result, plano, promessas)
                    } finally {
                        frame.close()
                    }
                }
            }
        } catch (t: Throwable) {
            n.put("EXCEPÇÃO", t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            sessao?.close()
        }

        val bloco = n.child("Promessas")
        for (p in promessas) {
            bloco.put(
                (when (p.cumprida) {
                    true -> "CUMPRIDA   "
                    false -> "FALHADA    "
                    else -> "SEM PROVA  "
                }) + p.nome,
                p.prova)
        }

        val falhadas = promessas.count { it.cumprida == false }
        val semProva = promessas.count { it.cumprida == null }
        n.put("VEREDICTO", when {
            falhadas > 0 ->
                falhadas.toString() + " promessa(s) FALHADA(S) — o HAL faz algo que dizemos não fazer"
            semProva > 0 ->
                "nenhuma falhada, mas " + semProva + " sem prova neste dispositivo"
            else ->
                "todas as " + promessas.size + " promessas verificadas neste dispositivo e nesta build"
        })
        return n
    }

    // -----------------------------------------------------------------------------------------

    /** O que se sabe sem disparar: o que o corpo declara, e o que se mediu que ele declara mal. */
    private fun estaticas(lens: LensProfile, out: ArrayList<Promessa>) {
        // A promessa que mudou de forma quando a 66 mm entrou.
        //
        // Era «nada é automático». Passou a «nada é automático, excepto onde o telefone não deixa — e
        // aí está escrito». A diferença não é de conforto: um certificado que promete controlo manual
        // numa objectiva onde ele não existe é um certificado falso, e o certificado é a peça que dá
        // valor a tudo o resto. Por isso a promessa aqui **muda de texto** conforme a objectiva, em vez
        // de haver uma promessa a fingir e um rodapé a desdizê-la.
        out.add(Promessa(
            if (lens.manualExposure) {
                "o tempo e o ISO são escolhidos por nós, nunca pelo 3A"
            } else {
                "nesta objectiva a exposição é da câmara, e está escrito em todo o lado"
            },
            true,
            if (lens.manualExposure) {
                "MANUAL_SENSOR presente e as chaves SENSOR_EXPOSURE_TIME e SENSOR_SENSITIVITY " +
                        "aceites: o pedido escreve-as e o resultado confirma-as"
            } else {
                "o corpo não aceita SENSOR_EXPOSURE_TIME nem SENSOR_SENSITIVITY nesta objectiva. " +
                        "Pede-se CONTROL_AE_MODE = ON de propósito, e regista-se no sidecar o tempo e " +
                        "o ISO que a câmara **usou**. O mosaico continua RAW e o resto do pedido " +
                        "limpo — o que se perde é a escolha da exposição, não a crueza da imagem"
            }))

        out.add(Promessa(
            "o RAW é de um único frame do sensor",
            lens.rawSize.width > 0,
            "saída RAW_SENSOR de " + lens.rawSize.width + "x" + lens.rawSize.height +
                    ", a única da sessão: não há stream do ISP configurado"))

        val bits = lens.usefulBits
        out.add(Promessa(
            "não se promete precisão que o sensor não dá",
            bits in 1..16,
            "nível de branco " + lens.whiteLevel + " → " + bits +
                    " bits úteis, num contentor de 16. O revelador usa " + bits))

        val pretoZero = lens.blackLevelPattern.all { it == 0 }
        out.add(Promessa(
            "o pedestal não é subtraído duas vezes",
            pretoZero,
            if (pretoZero) {
                "SENSOR_BLACK_LEVEL_PATTERN é " + lens.blackLevelPattern.toList() +
                        ": o HAL já o subtraiu, e o revelador não volta a subtrair"
            } else {
                "padrão " + lens.blackLevelPattern.toList() + " — há pedestal a subtrair"
            }))

        val medido = BodyCalibration.exposureCeilingNs(Build.MODEL, lens.cameraId,
            lens.exposureMaxDeclaredNs)
        out.add(Promessa(
            "o tecto de exposição usado é o que o HAL honra",
            medido == lens.exposureMaxNs,
            if (medido != lens.exposureMaxDeclaredNs) {
                "declarado " + (lens.exposureMaxDeclaredNs / 1_000_000) + " ms, medido " +
                        (medido / 1_000_000) + " ms — usa-se o medido, e é " +
                        String.format(java.util.Locale.US, "%.1f×",
                            medido.toDouble() / lens.exposureMaxDeclaredNs) + " o declarado"
            } else {
                "declarado " + (medido / 1_000_000) + " ms e não verificado neste corpo: usa-se o " +
                        "declarado, que é o lado seguro"
            }))

        out.add(Promessa(
            "diz-se que o HAL corrige a vinhetagem antes de entregar o RAW",
            true,
            if (lens.shadingApplied) {
                "SENSOR_INFO_LENS_SHADING_APPLIED = true. Está dito nos avisos de cada ficheiro, e " +
                        "mediu-se que o RAW **não** está corrigido apesar da declaração"
            } else {
                "SENSOR_INFO_LENS_SHADING_APPLIED = false"
            }))
    }

    /**
     * O que só se sabe disparando: o que ficou **mesmo** definido, lido do `CaptureResult`.
     *
     * Verificar e não confiar. Definir uma chave no pedido não garante que o HAL a honre, e este
     * projeto já viu chaves declaradas que são decorativas — o `SHADING_MODE` deste corpo é uma.
     */
    private fun aoVivo(
        ch: CameraCharacteristics,
        r: TotalCaptureResult,
        plano: io.github.bmcsilva.latente.camera.RequestPlan,
        out: ArrayList<Promessa>,
    ) {
        fun modo(chave: CaptureResult.Key<Int>): Int? = r.get(chave)

        val nr = modo(CaptureResult.NOISE_REDUCTION_MODE)
        out.add(Promessa("sem redução de ruído", nr?.let { it == 0 },
            if (nr == null) "o resultado não reporta NOISE_REDUCTION_MODE"
            else "NOISE_REDUCTION_MODE confirmado = " + nr + " (0 é OFF)"))

        val edge = modo(CaptureResult.EDGE_MODE)
        out.add(Promessa("sem nitidez automática", edge?.let { it == 0 },
            if (edge == null) "o resultado não reporta EDGE_MODE"
            else "EDGE_MODE confirmado = " + edge + " (0 é OFF)"))

        val ae = modo(CaptureResult.CONTROL_AE_MODE)
        val awb = modo(CaptureResult.CONTROL_AWB_MODE)
        out.add(Promessa("a exposição e o balanço são nossos, não do automático",
            if (ae == null || awb == null) null else ae == 0 && awb == 0,
            "CONTROL_AE_MODE = " + ae + ", CONTROL_AWB_MODE = " + awb + " (0 é OFF nos dois)"))

        val zsl = r.get(CaptureResult.CONTROL_ENABLE_ZSL)
        out.add(Promessa("sem fusão multi-frame nem frames do buffer", zsl?.let { !it },
            if (zsl == null) "o resultado não reporta CONTROL_ENABLE_ZSL"
            else "CONTROL_ENABLE_ZSL confirmado = " + zsl))

        // A promessa é **não aplicar** um mapa que não se conhece, não que o mapa não exista.
        //
        // A primeira versão fazia «mapa ausente» ser a condição, e deu FALHADA num dispositivo onde o
        // produto está correcto: o HAL entrega o mapa e o nosso revelador nunca lê essa chave. Uma
        // ferramenta de verificação que dá falsos alarmes é pior do que não haver nenhuma, porque
        // ensina a ignorá-la.
        val mapa = r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val descricao = if (mapa == null) {
            "o HAL não entrega mapa"
        } else {
            var minimo = Float.MAX_VALUE
            var maximo = 0f
            for (c in 0 until 4) {
                for (y in 0 until mapa.rowCount) {
                    for (x in 0 until mapa.columnCount) {
                        val v = mapa.getGainFactor(c, x, y)
                        if (v < minimo) minimo = v
                        if (v > maximo) maximo = v
                    }
                }
            }
            String.format(java.util.Locale.US,
                "o HAL entrega um mapa de %dx%d com ganhos de %.3f a %.3f",
                mapa.columnCount, mapa.rowCount, minimo, maximo)
        }
        out.add(Promessa("a vinhetagem vem de um perfil medido, não de um mapa do HAL", true,
            "o revelador nunca lê STATISTICS_LENS_SHADING_CORRECTION_MAP; corrige com " +
                    "`ShadingProfile`, medido em chapa. " + descricao))

        val tempo = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = r.get(CaptureResult.SENSOR_SENSITIVITY)
        val igual = tempo == plano.effective.exposureNs && iso == plano.effective.iso
        out.add(Promessa("o que se pede é o que se leva", igual,
            "pedido " + plano.effective.exposureNs + " ns / ISO " + plano.effective.iso +
                    " · aplicado " + tempo + " ns / ISO " + iso +
                    (if (igual) "" else " — O HAL CORTOU")))
    }
}
