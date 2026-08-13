package io.github.bmcsilva.latente.probe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Interface deliberadamente feia: esta é uma ferramenta de diagnóstico, não o produto.
 * Sem Compose, sem AppCompat, sem dependências — só a plataforma.
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var btnRun: Button
    private lateinit var btnSave: Button
    private lateinit var btnOpen: Button
    private lateinit var btnShare: Button

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var report: Node? = null
    private var lastTxtUri: Uri? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        val pad = dp(12)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "LATENTE · Sonda de capacidades"
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        root.addView(title)

        status = TextView(this)
        status.text = "F0 da especificação. Corre a sonda e guarda o relatório."
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        status.setPadding(0, dp(4), 0, dp(8))
        root.addView(status)

        val rowA = LinearLayout(this)
        rowA.orientation = LinearLayout.HORIZONTAL
        btnRun = button("Correr sonda") { runProbe() }
        btnSave = button("Guardar") { save() }
        rowA.addView(btnRun, equalWeight())
        rowA.addView(btnSave, equalWeight())
        root.addView(rowA)

        val rowB = LinearLayout(this)
        rowB.orientation = LinearLayout.HORIZONTAL
        btnOpen = button("Testar abertura") { testOpen() }
        btnShare = button("Partilhar") { share() }
        rowB.addView(btnOpen, equalWeight())
        rowB.addView(btnShare, equalWeight())
        root.addView(rowB)

        btnSave.isEnabled = false
        btnShare.isEnabled = false

        output = TextView(this)
        output.typeface = Typeface.MONOSPACE
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        output.setTextIsSelectable(true)
        output.setPadding(0, dp(10), 0, dp(10))
        output.text = "—"

        // O relatório tem linhas longas: precisa de rolamento nos dois eixos.
        val hs = HorizontalScrollView(this)
        hs.addView(output)
        val vs = ScrollView(this)
        vs.addView(hs)
        root.addView(vs, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    // -----------------------------------------------------------------------

    private fun runProbe() {
        busy(true, "a sondar…")
        io.execute {
            val result = try {
                CapabilityProbe(this).run()
            } catch (e: Throwable) {
                val n = Node("ERRO na sonda")
                n.put("excepção", e.toString())
                n.put("stack", e.stackTraceToString())
                n
            }
            val text = Txt.write(result)
            ui.post {
                report = result
                output.text = text
                btnSave.isEnabled = true
                busy(false, "sonda concluída · " + result.children.size + " secções")
            }
        }
    }

    private fun testOpen() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast("Falta a permissão de câmara")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        busy(true, "a tentar abrir cada câmara…")
        io.execute {
            val probe = CapabilityProbe(this)
            val node = try {
                probe.testOpen()
            } catch (e: Throwable) {
                val n = Node("ERRO no teste de abertura")
                n.put("excepção", e.toString())
                n
            }
            ui.post {
                val current = report
                if (current != null) {
                    current.children.add(node)
                    output.text = Txt.write(current)
                } else {
                    output.text = Txt.write(node)
                }
                btnSave.isEnabled = true
                busy(false, "teste de abertura concluído")
            }
        }
    }

    private fun save() {
        val current = report ?: return
        busy(true, "a escrever…")
        io.execute {
            val r = ReportWriter(this).write(current)
            ui.post {
                if (r.error != null) {
                    busy(false, "FALHOU: " + r.error)
                    toast("Não foi possível guardar")
                } else {
                    lastTxtUri = r.txtUri
                    btnShare.isEnabled = true
                    busy(false, "guardado em Downloads/Latente/" + r.baseName + ".{txt,json}")
                    toast("Guardado")
                }
            }
        }
    }

    private fun share() {
        val uri = lastTxtUri ?: return
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.putExtra(Intent.EXTRA_SUBJECT, "Latente · sonda de capacidades")
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Enviar relatório"))
    }

    // -----------------------------------------------------------------------

    private fun busy(on: Boolean, message: String) {
        status.text = message
        btnRun.isEnabled = !on
        btnOpen.isEnabled = !on
        if (on) {
            btnSave.isEnabled = false
            btnShare.isEnabled = false
        }
    }

    private fun button(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { action() }
        return b
    }

    private fun equalWeight(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.gravity = Gravity.CENTER_VERTICAL
        return lp
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
