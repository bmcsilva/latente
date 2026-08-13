package io.github.bmcsilva.latente.diag

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import io.github.bmcsilva.latente.export.Node
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A pergunta da terceira objectiva traseira, com resposta de sim ou não.
 *
 * A 66 mm deste corpo declara saída `RAW_SENSOR` e a capacidade RAW — o mosaico que ela desse seriam
 * dados verdadeiros do sensor. O que a impede são duas coisas, e esta ferramenta trata da segunda:
 *
 * 1. **Não deixa escolher o tempo nem o ISO.** Falta-lhe `MANUAL_SENSOR` e as chaves da exposição.
 *    Isso não se resolve com API nenhuma; resolve-se decidindo se uma objectiva onde a câmara decide
 *    a exposição tem lugar nesta aplicação.
 * 2. **Não se abre.** Só existe dentro de uma câmara lógica, e um `openCamera("6")` de uma aplicação
 *    de terceiros falha. O caminho documentado para lá chegar é o de multi-câmara: abrir a **lógica**
 *    e pedir uma saída presa à física, com `OutputConfiguration.setPhysicalCameraId`.
 *
 * É esse caminho que se ensaia aqui. Não se assume que funciona nem que não funciona — configura-se e
 * vê-se se o HAL aceita. Se aceitar, tenta-se um frame e diz-se o que chegou.
 *
 * Não usa a `CameraSession` da aplicação de propósito: aquela impõe o pedido limpo e manual, que é
 * exactamente o que esta objectiva não tem. Aqui o pedido é o mínimo que a pergunta exige.
 */
object PhysicalCameraProbe {

    fun run(ctx: Context, progress: (String) -> Unit): Node {
        val raiz = Node("Latente · objectivas físicas dentro de uma lógica")
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val thread = HandlerThread("latente-fisica").apply { start() }
        val handler = Handler(thread.looper)
        try {
            val fisicas = mapear(manager, raiz)
            for ((fisica, logica) in fisicas) {
                progress("a ensaiar a física $fisica dentro da lógica $logica…")
                raiz.children.add(ensaiar(manager, handler, logica, fisica))
            }
            if (fisicas.isEmpty()) {
                raiz.put("conclusão", "nenhuma física por ensaiar — nada a fazer")
            }
        } catch (t: Throwable) {
            raiz.put("excepção", t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            thread.quitSafely()
        }
        return raiz
    }

    /**
     * Que físicas existem, dentro de que lógicas, e o que declaram.
     *
     * Devolve só as que **não** estão na lista de topo: essas abrem-se directamente e não precisam
     * deste caminho.
     */
    private fun mapear(manager: CameraManager, raiz: Node): List<Pair<String, String>> {
        val topo = manager.cameraIdList.toSet()
        raiz.put("câmaras de topo", topo.toList())
        val alvos = ArrayList<Pair<String, String>>()
        for (idLogica in topo) {
            val ch = manager.getCameraCharacteristics(idLogica)
            val fisicas = ch.physicalCameraIds
            if (fisicas.isEmpty()) continue
            val n = Node("lógica $idLogica")
            n.put("físicas que declara", fisicas.toList())
            n.put("é multi-câmara lógica", capacidades(ch).contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA))
            for (idFisica in fisicas) {
                if (topo.contains(idFisica)) continue
                alvos.add(Pair(idFisica, idLogica))
                n.children.add(descrever(manager, idFisica))
            }
            raiz.children.add(n)
        }
        return alvos
    }

