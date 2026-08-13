package io.github.bmcsilva.latente.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.bmcsilva.latente.export.DngReader
import io.github.bmcsilva.latente.export.Library
import io.github.bmcsilva.latente.export.MediaStoreOut
import io.github.bmcsilva.latente.export.SidecarKeys
import io.github.bmcsilva.latente.export.SidecarRead
import io.github.bmcsilva.latente.export.Tiff16Writer
import io.github.bmcsilva.latente.render.Present
import io.github.bmcsilva.latente.render.RawPipeline
import io.github.bmcsilva.latente.render.ShadingProfile
import java.util.concurrent.Executors

/**
 * A biblioteca: os negativos que estão no disco, e o que se pode fazer com eles.
 *
 * Cada linha é **uma fotografia**, não um ficheiro — mostra os três papéis lado a lado, negativo,
 * receita e cópia revelada, e diz quais existem. Listar `.dng`, `.json` e `.tif` como entradas soltas
 * esconderia que são a mesma coisa.
 *
 * A acção que interessa é revelar: pega no negativo e na receita que ficou ao lado dele e produz o
 * TIFF de 16 bits. **A receita vem do sidecar** e não das definições actuais da aplicação — quem
 * revela um negativo de há um mês quer a revelação daquele dia, não a de hoje.
 */
class LibraryActivity : Activity() {

    private lateinit var lista: LinearLayout
    private lateinit var estado: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Uma revelação escreve 71 MB e leva alguns segundos. Se o ecrã se apagar a meio, a actividade
        // sai de cena e o trabalho perde-se — aconteceu, e é evitável com uma linha.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL

        estado = TextView(this)
        estado.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        estado.text = "a ler a pasta…"

        lista = LinearLayout(this)
        lista.orientation = LinearLayout.VERTICAL

        val scroll = ScrollView(this)
        scroll.addView(lista)
        raiz.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        raiz.addView(estado)

