package io.github.bmcsilva.latente.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import io.github.bmcsilva.latente.model.Exposure
import io.github.bmcsilva.latente.model.WhiteBalance

/**
 * O que se conseguiu impor e o que ficou de fora.
 *
 * Uma chave que não está em `getAvailableCaptureRequestKeys()` não vai ser honrada. Em vez de a
 * definir às cegas, registamos que foi ignorada — o utilizador tem direito a saber quais das
 * promessas este telefone não cumpre.
 */
class CleanOutcome {
    val skipped = ArrayList<String>()
    val notes = ArrayList<String>()
}

/**
 * O pedido limpo: tudo o que o ISP faria, desligado.
 *
 * Aplica-se a **todos** os pedidos, repetido e de captura, senão o visor mente.
 */
object CleanRequest {

    private val LINEAR_CURVE = floatArrayOf(0f, 0f, 1f, 1f)

    /**
     * @param manualExposure a objectiva aceita o tempo e o ISO que lhe dermos?
     *
     *   Falso muda **só** a parte da exposição: pede-se exposição automática em vez de a desligar, e
     *   não se escrevem as chaves do sensor. Tudo o resto — redução de ruído, nitidez, ZSL, curva de
     *   tons, balanço — continua a ser desligado exactamente como nas outras, porque essas chaves
     *   existem e são o que separa um RAW cru de um RAW mastigado. É a diferença entre «a câmara
     *   escolheu a exposição» e «a câmara processou a imagem»: a primeira aceita-se e regista-se, a
     *   segunda não se aceita nunca.
     */
    fun apply(
        b: CaptureRequest.Builder,
        ch: CameraCharacteristics,
        e: Exposure,
        manualExposure: Boolean = true,
        manualFocus: Boolean = true,
    ): CleanOutcome {
        val out = CleanOutcome()
        val keys: Set<CaptureRequest.Key<*>> = try {
            HashSet(ch.availableCaptureRequestKeys)
        } catch (t: Throwable) {
            emptySet()
        }

        // ---- 3A fora do caminho ----------------------------------------------------------

        val controlModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES) ?: IntArray(0)
        if (manualExposure && controlModes.contains(CameraMetadata.CONTROL_MODE_OFF)) {
            set(b, keys, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF, out)
            out.notes.add("CONTROL_MODE = OFF")
        } else {
            set(b, keys, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO, out)
            out.notes.add(if (manualExposure) {
                "CONTROL_MODE = AUTO (OFF não disponível); 3A desligado chave a chave"
            } else {
                "CONTROL_MODE = AUTO: esta objectiva não aceita exposição manual"
            })
        }

        if (manualExposure) {
            set(b, keys, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF, out)
        } else {
            // Ligado de propósito, e não por omissão. Desligar o AE numa objectiva onde não podemos
            // escrever o tempo deixava-a sem exposição de todo — nem nossa nem dela.
            set(b, keys, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON, out)
            out.notes.add("CONTROL_AE_MODE = ON: a exposição desta objectiva é escolhida pela câmara")
        }
        set(b, keys, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF, out)
        if (manualFocus) {
            set(b, keys, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF, out)
        } else {
            // Ligado de propósito, pela mesma razão do AE: numa objectiva que não aceita
            // `LENS_FOCUS_DISTANCE`, desligar o AF deixa-a parada onde ficou da última vez — e o foco
            // simplesmente não funciona. Vale para a que tem motor; a de foco fixo não tem para onde ir
            // e o modo de AF é indiferente.
            val modos = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val escolhido = if (modos.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            } else {
                CameraMetadata.CONTROL_AF_MODE_AUTO
            }
            set(b, keys, CaptureRequest.CONTROL_AF_MODE, escolhido, out)
            out.notes.add("CONTROL_AF_MODE ligado: esta objectiva não aceita a distância de foco")
        }
        set(b, keys, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF, out)
        set(b, keys, CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED, out)
        set(b, keys, CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF, out)

        // ---- um disparo = um frame do sensor ---------------------------------------------

        set(b, keys, CaptureRequest.CONTROL_ENABLE_ZSL, false, out)
        set(b, keys, CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100, out)

        // ---- estabilização ---------------------------------------------------------------
        // A de vídeo deforma a imagem e fica sempre desligada. A óptica move elementos de lente
        // e não adultera píxeis: é escolha do utilizador.

        set(b, keys, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF, out)

        val oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        if (oisModes.contains(e.oisMode)) {
            set(b, keys, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, e.oisMode, out)
        } else if (oisModes.isNotEmpty()) {
            set(b, keys, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisModes[0], out)
            out.notes.add("modo OIS pedido não disponível; usado " + oisModes[0])
        }

        // ---- blocos de pós-processamento -------------------------------------------------