    private fun descrever(manager: CameraManager, idFisica: String): Node {
        val n = Node("física $idFisica")
        try {
            val ch = manager.getCameraCharacteristics(idFisica)
            val caps = capacidades(ch)
            n.put("capacidade RAW", caps.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))
            n.put("capacidade MANUAL_SENSOR", caps.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
            n.put("distância focal mm", ch.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f)
            val tamanho = tamanhoRaw(ch)
            n.put("maior RAW", tamanho?.toString() ?: "sem saída RAW")
            // O que se pode **pedir** a esta física. Sem estas chaves não há exposição escolhida por nós,
            // e é isso que decide se ela tem lugar na aplicação — não é o HAL que decide, somos nós.
            val chaves = ch.availableCaptureRequestKeys.map { it.name }
            n.put("aceita SENSOR_EXPOSURE_TIME", chaves.contains("android.sensor.exposureTime"))
            n.put("aceita SENSOR_SENSITIVITY", chaves.contains("android.sensor.sensitivity"))
            n.put("aceita CONTROL_ENABLE_ZSL", chaves.contains("android.control.enableZsl"))
            // O que a nossa revelação exige para funcionar. Sem as matrizes de cor não há como levar
            // o mosaico a XYZ, e a `GlUniforms.fromCamera` recusa-se — com razão — a inventar.
            n.put("tem ForwardMatrix1", ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) != null)
            n.put("tem ForwardMatrix2", ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2) != null)
            n.put("vinhetagem já aplicada ao RAW",
                ch.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) ?: false)
            n.put("nível de branco", ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: -1)
            // O foco é uma capacidade à parte da exposição: uma objectiva pode não deixar escolher o
            // tempo e deixar escolher a distância, ou o contrário. Não se supõe — pergunta-se.
            n.put("distância mínima de foco dioptrias",
                ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f)
            n.put("aceita LENS_FOCUS_DISTANCE", chaves.contains("android.lens.focusDistance"))
            n.put("modos de AF", (ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?: IntArray(0)).toList())
            n.put("calibração da distância de foco",
                ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) ?: -1)
        } catch (t: Throwable) {
            n.put("não se leram as características", t.javaClass.simpleName)
        }
        return n
    }

    /**
     * O ensaio: abrir a lógica e pedir-lhe uma saída RAW presa à física.
     *
     * O critério é o que o HAL responde ao `createCaptureSession`, e não uma opinião sobre o que ele
     * devia suportar.
     */
    private fun ensaiar(
        manager: CameraManager,
        handler: Handler,
        idLogica: String,
        idFisica: String,
    ): Node {
        val n = Node("ensaio: RAW da física $idFisica pela lógica $idLogica")
        var dispositivo: CameraDevice? = null
        var reader: ImageReader? = null
        try {
            val chFisica = manager.getCameraCharacteristics(idFisica)
            val tamanho = tamanhoRaw(chFisica)
            if (tamanho == null) {
                n.put("resultado", "a física não declara saída RAW — não há o que pedir")
                return n
            }

            val aberta = CountDownLatch(1)
            var erroDeAbertura: String? = "sem resposta"
            manager.openCamera(idLogica, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    dispositivo = camera
                    erroDeAbertura = null
                    aberta.countDown()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    erroDeAbertura = "desligada"
                    camera.close()
                    aberta.countDown()
                }
                override fun onError(camera: CameraDevice, code: Int) {
                    erroDeAbertura = "erro de abertura $code"
                    camera.close()
                    aberta.countDown()
                }
            }, handler)
            aberta.await(5000, TimeUnit.MILLISECONDS)
            val dev = dispositivo
            if (dev == null) {
                n.put("resultado", "a lógica não abriu: " + (erroDeAbertura ?: "?"))
                return n
            }

            reader = ImageReader.newInstance(
                tamanho.width, tamanho.height, ImageFormat.RAW_SENSOR, 2)
            val saida = OutputConfiguration(reader.surface)
            saida.setPhysicalCameraId(idFisica)

            val configurada = CountDownLatch(1)
            var sessao: CameraCaptureSession? = null
            var erroDeConfig: String? = "sem resposta"
            dev.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(saida),
                { r -> handler.post(r) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        sessao = s
                        erroDeConfig = null
                        configurada.countDown()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        erroDeConfig = "o HAL recusou a combinação"
                        configurada.countDown()
                    }
                }))
            configurada.await(5000, TimeUnit.MILLISECONDS)

            val s = sessao
            n.put("configuração aceite", s != null)
            if (s == null) {
                n.put("resultado", "RECUSADO: " + (erroDeConfig ?: "?"))
                return n
            }

            // Aceite. Faltam duas perguntas: chega mesmo um frame, e é **um** frame?
            val chegou = CountDownLatch(1)
            reader.setOnImageAvailableListener({ chegou.countDown() }, handler)
            val pedido = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            pedido.addTarget(reader.surface)
            // ZSL desligado, e a seguir verifica-se se foi obedecido.
            //
            // É a pergunta que decide se esta objectiva pode entrar: um frame vindo de cache é, na
            // melhor das hipóteses, de outro instante; na pior, fusão de vários — que é exactamente o
            // processamento computacional que esta aplicação recusa. Não chega pedir; tem de se medir.
            pedido.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            val antes = android.os.SystemClock.elapsedRealtimeNanos()
            var resultado: TotalCaptureResult? = null
            val terminou = CountDownLatch(1)
            s.capture(pedido.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s2: CameraCaptureSession,
                    request: CaptureRequest,
                    r: TotalCaptureResult,
                ) {
                    resultado = r
                    terminou.countDown()
                }
            }, handler)
            terminou.await(6000, TimeUnit.MILLISECONDS)
            val temImagem = chegou.await(6000, TimeUnit.MILLISECONDS)
            n.put("frame entregue", temImagem)
            if (temImagem) {
                val img = reader.acquireLatestImage()
                if (img != null) {
                    n.put("mosaico entregue", img.width.toString() + "x" + img.height)
                    img.close()
                }
            }
            resultado?.let { r ->
                // A fonte de timestamp é REALTIME, logo comparável com `elapsedRealtimeNanos()`. Um
                // frame anterior ao pedido veio de cache.
                val ts = r.get(TotalCaptureResult.SENSOR_TIMESTAMP)
                if (ts != null) {
                    val atrasoMs = (ts - antes) / 1e6
                    n.put("atraso do frame ms", atrasoMs)
                    n.put("veredicto ZSL", if (atrasoMs < -20.0) {
                        "SUSPEITO: o frame é anterior ao pedido — parece cache ou fusão"
                    } else {
                        "o frame é posterior ao pedido: um frame, tirado agora"
                    })
                }
                n.put("ZSL que a câmara reporta",
                    r.get(TotalCaptureResult.CONTROL_ENABLE_ZSL)?.toString() ?: "não reportado")
                // Mesmo sem se poder **escolher**, o resultado costuma **reportar** o que a câmara usou.
                // É o que permitiria escrever um sidecar honesto: a exposição não foi nossa, mas fica
                // registada.
                n.put("tempo que a câmara usou ns",
                    r.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME) ?: -1L)
                n.put("ISO que a câmara usou", r.get(TotalCaptureResult.SENSOR_SENSITIVITY) ?: -1)
            }
            n.put("resultado", if (temImagem) "ACEITE e com frame" else "aceite mas sem frame")
            s.close()
        } catch (t: Throwable) {
            n.put("resultado", "RECUSADO por excepção: " +
                    t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            reader?.close()
            dispositivo?.close()
        }
        return n
    }

    private fun capacidades(ch: CameraCharacteristics): List<Int> =
        ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()

    private fun tamanhoRaw(ch: CameraCharacteristics): Size? {
        val mapa = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        return maiorRaw(mapa)
    }

    private fun maiorRaw(mapa: StreamConfigurationMap): Size? {
        val tamanhos = mapa.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return null
        var maior: Size? = null
        for (t in tamanhos) {
            if (maior == null || t.width.toLong() * t.height > maior.width.toLong() * maior.height) {
                maior = t
            }
        }
        return maior
    }
}
