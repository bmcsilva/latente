package io.github.bmcsilva.latente.model

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Size
import io.github.bmcsilva.latente.camera.HalClamp
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Uma objectiva é uma câmara física. Não há zoom óptico contínuo: há módulos.
 *
 * Tudo aqui é lido do dispositivo. Não há um único número suposto — foi essa a lição da F0.
 */
class LensProfile(
    val cameraId: String,
    val facing: Int,
    val hardwareLevel: Int,
    val focalMm: Float,
    val apertures: FloatArray,
    val minFocusDiopters: Float,
    val rawSize: Size,
    val activeArray: Rect?,
    val cfa: Int,
    val whiteLevel: Int,
    val blackLevelPattern: IntArray,
    val exposureMinNs: Long,
    /**
     * O tecto **efectivo**: o que o HAL honra, que num corpo calibrado não é o que declara.
     *
     * Guardam-se os dois, pela mesma razão que o `RequestPlan` guarda o nosso corte e o do HAL — os dois
     * interessam, e confundi-los foi o que fez a linha de programa ir ao ganho digital com quatro stops
     * de tempo ainda por gastar.
     */
    val exposureMaxNs: Long,
    /** O que o `SENSOR_INFO_EXPOSURE_TIME_RANGE` diz. No dispositivo de referência, uma mentira 17,5×. */
    val exposureMaxDeclaredNs: Long,
    val maxFrameDurationNs: Long,
    /** De `getOutputMinFrameDuration(RAW_SENSOR, tamanho)`. Piso da duração de frame. */
    val minFrameDurationNs: Long,
    val isoMin: Int,
    val isoMax: Int,
    val maxAnalogIso: Int,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val shadingApplied: Boolean,
    val oisModes: IntArray,
    /**
     * A exposição desta objectiva é escolhida por nós?
     *
     * Falso quer dizer que o corpo não aceita `SENSOR_EXPOSURE_TIME` nem `SENSOR_SENSITIVITY` aqui, e
     * que quem decide o tempo e o ISO é o 3A da câmara. A objectiva continua a dar RAW verdadeiro — o
     * mosaico não é mastigado —, mas a exposição não é nossa, e isso tem de atravessar tudo: o pedido
     * não escreve as chaves, o anel não oferece tempo nem ISO, o sidecar regista o que a câmara
     * escolheu, e o certificado não promete controlo manual onde ele não existe.
     */
    val manualExposure: Boolean,
    /**
     * O foco desta objectiva é escolhido por nós?
     *
     * É uma capacidade **à parte** da exposição, e a 66 mm prova-o: tem motor de foco — declara 2
     * dioptrias de distância mínima — e mesmo assim não aceita `LENS_FOCUS_DISTANCE`. Mandar-lhe
     * `AF = OFF` deixava-a parada onde estivesse, que foi o que se viu: o foco «não funcionava».
     *
     * Não confundir com foco fixo. A 14 mm não tem motor nenhum e está na hiperfocal; a 66 mm tem
     * motor e quem manda nele é a câmara. São três estados diferentes e o ecrã diz qual é.
     */
    val manualFocus: Boolean,
    /**
     * A câmara **lógica** por onde esta objectiva se abre, ou nulo se ela se abre directamente.
     *
     * Há objectivas que só existem dentro de uma lógica e que um `openCamera` directo não alcança —
     * as físicas 5 e 6 do corpo de referência. Chega-se lá abrindo a lógica e prendendo a saída à
     * física com `OutputConfiguration.setPhysicalCameraId`. Está medido que este corpo o permite.
     */
    val logicalId: String?,
    val blocking: List<String>,
    val desirable: List<String>,
) {

    val serves: Boolean get() = blocking.isEmpty()

    /** O id que se abre para chegar a esta objectiva. */
    val openId: String get() = logicalId ?: cameraId

    /** A física a que a saída se prende, ou nulo quando a objectiva se abre directamente. */
    val physicalId: String? get() = if (logicalId != null) cameraId else null

    val cropFactor: Double
        get() {
            val diag = hypot(sensorWidthMm.toDouble(), sensorHeightMm.toDouble())
            return if (diag > 0) FULL_FRAME_DIAGONAL_MM / diag else 0.0
        }

    val equivalentFocalMm: Int get() = (focalMm * cropFactor).roundToInt()

    /** Abertura equivalente a 35 mm: mesma profundidade de campo, mesma luz recolhida. */
    val equivalentAperture: Double get() = apertures.firstOrNull()?.times(cropFactor) ?: 0.0

    /** Quantos stops de luz este sensor está abaixo de full frame, por área. */
    val stopsBelowFullFrame: Double
        get() {
            val area = sensorWidthMm.toDouble() * sensorHeightMm
            return if (area > 0) Math.log(FULL_FRAME_AREA_MM2 / area) / Math.log(2.0) else 0.0
        }

    val usefulBits: Int get() = HalClamp.usefulBits(whiteLevel)

    val blackLevelAlreadySubtracted: Boolean
        get() = blackLevelPattern.size == 4 && blackLevelPattern.all { it == 0 }

    val hasOis: Boolean
        get() = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

    val cfaName: String
        get() = when (cfa) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "NIR"
            else -> "RGB/desconhecido($cfa)"
        }

    val label: String
        get() {
            val sb = StringBuilder()
            sb.append("id ").append(cameraId)
            sb.append(" · ").append(equivalentFocalMm).append(" mm")
            apertures.firstOrNull()?.let { sb.append(String.format(java.util.Locale.US, " f/%.1f", it)) }
            if (facing == CameraMetadata.LENS_FACING_FRONT) sb.append(" · frontal")
            if (minFocusDiopters == 0f) sb.append(" · foco fixo")
            else if (!manualFocus) sb.append(" · foco da câmara")
            if (!manualExposure) sb.append(" · exposição da câmara")
            return sb.toString()
        }

    /** Exposição de partida sensata: 1/125 s no ISO base, foco a 2 m. */
    fun defaultExposure(): Exposure {
        val t = HalClamp.exposure(8_000_000L, exposureMinNs, exposureMaxNs).applied
        return Exposure(
            exposureNs = t,
            iso = isoMin.coerceAtLeast(50),
            frameDurationNs = HalClamp.frameDuration(t, minFrameDurationNs),
            focusDiopters = HalClamp.focus(0.5f, minFocusDiopters).applied,
            aperture = apertures.firstOrNull(),
            oisMode = if (hasOis) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
        )
    }

    companion object {

        const val FULL_FRAME_DIAGONAL_MM = 43.2666
        const val FULL_FRAME_AREA_MM2 = 864.0

        /**
         * Critério de aptidão da F0, corrigido: bloqueante é só o que impede captura RAW manual.
         *
         * `MANUAL_POST_PROCESSING` e o nível `FULL` dizem respeito ao tonemap e à correcção de cor
         * do ISP, que não usamos — o revelador parte do RAW. Exigi-los excluía a
         * ultra-grande-angular, que é utilizável.
         */
        fun from(
            cameraId: String,
            ch: CameraCharacteristics,
            /** A lógica por onde se abre, quando esta objectiva é uma física lá dentro. */
            logicalId: String? = null,
        ): LensProfile? {
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val level = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
            val reqKeys = try {
                ch.availableCaptureRequestKeys
            } catch (e: Throwable) {
                emptyList<CaptureRequest.Key<*>>()
            }

            // **O que bloqueia é a falta de RAW, e só isso.**
            //
            // O controlo manual da exposição deixou de bloquear e passou a ser uma característica que a
            // objectiva declara ou não. A regra do projeto passa a ser «nada é automático, excepto onde
            // o telefone não deixa — e aí está escrito»: uma objectiva que dá RAW verdadeiro mas não
            // aceita o tempo nem o ISO continua a servir para fotografar sem mastigação, que é a
            // promessa central. O que ela não pode é fingir que a exposição foi nossa, e disso trata o
            // `manualExposure`, que atravessa o pedido, a UI, o sidecar e o certificado.
            //
            // Sem RAW não há nada a discutir: o pipeline inteiro parte do mosaico.
            val desirable = ArrayList<String>()

            val blocking = ArrayList<String>()
            if (rawSizes == null || rawSizes.isEmpty()) blocking.add("saída RAW_SENSOR")
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) blocking.add("capability RAW")

            val manual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
                    reqKeys.contains(CaptureRequest.SENSOR_EXPOSURE_TIME) &&
                    reqKeys.contains(CaptureRequest.SENSOR_SENSITIVITY)
            if (!manual) desirable.add("controlo manual da exposição")

            val focoNosso = reqKeys.contains(CaptureRequest.LENS_FOCUS_DISTANCE) &&
                    (ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
            if (!focoNosso) desirable.add("controlo manual do foco")

            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS)) {
                desirable.add("READ_SENSOR_SETTINGS")
            }
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) {
                desirable.add("MANUAL_POST_PROCESSING")
            }
            if (level != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL &&
                level != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3
            ) {
                desirable.add("nível FULL ou LEVEL_3")
            }
            if (!reqKeys.contains(CaptureRequest.NOISE_REDUCTION_MODE)) desirable.add("NOISE_REDUCTION_MODE")
            if (!reqKeys.contains(CaptureRequest.CONTROL_ENABLE_ZSL)) desirable.add("CONTROL_ENABLE_ZSL")

            // maior tamanho RAW disponível
            var biggest: Size? = null
            if (rawSizes != null) {
                for (s in rawSizes) {
                    if (biggest == null || s.width.toLong() * s.height > biggest.width.toLong() * biggest.height) {
                        biggest = s
                    }
                }
            }

            val black = IntArray(4)
            ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.copyTo(black, 0)
            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val expo = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

            return LensProfile(
                cameraId = cameraId,
                facing = ch.get(CameraCharacteristics.LENS_FACING) ?: -1,
                hardwareLevel = level,
                focalMm = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f,
                apertures = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: FloatArray(0),
                minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                rawSize = biggest ?: Size(0, 0),
                activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1,
                whiteLevel = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0,
                blackLevelPattern = black,
                exposureMinNs = expo?.lower ?: 0L,
                exposureMaxNs = BodyCalibration.exposureCeilingNs(
                    android.os.Build.MODEL, cameraId, expo?.upper ?: 0L),
                exposureMaxDeclaredNs = expo?.upper ?: 0L,
                maxFrameDurationNs = ch.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L,
                minFrameDurationNs = if (biggest != null) {
                    try {
                        map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, biggest)
                    } catch (e: Throwable) {
                        0L
                    }
                } else {
                    0L
                },
                isoMin = iso?.lower ?: 0,
                isoMax = iso?.upper ?: 0,
                maxAnalogIso = ch.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: 0,
                sensorWidthMm = phys?.width ?: 0f,
                sensorHeightMm = phys?.height ?: 0f,
                shadingApplied = ch.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) ?: false,
                oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0),
                manualExposure = manual,
                manualFocus = focoNosso,
                logicalId = logicalId,
                blocking = blocking,
                desirable = desirable,
            )
        }
    }
}