        raiz.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left + dp(10), b.top + dp(6), b.right + dp(10), b.bottom + dp(6))
            insets
        }
        setContentView(raiz)
        carregar()
    }

    /**
     * @param manterEstado depois de revelar, o resultado fica na linha de estado em vez de ser
     *   substituído pela contagem. Uma acção que apaga a sua própria mensagem parece não ter
     *   acontecido — e foi o que aconteceu no primeiro ensaio: o TIFF saiu e o ecrã dizia só quantas
     *   fotografias havia.
     */
    private fun carregar(manterEstado: Boolean = false) {
        worker.execute {
            val fotos = Library.shots(this)
            ui.post {
                lista.removeAllViews()
                if (fotos.isEmpty()) {
                    estado.text = "não há negativos em Downloads/Latente"
                    return@post
                }
                for (f in fotos) lista.addView(linha(f))
                if (!manterEstado) estado.text = contagem(fotos)
            }
        }
    }

    private fun contagem(fotos: List<Library.Shot>): String {
        val n = fotos.size
        val r = fotos.count { it.developed }
        return (if (n == 1) "1 fotografia" else "$n fotografias") + " · " +
                (if (r == 1) "1 revelada" else "$r reveladas")
    }

    private fun linha(f: Library.Shot): LinearLayout {
        val caixa = LinearLayout(this)
        caixa.orientation = LinearLayout.HORIZONTAL
        caixa.setPadding(0, dp(4), 0, dp(4))

        val texto = TextView(this)
        texto.typeface = Typeface.MONOSPACE
        texto.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        val papeis = StringBuilder()
        papeis.append("negativo ").append(f.sizeBytes / 1024 / 1024).append(" MB")
        papeis.append(if (f.hasRecipe) " · receita" else " · SEM RECEITA")
        papeis.append(if (f.developed) " · revelado" else "")
        texto.text = f.baseName + "\n" + papeis
        caixa.addView(texto, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Tocar no nome abre a análise, ali mesmo. Um ecrã à parte para isto seria pior: o que se
        // quer é comparar uma fotografia com a de cima, e para isso têm de estar as duas na lista.
        texto.setOnClickListener { alternarAnalise(f, caixa) }

        val b = Button(this)
        b.text = if (f.developed) "Revelar outra vez" else "Revelar"
        b.isAllCaps = false
        b.setOnClickListener {
            b.isEnabled = false
            revelar(f) { b.isEnabled = true }
        }
        caixa.addView(b)
        return caixa
    }

    /**
     * Revela um negativo com a receita que ficou ao lado dele.
     *
     * Revela-se no **CPU**, e não na GPU, por dois motivos: é o caminho que produz 16 bits, e os dois
     * já estão provados a concordar a 1 em 255 pelo botão da F2. Chamar a GPU aqui só para deitar o
     * resultado fora custaria 50 MB e 300 ms — num sítio onde já houve falta de memória.
     */
    private fun revelar(f: Library.Shot, aoTerminar: () -> Unit) {
        estado.text = "a trazer " + f.baseName + "…"
        worker.execute {
            var resumo: String
            try {
                val dng = Library.fetch(this, f.dngId, f.baseName + ".dng")
                    ?: throw IllegalStateException("o negativo não veio do MediaStore")
                // O sidecar lê-se **uma vez**. Havia três `readText` do mesmo ficheiro — receita, id da
                // câmara e orientação —, e três oportunidades de as três leituras discordarem.
                val receita = f.jsonId?.let { Library.fetch(this, it, f.baseName + ".json") }
                val texto = receita?.readText()
                val settings = SidecarRead.develop(texto)

                ui.post { estado.text = "a revelar " + f.baseName + "…" }
                val reader = DngReader.open(dng)
                val perfil = ShadingProfile.forDevice(
                    Build.MODEL, SidecarRead.cameraId(texto) ?: "")
                val linear = RawPipeline.develop(reader, perfil, settings)
                val nome = f.baseName + ".tif"
                // A mesma etiqueta de orientação do negativo: o TIFF sai igualmente na orientação do
                // sensor, e sem ela sairia deitado como o DNG saía antes.
                val orientacao = Present.exifOrientation(SidecarRead.rotationDegrees(texto))
                MediaStoreOut(this).write(nome, "image/tiff") { out ->
                    Tiff16Writer.write(out, linear, Tiff16Writer.iccFor(settings.output),
                        "Latente · revelado da receita do sidecar", orientacao)
                }
                resumo = nome + " escrito · " + settings.kelvin + " K · " +
                        String.format(java.util.Locale.US, "%+.2f EV", settings.exposureEv)
            } catch (t: Throwable) {
                resumo = "falhou: " + t.javaClass.simpleName + ": " + (t.message ?: "")
            }
            ui.post {
                estado.text = resumo
                aoTerminar()
                carregar(manterEstado = true)
            }
        }
    }

    /**
     * Mostra ou esconde a análise de uma fotografia.
     *
     * Não é o sidecar despejado: é a leitura dele. Por ordem de importância, o que interessa a quem
     * abre um negativo meses depois é **se o que está no ficheiro é o que se pediu** — se o frame é o
     * da captura, onde o HAL divergiu do pedido, e o que ele faz pelas costas. A receita vem depois,
     * porque essa já se sabe que está lá.
     */
    private fun alternarAnalise(f: Library.Shot, caixa: LinearLayout) {
        val pai = caixa.parent as? LinearLayout ?: return
        val indice = pai.indexOfChild(caixa)
        val seguinte = if (indice + 1 < pai.childCount) pai.getChildAt(indice + 1) else null
        if (seguinte is TextView && seguinte.tag == "analise") {
            pai.removeView(seguinte)
            return
        }
        val id = f.jsonId
        if (id == null) {
            estado.text = f.baseName + " não tem receita — foi disparado antes da F5?"
            return
        }
        worker.execute {
            val ficheiro = Library.fetch(this, id, f.baseName + ".json")
            val texto = if (ficheiro == null) {
                "a receita não veio do MediaStore"
            } else {
                try {
                    Analise.ler(ficheiro.readText())
                } catch (t: Throwable) {
                    "a receita não se leu: " + t.javaClass.simpleName
                }
            }
            ui.post {
                val v = TextView(this)
                v.tag = "analise"
                v.typeface = Typeface.MONOSPACE
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                v.setTextIsSelectable(true)
                v.setPadding(dp(8), 0, 0, dp(8))
                v.text = texto
                val i2 = pai.indexOfChild(caixa)
                if (i2 >= 0) pai.addView(v, i2 + 1)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

/**
 * A leitura do sidecar, para o ecrã de análise.
 *
 * Usa o `org.json` da plataforma — está no Android desde sempre e não é dependência nenhuma. Onde ao
 * `SidecarRead` bastam expressões dirigidas para meia dúzia de campos conhecidos, aqui percorre-se a
 * árvore para a mostrar, e para isso um leitor a sério é mais simples. É também a razão de esta parte
 * não estar no `SidecarRead`: o `org.json` não existe nos testes de JVM, e o que se lê para revelar tem
 * de ser testável — o que se lê só para mostrar ao utilizador não precisa.
 */
private object Analise {

    fun ler(json: String): String {
        val raiz = org.json.JSONObject(json)
        val sb = StringBuilder()

        // 1. O frame é o que se pediu? É a primeira pergunta porque é a única que invalida tudo.
        val casado = raiz.optBoolean("imagem casada com o resultado pelo timestamp", true)
        sb.append(if (casado) {
            "frame casado com o pedido pelo timestamp\n"
        } else {
            "ATENÇÃO: o frame não é o do pedido — é um frame do visor, de outro instante\n"
        })
        raiz.optString("fase").takeIf { it.isNotEmpty() }?.let {
            sb.append("origem: ").append(it).append("\n")
        }

        val filhos = raiz.optJSONArray("filhos") ?: return sb.toString()
        for (i in 0 until filhos.length()) {
            val c = filhos.optJSONObject(i) ?: continue
            when (c.optString("nome")) {
                "Pedido vs aplicado" -> divergencias(sb, c)
                "Revelação" -> bloco(sb, "Receita", c, listOf(
                    SidecarKeys.DEVELOP_EV, SidecarKeys.KELVIN,
                    SidecarKeys.SHADING_STRENGTH, SidecarKeys.ROLLOFF, "espaço de saída"))
                "Avisos de honestidade" -> avisos(sb, c)
            }
        }
        return sb.toString()
    }

    /**
     * Só o que **divergiu**. Repetir os valores que coincidiram enchia o ecrã de linhas sem
     * informação, e o que interessa é o que o HAL fez diferente do que lhe pedimos.
     */
    private fun divergencias(sb: StringBuilder, c: org.json.JSONObject) {
        sb.append("\nPedido vs aplicado\n")
        val t = c.optLong("tempo enviado ns")
        val tHal = c.optLong("tempo aplicado pelo HAL ns")
        sb.append(String.format(java.util.Locale.US, "  tempo   %.3f ms", tHal / 1e6))
        if (t != tHal) sb.append(String.format(java.util.Locale.US, "  (pedimos %.3f)", t / 1e6))
        sb.append("\n")

        val iso = c.optInt("ISO enviado")
        val isoHal = c.optInt("ISO aplicado pelo HAL")
        sb.append("  ISO     ").append(isoHal)
        if (iso != isoHal) sb.append("  (pedimos ").append(iso).append(")")
        sb.append("\n")

        sb.append("  balanço ").append(c.optInt("balanço de brancos escolhido K")).append(" K")
        val tinta = c.optDouble("tinta escolhida", 0.0)
        if (Math.abs(tinta) > 0.005) {
            sb.append(String.format(java.util.Locale.US, " tinta %+.2f", tinta))
        }
        sb.append("\n")

        if (c.optBoolean("tempo cortado pelo HAL") || c.optBoolean("ISO cortado pelo HAL")) {
            sb.append("  o HAL cortou o que lhe pedimos\n")
        }
        if (c.optBoolean("tempo cortado por nós") || c.optBoolean("ISO cortado por nós")) {
            sb.append("  cortámos antes de enviar, para o HAL não descartar o frame\n")
        }
    }

    private fun bloco(
        sb: StringBuilder,
        titulo: String,
        c: org.json.JSONObject,
        chaves: List<String>,
    ) {
        sb.append("\n").append(titulo).append("\n")
        for (k in chaves) {
            if (!c.has(k)) continue
            sb.append("  ").append(k).append(": ").append(c.get(k)).append("\n")
        }
    }

    private fun avisos(sb: StringBuilder, c: org.json.JSONObject) {
        val a = c.optJSONArray("avisos") ?: return
        if (a.length() == 0) return
        sb.append("\nO que o HAL faz pelas nossas costas\n")
        for (i in 0 until a.length()) sb.append("  · ").append(a.optString(i)).append("\n")
    }
}
