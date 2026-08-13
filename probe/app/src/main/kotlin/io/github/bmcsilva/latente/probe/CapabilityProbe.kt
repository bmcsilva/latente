package io.github.bmcsilva.latente.probe

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.util.SizeF
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Passo F0 da especificação: despeja tudo o que decide se o projeto é viável neste telefone.
 *
 * Não abre câmaras na passagem principal — só lê características, o que não exige permissão.
 * O teste de abertura é uma segunda passagem explícita, porque é lento e pode falhar por
 * restrições do fabricante.
 */
class CapabilityProbe(ctx: Context) {

    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Diagonal de um fotograma 35 mm (36 × 24 mm), para o factor de recorte. */
    private val fullFrameDiagonalMm = 43.2666f

    private val formatsOfInterest = listOf(
        ImageFormat.RAW_SENSOR to "RAW_SENSOR",
        ImageFormat.RAW10 to "RAW10",
        ImageFormat.RAW12 to "RAW12",
        ImageFormat.RAW_PRIVATE to "RAW_PRIVATE",
        ImageFormat.YUV_420_888 to "YUV_420_888",
        ImageFormat.PRIVATE to "PRIVATE",
        ImageFormat.JPEG to "JPEG",
    )

    // -----------------------------------------------------------------------
    // passagem principal
    // -----------------------------------------------------------------------

    fun run(): Node {
        val root = Node("LATENTE · Sonda de capacidades")

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        root.put("gerado em", stamp)
        root.put("fabricante", Build.MANUFACTURER)
        root.put("modelo", Build.MODEL)
        root.put("dispositivo", Build.DEVICE)
        root.put("placa", Build.BOARD)
        root.put("hardware", Build.HARDWARE)
        root.put("Android", Build.VERSION.RELEASE)
        root.put("API", Build.VERSION.SDK_INT)
        root.put("sonda", "0.1")

        val logicals: Array<String> = try {
            mgr.cameraIdList
        } catch (e: CameraAccessException) {
            root.put("ERRO", "cameraIdList falhou: ${e.reason}")
            emptyArray()
        }
        root.put("câmaras lógicas", logicals.toList())

        // As físicas dentro de uma lógica não aparecem em cameraIdList.
        val seen = LinkedHashSet<String>()
        val physicalOf = LinkedHashMap<String, String>()
        for (id in logicals) {
            seen.add(id)
            val ch = characteristicsOrNull(id) ?: continue
            for (pid in ch.physicalCameraIds) {
                if (physicalOf[pid] == null) physicalOf[pid] = id
            }
        }
        root.put("câmaras físicas descobertas", physicalOf.keys.toList())
        root.put("total a sondar", seen.size + physicalOf.keys.count { !seen.contains(it) })

        for (id in logicals) {
            root.children.add(probe(id, null))
        }
        for ((pid, parent) in physicalOf) {
            if (seen.contains(pid)) continue
            root.children.add(probe(pid, parent))
        }

        root.children.add(summary(logicals, physicalOf))
        return root
    }

    private fun characteristicsOrNull(id: String): CameraCharacteristics? = try {
        mgr.getCameraCharacteristics(id)
    } catch (e: Throwable) {
        null
    }

    // -----------------------------------------------------------------------
    // uma câmara
    // -----------------------------------------------------------------------

    private fun probe(id: String, parentLogical: String?): Node {
        val title = if (parentLogical == null) "Câmara $id" else "Câmara $id (física de $parentLogical)"
        val n = Node(title)
        n.put("id", id)
        n.put("é física de", parentLogical)

        val ch = characteristicsOrNull(id)
        if (ch == null) {
            n.put("ERRO", "getCameraCharacteristics falhou — inacessível a terceiros")
            return n
        }

        val level = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val facing = ch.get(CameraCharacteristics.LENS_FACING)

        n.put("orientação", nameOf("LENS_FACING_", facing))
        n.put("nível de hardware", nameOf("INFO_SUPPORTED_HARDWARE_LEVEL_", level))
        n.put("capabilities", namesOf("REQUEST_AVAILABLE_CAPABILITIES_", caps))

        val v = verdict(ch)
        n.put("VEREDICTO", v.text())
        if (v.desirable.isNotEmpty() && v.serves) {
            n.put("reservas", v.desirable)
        }

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        streams(n.child("Streams"), map)
        sensor(n.child("Sensor"), ch)
        exposure(n.child("Exposição"), ch)
        optics(n.child("Óptica"), ch)
        processing(n.child("Processamento"), ch)
        colour(n.child("Cor"), ch)
        keys(n.child("Chaves que o HAL declara"), ch)

        return n
    }

