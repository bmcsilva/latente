package io.github.bmcsilva.latente.export

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import io.github.bmcsilva.latente.camera.CleanOutcome
import io.github.bmcsilva.latente.camera.RequestPlan
import io.github.bmcsilva.latente.model.LensProfile
import io.github.bmcsilva.latente.render.DevelopSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O ficheiro que acompanha o negativo.
 *
 * O DNG é imutável; o sidecar é onde vive tudo o que o DNG não consegue guardar: o que se pediu
 * contra o que se levou, as chaves que o HAL ignorou, o balanço de brancos escolhido, e o perfil
 * da objectiva.
 */
object Sidecar {

    /**
     * @param matchedByTimestamp a imagem gravada é mesmo a do pedido, casada pelo `SENSOR_TIMESTAMP`?
     *   Quando é `false`, o ficheiro leva um frame do visor — com os mesmos parâmetros manuais, mas de
     *   outro instante. Fica registado porque é exactamente o género de coisa que o sidecar existe
     *   para dizer: o resultado é utilizável, mas não é o que se pediu, e quem revelar tem de o saber.
     *   Antes disto o aviso vivia só no rodapé do visor, que desaparece ao frame seguinte.
     * @param source de onde veio o disparo. O visor não pára o pedido repetido; o disparo da F1 pára.
     * @param develop as definições de revelação em vigor no visor quando se disparou. É isto que faz o
     *   sidecar **reconstruir** a revelação em vez de só a descrever: com estes valores e o DNG, o
     *   `tools/develop.py --sidecar` reproduz a imagem que estava no ecrã.
     */
    fun build(
        baseName: String,
        profile: LensProfile,
        plan: RequestPlan,
        result: TotalCaptureResult?,
        outcome: CleanOutcome,
        deviceLabel: String,
        matchedByTimestamp: Boolean = true,
        source: String = "F1",
        develop: DevelopSettings? = null,
        /**
         * A rotação em graus que põe a imagem de pé. Vai para o sidecar porque o TIFF revelado precisa
         * dela mais tarde: sai na orientação do sensor, como o negativo, e sem esta etiqueta sairia
         * deitado.
         */
        rotationDegrees: Int = 0,
    ): Node {
        val root = Node("Latente · negativo $baseName")
        root.put("gerado em", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        root.put("dispositivo", deviceLabel)
        root.put("fase", source)
        root.put("imagem casada com o resultado pelo timestamp", matchedByTimestamp)
        root.put(SidecarKeys.ROTATION_DEGREES, rotationDegrees)
        if (!matchedByTimestamp) {
            root.put("AVISO", "o frame gravado não é o do pedido — é um frame do visor, com os " +
                    "mesmos parâmetros manuais mas de outro instante")
        }

        objectiva(root.child("Objectiva"), profile)
        pedido(root.child(if (profile.manualExposure) "Pedido vs aplicado" else "O que a câmara usou"), plan, result, profile.manualExposure, profile.manualFocus)
        resultado(root.child("Resultado do sensor"), result)
        develop?.let { revelacao(root.child("Revelação"), it) }
        limpeza(root.child("Pedido limpo"), outcome, result)
        avisos(root.child("Avisos de honestidade"), profile, result)

        return root
    }

    // -----------------------------------------------------------------------------------------

    private fun objectiva(n: Node, p: LensProfile) {
        n.put(SidecarKeys.CAMERA_ID, p.cameraId)
        // A pergunta que muda a leitura de tudo o que vem a seguir: o tempo e o ISO deste ficheiro
        // foram escolhidos por nós ou pela câmara? Sem isto, quem revelar meses depois lê os valores
        // do «pedido vs aplicado» e supõe que os pedimos.
        n.put("exposição escolhida por nós", p.manualExposure)
        n.put("foco escolhido por nós", p.manualFocus)
        p.logicalId?.let { n.put("alcançada pela câmara lógica", it) }
        n.put("distância focal mm", p.focalMm)
        n.put("equivalente 35 mm", p.equivalentFocalMm)
        n.put("abertura", p.apertures.firstOrNull())
        n.put("abertura equivalente 35 mm", p.equivalentAperture)
        n.put("factor de recorte", p.cropFactor)
        n.put("stops abaixo de full frame", p.stopsBelowFullFrame)
        n.put("tamanho RAW", p.rawSize.width.toString() + "x" + p.rawSize.height)
        n.put("mosaico de cor", p.cfaName)
        n.put("nível de branco", p.whiteLevel)
        n.put("bits úteis por pixel", p.usefulBits)
        n.put("padrão de nível de preto", p.blackLevelPattern.toList())
    }

    private fun pedido(n: Node, plan: RequestPlan, result: TotalCaptureResult?, manual: Boolean, manualFoco: Boolean) {
        val appliedTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val appliedIso = result?.get(CaptureResult.SENSOR_SENSITIVITY)
        val appliedFocus = result?.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val appliedAperture = result?.get(CaptureResult.LENS_APERTURE)

        if (manual) {
            n.put("tempo pedido ns", plan.time.requested)
            n.put("tempo enviado ns", plan.time.applied)
            n.put("tempo aplicado pelo HAL ns", appliedTime)
            n.put("tempo cortado por nós", plan.time.clamped)
            n.put("tempo cortado pelo HAL", appliedTime != null && appliedTime != plan.time.applied)

            n.put("ISO pedido", plan.iso.requested)
            n.put("ISO enviado", plan.iso.applied)
            n.put("ISO aplicado pelo HAL", appliedIso)
            n.put("ISO cortado por nós", plan.iso.clamped)
            n.put("ISO cortado pelo HAL", appliedIso != null && appliedIso != plan.iso.applied)
        } else {
            // Nada foi pedido, portanto nada se diz que foi. A primeira versão escrevia «tempo pedido»
            // e «cortado pelo HAL» com os números que a aplicação tinha em memória — e essa é a mentira
            // exacta que este ficheiro existe para não contar: dava a entender que pedimos 2,16 ms e
            // que o HAL nos cortou para 30, quando não pedimos coisa nenhuma.
            n.put("exposição pedida por nós", false)
            n.put("tempo que a câmara escolheu ns", appliedTime)
            n.put("ISO que a câmara escolheu", appliedIso)
        }

        if (manualFoco) {
            n.put("foco pedido dioptrias", plan.focus.requested)
            n.put("foco enviado dioptrias", plan.focus.applied)
            n.put("foco aplicado dioptrias", appliedFocus)
        } else {
            // Pela mesma razão do tempo e do ISO: não se diz que se pediu o que não se pediu. Aqui o
            // valor tem leitura própria — é onde o autofoco da câmara pousou.
            n.put("foco pedido por nós", false)
            n.put("foco onde a câmara pousou dioptrias", appliedFocus)
        }

        n.put("abertura pedida", plan.aperture?.requested)
        n.put("abertura enviada", plan.aperture?.applied)
        n.put("abertura aplicada", appliedAperture)

        n.put("duração de frame enviada ns", plan.effective.frameDurationNs)
        n.put("balanço de brancos escolhido K", plan.effective.kelvin)
        n.put("tinta escolhida", plan.effective.tint)
        n.put("nota balanço", "não é aplicado ao RAW. Vai por COLOR_CORRECTION_GAINS, que determina " +
                "SENSOR_NEUTRAL_COLOR_POINT e por aí o AsShotNeutral do DNG — o mosaico fica " +
                "intocado e são só os metadados a declarar a intenção")
        n.put("ponto neutro resultante", result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            ?.map { it.toDouble() })
    }

    private fun resultado(n: Node, result: TotalCaptureResult?) {
        if (result == null) {
            n.put("ERRO", "sem resultado de captura")
            return
        }

        n.put("timestamp do sensor ns", result.get(CaptureResult.SENSOR_TIMESTAMP))
        n.put("nível de preto dinâmico", result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.toList())
        n.put("nível de branco dinâmico", result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL))
        n.put("green split", result.get(CaptureResult.SENSOR_GREEN_SPLIT))
        n.put("ponto neutro", result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toDouble() })
        n.put("perfil de ruído", result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.toList()?.map { it })
        n.put("rolling shutter skew ns", result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW))
        n.put("distância focal aplicada mm", result.get(CaptureResult.LENS_FOCAL_LENGTH))
        n.put("boost de sensibilidade pós-RAW", result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST))
        n.put("mapa de shading presente",
            result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) != null)
    }

    /**
     * Como se revelou, com precisão suficiente para repetir.
     *
     * O DNG é o negativo e é imutável; isto é a receita. Sem ela, quem abrir o ficheiro daqui a um ano
     * consegue revelá-lo de alguma maneira, mas não **daquela** — e a promessa do projeto é que o que
     * se viu é o que se gravou, o que só se sustenta se a receita ficar guardada.
     */
    private fun revelacao(n: Node, d: DevelopSettings) {
        n.put(SidecarKeys.DEVELOP_EV, d.exposureEv)
        n.put(SidecarKeys.KELVIN, d.kelvin)
        n.put(SidecarKeys.SHADING_STRENGTH, d.shadingStrength)
        n.put(SidecarKeys.ROLLOFF, d.rolloff)
        n.put("espaço de saída", d.output.name)
        n.put("meia resolução", d.halfResolution)
        n.put("como repetir",
            "python3 tools/develop.py --sidecar <este ficheiro> <o dng ao lado>")
    }

    private fun limpeza(n: Node, outcome: CleanOutcome, result: TotalCaptureResult?) {
        n.put("chaves ignoradas", if (outcome.skipped.isEmpty()) "nenhuma" else outcome.skipped)
        n.put("notas", if (outcome.notes.isEmpty()) "nenhuma" else outcome.notes)

        // Verificar, não confiar: o que o resultado diz que ficou realmente definido.
        if (result != null) {
            n.put("NOISE_REDUCTION_MODE confirmado", modeName(result.get(CaptureResult.NOISE_REDUCTION_MODE)))
            n.put("EDGE_MODE confirmado", modeName(result.get(CaptureResult.EDGE_MODE)))
            n.put("SHADING_MODE confirmado", modeName(result.get(CaptureResult.SHADING_MODE)))
            n.put("COLOR_CORRECTION_ABERRATION_MODE confirmado",
                modeName(result.get(CaptureResult.COLOR_CORRECTION_ABERRATION_MODE)))
            n.put("DISTORTION_CORRECTION_MODE confirmado",
                modeName(result.get(CaptureResult.DISTORTION_CORRECTION_MODE)))
            n.put("CONTROL_MODE confirmado", modeName(result.get(CaptureResult.CONTROL_MODE)))
            n.put("CONTROL_AE_MODE confirmado", modeName(result.get(CaptureResult.CONTROL_AE_MODE)))
            n.put("CONTROL_AWB_MODE confirmado", modeName(result.get(CaptureResult.CONTROL_AWB_MODE)))
            n.put("CONTROL_AF_MODE confirmado", modeName(result.get(CaptureResult.CONTROL_AF_MODE)))
            n.put("TONEMAP_MODE confirmado", modeName(result.get(CaptureResult.TONEMAP_MODE)))
            n.put("LENS_OPTICAL_STABILIZATION_MODE confirmado",
                modeName(result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)))
        }
    }

    private fun avisos(n: Node, p: LensProfile, result: TotalCaptureResult?) {
        val list = ArrayList<String>()

        if (p.shadingApplied) {
            list.add("o HAL corrige vinhetagem antes de entregar o RAW (SENSOR_INFO_LENS_SHADING_APPLIED)")
        }
        if (p.blackLevelAlreadySubtracted) {
            list.add("nível de preto já subtraído pelo HAL; não subtrair de novo na revelação")
        }
        if (p.usefulBits in 1..11) {
            list.add("RAW de " + p.usefulBits + " bits úteis, não 16")
        }
        // Diz-se o tecto que se usa, e quando difere do declarado diz-se que difere. Repetir o
        // declarado depois de o ter medido e desmentido era o contrário do que este projeto promete.
        if (p.exposureMaxNs != p.exposureMaxDeclaredNs) {
            list.add("tecto de exposição: declarado " + (p.exposureMaxDeclaredNs / 1_000_000) +
                    " ms, medido " + (p.exposureMaxNs / 1_000_000) + " ms — usa-se o medido")
        } else if (p.exposureMaxNs in 1..999_999_999L) {
            list.add("tecto de exposição de " + (p.exposureMaxNs / 1_000_000) +
                    " ms, declarado e não verificado neste corpo")
        }
        if (p.isoMax > p.maxAnalogIso && p.maxAnalogIso > 0) {
            list.add("acima de ISO " + p.maxAnalogIso + " o ganho é digital: não compra sinal")
        }
        val nr = result?.get(CaptureResult.NOISE_REDUCTION_MODE)
        if (nr != null && nr != CameraMetadata.NOISE_REDUCTION_MODE_OFF) {
            list.add("ATENÇÃO: redução de ruído activa no resultado (" + modeName(nr) + ")")
        }
        val edge = result?.get(CaptureResult.EDGE_MODE)
        if (edge != null && edge != CameraMetadata.EDGE_MODE_OFF) {
            list.add("ATENÇÃO: nitidez activa no resultado (" + modeName(edge) + ")")
        }

        n.put("avisos", if (list.isEmpty()) "nenhum" else list)
        n.put("nota", "estes avisos existem para não se prometer mais do que o telefone entrega")
    }

    private fun modeName(v: Int?): String? = v?.toString()
}
