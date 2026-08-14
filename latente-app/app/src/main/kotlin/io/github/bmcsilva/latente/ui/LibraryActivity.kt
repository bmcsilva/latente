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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.bmcsilva.latente.export.DngReader
import io.github.bmcsilva.latente.export.Library
import io.github.bmcsilva.latente.export.MediaStoreOut
import io.github.bmcsilva.latente.export.SidecarKeys
import io.github.bmcsilva.latente.export.RgbBitmap
import io.github.bmcsilva.latente.export.SidecarRead
import io.github.bmcsilva.latente.export.Thumbnails
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

    private companion object {
        // As entradas do menu da fotografia. Números e não posições: a ordem muda, o significado não.
        const val VER_NEGATIVO = 0
        const val VER_JPEG = 1
        const val VER_TIFF = 2
        const val ANALISE = 3
        const val REVELAR_TIFF = 4
        const val REVELAR_JPEG = 5
        const val APAGAR_COPIAS = 6
        const val APAGAR_TUDO = 7
    }

    private lateinit var lista: LinearLayout
    private lateinit var estado: TextView
    private lateinit var titulo: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /**
     * As miniaturas têm fio próprio.
     *
     * Uma revelação a sério leva segundos, e se as miniaturas fossem na mesma fila ficavam atrás dela
     * — a lista aparecia vazia enquanto uma fotografia se revelava. Um fio só, e não vários, porque o
     * que as limita é ler o negativo do disco: pô-las a correr em paralelo só multiplicava a memória.
     */
    private val fioDasMiniaturas = Executors.newSingleThreadExecutor()

    override fun onDestroy() {
        super.onDestroy()
        // Dois fios por cada vez que este ecrã abre, e o processo é o mesmo: sem isto ficavam a
        // acumular threads paradas até a aplicação morrer.
        worker.shutdown()
        fioDasMiniaturas.shutdown()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Sem a barra do sistema, como no visor: uma faixa cinzenta a dizer «Latente · negativos» por
        // cima de um ecrã preto que já diz «NEGATIVOS» é a mesma informação duas vezes, e a segunda
        // custa 150 px de lista.
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        // Uma revelação escreve 71 MB e leva alguns segundos. Se o ecrã se apagar a meio, a actividade
        // sai de cena e o trabalho perde-se — aconteceu, e é evitável com uma linha.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setBackgroundColor(Palette.PRETO)

        // A faixa de cima, como no visor: uma etiqueta pequena e cinzenta, o valor por baixo.
        titulo = TextView(this)
        titulo.typeface = Typeface.MONOSPACE
        titulo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        titulo.setTextColor(android.graphics.Color.WHITE)
        titulo.text = "—"
        val etiqueta = TextView(this)
        etiqueta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        etiqueta.setTextColor(Palette.CINZA)
        etiqueta.letterSpacing = 0.12f
        etiqueta.text = "NEGATIVOS"
        val nomes = LinearLayout(this)
        nomes.orientation = LinearLayout.VERTICAL
        nomes.addView(etiqueta)
        nomes.addView(titulo)

        // O caminho de volta, à vista.
        //
        // Havia só o gesto do sistema, e um ecrã que se abre por um botão tem de se fechar por outro —
        // ainda mais neste, onde se entra a partir da câmara e se quer voltar a ela depressa.
        val voltar = TextView(this)
        voltar.text = "‹"
        voltar.gravity = android.view.Gravity.CENTER
        voltar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        voltar.setTextColor(android.graphics.Color.WHITE)
        voltar.isClickable = true
        voltar.background = MoonBackground(
            Palette.PASTILHA, Palette.CONTORNO, dp(1).toFloat(), 0f, 0f)
        voltar.setOnClickListener { finish() }

        val faixa = LinearLayout(this)
        faixa.orientation = LinearLayout.HORIZONTAL
        faixa.gravity = android.view.Gravity.CENTER_VERTICAL
        faixa.setPadding(dp(6), dp(2), dp(6), dp(8))
        val lpVoltar = LinearLayout.LayoutParams(dp(44), dp(44))
        lpVoltar.rightMargin = dp(12)
        faixa.addView(voltar, lpVoltar)
        faixa.addView(nomes, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        estado = TextView(this)
        estado.typeface = Typeface.MONOSPACE
        estado.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        estado.setTextColor(Palette.CINZA)
        estado.setPadding(dp(6), dp(6), dp(6), 0)
        estado.text = "a ler a pasta…"

        lista = LinearLayout(this)
        lista.orientation = LinearLayout.VERTICAL

        val scroll = ScrollView(this)
        scroll.addView(lista)
        raiz.addView(faixa)
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
                for (f in fotos) {
                    if (lista.childCount > 0) lista.addView(risco())
                    lista.addView(linha(f))
                }
                titulo.text = contagem(fotos)
                if (!manterEstado) estado.text = ""
            }
        }
    }

    private fun contagem(fotos: List<Library.Shot>): String {
        val n = fotos.size
        val r = fotos.count { it.developed }
        return (if (n == 1) "1 fotografia" else "$n fotografias") + " · " +
                (if (r == 1) "1 revelada" else "$r reveladas")
    }

    /**
     * Uma fotografia na lista: a miniatura, o nome, quando foi, e que papéis existem.
     *
     * A miniatura primeiro, e é o que muda tudo: uma lista de nomes obriga a abrir para saber o que
     * lá está, e ninguém reconhece uma fotografia por `LTNT_0007`. Sai da mesma revelação que o TIFF,
     * com a receita do sidecar — ver `Thumbnails`.
     */
    private fun linha(f: Library.Shot): LinearLayout {
        val caixa = LinearLayout(this)
        caixa.orientation = LinearLayout.HORIZONTAL
        caixa.gravity = android.view.Gravity.CENTER_VERTICAL
        caixa.setPadding(dp(6), dp(6), dp(6), dp(6))

        val mini = android.widget.ImageView(this)
        mini.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        // O lugar da miniatura está sempre lá, com ou sem ela: uma lista cujas linhas mudam de altura
        // conforme as imagens chegam salta debaixo do dedo.
        //
        // Cantos pouco redondos, e não a pastilha do resto da aplicação: uma fotografia com as pontas
        // arredondadas como um botão deixa de se ler como fotografia.
        val moldura = android.graphics.drawable.GradientDrawable()
        moldura.setColor(Palette.PASTILHA)
        moldura.setStroke(dp(1), Palette.CONTORNO)
        moldura.cornerRadius = dp(4).toFloat()
        mini.background = moldura
        mini.clipToOutline = true
        val lpMini = LinearLayout.LayoutParams(dp(72), dp(54))
        lpMini.rightMargin = dp(10)
        caixa.addView(mini, lpMini)
        mostrarMiniatura(f, mini)

        val coluna = LinearLayout(this)
        coluna.orientation = LinearLayout.VERTICAL

        // O número grande e o carimbo de tempo apagado atrás dele.
        //
        // O nome inteiro não cabe na linha — `LTNT_0001_20260813-163835` são 25 caracteres — e cortado
        // ao fim ficava `…-16383`, que é o pior sítio para cortar. Assim o que identifica lê-se
        // inteiro, o resto acompanha em cinzento, e se faltar largura o que se perde é a parte que a
        // linha de baixo já diz por extenso.
        val nome = TextView(this)
        nome.typeface = Typeface.MONOSPACE
        nome.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        nome.setTextColor(android.graphics.Color.WHITE)
        nome.maxLines = 1
        nome.ellipsize = android.text.TextUtils.TruncateAt.END
        val corte = f.baseName.indexOf('_', f.baseName.indexOf('_') + 1)
        if (corte > 0) {
            val t = android.text.SpannableString(
                f.baseName.substring(0, corte) + "  " + f.baseName.substring(corte + 1))
            t.setSpan(android.text.style.RelativeSizeSpan(0.75f), corte, t.length, 0)
            t.setSpan(android.text.style.ForegroundColorSpan(Palette.APAGADO), corte, t.length, 0)
            nome.text = t
        } else {
            nome.text = f.baseName
        }
        coluna.addView(nome)

        val quando = TextView(this)
        quando.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        quando.setTextColor(Palette.CINZA)
        quando.letterSpacing = 0.08f
        quando.text = data(f.dateSeconds) + "  ·  " + (f.sizeBytes / 1024 / 1024) + " MB"
        coluna.addView(quando)

        // Os três papéis, e os que faltam **também aparecem**, apagados. Esconder o que falta faz uma
        // fotografia sem receita parecer igual a uma com receita, e não é igual: não se pode revelar
        // como foi vista.
        val papeis = TextView(this)
        papeis.typeface = Typeface.MONOSPACE
        papeis.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        papeis.letterSpacing = 0.1f
        papeis.setPadding(0, dp(3), 0, 0)
        val selos = android.text.SpannableStringBuilder()
        // O selo do negativo diz **como** ele está guardado: solto, ou dentro do arquivo comprimido.
        selo(selos, if (f.archived) "ZIP" else "DNG", true)
        selo(selos, "RCP", f.hasRecipe)
        selo(selos, "TIFF", f.tifId != null)
        selo(selos, "JPEG", f.jpgId != null)
        papeis.text = selos
        coluna.addView(papeis)

        caixa.addView(coluna, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // A linha inteira abre o menu da fotografia.
        //
        // Havia um botão «REVELAR» ao lado, e a lista passou a ter mais coisas para fazer do que
        // revelar: ver, analisar, apagar as cópias, apagar tudo. Um botão por acção não cabe na linha,
        // e escolher qual delas merece botão era escolher mal. O menu diz o que há, mostra apagado o
        // que não se pode fazer e porquê, e devolve a linha à fotografia.
        caixa.isClickable = true
        caixa.setOnClickListener { menuDaFotografia(caixa, f) }
        return caixa
    }

    /**
     * Tudo o que se pode fazer a uma fotografia, num menu só.
     *
     * O que não se pode fazer **aparece na mesma**, apagado e com a razão à frente — é a regra dos
     * menus desta aplicação, e aqui vale por uma razão nova: uma fotografia a que falta o negativo
     * tem de o dizer, senão parece uma fotografia inteira que se recusa a abrir.
     */
    private fun menuDaFotografia(ancora: android.view.View, f: Library.Shot) {
        // Os tamanhos que o menu promete saem do **negativo cru**, e o que a lista sabe é o tamanho no
        // disco — que num arquivo é a versão comprimida. As duas razões são medidas: o arquivo fica a
        // cerca de 28% do cru, o TIFF de 16 bits é três vezes o cru (três canais de dois bytes contra
        // um de dois), e o JPEG a 95 deu 3,1 MB para um negativo de 24. São estimativas, e é por isso
        // que levam til à frente.
        val cru = if (f.archived) f.sizeBytes * 7 / 2 else f.sizeBytes
        val megas = cru / 1024 / 1024
        val opcoes = listOf(
            Opcao(VER_NEGATIVO, "VER O NEGATIVO",
                if (f.orphan) "o negativo foi apagado" else "revelado por nós, na galeria",
                disponivel = !f.orphan),
            Opcao(VER_JPEG, "VER O JPEG",
                if (f.jpgId == null) "ainda não revelado em JPEG" else "na galeria do telefone",
                disponivel = f.jpgId != null),
            Opcao(VER_TIFF, "VER O TIFF",
                if (f.tifId == null) "ainda não revelado em TIFF" else "na galeria do telefone",
                disponivel = f.tifId != null),
            Opcao(ANALISE, "ANÁLISE",
                if (f.hasRecipe) "o que o HAL fez pelas costas" else "sem receita ao lado",
                disponivel = f.hasRecipe),
            Opcao(REVELAR_TIFF, "REVELAR EM TIFF",
                if (f.orphan) "sem negativo não há revelação"
                else "arquivo · sem perdas · ~" + (megas * 3) + " MB",
                activa = f.tifId != null, disponivel = !f.orphan),
            Opcao(REVELAR_JPEG, "REVELAR EM JPEG",
                if (f.orphan) "sem negativo não há revelação"
                else "para ver e partilhar · ~" + Math.max(megas / 8, 1) + " MB",
                activa = f.jpgId != null, disponivel = !f.orphan),
            Opcao(APAGAR_COPIAS, "APAGAR AS CÓPIAS",
                if (f.developed) "fica o negativo e a receita" else "não há cópias",
                disponivel = f.developed),
            Opcao(APAGAR_TUDO, "APAGAR TUDO",
                if (f.orphan) "o que resta desta fotografia" else "negativo, receita e cópias"))
        PickerPopup.mostrar(this, ancora, opcoes, multipla = false) { qual ->
            when (qual) {
                VER_NEGATIVO -> verNegativo(f)
                VER_JPEG -> verNaGaleria(f.jpgId, "image/jpeg")
                VER_TIFF -> verNaGaleria(f.tifId, "image/tiff")
                ANALISE -> alternarAnalise(f, ancora as LinearLayout)
                REVELAR_TIFF -> revelar(f, false) {}
                REVELAR_JPEG -> revelar(f, true) {}
                APAGAR_COPIAS -> apagar(f, true)
                else -> apagar(f, false)
            }
        }
    }

    /**
     * Manda a galeria do telefone abrir uma cópia revelada.
     *
     * A galeria é melhor visualizador do que qualquer um que eu escrevesse, e é a que o utilizador já
     * sabe usar. O JPEG abre em qualquer uma; o TIFF de 16 bits pode não abrir em nenhuma, e nesse
     * caso diz-se — em vez de não acontecer nada, que é como uma aplicação parece avariada.
     */
    private fun verNaGaleria(id: Long?, tipo: String) {
        if (id == null) return
        val uri = android.content.ContentUris.withAppendedId(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
        i.setDataAndType(uri, tipo)
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (t: android.content.ActivityNotFoundException) {
            estado.text = "não há no telefone nada que abra " + tipo
        }
    }

    /**
     * Revela o negativo para uma imagem temporária e manda a galeria mostrá-la.
     *
     * Ideia do utilizador: em vez de escrever um visualizador, extrai-se o que for preciso e usa-se o
     * do telefone. O que se extrai é a **nossa** revelação, e não o mosaico — uma galeria a mostrar um
     * DNG mostra a ideia que o fabricante tem dele.
     */
    private fun verNegativo(f: Library.Shot) {
        estado.text = "a revelar " + f.baseName + " para ver…"
        fioDasMiniaturas.execute {
            val ficheiro = Thumbnails.previsualizacao(this, f)
            ui.post {
                if (ficheiro == null) {
                    estado.text = "não se conseguiu revelar " + f.baseName
                    return@post
                }
                estado.text = ""
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
                i.setDataAndType(
                    io.github.bmcsilva.latente.export.PreviewProvider.uri(ficheiro.name),
                    "image/jpeg")
                i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(i)
                } catch (t: android.content.ActivityNotFoundException) {
                    estado.text = "não há no telefone nada que abra imagens"
                }
            }
        }
    }

    /**
     * Apaga, e diz quanto libertou.
     *
     * Apaga mesmo, sem passar pela reciclagem: quem apaga um negativo quer o espaço agora, e não daqui
     * a trinta dias. É por isso que o menu diz sempre o que leva — a escolha é a confirmação.
     */
    private fun apagar(f: Library.Shot, soCopias: Boolean) {
        worker.execute {
            val quantos = Library.apagar(this, f, soCopias)
            if (!soCopias) Thumbnails.invalidate(this, f.baseName)
            ui.post {
                estado.text = if (quantos == 0) {
                    "não se apagou nada — o dono dos ficheiros perdeu-se numa reinstalação?"
                } else {
                    f.baseName + " · " + quantos + (if (quantos == 1) " ficheiro" else " ficheiros") +
                            " apagados"
                }
                carregar(manterEstado = true)
            }
        }
    }

    /** O risco entre duas fotografias: sem ele, duas linhas seguidas lêem-se como uma. */
    private fun risco(): android.view.View {
        val v = android.view.View(this)
        v.setBackgroundColor(Palette.CONTORNO)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        return v
    }

    /** Um papel: aceso quando o ficheiro existe, apagado quando falta. */
    private fun selo(sb: android.text.SpannableStringBuilder, nome: String, existe: Boolean) {
        val inicio = sb.length
        if (sb.isNotEmpty()) sb.append("  ")
        sb.append(nome)
        sb.setSpan(android.text.style.ForegroundColorSpan(
            if (existe) Palette.CIANO else Palette.APAGADO), inicio, sb.length, 0)
    }

    /**
     * A miniatura que já está feita aparece já; a que falta é revelada em segundo plano.
     *
     * A vista fica etiquetada com o nome da fotografia porque as linhas se refazem — depois de uma
     * revelação, por exemplo — e uma miniatura que chega tarde não pode ir parar à linha errada.
     */
    private fun mostrarMiniatura(f: Library.Shot, alvo: android.widget.ImageView) {
        alvo.tag = f.baseName
        val feita = Thumbnails.cached(this, f.baseName)
        if (feita != null) {
            alvo.setImageBitmap(feita)
            return
        }
        alvo.setImageBitmap(null)
        fioDasMiniaturas.execute {
            val b = Thumbnails.ensure(this, f) ?: return@execute
            ui.post { if (alvo.tag == f.baseName) alvo.setImageBitmap(b) }
        }
    }

    private fun data(segundos: Long): String {
        if (segundos <= 0L) return "—"
        val f = java.text.SimpleDateFormat(
            "d 'de' MMMM 'às' HH:mm", java.util.Locale.forLanguageTag("pt-PT"))
        return f.format(java.util.Date(segundos * 1000L))
    }

    /**
     * Revela um negativo com a receita que ficou ao lado dele.
     *
     * Revela-se no **CPU**, e não na GPU, por dois motivos: é o caminho que produz 16 bits, e os dois
     * já estão provados a concordar a 1 em 255 pelo botão da F2. Chamar a GPU aqui só para deitar o
     * resultado fora custaria 50 MB e 300 ms — num sítio onde já houve falta de memória.
     */
    private fun revelar(f: Library.Shot, jpeg: Boolean, aoTerminar: () -> Unit) {
        estado.text = "a trazer " + f.baseName + "…"
        worker.execute {
            var resumo: String
            try {
                val dng = Library.negativo(this, f)
                    ?: throw IllegalStateException("o negativo não veio do MediaStore")
                // O sidecar lê-se **uma vez**. Havia três `readText` do mesmo ficheiro — receita, id da
                // câmara e orientação —, e três oportunidades de as três leituras discordarem.
                val texto = Library.receita(this, f)
                val settings = SidecarRead.develop(texto)

                ui.post { estado.text = "a revelar " + f.baseName + "…" }
                val reader = DngReader.open(dng)
                val perfil = ShadingProfile.forDevice(
                    Build.MODEL, SidecarRead.cameraId(texto) ?: "")
                var linear: io.github.bmcsilva.latente.render.Rgb? =
                    RawPipeline.develop(reader, perfil, settings)
                // A mesma etiqueta de orientação do negativo: a cópia sai igualmente na orientação do
                // sensor, e sem ela sairia deitada como o DNG saía antes.
                val orientacao = Present.exifOrientation(SidecarRead.rotationDegrees(texto))
                val nome = f.baseName + (if (jpeg) ".jpg" else ".tif")
                if (jpeg) {
                    // O linear larga-se **antes** de comprimir: são 150 MB de vírgula flutuante, e o
                    // `Bitmap` que os substitui são 48. Segurá-los aos dois é onde a memória falta.
                    val bitmap = RgbBitmap.encode(linear!!)
                    linear = null
                    val direito = RgbBitmap.rotate(bitmap, SidecarRead.rotationDegrees(texto))
                    MediaStoreOut(this).write(nome, "image/jpeg") { out ->
                        direito.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                } else {
                    MediaStoreOut(this).write(nome, "image/tiff") { out ->
                        Tiff16Writer.write(out, linear!!, Tiff16Writer.iccFor(settings.output),
                            "Latente · revelado da receita do sidecar", orientacao)
                    }
                }
                resumo = nome + " escrito · " + settings.kelvin + " K · " +
                        String.format(java.util.Locale.US, "%+.2f EV", settings.exposureEv)
                // O negativo veio para a cache para se poder ler às saltas, e sai de lá agora: são
                // 24 MB por revelação, e ficavam a acumular num telefone onde o espaço é o problema
                // que estamos a tentar resolver.
                dng.delete()
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
        // Pela etiqueta e não pelo tipo: a análise passou a vir dentro de uma caixa, e um teste por
        // tipo deixou de fechar o que abria — tocar outra vez empilhava painéis.
        if (seguinte != null && seguinte.tag == "analise") {
            pai.removeView(seguinte)
            return
        }
        if (f.jsonId == null && f.zipId == null) {
            estado.text = f.baseName + " não tem receita — foi disparado antes da F5?"
            return
        }
        worker.execute {
            val json = Library.receita(this, f)
            val texto = if (json == null) {
                "a receita não veio do MediaStore"
            } else {
                try {
                    Analise.ler(json)
                } catch (t: Throwable) {
                    "a receita não se leu: " + t.javaClass.simpleName
                }
            }
            ui.post {
                val v = TextView(this)
                v.tag = "analise"
                v.typeface = Typeface.MONOSPACE
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                v.setTextColor(Palette.CINZA)
                v.setTextIsSelectable(true)
                v.setPadding(dp(10), dp(8), dp(10), dp(10))
                // O nome inteiro do ficheiro abre a análise: é aqui que ele serve, e é a razão de a
                // linha lá em cima poder mostrar só o número.
                val cabeca = android.text.SpannableStringBuilder(f.baseName + "\n\n")
                cabeca.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE),
                    0, f.baseName.length, 0)
                cabeca.append(texto)
                v.text = cabeca
                // Recuado e com fundo próprio: pertence à linha de cima e não é mais uma fotografia.
                val caixaDaAnalise = LinearLayout(this)
                caixaDaAnalise.tag = "analise"
                caixaDaAnalise.setPadding(dp(72 + 16), 0, 0, dp(10))
                val fundo = android.graphics.drawable.GradientDrawable()
                fundo.setColor(Palette.PASTILHA)
                fundo.cornerRadius = dp(6).toFloat()
                v.background = fundo
                caixaDaAnalise.addView(v, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val i2 = pai.indexOfChild(caixa)
                if (i2 >= 0) pai.addView(caixaDaAnalise, i2 + 1)
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