        setIfModeAvailable(b, keys, CaptureRequest.NOISE_REDUCTION_MODE,
            CameraMetadata.NOISE_REDUCTION_MODE_OFF,
            ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), out,
            "redução de ruído")

        setIfModeAvailable(b, keys, CaptureRequest.EDGE_MODE,
            CameraMetadata.EDGE_MODE_OFF,
            ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), out,
            "edge")

        setIfModeAvailable(b, keys, CaptureRequest.HOT_PIXEL_MODE,
            CameraMetadata.HOT_PIXEL_MODE_OFF,
            ch.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES), out,
            "hot pixel")

        setIfModeAvailable(b, keys, CaptureRequest.SHADING_MODE,
            CameraMetadata.SHADING_MODE_OFF,
            ch.get(CameraCharacteristics.SHADING_AVAILABLE_MODES), out,
            "shading")

        setIfModeAvailable(b, keys, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF,
            ch.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES), out,
            "aberração cromática")

        setIfModeAvailable(b, keys, CaptureRequest.DISTORTION_CORRECTION_MODE,
            CameraMetadata.DISTORTION_CORRECTION_MODE_OFF,
            ch.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES), out,
            "correcção de distorção")

        applyLinearTonemap(b, ch, keys, out)
        applyWhiteBalance(b, ch, keys, e, out)

        // ---- sem recorte digital ---------------------------------------------------------

        ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
            set(b, keys, CaptureRequest.SCALER_CROP_REGION, it, out)
        }
        set(b, keys, CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f, out)

        // Pedimos o mapa de shading para o DNG. No dispositivo de referência a chave de resultado
        // não existe, portanto não virá — mas pede-se, e diz-se se não vier.
        set(b, keys, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
            CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON, out)

        // ---- exposição manual ------------------------------------------------------------

        if (manualExposure) {
            set(b, keys, CaptureRequest.SENSOR_EXPOSURE_TIME, e.exposureNs, out)
            set(b, keys, CaptureRequest.SENSOR_SENSITIVITY, e.iso, out)
            set(b, keys, CaptureRequest.SENSOR_FRAME_DURATION, e.frameDurationNs, out)
        } else {
            out.notes.add("tempo, ISO e duração de frame não enviados: o corpo não os aceita aqui")
        }
        if (manualFocus) set(b, keys, CaptureRequest.LENS_FOCUS_DISTANCE, e.focusDiopters, out)
        e.aperture?.let { set(b, keys, CaptureRequest.LENS_APERTURE, it, out) }

        return out
    }

    /**
     * Tonemap linear.
     *
     * A especificação assumia `GAMMA_VALUE` com `TONEMAP_GAMMA = 1.0`, mas no dispositivo de
     * referência esse modo não existe e a chave do gama também não. Sobra `CONTRAST_CURVE` com uma
     * curva identidade, que dá o mesmo resultado.
     *
     * O tonemap não afecta o RAW — só as saídas processadas. Define-se por higiene e porque em
     * alguns HAL influencia metadados.
     */
    private fun applyLinearTonemap(
        b: CaptureRequest.Builder,
        ch: CameraCharacteristics,
        keys: Set<CaptureRequest.Key<*>>,
        out: CleanOutcome,
    ) {
        val modes = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)

        if (modes.contains(CameraMetadata.TONEMAP_MODE_GAMMA_VALUE) &&
            keys.contains(CaptureRequest.TONEMAP_GAMMA)
        ) {
            set(b, keys, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE, out)
            set(b, keys, CaptureRequest.TONEMAP_GAMMA, 1.0f, out)
            out.notes.add("tonemap linear por GAMMA_VALUE")
            return
        }

        if (modes.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) &&
            keys.contains(CaptureRequest.TONEMAP_CURVE)
        ) {
            set(b, keys, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE, out)
            b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(LINEAR_CURVE, LINEAR_CURVE, LINEAR_CURVE))
            out.notes.add("tonemap linear por CONTRAST_CURVE (GAMMA_VALUE indisponível)")
            return
        }

        out.notes.add("AVISO: não foi possível impor tonemap linear")
    }

    /**
     * O balanço de brancos escolhido, por via dos ganhos de cor.
     *
     * Não toca no RAW — com o AWB desligado os ganhos aplicam-se às saídas processadas. O que se
     * ganha é o `SENSOR_NEUTRAL_COLOR_POINT`, de onde o `DngCreator` deriva o `AsShotNeutral`.
     * Sem isto o DNG sai com `AsShotNeutral = [1,1,1]` e qualquer revelador o mostra verde.
     */
    private fun applyWhiteBalance(
        b: CaptureRequest.Builder,
        ch: CameraCharacteristics,
        keys: Set<CaptureRequest.Key<*>>,
        e: Exposure,
        out: CleanOutcome,
    ) {
        if (!keys.contains(CaptureRequest.COLOR_CORRECTION_GAINS)) {
            out.skipped.add(CaptureRequest.COLOR_CORRECTION_GAINS.name)
            out.notes.add("AVISO: sem COLOR_CORRECTION_GAINS o DNG sai sem balanço de brancos")
            return
        }
        val gains = WhiteBalance.gains(ch, e.kelvin, e.tint)
        if (gains == null) {
            out.notes.add("AVISO: matrizes de cor ausentes; não se conseguiu derivar o balanço")
            return
        }
        set(b, keys, CaptureRequest.COLOR_CORRECTION_MODE,
            CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX, out)
        b.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
        out.notes.add(String.format(java.util.Locale.US,
            "balanço %d K, tinta %+.2f, por ganhos R %.3f G %.3f B %.3f", e.kelvin, e.tint,
            gains.red, gains.greenEven, gains.blue))
    }

    private fun <T> set(
        b: CaptureRequest.Builder,
        keys: Set<CaptureRequest.Key<*>>,
        key: CaptureRequest.Key<T>,
        value: T,
        out: CleanOutcome,
    ) {
        if (keys.isEmpty() || keys.contains(key)) {
            b.set(key, value)
        } else {
            out.skipped.add(key.name)
        }
    }

    /** Não basta a chave existir: o modo pedido tem de estar na lista de modos disponíveis. */
    private fun setIfModeAvailable(
        b: CaptureRequest.Builder,
        keys: Set<CaptureRequest.Key<*>>,
        key: CaptureRequest.Key<Int>,
        mode: Int,
        available: IntArray?,
        out: CleanOutcome,
        label: String,
    ) {
        if (available == null || !available.contains(mode)) {
            out.skipped.add(key.name + " (OFF não disponível)")
            out.notes.add("AVISO: não se consegue desligar $label")
            return
        }
        set(b, keys, key, mode, out)
    }
}
