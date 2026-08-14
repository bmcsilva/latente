package io.github.bmcsilva.latente.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.bmcsilva.latente.camera.CameraSession
import io.github.bmcsilva.latente.camera.HalClamp
import io.github.bmcsilva.latente.camera.RequestPlan
import io.github.bmcsilva.latente.camera.Planner
import io.github.bmcsilva.latente.diag.Certificate
import io.github.bmcsilva.latente.diag.Experiments
import io.github.bmcsilva.latente.diag.GpuCheck
import io.github.bmcsilva.latente.diag.PhysicalCameraProbe
import io.github.bmcsilva.latente.diag.PreviewProbe
import io.github.bmcsilva.latente.export.DngWriter
import io.github.bmcsilva.latente.export.Json
import io.github.bmcsilva.latente.export.MediaStoreOut
import io.github.bmcsilva.latente.export.Node
import io.github.bmcsilva.latente.export.Sidecar
import io.github.bmcsilva.latente.export.Txt
import io.github.bmcsilva.latente.model.Body
import io.github.bmcsilva.latente.model.LensProfile
import io.github.bmcsilva.latente.render.DevelopSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * F1: um botão, parâmetros manuais, escrita de DNG. Sem visor.
 *
 * Interface em Views puros de propósito. É andaime de verificação — em F3/F4 é substituída pelo
 * visor a sério em Compose. Não vale a pena investir numa UI que vai ser deitada fora.
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var status: TextView
    private val buttons = ArrayList<Button>()

    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var body: Body? = null
    private var lens: LensProfile? = null
    private var shotCounter = 1

    /**
     * Permite disparar a verificação por adb, sem tocar no ecrã:
     * `am start -n <pkg>/.ui.MainActivity -e auto experiencias`
     */
    private var auto: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        // O ecrã fica aceso enquanto a aplicação estiver à frente, que é o que uma aplicação de câmara
        // precisa: uma medição longa ou uma exposição de 1,8 s não podem depender do temporizador do
        // ecrã. (Chegou aqui por outra razão — atribuí um `ERROR_CAMERA_DISABLED` ao ecrã a apagar-se,
        // e estava errado; o erro era do nosso fecho da sessão. A bandeira ficou porque é certa por si.)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pad = dp(10)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        // O relatório em cima, a ocupar o espaço todo; os controlos em baixo, ao alcance do polegar.
        // A primeira versão tinha os botões no topo e o título grande do One UI tapava-os.
        output = TextView(this)
        output.typeface = Typeface.MONOSPACE
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        output.setTextIsSelectable(true)
        output.text = "—"

        val hs = HorizontalScrollView(this)
        hs.addView(output)
        val vs = ScrollView(this)
        vs.addView(hs)
        root.addView(vs, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        status.setPadding(0, dp(6), 0, dp(6))
        status.text = "a inspeccionar o corpo…"
        root.addView(status)

        val rowA = LinearLayout(this)
        rowA.orientation = LinearLayout.HORIZONTAL
        rowA.addView(button("Objectiva ▸") { nextLens() }, weight())
        rowA.addView(button("Disparar → DNG") { shoot() }, weight())
        root.addView(rowA)

        val rowB = LinearLayout(this)
        rowB.orientation = LinearLayout.HORIZONTAL
        rowB.addView(button("Série de exposições") { shootBracket() }, weight())
        root.addView(rowB)

        val rowC = LinearLayout(this)
        rowC.orientation = LinearLayout.HORIZONTAL
        rowC.addView(button("Correr as 9 experiências") { runExperiments() }, weight())
        root.addView(rowC)

        val rowD = LinearLayout(this)
        rowD.orientation = LinearLayout.HORIZONTAL
        rowD.addView(button("Revelar · CPU vs GPU") { develop() }, weight())
        root.addView(rowD)

        val rowE = LinearLayout(this)
        rowE.orientation = LinearLayout.HORIZONTAL
        rowE.addView(button("Medir o visor RAW") { measurePreview() }, weight())
        rowE.addView(button("Abrir o visor") { openViewfinder() }, weight())
        rowE.addView(button("Certificado") { certificar() }, weight())
        rowE.addView(button("Físicas") { fisicas() }, weight())
        rowE.addView(button("Negativos") {
            startActivity(android.content.Intent(this, LibraryActivity::class.java))
        }, weight())
        // Abre o visor a registar temperatura, bateria e fps de dez em dez segundos. A pergunta é o
        // que acontece ao fim de vinte minutos, e por isso o botão está aqui e não no visor: é uma
        // medição, e as medições vivem neste ecrã.
        rowE.addView(button("Uso prolongado") {
            val i = android.content.Intent(this, ViewfinderActivity::class.java)
            i.putExtra("registar", "uso")
            startActivity(i)
        }, weight())
        root.addView(rowE)

        // Desde o Android 15 com targetSdk 35+, o edge-to-edge é obrigatório: sem isto o conteúdo
        // desenha por baixo da barra de estado e da barra de navegação.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }

        setContentView(root)

        auto = intent?.getStringExtra("auto")

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            inspect()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, granted: IntArray) {
        super.onRequestPermissionsResult(code, perms, granted)
        if (granted.isNotEmpty() && granted[0] == PackageManager.PERMISSION_GRANTED) {
            inspect()
        } else {
            status.text = "sem permissão de câmara — a F1 não pode correr"
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun inspect() {
        busy(true, "a inspeccionar o corpo…")
        worker.execute {
            val b = Body(this)
            ui.post {
                body = b
                val usable = b.usable()
                lens = usable.firstOrNull { it.facing == 1 } ?: usable.firstOrNull()
                val sb = StringBuilder()
                sb.append(b.deviceLabel()).append("\n\n")
                b.error?.let { sb.append("ERRO: ").append(it).append("\n\n") }
                sb.append("Objectivas utilizáveis:\n")
                for (l in usable) sb.append("  ").append(l.label).append("\n")
                if (b.rejected().isNotEmpty()) {
                    sb.append("\nRecusadas:\n")
                    for (l in b.rejected()) {
                        sb.append("  ").append(l.label).append(" — falta ")
                            .append(l.blocking.joinToString(", ")).append("\n")
                    }
                }
                if (b.physicalOnly.isNotEmpty()) {
                    sb.append("\nSó dentro de uma lógica: ")
                        .append(b.physicalOnly.joinToString(", ")).append("\n")
                }
                output.text = sb.toString()
                busy(false, describeSelection())

                when (auto) {
                    "experiencias" -> {
                        auto = null
                        runExperiments()
                    }
                    "disparar" -> {
                        auto = null
                        shoot()
                    }
                    "revelar" -> {
                        auto = null
                        develop()
                    }
                    "visor" -> {
                        auto = null
                        measurePreview()
                    }
                    "certificado" -> {
                        auto = null
                        certificar()
                    }
                    "fisicas" -> {
                        auto = null
                        fisicas()
                    }
                }
            }
        }
    }

    private fun nextLens() {
        val usable = body?.usable() ?: return
        if (usable.isEmpty()) return
        val i = usable.indexOfFirst { it.cameraId == lens?.cameraId }
        lens = usable[(i + 1) % usable.size]
        status.text = describeSelection()
    }

    private fun describeSelection(): String {
        val l = lens ?: return "sem objectiva utilizável neste corpo"
        return l.label + " · " + l.usefulBits + " bits · tecto " +
                (l.exposureMaxNs / 1_000_000) + " ms · ISO " + l.isoMin + "–" + l.isoMax +
                " (analógico até " + l.maxAnalogIso + ")"
    }

    // -----------------------------------------------------------------------------------------

    private fun shoot() {
        val profile = lens ?: return
        val corpo = body ?: return
        busy(true, "a disparar…")
        worker.execute {
            val log = StringBuilder()
            val plan = Planner.plan(profile, profile.defaultExposure())
            log.append("Objectiva: ").append(profile.label).append("\n")
            log.append("Exposição: ").append(plan.effective.describe()).append("\n\n")
            withFreshSession(profile, log) { session ->
                captureAndWrite(session, profile, corpo, plan, log)
            }
            finishShot(log.toString(), "concluído")
        }
    }

    /**
     * Série de exposições, uma sessão por valor.
     *
     * Existe por necessidade: a F1 tem os parâmetros no código e não há visor, portanto não há como
     * saber se a exposição serve para a cena. Foi isto que faltou para medir uma chapa plana num
     * ecrã branco, onde 8 ms a ISO 50 corta o sinal e a medição fica sem valor.
     *
     * Uma sessão nova por valor porque mudar a exposição a meio de uma sessão é frágil.
     */
    private fun shootBracket() {
        val profile = lens ?: return
        val corpo = body ?: return
        busy(true, "a disparar série…")
        worker.execute {
            val log = StringBuilder()
            log.append("Série em ").append(profile.label).append("\n")
            val tempos = longArrayOf(250_000L, 1_000_000L, 4_000_000L, 16_000_000L, 62_500_000L)
            for (i in tempos.indices) {
                val t = tempos[i]
                ui.post { status.text = "série " + (i + 1) + "/" + tempos.size + "…" }
                val base = profile.defaultExposure()
                val plan = Planner.plan(profile, base.copy(
                    exposureNs = t,
                    frameDurationNs = HalClamp.frameDuration(t, profile.minFrameDurationNs)))
                log.append("\n=== ").append(plan.effective.describe()).append(" ===\n")
                withFreshSession(profile, log) { session ->
                    captureAndWrite(session, profile, corpo, plan, log)
                }
            }
            finishShot(log.toString(), "série concluída · " + tempos.size + " ficheiros")
        }
    }

    /** Abre, configura, corre e fecha. Uma sessão por disparo, que é o que se provou robusto. */
    private fun withFreshSession(
        profile: LensProfile,
        log: StringBuilder,
        block: (CameraSession) -> Unit,
    ) {
        var session: CameraSession? = null
        try {
            session = CameraSession(this, profile.openId, profile.physicalId)
            val abertura = session.open()
            if (abertura != null) {
                log.append("abertura falhou: ").append(abertura).append("\n")
                return
            }
            val config = session.configure(profile.rawSize)
            if (config != null) {
                log.append("configuração falhou: ").append(config).append("\n")
                return
            }
            block(session)
        } catch (t: Throwable) {
            log.append("EXCEPÇÃO: ").append(t.javaClass.simpleName).append(": ")
                .append(t.message ?: "").append("\n")
        } finally {
            session?.close()
        }
    }

    private fun captureAndWrite(
        session: CameraSession,
        profile: LensProfile,
        corpo: Body,
        plan: RequestPlan,
        log: StringBuilder,
    ) {
        val settle = session.settle(plan.effective)
        if (!settle.settledAtRequested) {
            log.append("não assentou nos valores pedidos: ").append(settle.error ?: "?").append("\n")
            return
        }
        val (frame, erro) = session.captureOne(plan.effective)
        session.stopRepeating()
        if (frame == null) {
            log.append("captura falhou: ").append(erro).append("\n")
            return
        }
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val nome = String.format(Locale.US, "LTNT_%04d_%s", shotCounter, stamp)
            val sidecar = Sidecar.build(
                nome, profile, plan, frame.result, frame.outcome, corpo.deviceLabel(),
                frame.matchedByTimestamp)
            val resumo = "Latente F1 · " + profile.label + " · " + plan.effective.describe()
            val dng = DngWriter.write(
                this, session.imageCharacteristics, frame.result, frame.image, "$nome.dng", resumo)
            MediaStoreOut(this).writeText("$nome.json", "application/json", Json.write(sidecar))
            log.append(nome).append(".dng  (~").append(dng.bytes / 1024 / 1024).append(" MB)")
            if (!frame.matchedByTimestamp) log.append("  [frame não emparelhado]")
            log.append("\n")
            shotCounter++
        } catch (t: Throwable) {
            log.append("escrita falhou: ").append(t.javaClass.simpleName).append(": ")
                .append(t.message ?: "").append("\n")
        } finally {
            frame.close()
        }
    }

    private fun finishShot(text: String, statusText: String) {
        ui.post {
            output.text = text
            busy(false, statusText)
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun runExperiments() {
        val profile = lens ?: return
        busy(true, "a correr as experiências…")
        worker.execute {
            val node = try {
                Experiments(this, profile).runAll { message ->
                    ui.post { status.text = message }
                }
            } catch (t: Throwable) {
                val n = Node("ERRO nas experiências")
                n.put("excepção", t.toString())
                n.put("stack", t.stackTraceToString())
                n
            }
            val text = Txt.write(node)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val base = "latente-f1-experiencias-$stamp"
            var saved = "não guardado"
            try {
                MediaStoreOut(this).writeText("$base.txt", "text/plain", text)
                MediaStoreOut(this).writeText("$base.json", "application/json", Json.write(node))
                saved = "guardado em Downloads/Latente/$base.{txt,json}"
            } catch (t: Throwable) {
                saved = "falhou a guardar: " + t.message
            }
            ui.post {
                output.text = text
                busy(false, saved)
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    /**
     * Revela o último DNG pelos dois caminhos e mede a diferença.
     *
     * É o critério de aceitação da F2 reduzido a um botão: o CPU em Kotlin está provado por 118
     * testes na JVM, o shader não se pode correr sem GPU — mas os dois estão aqui, e podem ser
     * postos a discordar. Sai também o TIFF de 16 bits, que é o produto da fase.
     */
    private fun develop() {
        busy(true, "a revelar…")
        worker.execute {
            val node = try {
                GpuCheck.run(this, DevelopSettings()) { message ->
                    ui.post { status.text = message }
                }
            } catch (t: Throwable) {
                val n = Node("ERRO na revelação")
                n.put("excepção", t.toString())
                n.put("stack", t.stackTraceToString())
                n
            }
            val text = Txt.write(node)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            var saved = "não guardado"
            try {
                MediaStoreOut(this).writeText(
                    "latente-f2-revelacao-$stamp.txt", "text/plain", text)
                saved = "guardado em Downloads/Latente/latente-f2-revelacao-$stamp.txt"
            } catch (t: Throwable) {
                saved = "falhou a guardar: " + t.message
            }
            ui.post {
                output.text = text
                busy(false, saved)
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    /**
     * Mede se o visor pode ser alimentado pelo stream RAW.
     *
     * Cada frame RAW são 25 MB a atravessar a fronteira CPU → GPU. Mede-se antes de construir a UI da
     * F3, porque a resposta muda o que se constrói.
     */
    private fun measurePreview() {
        val profile = lens ?: return
        busy(true, "a medir o visor…")
        worker.execute {
            val node = try {
                PreviewProbe.run(this, profile) { message -> ui.post { status.text = message } }
            } catch (t: Throwable) {
                val n = Node("ERRO na medição do visor")
                n.put("excepção", t.toString())
                n.put("stack", t.stackTraceToString())
                n
            }
            val text = Txt.write(node)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            var saved = "não guardado"
            try {
                MediaStoreOut(this).writeText("latente-f3-visor-$stamp.txt", "text/plain", text)
                saved = "guardado em Downloads/Latente/latente-f3-visor-$stamp.txt"
            } catch (t: Throwable) {
                saved = "falhou a guardar: " + t.message
            }
            ui.post {
                output.text = text
                busy(false, saved)
            }
        }
    }

    /**
     * O certificado anti-mastigação: cada promessa do produto ligada à prova dela.
     *
     * É o critério da F6, e existe porque uma promessa que não se pode verificar é publicidade.
     */
    /**
     * O ensaio das objectivas físicas: dá para lá chegar pela câmara lógica?
     *
     * Fica no banco de ensaios e não no visor porque é uma pergunta sobre o **corpo**, respondida uma
     * vez por modelo de telefone. O visor só precisa de saber a conclusão.
     */
    private fun fisicas() {
        busy(true, "a ensaiar as objectivas físicas…")
        worker.execute {
            val node = try {
                PhysicalCameraProbe.run(this) { m -> ui.post { status.text = m } }
            } catch (t: Throwable) {
                val n = Node("ERRO no ensaio das físicas")
                n.put("excepção", t.toString())
                n.put("stack", t.stackTraceToString())
                n
            }
            val text = Txt.write(node)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            var saved = "não guardado"
            try {
                MediaStoreOut(this).writeText("latente-fisicas-$stamp.txt", "text/plain", text)
                MediaStoreOut(this).writeText(
                    "latente-fisicas-$stamp.json", "application/json", Json.write(node))
                saved = "guardado em Downloads/Latente/latente-fisicas-$stamp.{txt,json}"
            } catch (t: Throwable) {
                saved = "falhou a guardar: " + t.message
            }
            ui.post {
                output.text = text
                busy(false, saved)
            }
        }
    }

    private fun certificar() {
        val profile = lens ?: return
        busy(true, "a certificar…")
        worker.execute {
            val node = try {
                Certificate.run(this, profile) { m -> ui.post { status.text = m } }
            } catch (t: Throwable) {
                val n = Node("ERRO no certificado")
                n.put("excepção", t.toString())
                n.put("stack", t.stackTraceToString())
                n
            }
            val text = Txt.write(node)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            var saved = "não guardado"
            try {
                MediaStoreOut(this).writeText(
                    "latente-certificado-$stamp.txt", "text/plain", text)
                MediaStoreOut(this).writeText(
                    "latente-certificado-$stamp.json", "application/json", Json.write(node))
                saved = "guardado em Downloads/Latente/latente-certificado-$stamp.{txt,json}"
            } catch (t: Throwable) {
                saved = "falhou a guardar: " + t.message
            }
            ui.post {
                output.text = text
                busy(false, saved)
            }
        }
    }

    /** O visor a sério: o stream RAW revelado pelo nosso pipeline, no ecrã. */
    private fun openViewfinder() {
        startActivity(android.content.Intent(this, ViewfinderActivity::class.java))
    }

    // -----------------------------------------------------------------------------------------

    private fun busy(on: Boolean, message: String) {
        status.text = message
        for (b in buttons) b.isEnabled = !on
    }

    private fun button(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { action() }
        buttons.add(b)
        return b
    }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
