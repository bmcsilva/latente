package io.github.bmcsilva.latente.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import io.github.bmcsilva.latente.model.Exposure
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Um frame RAW e o resultado que o descreve. Fechar sempre. */
class RawFrame(
    val image: Image,
    val result: TotalCaptureResult,
    val outcome: CleanOutcome,
    /**
     * `false` quando não se conseguiu casar a imagem com o resultado pelo timestamp e se aceitou
     * a mais recente. Como todos os frames da sessão correm com os mesmos parâmetros manuais, serve
     * para medir — mas tem de ser dito, não escondido.
     */
    val matchedByTimestamp: Boolean = true,
) : Closeable {
    override fun close() {
        try {
            image.close()
        } catch (t: Throwable) {
            // já fechado
        }
    }
}

class SettleResult(
    val last: TotalCaptureResult?,
    val frames: Int,
    val error: String?,
    /** O sensor chegou mesmo aos valores pedidos, ou só passaram frames? */
    val settledAtRequested: Boolean = false,
)

/**
 * Sessão de captura RAW, com API bloqueante.
 *
 * Bloqueante de propósito: a F1 é verificação passo a passo, e sequência explícita é mais fácil de
 * raciocinar do que uma teia de *callbacks*. Chamar sempre fora da thread principal.
 *
 * A sessão tem apenas a saída RAW. Não há saída do ISP — quando o visor existir (F3), desenha o
 * resultado do nosso pipeline, alimentado pelo mesmo stream RAW.
 */