    // -----------------------------------------------------------------------
    // secções
    // -----------------------------------------------------------------------

    private fun streams(n: Node, map: StreamConfigurationMap?) {
        if (map == null) {
            n.put("ERRO", "sem SCALER_STREAM_CONFIGURATION_MAP")
            return
        }
        for ((fmt, label) in formatsOfInterest) {
            val sizes = try {
                map.getOutputSizes(fmt)
            } catch (e: Throwable) {
                null
            }
            if (sizes == null || sizes.isEmpty()) {
                n.put(label, null)
                continue
            }
            n.put(label, sizes.map { describeSize(map, fmt, it) })
        }
        // Só aqui aparece o RAW de resolução total dos sensores quad-Bayer.
        val hi = try {
            map.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR)
        } catch (e: Throwable) {
            null
        }
        n.put("RAW_SENSOR alta resolução", hi?.map { describeSize(map, ImageFormat.RAW_SENSOR, it) })
    }

    private fun describeSize(map: StreamConfigurationMap, fmt: Int, s: Size): String {
        val minDur = try {
            map.getOutputMinFrameDuration(fmt, s)
        } catch (e: Throwable) {
            0L
        }
        val stall = try {
            map.getOutputStallDuration(fmt, s)
        } catch (e: Throwable) {
            0L
        }
        val mp = s.width.toLong() * s.height / 1_000_000.0
        val sb = StringBuilder()
        sb.append(s.width).append("x").append(s.height)
        sb.append(String.format(Locale.US, " (%.1f MP)", mp))
        if (minDur > 0) {
            sb.append(String.format(Locale.US, " · %.1f fps", 1e9 / minDur))
        }
        if (stall > 0) {
            sb.append(String.format(Locale.US, " · stall %.1f ms", stall / 1e6))
        }
        return sb.toString()
    }

    private fun sensor(n: Node, ch: CameraCharacteristics) {
        val pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pre = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val phys: SizeF? = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        n.put("matriz de píxeis", pixelArray?.let { "${it.width}x${it.height}" })
        n.put("área activa", active?.let { rectStr(it) })
        n.put("área activa pré-correcção", pre?.let { rectStr(it) })
        n.put("tamanho físico mm", phys?.let { String.format(Locale.US, "%.2f x %.2f", it.width, it.height) })

        if (phys != null) {
            val diag = hypot(phys.width.toDouble(), phys.height.toDouble())
            n.put("diagonal mm", diag)
            n.put("factor de recorte", fullFrameDiagonalMm / diag)
            n.put("área mm2", phys.width.toDouble() * phys.height)
            // 36 x 24 = 864 mm2
            n.put("stops abaixo de full frame", log2(864.0 / (phys.width.toDouble() * phys.height)))
        }

        n.put("mosaico de cor", nameOf("SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_",
            ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)))
        val white = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        n.put("nível de branco", white)
        if (white != null && white > 0) {
            // O formato RAW_SENSOR é um contentor de 16 bits; os bits úteis vêm daqui.
            val bits = Math.ceil(Math.log(white + 1.0) / Math.log(2.0)).toInt()
            n.put("bits úteis por pixel", bits)
            n.put("alcance dinâmico máximo teórico", String.format(Locale.US, "%.1f stops", bits.toDouble()))
            if (bits < 12) {
                n.put("aviso bits", "RAW de $bits bits — o contentor tem 16 mas o sensor entrega $bits; " +
                        "o revelador não deve assumir precisão que não existe")
            }
        }

        val black: BlackLevelPattern? = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        if (black != null) {
            val v = IntArray(4)
            black.copyTo(v, 0)
            n.put("padrão de nível de preto", v.toList())
            if (v[0] == 0 && v[1] == 0 && v[2] == 0 && v[3] == 0) {
                n.put("aviso nível de preto", "padrão todo a zero — o HAL já subtraiu o pedestal antes " +
                        "de entregar o RAW; não voltar a subtrair na revelação")
            }
        } else {
            n.put("padrão de nível de preto", null)
        }

        n.put("fonte de timestamp", nameOf("SENSOR_INFO_TIMESTAMP_SOURCE_",
            ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)))
        n.put("máx. saídas RAW simultâneas", ch.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW))

        // Responde directamente ao teste 10.4 da especificação.
        val shadingApplied = ch.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED)
        n.put("shading JÁ APLICADO ao RAW", shadingApplied)
        if (shadingApplied == true) {
            n.put("aviso", "o HAL corrige vinhetagem antes de entregar o RAW — o RAW não é totalmente cru")
        }

        n.put("zoom digital máx.", ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
        n.put("intervalo de zoom", ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { rangeStr(it) })
    }

    private fun exposure(n: Node, ch: CameraCharacteristics) {
        val expo: Range<Long>? = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (expo != null) {
            n.put("exposição mín. ns", expo.lower)
            n.put("exposição máx. ns", expo.upper)
            n.put("exposição mín.", fmtExposure(expo.lower))
            n.put("exposição máx.", fmtExposure(expo.upper))
            if (expo.upper < 1_000_000_000L) {
                n.put("aviso exposição", "tecto de " + fmtExposure(expo.upper) +
                        " — sem exposições longas por Camera2. Se a aplicação do fabricante " +
                        "conseguir mais, é por um caminho privado que não está aqui exposto")
            }
        } else {
            n.put("exposição", null)
            n.put("aviso", "sem SENSOR_INFO_EXPOSURE_TIME_RANGE — exposição manual improvável")
        }

        val maxFrame = ch.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
        n.put("duração máx. de frame ns", maxFrame)
        n.put("duração máx. de frame", maxFrame?.let { fmtExposure(it) })

        val iso: Range<Int>? = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        n.put("ISO mín.", iso?.lower)
        n.put("ISO máx.", iso?.upper)

        val maxAnalog = ch.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
        n.put("ISO analógico máx.", maxAnalog)
        if (maxAnalog != null && iso != null) {
            if (iso.upper > maxAnalog) {
                n.put("ganho digital acima de", "ISO $maxAnalog (até ${iso.upper} é volume, não sinal)")
                n.put("stops de ganho digital", log2(iso.upper.toDouble() / maxAnalog))
            } else {
                n.put("ganho digital", "nenhum — todo o intervalo de ISO é analógico")
            }
        }
    }

    private fun optics(n: Node, ch: CameraCharacteristics) {
        val phys: SizeF? = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val crop = if (phys != null) {
            fullFrameDiagonalMm / hypot(phys.width.toDouble(), phys.height.toDouble())
        } else {
            null
        }

        val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        n.put("distâncias focais mm", focals?.map { fmt2(it.toDouble()) })
        if (focals != null && crop != null) {
            n.put("equivalente 35 mm", focals.map { (it * crop).roundToInt().toString() + " mm" })
        }

        val apertures = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        n.put("aberturas", apertures?.map { "f/" + fmt2(it.toDouble()) })
        n.put("diafragma variável", apertures != null && apertures.size > 1)
        if (apertures != null && crop != null) {
            n.put("abertura equivalente 35 mm", apertures.map { "f/" + fmt2(it * crop) })
        }

        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        n.put("foco mín. dioptrias", minFocus)
        n.put("foco mín.", if (minFocus != null && minFocus > 0f) fmtDistance(1.0 / minFocus) else "fixo ou desconhecido")

        val hyper = ch.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
        n.put("hiperfocal dioptrias", hyper)
        n.put("hiperfocal", if (hyper != null && hyper > 0f) fmtDistance(1.0 / hyper) else "—")

        n.put("calibração da distância de foco", nameOf("LENS_INFO_FOCUS_DISTANCE_CALIBRATION_",
            ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)))
        n.put("estabilização óptica", namesOf("LENS_OPTICAL_STABILIZATION_MODE_",
            ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)))
        n.put("tem LENS_DISTORTION", ch.get(CameraCharacteristics.LENS_DISTORTION) != null)
        n.put("tem LENS_INTRINSIC_CALIBRATION", ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) != null)
    }

    private fun processing(n: Node, ch: CameraCharacteristics) {
        fun block(label: String, prefix: String, modes: IntArray?, offValue: Int) {
            n.put(label, namesOf(prefix, modes))
            n.put("$label · OFF disponível", modes?.contains(offValue))
        }

        block("redução de ruído", "NOISE_REDUCTION_MODE_",
            ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
            CameraMetadata.NOISE_REDUCTION_MODE_OFF)

        block("edge (nitidez)", "EDGE_MODE_",
            ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
            CameraMetadata.EDGE_MODE_OFF)

        block("hot pixel", "HOT_PIXEL_MODE_",
            ch.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
            CameraMetadata.HOT_PIXEL_MODE_OFF)

        block("shading", "SHADING_MODE_",
            ch.get(CameraCharacteristics.SHADING_AVAILABLE_MODES),
            CameraMetadata.SHADING_MODE_OFF)

        block("aberração cromática", "COLOR_CORRECTION_ABERRATION_MODE_",
            ch.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)

        block("correcção de distorção", "DISTORTION_CORRECTION_MODE_",
            ch.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES),
            CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)

        n.put("tonemap", namesOf("TONEMAP_MODE_",
            ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)))
        n.put("tonemap · pontos de curva", ch.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS))

        n.put("mapa de shading (statistics)", namesOf("STATISTICS_LENS_SHADING_MAP_MODE_",
            ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)))
        n.put("mapa de hot pixel (statistics)", ch.get(
            CameraCharacteristics.STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES)?.toList())

        n.put("control modes", namesOf("CONTROL_MODE_",
            ch.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES)))
        n.put("AE modes", namesOf("CONTROL_AE_MODE_",
            ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)))
        n.put("AE · OFF disponível", ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            ?.contains(CameraMetadata.CONTROL_AE_MODE_OFF))
        n.put("AF modes", namesOf("CONTROL_AF_MODE_",
            ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)))
        n.put("AF · OFF disponível", ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CameraMetadata.CONTROL_AF_MODE_OFF))
        n.put("AWB modes", namesOf("CONTROL_AWB_MODE_",
            ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)))
        n.put("AWB · OFF disponível", ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.contains(CameraMetadata.CONTROL_AWB_MODE_OFF))
        n.put("estabilização de vídeo", namesOf("CONTROL_VIDEO_STABILIZATION_MODE_",
            ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)))
    }

    private fun colour(n: Node, ch: CameraCharacteristics) {
        n.put("iluminante de referência 1", nameOf("SENSOR_REFERENCE_ILLUMINANT1_",
            ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)))
        n.put("iluminante de referência 2", ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
            ?.let { nameOf("SENSOR_REFERENCE_ILLUMINANT1_", it.toInt()) })

        n.put("colorTransform1", mat(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)))
        n.put("colorTransform2", mat(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)))
        n.put("calibrationTransform1", mat(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)))
        n.put("calibrationTransform2", mat(ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)))
        n.put("forwardMatrix1", mat(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)))
        n.put("forwardMatrix2", mat(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)))

        val ok = ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) != null &&
                ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null
        n.put("ciência da cor completa", ok)
        if (!ok) {
            n.put("aviso", "matrizes ausentes — o DNG sairá com cor errada; é preciso perfil próprio")
        }
    }

    private fun keys(n: Node, ch: CameraCharacteristics) {
        // Verificar, não confiar: uma chave ausente daqui não vai ser honrada.
        val reqKeys = try {
            ch.availableCaptureRequestKeys
        } catch (e: Throwable) {
            emptyList<CaptureRequest.Key<*>>()
        }
        val resKeys = try {
            ch.availableCaptureResultKeys
        } catch (e: Throwable) {
            emptyList<CaptureResult.Key<*>>()
        }

        n.put("total de chaves de pedido", reqKeys.size)
        n.put("total de chaves de resultado", resKeys.size)

        val wantedRequest = LinkedHashMap<String, CaptureRequest.Key<*>>()
        wantedRequest["CONTROL_MODE"] = CaptureRequest.CONTROL_MODE
        wantedRequest["CONTROL_AE_MODE"] = CaptureRequest.CONTROL_AE_MODE
        wantedRequest["CONTROL_AWB_MODE"] = CaptureRequest.CONTROL_AWB_MODE
        wantedRequest["CONTROL_AF_MODE"] = CaptureRequest.CONTROL_AF_MODE
        wantedRequest["CONTROL_AE_ANTIBANDING_MODE"] = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE
        wantedRequest["CONTROL_SCENE_MODE"] = CaptureRequest.CONTROL_SCENE_MODE
        wantedRequest["CONTROL_EFFECT_MODE"] = CaptureRequest.CONTROL_EFFECT_MODE
        wantedRequest["CONTROL_ENABLE_ZSL"] = CaptureRequest.CONTROL_ENABLE_ZSL
        wantedRequest["CONTROL_POST_RAW_SENSITIVITY_BOOST"] = CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST
        wantedRequest["CONTROL_VIDEO_STABILIZATION_MODE"] = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE
        wantedRequest["CONTROL_ZOOM_RATIO"] = CaptureRequest.CONTROL_ZOOM_RATIO
        wantedRequest["LENS_OPTICAL_STABILIZATION_MODE"] = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE
        wantedRequest["LENS_FOCUS_DISTANCE"] = CaptureRequest.LENS_FOCUS_DISTANCE
        wantedRequest["LENS_APERTURE"] = CaptureRequest.LENS_APERTURE
        wantedRequest["LENS_FOCAL_LENGTH"] = CaptureRequest.LENS_FOCAL_LENGTH
        wantedRequest["NOISE_REDUCTION_MODE"] = CaptureRequest.NOISE_REDUCTION_MODE
        wantedRequest["EDGE_MODE"] = CaptureRequest.EDGE_MODE
        wantedRequest["HOT_PIXEL_MODE"] = CaptureRequest.HOT_PIXEL_MODE
        wantedRequest["SHADING_MODE"] = CaptureRequest.SHADING_MODE
        wantedRequest["COLOR_CORRECTION_ABERRATION_MODE"] = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE
        wantedRequest["COLOR_CORRECTION_MODE"] = CaptureRequest.COLOR_CORRECTION_MODE
        wantedRequest["COLOR_CORRECTION_GAINS"] = CaptureRequest.COLOR_CORRECTION_GAINS
        wantedRequest["COLOR_CORRECTION_TRANSFORM"] = CaptureRequest.COLOR_CORRECTION_TRANSFORM
        wantedRequest["DISTORTION_CORRECTION_MODE"] = CaptureRequest.DISTORTION_CORRECTION_MODE
        wantedRequest["TONEMAP_MODE"] = CaptureRequest.TONEMAP_MODE
        wantedRequest["TONEMAP_GAMMA"] = CaptureRequest.TONEMAP_GAMMA
        wantedRequest["TONEMAP_CURVE"] = CaptureRequest.TONEMAP_CURVE
        wantedRequest["SENSOR_EXPOSURE_TIME"] = CaptureRequest.SENSOR_EXPOSURE_TIME
        wantedRequest["SENSOR_SENSITIVITY"] = CaptureRequest.SENSOR_SENSITIVITY
        wantedRequest["SENSOR_FRAME_DURATION"] = CaptureRequest.SENSOR_FRAME_DURATION
        wantedRequest["SCALER_CROP_REGION"] = CaptureRequest.SCALER_CROP_REGION
        wantedRequest["STATISTICS_LENS_SHADING_MAP_MODE"] = CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE
        wantedRequest["STATISTICS_HOT_PIXEL_MAP_MODE"] = CaptureRequest.STATISTICS_HOT_PIXEL_MAP_MODE

        val absentReq = ArrayList<String>()
        for ((label, key) in wantedRequest) {
            val present = reqKeys.contains(key)
            n.put("req · $label", if (present) "sim" else "AUSENTE")
            if (!present) absentReq.add(label)
        }

        val wantedResult = LinkedHashMap<String, CaptureResult.Key<*>>()
        wantedResult["SENSOR_TIMESTAMP"] = CaptureResult.SENSOR_TIMESTAMP
        wantedResult["SENSOR_EXPOSURE_TIME"] = CaptureResult.SENSOR_EXPOSURE_TIME
        wantedResult["SENSOR_SENSITIVITY"] = CaptureResult.SENSOR_SENSITIVITY
        wantedResult["SENSOR_DYNAMIC_BLACK_LEVEL"] = CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL
        wantedResult["SENSOR_DYNAMIC_WHITE_LEVEL"] = CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL
        wantedResult["SENSOR_NOISE_PROFILE"] = CaptureResult.SENSOR_NOISE_PROFILE
        wantedResult["SENSOR_NEUTRAL_COLOR_POINT"] = CaptureResult.SENSOR_NEUTRAL_COLOR_POINT
        wantedResult["SENSOR_GREEN_SPLIT"] = CaptureResult.SENSOR_GREEN_SPLIT
        wantedResult["SENSOR_ROLLING_SHUTTER_SKEW"] = CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW
        wantedResult["STATISTICS_LENS_SHADING_CORRECTION_MAP"] = CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP
        wantedResult["LENS_FOCUS_DISTANCE"] = CaptureResult.LENS_FOCUS_DISTANCE
        wantedResult["LENS_APERTURE"] = CaptureResult.LENS_APERTURE

        val absentRes = ArrayList<String>()
        for ((label, key) in wantedResult) {
            val present = resKeys.contains(key)
            n.put("res · $label", if (present) "sim" else "AUSENTE")
            if (!present) absentRes.add(label)
        }

        n.put("RESUMO chaves de pedido ausentes", if (absentReq.isEmpty()) "nenhuma" else absentReq.joinToString(", "))
        n.put("RESUMO chaves de resultado ausentes", if (absentRes.isEmpty()) "nenhuma" else absentRes.joinToString(", "))
    }

    /**
     * O veredicto separa o que é bloqueante do que é apenas desejável.
     *
     * Bloqueante é só o que impede captura RAW manual: saída RAW_SENSOR, MANUAL_SENSOR, e as
     * chaves de tempo de exposição e de ISO efectivamente declaradas.
     *
     * `MANUAL_POST_PROCESSING` e o nível `FULL` **não** são bloqueantes neste projeto: dizem
     * respeito ao tonemap e à correcção de cor do ISP, que não usamos — o revelador é nosso e
     * parte do RAW. Excluir uma câmara por lhes faltarem descartaria objectivas utilizáveis.
     */
    private class Verdict(val blocking: List<String>, val desirable: List<String>) {

        val serves: Boolean
            get() = blocking.isEmpty()

        fun text(): String = when {
            blocking.isNotEmpty() -> "NÃO SERVE — falta: " + blocking.joinToString(", ")
            desirable.isNotEmpty() -> "SERVE COM RESERVAS — captura RAW manual possível"
            else -> "SERVE — captura RAW manual possível"
        }
    }

    private fun verdict(ch: CameraCharacteristics): Verdict {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val level = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val reqKeys = try {
            ch.availableCaptureRequestKeys
        } catch (e: Throwable) {
            emptyList<CaptureRequest.Key<*>>()
        }

        val blocking = ArrayList<String>()
        if (map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() != true) blocking.add("saída RAW_SENSOR")
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) blocking.add("capability RAW")
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) blocking.add("MANUAL_SENSOR")
        if (!reqKeys.contains(CaptureRequest.SENSOR_EXPOSURE_TIME)) blocking.add("chave SENSOR_EXPOSURE_TIME")
        if (!reqKeys.contains(CaptureRequest.SENSOR_SENSITIVITY)) blocking.add("chave SENSOR_SENSITIVITY")

        val desirable = ArrayList<String>()
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS)) {
            desirable.add("READ_SENSOR_SETTINGS (não se sabe o que o HAL aplicou)")
        }
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) {
            desirable.add("MANUAL_POST_PROCESSING (irrelevante: o revelador é nosso)")
        }
        if (level != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL &&
            level != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3) {
            desirable.add("nível FULL ou LEVEL_3")
        }
        if (!reqKeys.contains(CaptureRequest.NOISE_REDUCTION_MODE)) {
            desirable.add("chave NOISE_REDUCTION_MODE (não se consegue exigir NR desligada)")
        }
        if (!reqKeys.contains(CaptureRequest.CONTROL_ENABLE_ZSL)) {
            desirable.add("chave CONTROL_ENABLE_ZSL (não se consegue desligar ZSL)")
        }
        return Verdict(blocking, desirable)
    }

    private fun summary(logicals: Array<String>, physicalOf: Map<String, String>): Node {
        val n = Node("Resumo")
        val back = ArrayList<String>()
        val front = ArrayList<String>()
        val rejected = ArrayList<String>()

        val all = LinkedHashSet<String>()
        all.addAll(logicals)
        all.addAll(physicalOf.keys)

        for (id in all) {
            val ch = characteristicsOrNull(id) ?: continue
            val v = verdict(ch)

            val label = StringBuilder("id $id")
            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            if (focal != null && focal.isNotEmpty() && phys != null) {
                val crop = fullFrameDiagonalMm / hypot(phys.width.toDouble(), phys.height.toDouble())
                label.append(" · ").append((focal[0] * crop).roundToInt()).append(" mm equiv.")
            }
            val ap = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            if (ap != null && ap.isNotEmpty()) label.append(" f/").append(fmt2(ap[0].toDouble()))

            val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            if (minFocus == null || minFocus == 0f) label.append(" · foco fixo")
            if (physicalOf.containsKey(id)) label.append(" · só via lógica ").append(physicalOf[id])
            if (v.serves && v.desirable.isNotEmpty()) label.append(" · com reservas")

            if (!v.serves) {
                rejected.add(label.toString() + " — falta " + v.blocking.joinToString(", "))
                continue
            }
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraMetadata.LENS_FACING_FRONT) front.add(label.toString())
            else back.add(label.toString())
        }

        n.put("objectivas traseiras utilizáveis", if (back.isEmpty()) "NENHUMA" else back)
        n.put("objectivas frontais utilizáveis", if (front.isEmpty()) "NENHUMA" else front)
        n.put("total utilizável", back.size + front.size)
        n.put("recusadas", if (rejected.isEmpty()) "nenhuma" else rejected)
        n.put("nota", "as objectivas do projeto são exactamente estas câmaras — não há zoom óptico contínuo")
        n.put("aviso", "um id que só existe dentro de uma câmara lógica pode não abrir directamente; " +
                "confirmar no teste de abertura")
        return n
    }

    // -----------------------------------------------------------------------
    // teste de abertura (segunda passagem, exige permissão CAMERA)
    // -----------------------------------------------------------------------

    fun testOpen(): Node {
        val n = Node("Teste de abertura")
        n.put("nota", "confirma quais as câmaras que uma aplicação de terceiros consegue mesmo abrir")

        val thread = HandlerThread("probe-open")
        thread.start()
        val handler = Handler(thread.looper)

        val ids = LinkedHashSet<String>()
        try {
            for (id in mgr.cameraIdList) {
                ids.add(id)
                characteristicsOrNull(id)?.physicalCameraIds?.let { ids.addAll(it) }
            }
        } catch (e: Throwable) {
            n.put("ERRO", e.toString())
        }

        for (id in ids) {
            n.put("id $id", tryOpen(id, handler))
        }

        thread.quitSafely()
        return n
    }

    private fun tryOpen(id: String, handler: Handler): String {
        val latch = CountDownLatch(1)
        var outcome = "sem resposta em 5 s"
        var device: CameraDevice? = null

        val cb = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                outcome = "ABERTA"
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                outcome = "desligada"
                camera.close()
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                outcome = "ERRO " + when (error) {
                    ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
                    ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
                    ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED (política do dispositivo)"
                    ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
                    ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
                    else -> error.toString()
                }
                camera.close()
                latch.countDown()
            }
        }

        try {
            mgr.openCamera(id, cb, handler)
        } catch (e: SecurityException) {
            return "recusada — falta permissão CAMERA"
        } catch (e: IllegalArgumentException) {
            return "recusada — id inacessível a terceiros"
        } catch (e: Throwable) {
            return "recusada — " + e.javaClass.simpleName + ": " + (e.message ?: "")
        }

        latch.await(5, TimeUnit.SECONDS)
        device?.close()
        return outcome
    }

    // -----------------------------------------------------------------------
    // utilitários
    // -----------------------------------------------------------------------

    /**
     * Nomes das constantes por reflexão sobre CameraMetadata.
     *
     * Evita transcrever à mão dezenas de inteiros — e continua correcto quando a plataforma
     * acrescenta valores novos. Requer que a ofuscação esteja desligada.
     */
    private val nameTables = HashMap<String, Map<Int, String>>()

    private fun table(prefix: String): Map<Int, String> {
        val cached = nameTables[prefix]
        if (cached != null) return cached
        val m = LinkedHashMap<Int, String>()
        for (f in CameraMetadata::class.java.declaredFields) {
            if (!f.name.startsWith(prefix)) continue
            if (f.type != Int::class.javaPrimitiveType) continue
            try {
                f.isAccessible = true
                m[f.getInt(null)] = f.name.substring(prefix.length)
            } catch (e: Throwable) {
                // constante inacessível: ignora-se
            }
        }
        nameTables[prefix] = m
        return m
    }

    private fun nameOf(prefix: String, value: Int?): String? {
        if (value == null) return null
        val name = table(prefix)[value]
        return if (name != null) "$name ($value)" else "DESCONHECIDO ($value)"
    }

    private fun namesOf(prefix: String, values: IntArray?): List<String>? {
        if (values == null) return null
        val t = table(prefix)
        val out = ArrayList<String>(values.size)
        for (v in values) out.add(t[v] ?: "DESCONHECIDO($v)")
        return out
    }

    private fun mat(t: ColorSpaceTransform?): Mat3? {
        if (t == null) return null
        val v = DoubleArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                v[row * 3 + col] = t.getElement(col, row).toDouble()
            }
        }
        return Mat3(v)
    }

    private fun rectStr(r: Rect): String =
        "${r.width()}x${r.height()} em (${r.left}, ${r.top})"

    private fun rangeStr(r: Range<Float>): String =
        fmt2(r.lower.toDouble()) + " … " + fmt2(r.upper.toDouble())

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun log2(v: Double): Double = Math.log(v) / Math.log(2.0)

    private fun fmtExposure(ns: Long): String {
        val s = ns / 1e9
        if (s >= 1.0) return String.format(Locale.US, "%.2f s", s)
        val denom = 1.0 / s
        return "1/" + (if (denom >= 100) denom.roundToInt().toString()
        else String.format(Locale.US, "%.1f", denom)) + " s"
    }

    private fun fmtDistance(m: Double): String =
        if (m >= 1.0) String.format(Locale.US, "%.2f m", m)
        else String.format(Locale.US, "%.1f cm", m * 100)
}