class CameraSession(
    private val ctx: Context,
    /** O id que se **abre**. Numa objectiva física, é o da lógica que a contém. */
    val cameraId: String,
    /**
     * A física a que as saídas se prendem, ou nulo no caso normal.
     *
     * Há objectivas que não abrem sozinhas e só se alcançam por dentro de uma lógica. Prende-se a
     * saída à física com `OutputConfiguration.setPhysicalCameraId` e o HAL entrega o mosaico dessa —
     * está medido neste corpo, com RAW e sem cache.
     */
    private val physicalId: String? = null,
) : Closeable {

    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** As da câmara que se abre. É a elas que o **pedido** obedece: as chaves disponíveis são as suas. */
    val characteristics: CameraCharacteristics = manager.getCameraCharacteristics(cameraId)

    /**
     * As da câmara que produz a **imagem**.
     *
     * Iguais às de cima no caso normal, e as da física quando a saída está presa a uma. A distinção
     * não é formalidade: as matrizes de cor, o nível de branco e o padrão do mosaico têm de vir do
     * sensor que tirou a fotografia. Usar as da lógica revelaria o mosaico de uma objectiva com a cor
     * de outra, e o erro passaria despercebido porque a imagem sairia plausível.
     */
    val imageCharacteristics: CameraCharacteristics =
        if (physicalId != null) manager.getCameraCharacteristics(physicalId) else characteristics

    /**
     * A exposição desta objectiva é nossa?
     *
     * Lê-se do sensor que tira a imagem, não da lógica. Falso manda o pedido pedir exposição
     * automática em vez de a desligar — sem isso ficava sem 3A e sem valores nossos, ou seja sem
     * exposição nenhuma.
     */
    val manualExposure: Boolean = temControloManual(imageCharacteristics)

    /** O foco é nosso? Capacidade à parte da exposição — ver `LensProfile.manualFocus`. */
    val manualFocus: Boolean = temControloDeFoco(imageCharacteristics)

    private val thread = HandlerThread("latente-cam-$cameraId").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var yuvReader: ImageReader? = null

    /**
     * Porque é que a câmara morreu, se morreu.
     *
     * Aprendido na primeira corrida da F1: pedir uma exposição acima do tecto declarado não é
     * cortado — o frame falha e o HAL **fecha o dispositivo**. Sem isto, tudo o que vem depois
     * rebenta com um `IllegalStateException` opaco em vez de dizer o que se passou.
     */
    @Volatile
    private var died: String? = null

    /**
     * O *listener* **avisa e nunca adquire**. Quem consome é que decide.
     *
     * Houve duas versões antes desta, e as duas partiram do mesmo erro: deixar o *listener* adquirir
     * imagens. Aprendido na F1 — durante o pedido repetido, guardar imagens sem as fechar esgota o
     * `maxImages` do `ImageReader`; à quarta, o `acquireNextImage` recusa e **o reader nunca volta a
     * notificar**. Depois o visor reutilizou essa fila e caiu no mesmo: a fase 1 correu 60 frames e a
     * fase 2 morreu ao décimo. Num visor a sério seria a imagem a congelar ao fim de uns segundos.
     *
     * E os dois caminhos não coexistiam: com o visor a consumir, a fila do disparo ficava vazia e o
     * sintoma era «resultado sem imagem», que manda procurar no sítio errado.
     *
     * Agora há um caminho só. O *listener* liberta uma permissão e mais nada; o consumidor adquire e
     * fecha, e escolhe o critério: o **mais recente** para o visor, que mostra o presente e não uma
     * fila do passado, ou **um timestamp concreto** para o disparo, que quer aquele frame.
     */
    private val disponivel = java.util.concurrent.Semaphore(0)

    /**
     * Contabilidade do stream, para quando ele parar e não se saber porquê.
     *
     * Numa corrida o consumidor deixou de receber frames enquanto o logcat mostrava a câmara a
     * produzir 313. A causa ficou por saber porque o `acquireLatestImage` estava dentro de um
     * `catch (t: Throwable) { null }` — o instrumento escondia exactamente a informação necessária.
     * Engolir excepções em código de diagnóstico é pior do que não ter diagnóstico.
     */
    @Volatile
    var streamAvisos = 0L
        private set

    @Volatile
    var streamEntregues = 0L
        private set

    @Volatile
    var streamVazios = 0L
        private set

    @Volatile
    var streamUltimaFalha: String? = null
        private set

    val isAlive: Boolean get() = device != null && died == null

    val deathReason: String? get() = died

    /**
     * O código de erro do `CameraDevice` por extenso.
     *
     * «erro 3» custou uma corrida de medição a interpretar, e a interpretação estava errada: atribuí-o
     * ao ecrã a apagar-se, e o logcat mostrou depois a câmara a produzir 313 frames com o ecrã aceso.
     * O erro era **consequência de nós fecharmos a sessão**, não a causa da paragem. Fica com o nome à
     * frente para a próxima pessoa não perder o mesmo tempo — e sem uma causa inventada ao lado dele.
     */
    private fun nomeDoErro(code: Int): String = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ->
            "ERROR_CAMERA_IN_USE ($code) — outra aplicação tem a câmara"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
            "ERROR_MAX_CAMERAS_IN_USE ($code)"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
            "ERROR_CAMERA_DISABLED ($code) — revogada por política do dispositivo"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE ->
            "ERROR_CAMERA_DEVICE ($code) — falha fatal do dispositivo"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
            "ERROR_CAMERA_SERVICE ($code) — falha do serviço de câmara"
        else -> "erro $code, desconhecido"
    }

    // -----------------------------------------------------------------------------------------

    fun open(timeoutMs: Long = 5000): String? {
        val latch = CountDownLatch(1)
        var error: String? = "sem resposta em ${timeoutMs} ms"

        val cb = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                error = null
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                died = "desligada pelo sistema"
                error = "desligada"
                camera.close()
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, code: Int) {
                died = "o HAL fechou o dispositivo: " + nomeDoErro(code)
                error = "erro de abertura: " + nomeDoErro(code)
                camera.close()
                latch.countDown()
            }
        }

        try {
            manager.openCamera(cameraId, cb, handler)
        } catch (e: SecurityException) {
            return "falta a permissão de câmara"
        } catch (e: Throwable) {
            return e.javaClass.simpleName + ": " + (e.message ?: "")
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return error
    }

    /**
     * @param yuvSize se não for nulo, acrescenta uma segunda saída YUV. Serve para responder à
     *   pergunta da F1: a combinação RAW + preview é aceite com uma só saída RAW?
     */
    fun configure(rawSize: Size, yuvSize: Size? = null, timeoutMs: Long = 5000): String? {
        val dev = device ?: return "câmara não aberta"

        val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3)
        raw.setOnImageAvailableListener({ _ ->
            streamAvisos++
            disponivel.release()
        }, handler)
        rawReader = raw

        val outputs = ArrayList<OutputConfiguration>()
        val saidaRaw = OutputConfiguration(raw.surface)
        physicalId?.let { saidaRaw.setPhysicalCameraId(it) }
        outputs.add(saidaRaw)

        if (yuvSize != null) {
            val yuv = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 3)
            // Não interessa o conteúdo: só saber se a combinação é aceite. Fechar logo, senão o
            // stream para.
            yuv.setOnImageAvailableListener({ r ->
                try {
                    r.acquireNextImage()?.close()
                } catch (t: Throwable) {
                    // ignorar
                }
            }, handler)
            yuvReader = yuv
            outputs.add(OutputConfiguration(yuv.surface))
        }

        val latch = CountDownLatch(1)
        var error: String? = "sem resposta em ${timeoutMs} ms"

        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                error = null
                latch.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                error = "configuração recusada — combinação de streams não suportada"
                latch.countDown()
            }
        }

        try {
            dev.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, executor, cb))
        } catch (e: Throwable) {
            return e.javaClass.simpleName + ": " + (e.message ?: "")
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return error
    }

    // -----------------------------------------------------------------------------------------

    /**
     * @param override aplicado **depois** do pedido limpo, para as experiências poderem desviar-se
     *   dele de propósito — comparar `SHADING_MODE` ligado e desligado, por exemplo. Em uso normal
     *   é nulo e o pedido limpo manda.
     */
    private fun buildRequest(
        e: Exposure,
        override: ((CaptureRequest.Builder) -> Unit)? = null,
    ): Pair<CaptureRequest.Builder, CleanOutcome> {
        val dev = device ?: throw IllegalStateException("câmara não aberta")
        // TEMPLATE_MANUAL existe precisamente para isto: nada de automático no arranque.
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL)
        rawReader?.let { b.addTarget(it.surface) }
        yuvReader?.let { b.addTarget(it.surface) }
        val outcome = CleanRequest.apply(b, characteristics, e, manualExposure, manualFocus)
        override?.invoke(b)
        return Pair(b, outcome)
    }

    /**
     * Deixa o sensor assentar nos parâmetros manuais antes do disparo.
     *
     * Conta apenas os frames que **já reportam os valores pedidos**, não frames quaisquer. A versão
     * anterior contava três resultados e seguia; numa sessão onde já corria um pedido repetido, os
     * três podiam ser frames velhos ainda com as definições anteriores, e a captura seguinte era
     * submetida a meio da transição — `onCaptureFailed` razão 0. Era isto que fazia falhar tudo
     * menos a primeira medição de cada sessão.
     */
    fun settle(
        e: Exposure,
        frames: Int = 4,
        timeoutMs: Long = 4000,
        override: ((CaptureRequest.Builder) -> Unit)? = null,
    ): SettleResult {
        // Sem controlo manual não há valores nossos por que esperar: o `matchesRequested` compararia o
        // que pedimos com o que o 3A escolheu e nunca coincidiria. Espera-se por frames, e mais nada.
        if (!manualExposure) return assentarSemManual(e, frames, timeoutMs, override)
        died?.let { return SettleResult(null, 0, "a câmara já tinha morrido: $it") }
        val s = session ?: return SettleResult(null, 0, "sessão não configurada")
        val latch = CountDownLatch(frames)
        var last: TotalCaptureResult? = null
        var error: String? = null
        var settled = false

        val cb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s2: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                last = result
                if (matchesRequested(result, e)) {
                    settled = true
                    latch.countDown()
                }
            }

            override fun onCaptureFailed(
                s2: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                error = "frame falhado, razão " + failure.reason
                latch.countDown()
            }
        }

        return try {
            val (b, _) = buildRequest(e, override)
            s.setRepeatingRequest(b.build(), cb, handler)
            val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done && error == null) {
                error = if (last == null) {
                    "nenhum frame chegou"
                } else {
                    "chegaram frames, mas nenhum com os valores pedidos"
                }
            }
            died?.let { error = "a câmara morreu: $it" }
            SettleResult(last, frames - latch.count.toInt(), error, settled)
        } catch (t: Throwable) {
            SettleResult(null, 0, t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    /**
     * Um disparo, um frame.
     *
     * Pára o pedido repetido e esvazia a fila antes de disparar, para que o frame recolhido seja
     * garantidamente o da captura e não um do visor. Depois emparelha imagem e resultado pelo
     * timestamp do sensor.
     */
    fun captureOne(
        e: Exposure,
        timeoutMs: Long = 6000,
        override: ((CaptureRequest.Builder) -> Unit)? = null,
    ): Pair<RawFrame?, String?> {
        died?.let { return Pair(null, "a câmara já tinha morrido: $it") }
        val s = session ?: return Pair(null, "sessão não configurada")

        // Não se pára o pedido repetido.
        //
        // Uma versão antiga parava-o e disparava logo a seguir; o pipeline ainda estava a esvaziar e o
        // HAL descartava o pedido — `onCaptureFailed` razão 0, ou um resultado sem imagem nenhuma.
        // Manter o repetido a correr e intercalar a captura é o padrão normal do Camera2, e a imagem
        // certa identifica-se pelo timestamp do sensor.
        //
        // Limpa-se o que estiver pendente para que os frames a considerar sejam todos posteriores ao
        // disparo. Isto funciona com o visor a correr: o consumo é o mesmo em ambos.
        drainImages()

        val latch = CountDownLatch(1)
        var result: TotalCaptureResult? = null
        var error: String? = null

        val cb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s2: CameraCaptureSession,
                request: CaptureRequest,
                r: TotalCaptureResult,
            ) {
                result = r
                latch.countDown()
            }

            override fun onCaptureFailed(
                s2: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                error = "captura falhada, razão " + failure.reason
                latch.countDown()
            }
        }

        val outcome: CleanOutcome
        try {
            val built = buildRequest(e, override)
            outcome = built.second
            s.capture(built.first.build(), cb, handler)
        } catch (t: Throwable) {
            return Pair(null, t.javaClass.simpleName + ": " + (t.message ?: ""))
        }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        died?.let { return Pair(null, "a câmara morreu durante a captura: $it") }
        val r = result ?: return Pair(null, error ?: "sem resultado de captura")

        // Percorre-se por ordem, e não pelo mais recente: procura-se **aquele** frame. Os que não
        // servem são fechados pelo `consumir`, o que devolve lugares ao reader.
        val querido = r.get(TotalCaptureResult.SENSOR_TIMESTAMP)
        var ultimo: Image? = null
        var vistos = 0
        val img = consumir(
            timeoutMs,
            maisRecente = false,
            // Guarda-se o mais recente dos recusados, para o caso de o certo nunca aparecer. É
            // recurso, e o `RawFrame` di-lo: um frame do visor tem os mesmos parâmetros manuais, mas
            // não é o frame que se pediu.
            descartado = { candidato ->
                ultimo?.close()
                ultimo = candidato
            },
        ) { candidato ->
            vistos++
            querido != null && candidato.timestamp == querido
        }

        if (img != null) {
            ultimo?.close()
            return Pair(RawFrame(img, r, outcome), null)
        }
        val recurso = ultimo
        if (recurso != null) {
            return Pair(RawFrame(recurso, r, outcome, matchedByTimestamp = false), null)
        }
        return Pair(null, "resultado sem imagem — o ImageReader não entregou nenhum frame em " +
                timeoutMs + " ms (vistos: " + vistos + ")")
    }

    /**
     * Esperar por frames, quando não há valores nossos por que esperar.
     *
     * O `settle` normal conta os frames que **já reportam o que se pediu**. Numa objectiva sem controlo
     * manual isso nunca acontece — o 3A escolhe o que quer —, e esperar por coincidência daria sempre
     * o erro «chegaram frames, mas nenhum com os valores pedidos». Aqui conta-se o que faz sentido
     * contar: que o 3A teve tempo de assentar.
     */
    private fun assentarSemManual(
        e: Exposure,
        frames: Int,
        timeoutMs: Long,
        override: ((CaptureRequest.Builder) -> Unit)?,
    ): SettleResult {
        died?.let { return SettleResult(null, 0, "a câmara já tinha morrido: $it") }
        val s = session ?: return SettleResult(null, 0, "sessão não configurada")
        val latch = CountDownLatch(frames)
        var last: TotalCaptureResult? = null
        var error: String? = null
        val cb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s2: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                last = result
                latch.countDown()
            }

            override fun onCaptureFailed(
                s2: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                error = "frame falhado, razão " + failure.reason
                latch.countDown()
            }
        }
        return try {
            val (b, _) = buildRequest(e, override)
            s.setRepeatingRequest(b.build(), cb, handler)
            val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done && error == null) error = "não chegaram frames suficientes"
            died?.let { error = "a câmara morreu: $it" }
            SettleResult(last, frames - latch.count.toInt(), error, done)
        } catch (t: Throwable) {
            SettleResult(null, 0, t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    /**
     * O último resultado do pedido repetido.
     *
     * Existe por causa das objectivas onde a exposição não é nossa: ali, o que se pede não é o que se
     * aplica, e a telemetria tem de mostrar o que a câmara **usou** e não o que nós sugerimos. Sem
     * isto o visor anunciaria um tempo que o ficheiro não leva — a mentira que este projeto existe
     * para não contar.
     */
    @Volatile
    var ultimoResultado: TotalCaptureResult? = null
        private set

    private val guardaResultado = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            ultimoResultado = result
        }
    }

    private fun temControloDeFoco(ch: CameraCharacteristics): Boolean {
        val minimo = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (minimo <= 0f) return false
        return try {
            ch.availableCaptureRequestKeys.contains(CaptureRequest.LENS_FOCUS_DISTANCE)
        } catch (t: Throwable) {
            false
        }
    }

    private fun temControloManual(ch: CameraCharacteristics): Boolean {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        if (!caps.contains(
                android.hardware.camera2.CameraMetadata
                    .REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            return false
        }
        val chaves = try {
            ch.availableCaptureRequestKeys
        } catch (t: Throwable) {
            return false
        }
        return chaves.contains(CaptureRequest.SENSOR_EXPOSURE_TIME) &&
                chaves.contains(CaptureRequest.SENSOR_SENSITIVITY)
    }

    /** O resultado reporta os valores que se pediram? Tolerância de 2% no tempo. */
    private fun matchesRequested(result: TotalCaptureResult, e: Exposure): Boolean {
        val t = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME)
        if (t != null) {
            val delta = Math.abs(t - e.exposureNs).toDouble()
            if (delta > e.exposureNs * 0.02) return false
        }
        val iso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY)
        if (iso != null && iso != e.iso) return false
        return true
    }

    // -----------------------------------------------------------------------------------------
    // Consumo contínuo, para o visor
    // -----------------------------------------------------------------------------------------

    /**
     * Arranca o consumo contínuo, para o visor.
     *
     * Assume que o pedido repetido já está a correr — quem chama faz `settle` primeiro, para o sensor
     * já estar nos valores manuais quando se começa a contar.
     */
    fun startStream() {
        drainImages()
    }

    /**
     * O frame mais recente, ou nulo se não chegar nenhum no prazo. Quem recebe **tem de o fechar**.
     *
     * Devolve o mais recente e não o mais antigo, de propósito: um visor que mostrasse a fila do
     * passado acumularia atraso em vez de largar frames, e um visor atrasado é pior do que um visor
     * com menos frames.
     */
    fun nextImage(timeoutMs: Long = 1000): Image? = consumir(timeoutMs, maisRecente = true) { true }

    fun stopStream() {
        drainImages()
    }

    /**
     * O consumidor único: espera por aviso, adquire, e devolve o primeiro que sirva.
     *
     * @param maisRecente `true` salta os frames em atraso e traz o presente — é o visor. `false`
     *   percorre-os por ordem, que é o que o disparo precisa para encontrar um timestamp concreto.
     * @param aceita o frame serve? Quem devolve `true` fica com a posse.
     * @param descartado recebe a **posse** dos que não servem, e passa a ser responsável por os
     *   fechar. Se for nulo, fecham-se aqui. Ter isto explícito não é cerimónia: a primeira versão
     *   guardava um frame recusado enquanto este método o fechava, o que é uso depois de fechar.
     */
    private fun consumir(
        timeoutMs: Long,
        maisRecente: Boolean,
        descartado: ((Image) -> Unit)? = null,
        aceita: (Image) -> Boolean,
    ): Image? {
        val fim = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val restam = (fim - System.nanoTime()) / 1_000_000L
            if (restam <= 0) return null
            if (!disponivel.tryAcquire(restam, TimeUnit.MILLISECONDS)) return null

            val img = try {
                if (maisRecente) {
                    // Uma permissão por frame produzido, mas isto traz um e descarta o resto — as
                    // permissões em atraso deixam de corresponder a coisa nenhuma.
                    disponivel.drainPermits()
                    rawReader?.acquireLatestImage()
                } else {
                    rawReader?.acquireNextImage()
                }
            } catch (t: Throwable) {
                streamUltimaFalha = t.javaClass.simpleName + ": " + (t.message ?: "")
                null
            }

            if (img == null) {
                // Há permissões mas não há imagem: o reader já descartou o que elas contavam. Sem
                // limpar, o laço girava a consumi-las sem nunca bloquear.
                streamVazios++
                disponivel.drainPermits()
                continue
            }
            if (aceita(img)) {
                streamEntregues++
                return img
            }
            if (descartado != null) descartado(img) else img.close()
        }
    }

    /** O que se passou no stream, para o relatório. */
    fun streamDiagnostico(): String =
        "avisos do reader " + streamAvisos + " · entregues " + streamEntregues +
                " · pedidos vazios " + streamVazios +
                " · permissões pendentes " + disponivel.availablePermits() +
                (streamUltimaFalha?.let { " · última falha: $it" } ?: " · sem falhas")

    /**
     * Muda o pedido repetido, sem esperar por nada.
     *
     * O visor precisa de mexer na exposição ao vivo, e por isso não pode usar o `settle`, que bloqueia
     * até os valores chegarem. Quem chama tem de contar com **latência de pipeline**: os frames já em
     * voo continuam a trazer a exposição antiga, e medir um desses depois de mudar faz o fotómetro
     * corrigir duas vezes o mesmo erro e oscilar.
     */
    fun updateRepeating(e: Exposure, override: ((CaptureRequest.Builder) -> Unit)? = null): String? {
        died?.let { return "a câmara morreu: $it" }
        val s = session ?: return "sessão não configurada"
        return try {
            val (b, _) = buildRequest(e, override)
            s.setRepeatingRequest(b.build(), guardaResultado, handler)
            null
        } catch (t: Throwable) {
            t.javaClass.simpleName + ": " + (t.message ?: "")
        }
    }

    fun stopRepeating() {
        try {
            session?.stopRepeating()
        } catch (t: Throwable) {
            // ignorar
        }
    }

    /**
     * Larga tudo o que o reader tenha pendente e põe as permissões a zero.
     *
     * Faz-se antes de um disparo, para que os frames a considerar sejam todos posteriores ao pedido, e
     * ao arrancar e parar o stream. Adquirir e fechar é o que devolve lugares ao `ImageReader`; não
     * chega esquecer as permissões.
     */
    private fun drainImages() {
        val r = rawReader ?: return
        while (true) {
            val img = try {
                r.acquireNextImage()
            } catch (t: Throwable) {
                null
            } ?: break
            img.close()
        }
        disponivel.drainPermits()
    }

    override fun close() {
        stopRepeating()
        drainImages()
        try {
            session?.close()
        } catch (t: Throwable) {
            // ignorar
        }
        try {
            device?.close()
        } catch (t: Throwable) {
            // ignorar
        }
        try {
            rawReader?.close()
        } catch (t: Throwable) {
            // ignorar
        }
        try {
            yuvReader?.close()
        } catch (t: Throwable) {
            // ignorar
        }
        session = null
        device = null
        rawReader = null
        yuvReader = null
        thread.quitSafely()
    }
}
