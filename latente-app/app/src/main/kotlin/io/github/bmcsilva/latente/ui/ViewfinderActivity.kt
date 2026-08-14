package io.github.bmcsilva.latente.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.bmcsilva.latente.camera.CameraSession
import io.github.bmcsilva.latente.camera.HalClamp
import io.github.bmcsilva.latente.camera.Planner
import io.github.bmcsilva.latente.export.Archive
import io.github.bmcsilva.latente.export.DngWriter
import io.github.bmcsilva.latente.export.Json
import io.github.bmcsilva.latente.export.MediaStoreOut
import io.github.bmcsilva.latente.export.Sidecar
import io.github.bmcsilva.latente.model.Body
import io.github.bmcsilva.latente.model.ExposureProgram
import io.github.bmcsilva.latente.model.Meter
import io.github.bmcsilva.latente.diag.UsageLog
import io.github.bmcsilva.latente.model.Settings
import io.github.bmcsilva.latente.model.LensProfile
import io.github.bmcsilva.latente.render.Demosaic
import io.github.bmcsilva.latente.render.DevelopSettings
import io.github.bmcsilva.latente.render.GlPreview
import io.github.bmcsilva.latente.render.GlUniforms
import io.github.bmcsilva.latente.render.Present
import io.github.bmcsilva.latente.render.ShadingProfile

/**
 * O visor: o stream RAW revelado pelo nosso pipeline, no ecrã.
 *
 * O que se vê aqui **não passa pelo ISP do fabricante**. Vem do mosaico do sensor pelos mesmos
 * shaders que produzem o ficheiro, com a mesma matriz de cor, o mesmo balanço e a mesma correcção de
 * vinhetagem. É o *Setting Effect ON* de uma Sony, feito por nós porque o telefone não o oferece.
 *
 * A única diferença permitida face ao ficheiro é a resolução: o visor agrupa cada quadrado 2×2 do
 * mosaico num pixel, o que é média de amostras verdadeiras e não invenção. Existe por causa da taxa
 * de frames, e está medido — 19 a 28 fps, limitado pela câmara e não por nós.
 *
 * Toda a vida do GL corre no fio de render, porque um contexto EGL pertence à thread que o torna
 * corrente. A actividade só lhe entrega a superfície e recolhe o estado.
 */
class ViewfinderActivity : Activity() {

    private companion object {
        /** 12000 K e 2000 K, em mired. A gama fotográfica útil, das sombras azuis à vela. */
        const val MIRED_MIN = 83f
        const val MIRED_MAX = 500f

        // As posições do anel. Tempo e ISO **só existem no modo que as possui**: em P é a aplicação que
        // as escolhe, e um comando que não muda nada é pior do que comando nenhum — ensina que os modos
        // não querem dizer nada. Em S o tempo é do utilizador; em M o tempo e o ISO.
        const val ANEL_FOCO = 0
        const val ANEL_KELVIN = 1
        const val ANEL_TINTA = 2
        const val ANEL_EV = 3
        const val ANEL_TEMPO = 4
        const val ANEL_ISO = 5
        const val ANEL_POSICOES = 6

        /**
         * A mordida, em dp. São medidas de uma só conta e por isso ficam juntas.
         *
         * O disparador tem 76 dp de vista e 74 de círculo desenhado; o círculo que morde é o dele mais
         * uma folga, `LUA_RAIO`. Um arco de raio 39 não atravessa uma aresta maior do que 78 dp — daí a
         * pastilha vertical ter a largura do disparador e não mais.
         *
         * `LUA_FUNDO` é o que resta de conta feita: com a corda a valer a aresta toda (76 menos o
         * contorno), o centro do círculo fica 10,5 dp fora da pastilha, e 39 − 10,5 dá os 28 que a
         * concavidade come. É também o quanto a pastilha sobe para encostar ao disparador.
         */
        const val LUA_RAIO = 39
        const val LUA_LARGURA = 76
        const val LUA_ALTURA = 64
        const val LUA_FUNDO = 28
        /** Em retrato a aresta mordida é a altura, 44 dp, e o arco cabe nela com 8 de profundidade. */
        const val LUA_MORDIDA = 8

        // As ajudas, em bits: combinam-se, e por isso não são um índice.
        const val AJUDA_PICOS = 1
        const val AJUDA_ZEBRAS = 2
        const val AJUDA_HISTOGRAMA = 4
        const val AJUDA_NIVEL = 8

        /**
         * Uma cor por parâmetro, e a barra toma a do que está escolhido.
         *
         * Com a máquina ao olho não se lê o nome da pastilha: vê-se a cor da barra pelo canto do olho e
         * já se sabe o que o dedo vai mexer. As cores não são arbitrárias — o foco leva o ciano do
         * realce de picos, a temperatura leva um tom quente porque é disso que fala, e a tinta leva o
         * magenta do eixo que percorre.
         */
        fun corDoParametro(i: Int): Int = when (i) {
            ANEL_FOCO -> 0xFF00FFF2.toInt()
            ANEL_KELVIN -> 0xFFFFC069.toInt()
            ANEL_TINTA -> 0xFFE87AD8.toInt()
            ANEL_EV -> 0xFFE8EAED.toInt()
            ANEL_TEMPO -> 0xFF7FB3FF.toInt()
            else -> 0xFF9CE07A.toInt()
        }

        // A paleta mudou-se para o `Palette`, que é onde a biblioteca também lhe chega. Os nomes
        // ficam: são cento e tal usos neste ficheiro, e trocá-los não muda um pixel.
        const val PRETO = Palette.PRETO
        const val CINZA = Palette.CINZA
        const val CIANO = Palette.CIANO
        const val AMBAR = Palette.AMBAR
    }

    private lateinit var superficie: SurfaceView
    private lateinit var estado: TextView
    private lateinit var vinhetaView: TextView
    private lateinit var fpsView: TextView
    private lateinit var avisosView: TextView
    private lateinit var campoModo: TextView
    private lateinit var campoMargem: TextView
    private lateinit var campoCortado: TextView
    private lateinit var campoTempo: TextView
    private lateinit var campoAbertura: TextView
    private lateinit var campoIso: TextView
    private lateinit var campoEv: TextView
    private lateinit var campoFoco: TextView
    private lateinit var campoKelvin: TextView
    private lateinit var campoTinta: TextView
    private lateinit var campoVisor: TextView
    private val ui = Handler(Looper.getMainLooper())

    private var render: Render? = null
    private var lente: LensProfile? = null
    private var objectivas: List<LensProfile> = emptyList()

    /** As traseiras que o corpo tem e a aplicação não pode usar. Existem para se poder dizer porquê. */
    private var recusadas: List<LensProfile> = emptyList()
    private var qualObjectiva = 0
    private var arranque: io.github.bmcsilva.latente.model.Exposure? = null
    private var arranqueDeAbertura = 0f
    private var superficieViva: android.view.Surface? = null
    private var larguraViva = 0
    private var alturaViva = 0
    private var etiqueta = ""
    private var maxDioptrias = 10f
    private lateinit var ajudas: AidsView
    private var ajuda = 0
    private var prefs: Settings? = null
    private var sensores: android.hardware.SensorManager? = null
    private var ouvinteDoNivel: android.hardware.SensorEventListener? = null

    /** O que o comando mexe: 0 foco, 1 Kelvin, 2 tinta. */
    private var anel = 0
    private var barra: ParameterSlider? = null
    private lateinit var pastilhas: LinearLayout
    private lateinit var raiz: LinearLayout
    private lateinit var topo: LinearLayout
    private lateinit var fundo: LinearLayout
    private lateinit var caixaDoVisor: AspectBox
    private lateinit var coluna: LinearLayout
    private lateinit var colunaDoVisor: LinearLayout
    private lateinit var faixaDoDisparo: FrameLayout
    private lateinit var blocoTopo: LinearLayout
    private lateinit var grelha: LinearLayout
    /** Onde as pastilhas e a fila dos comandos vivem em retrato. Ver `arrumar`. */
    private lateinit var caixaDePastilhas: FrameLayout
    private lateinit var caixaDosComandos: FrameLayout
    private lateinit var caixaDaBarra: FrameLayout
    /** A etiqueta do `MODO`: é por ela que o topo da imagem se alinha em paisagem. */
    private var etiquetaDoModo: TextView? = null
    private var emPaisagem = false

    /** A rotação do ecrã da última vez que se arrumou. Ver `ouvinteDoEcra`. */
    private var rotacaoArrumada = -1

    /** Ver onde é criado: só tem peso em paisagem, e em retrato tem de estar `GONE`. */
    private lateinit var espacadorDaBarra: View
    private var botaoDoDisparo: ShutterButton? = null
    private var comandosRef: LinearLayout? = null
    private var celulaEsquerdaRef: LinearLayout? = null
    private var direitaRef: LinearLayout? = null
    private var botaoDeAjudas: TextView? = null
    private var botaoDeDestinos: TextView? = null

    /**
     * O registo de uso prolongado, quando a sessão foi aberta para o medir.
     *
     * Nulo é o caso normal: fotografar não escreve ficheiros de telemetria. Liga-se pelo botão «Uso
     * prolongado» das experiências, ou por `-e registar uso`.
     */
    private var registoDeUso: UsageLog? = null
    private var botaoDaObjectiva: TextView? = null
    private var trocarPara: ((Int) -> Unit)? = null
    private var botaoDoModo: TextView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // A barra de título do sistema comia 150 px de altura e escrevia «Latente · visor» por cima de
        // uma aplicação que já se identifica. Esse espaço é do visor.
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // As preferências antes do ecrã: há botões que nascem com o estado guardado, e um botão que
        // nasce a dizer outra coisa é o botão a mentir sobre a máquina.
        prefs = Settings(this)
        anel = prefs?.dial ?: 0
        ajuda = prefs?.aids ?: 0

        // O ecrã em três faixas, e não com os controlos por cima da imagem.
        //
        // É a estrutura do desenho, e é também a correcção de dois defeitos que se viram no telefone: os
        // botões tapavam a telemetria quando aparecia um aviso, porque o bloco de texto crescia para
        // cima contra eles. Com faixas, nada se sobrepõe a nada — o visor tem a sua altura e o *chrome*
        // tem a dele. A imagem é 3:4 e o ecrã é mais alto do que isso: as barras que sobravam pretas
        // passam a ser onde os instrumentos vivem.
        raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setBackgroundColor(PRETO)

        // --- faixa de cima: nome e o essencial da exposição ---
        topo = LinearLayout(this)
        topo.orientation = LinearLayout.VERTICAL
        topo.setPadding(dp(16), dp(2), dp(16), dp(4))

        // O que decide a fotografia, numa linha só e sempre no mesmo sítio: modo, margem até ao corte,
        // e quanto já está cortado. Um fotógrafo lê isto de relance e decide se dispara.
        //
        // O nome vai na mesma linha, à direita e discreto. Numa faixa só para ele custava 55 px de
        // altura, e esses 55 px são do visor — a aplicação identifica-se uma vez, a exposição lê-se a
        // cada disparo.
        // O bloco de cima e a grelha são **montados por orientação**, como as pastilhas.
        //
        // Estavam construídos de uma vez no `onCreate`, e a paisagem só os podia apertar: três campos a
        // dividir 960 px dão 83 dp cada, e «TEMPERATURA» não cabe em 83 — as etiquetas partiam-se em
        // duas linhas e a grelha desalinhava. Uma coluna estreita não quer os mesmos campos mais
        // apertados; quer menos por linha.
        blocoTopo = LinearLayout(this)
        blocoTopo.orientation = LinearLayout.VERTICAL
        vinhetaView = TextView(this)
        fpsView = TextView(this)
        for (v in arrayOf(vinhetaView, fpsView)) {
            v.typeface = Typeface.MONOSPACE
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            v.setTextColor(CINZA)
            v.maxLines = 1
        }
        topo.addView(blocoTopo)

        // **INVISIBLE e nunca GONE**, e uma linha só.
        //
        // Com GONE, aparecer um aviso encolhia a faixa do visor — e uma `SurfaceView` que muda de
        // tamanho faz `surfaceChanged`, que aqui **reinicia a câmara toda**. Via-se: a imagem saltava
        // sempre que o corte no sensor acendia, e a telemetria ficava presa no primeiro relato porque o
        // fio de render nunca sobrevivia um segundo. O *chrome* de uma câmara tem altura fixa: o espaço
        // do aviso está lá sempre, ocupado ou vazio.
        avisosView = TextView(this)
        avisosView.typeface = Typeface.MONOSPACE
        avisosView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        avisosView.setTextColor(AMBAR)
        avisosView.visibility = View.INVISIBLE
        avisosView.setPadding(0, dp(6), 0, 0)
        // Em manual acendem cinco avisos ao mesmo tempo e a linha não chega para eles. Cortar o fim
        // esconde justamente o que não coube; crescer para duas linhas reiniciava a câmara. Fica a
        // andar: uma linha só, a deslizar sem fim, como um painel de informação.
        avisosView.setSingleLine(true)
        avisosView.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        avisosView.marqueeRepeatLimit = -1
        avisosView.setHorizontallyScrolling(true)
        // Sem isto o `marquee` só anda com o foco, e um `TextView` de telemetria nunca o tem.
        avisosView.isSelected = true
        // A barra de acento antes do texto, do desenho do utilizador.
        //
        // Um aviso é a única coisa deste ecrã que interrompe — e sem marca nenhuma era uma linha âmbar
        // entre outras linhas de texto. A barra dá-lhe princípio: o olho encontra o sítio antes de ler
        // a palavra, que é o que se quer de um aviso.
        //
        // Desenho composto e não uma vista à parte: o texto desliza e a barra tem de ficar quieta no
        // início da linha. Numa fila de duas vistas, a que desliza teria de ser medida à parte, e a
        // faixa mudava de altura — que aqui reinicia a câmara.
        val acento = android.graphics.drawable.ColorDrawable(AMBAR)
        acento.setBounds(0, 0, dp(3), avisosView.lineHeight)
        avisosView.setCompoundDrawablesRelative(acento, null, null, null)
        avisosView.compoundDrawablePadding = dp(8)

        topo.addView(avisosView)

        // --- faixa do meio: o visor, com as ajudas por cima ---
        caixaDoVisor = AspectBox(this)
        superficie = SurfaceView(this)
        caixaDoVisor.addView(superficie, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ajudas = AidsView(this)
        caixaDoVisor.addView(ajudas, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Peso 1: o visor fica com o que sobrar depois das duas faixas. A imagem é enquadrada dentro
        // desta caixa pelo `Present.fit`, que nunca corta — a proporção certa é dele e não do layout.


        // --- faixa de baixo: instrumentos e comandos ---
        fundo = LinearLayout(this)
        fundo.orientation = LinearLayout.VERTICAL
        fundo.setPadding(dp(16), dp(4), dp(16), dp(2))

        grelha = LinearLayout(this)
        grelha.orientation = LinearLayout.VERTICAL
        fundo.addView(grelha)

        // A do visor só quando difere do disparo por mais de meio stop. Fora disso está escondida, que
        // é o mesmo que dizer «o que vês é o que levas».
        campoVisor = TextView(this)
        campoVisor.typeface = Typeface.MONOSPACE
        campoVisor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        campoVisor.setTextColor(CIANO)
        campoVisor.visibility = View.INVISIBLE
        campoVisor.maxLines = 1
        campoVisor.setPadding(0, dp(4), 0, 0)
        fundo.addView(campoVisor)

        val comando = ParameterSlider(this)
        comando.aoMudar = { v, doUtilizador ->
            if (doUtilizador) {
                render?.let { r ->
                when (anel) {
                    // Linear em **dioptrias**, o inverso da distância: o curso reparte-se pelo perto,
                    // que é onde o foco é crítico. Linear em metros daria 90% do curso ao infinito.
                    // Numa objectiva de foco fixo o comando não tem para onde ir. Ignora-se em vez de
                    // pedir um foco que o HAL não honra e depois mostrar uma distância que não existe.
                    0 -> if (maxDioptrias > 0f) r.pedirFoco(maxDioptrias * v / 1000f)
                    // Linear em **mired**, o inverso da temperatura. Passos iguais em mired são passos
                    // perceptualmente iguais; em Kelvin, mil graus a 3000 K vêem-se e a 10000 K não.
                    1 -> r.pedirKelvin((1e6f / (MIRED_MIN + (MIRED_MAX - MIRED_MIN) * v / 1000f)).toInt())
                    2 -> r.pedirTinta(v / 500f - 1f)
                    // Compensação de −3 a +3 EV. Não é mais um parâmetro contínuo por gosto: sem ela o
                    // fotómetro tem o alvo fixo e não há como dizer «expõe um stop mais claro».
                    ANEL_EV -> r.pedirCompensacao(v / 1000f * 6f - 3f)
                    // Tempo e ISO em escala **logarítmica**, porque a fotografia é geométrica: entre
                    // 1/1000 e 1/500 vai um stop, e entre 1 s e 1,002 s não vai nada. Linear dava
                    // noventa por cento do curso aos tempos longos e um dedo a toda a luz do dia.
                    ANEL_TEMPO -> lente?.let { l ->
                        r.pedirTempo(escalaLog(v, l.exposureMinNs.toDouble(),
                            l.exposureMaxNs.toDouble()).toLong())
                    }
                    else -> lente?.let { l ->
                        r.pedirIso(Math.round(
                            escalaLog(v, l.isoMin.toDouble(), l.isoMax.toDouble())).toInt())
                    }
                }
                }
            }
        }
        // Fim do gesto: uma escrita em disco, não mil.
        comando.aoLargar = { render?.guardarEscolhas() }
        barra = comando
        // Como as pastilhas: contentor a guardar o lugar, porque em paisagem a barra muda de coluna.
        caixaDaBarra = FrameLayout(this)
        caixaDaBarra.addView(comando, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // O espaçador que faz a barra descer, **e que só pode existir em paisagem**.
        //
        // Em paisagem a faixa de baixo é uma coluna com altura a mais, e a barra ficava colada aos
        // campos com um vazio por baixo. Com peso, a folga vai toda para cima dela: a barra desce até à
        // altura das pastilhas que estão por baixo da imagem, que é onde o dedo já anda.
        //
        // Em retrato tem de ir a `GONE`, e não basta não haver folga: um `LinearLayout` medido em
        // `AT_MOST` — que é como um filho `WRAP_CONTENT` é medido — **também reparte o excesso pelos
        // pesos**. A faixa de baixo esticava-se até ao topo do ecrã e o visor ficava com zero. Partiu o
        // retrato todo, e a lição fica: peso dentro de uma caixa que se mede pelo conteúdo não é
        // inofensivo.
        espacadorDaBarra = View(this)
        fundo.addView(espacadorDaBarra, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val lpComando = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpComando.topMargin = dp(6)
        lpComando.bottomMargin = dp(4)
        fundo.addView(caixaDaBarra, lpComando)

        // Os parâmetros em fila, e não um botão que cicla.
        //
        // O botão único obrigava a carregar quatro vezes para chegar ao quinto parâmetro, e nunca
        // mostrava os outros — para saber o que havia era preciso percorrê-los. Com a fila vê-se de uma
        // vez o que a objectiva e o modo oferecem, e vai-se direito ao que se quer. É também o que
        // ensina a diferença entre os modos sem uma linha de texto: em P há quatro pastilhas, em M há
        // seis, e a 66 mm tem três.
        pastilhas = LinearLayout(this)
        pastilhas.orientation = LinearLayout.HORIZONTAL
        // Um contentor vazio, e não a fila metida aqui de uma vez.
        //
        // Em paisagem as pastilhas mudam de coluna — vão para a banda por baixo da imagem —, e voltar
        // a metê-las nesta faixa por índice é contar filhos à mão. O contentor guarda o lugar: em
        // retrato tem-nas dentro, em paisagem fica vazio e não ocupa nada.
        caixaDePastilhas = FrameLayout(this)
        val lpPastilhas = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpPastilhas.topMargin = dp(8)
        fundo.addView(caixaDePastilhas, lpPastilhas)

        // Nasce com o modo **guardado**, não com um «P» escrito à mão: a câmara reabria em S e o botão
        // dizia P, que é o botão a mentir sobre o estado da máquina.
        val modo = botaoDeMenu((prefs?.mode ?: ExposureProgram.Mode.P)
            .let { if (it == ExposureProgram.Mode.A) ExposureProgram.Mode.P else it }.name,
            lua = 1)
        botaoDoModo = modo
        // O menu, como nos outros dois. Ficara o código antigo — ciclar e, na 66 mm, recusar com uma
        // mensagem —, e é pior: uma recusa numa linha que desaparece não diz **o que existe**. O menu
        // mostra os três modos apagados com a razão ao lado, que é a mesma honestidade das objectivas
        // recusadas.
        modo.setOnClickListener { menuDeModos(modo) }

        val disparador = ShutterButton(this)
        disparador.setOnClickListener {
            render?.pedirDisparo()
            disparador.ocupado = true
            // Volta ao branco quando a mensagem do disparo chegar; este é o limite de segurança para o
            // caso de ela não chegar, e vale mais do que um botão preso a vermelho.
            ui.postDelayed({ disparador.ocupado = false }, 4000)
        }
        botaoDoDisparo = disparador

        val trocar = botaoDeMenu("23 MM")
        trocar.setOnClickListener { menuDeObjectivas(trocar) }
        botaoDaObjectiva = trocar
        trocarPara = { qual ->
            qualObjectiva = qual
            val nova = objectivas[qualObjectiva]
            // Carrega-se a **luz**, não os números: de f/1,8 para f/2,2 há 0,58 stops menos luz para a
            // mesma cena, e arrancar com o mesmo tempo e ISO daria uma primeira imagem subexposta até o
            // fotómetro convergir. Herdar números em vez de exposição é confundir a unidade.
            arranque = render?.exposicaoActual()
            arranqueDeAbertura = lente?.apertures?.firstOrNull() ?: 0f
            lente = nova
            // Outro mosaico pode ter outra proporção, e a caixa do visor é medida por ela.
            aplicarProporcaoDoVisor()
            botaoDaObjectiva?.let {
                rotularBotaoDeMenu(it, nova.equivalentFocalMm.toString() + " MM", false)
            }
            maxDioptrias = nova.minFocusDiopters
            anel = if (maxDioptrias > 0f) anel else 1   // sem foco, o comando começa no Kelvin
            rotularAnel()
            // Trocar de objectiva é abrir outra câmara: pára-se o fio de render, que fecha o contexto
            // EGL e a sessão, e arranca-se outro. Reaproveitar a sessão não é possível — o tamanho do
            // mosaico muda, e com ele a textura e os uniformes todos.
            reiniciarRender()
        }

        // Um botão, e o menu diz o que há.
        //
        // Antes eram quatro círculos com iniciais — P, Z, H, N. O problema não era o espaço: era que
        // uma inicial não se explica a si própria. O menu mostra os nomes e o que cada um faz, mostra
        // quais estão ligados, e deixa combiná-los sem os adivinhar. Custa um toque; poupa decorar a
        // interface.
        val instrumentos = botaoDeMenu("AJUDAS", lua = 2)
        instrumentos.setOnClickListener { menuDeAjudas(instrumentos) }
        botaoDeAjudas = instrumentos

        // A saída do visor, que até aqui não existia.
        //
        // O ícone da gaveta passou a abrir a câmara, como faz uma aplicação de fotografia. Mas o visor
        // não tinha caminho para lado nenhum — nem para os negativos nem para as experiências —, e
        // trocar o lançador sem lhe dar um deixava dois ecrãs órfãos, alcançáveis só por adb.
        //
        // Um menu e não dois botões: é o idioma que o ecrã já fala, e os dois destinos visitam-se de
        // vez em quando. Dois botões custariam largura à fila onde se fotografa.
        val ir = botaoDeMenu("")
        // Três linhas em vez da palavra «IR»: é o sinal que toda a gente já sabe ler, e num botão de
        // 44 dp a palavra roubava o espaço que a mão quer para acertar. Sem o triângulo do acento —
        // as três linhas já dizem «abre uma lista», e dois sinais para a mesma coisa é ruído.
        ir.text = ""
        // Por cima e não ao lado do texto: um desenho composto num `TextView` fica **à esquerda** do
        // sítio onde o texto ficaria, e com o texto vazio isso é encostado à margem. Como frente da
        // vista, o `MenuGlyph` recebe a caixa toda e desenha-se no meio dela.
        ir.setPadding(0, 0, 0, 0)
        // A gravidade **explícita**: sem ela a frente da vista encosta-se à esquerda em vez de encher
        // a caixa, e as três linhas ficavam coladas ao bordo da pastilha. Medido a olho no ecrã e
        // depois em píxeis: o glifo começava exactamente no bordo esquerdo.
        ir.foregroundGravity = android.view.Gravity.CENTER
        ir.foreground = MenuGlyph(Color.WHITE, dp(16).toFloat(), dp(2).toFloat())
        ir.setOnClickListener { menuDeDestinos(ir) }
        botaoDeDestinos = ir

        // O disparador ao centro, os comandos aos lados.
        //
        // É a disposição de uma câmara e não a de um formulário: o que se carrega a cada fotografia
        // fica onde o polegar cai, e o que se mexe de vez em quando fica à volta. Antes eram seis
        // rectângulos iguais em fila — e num deles estava o disparo, indistinguível dos outros.
        val comandos = LinearLayout(this)
        comandosRef = comandos
        comandos.orientation = LinearLayout.HORIZONTAL
        comandos.gravity = android.view.Gravity.CENTER_VERTICAL
        // Três células com peso igual dos lados, e o disparador no meio.
        //
        // Estava tudo num `FrameLayout` — os grupos encostados às pontas e o círculo centrado —, e as
        // contas não fechavam: o grupo da direita começava aos 198 dp e o disparador acabava aos 202,
        // ou seja **sobrepunham-se**. Com pesos, a folga reparte-se sozinha pelos dois lados e deixa de
        // depender de eu somar larguras à mão.
        val celulaEsquerda = LinearLayout(this)
        celulaEsquerdaRef = celulaEsquerda
        celulaEsquerda.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        // Estica-se pela célula: é o único da esquerda e um botão largo é um alvo fácil.
        celulaEsquerda.addView(instrumentos, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        comandos.addView(celulaEsquerda, LinearLayout.LayoutParams(0, dp(80), 1f))

        // Quem o coloca é o `arrumar`: em retrato entre as duas meias-luas, em paisagem na sua faixa.
        comandos.gravity = android.view.Gravity.CENTER_VERTICAL

        val direita = LinearLayout(this)
        direitaRef = direita
        direita.orientation = LinearLayout.HORIZONTAL
        // Encostado ao disparador, e não à margem do ecrã: é a meia-lua que dá sentido ao botão do
        // modo estar ali, e afastá-lo desfazia o encaixe. A objectiva estica-se até à borda, como o
        // «Ajudas» faz do outro lado — assim a fila fica cheia dos dois lados do círculo.
        direita.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        // As larguras têm de caber na célula, que é metade do que sobra depois da meia-lua: 984 px
        // menos os 288 da meia-lua dá 348 px de cada lado, ou seja 116 dp. Com 130 dp o grupo entrava
        // por cima do disparador — e o «P» aparecia meio tapado.
        // Largura para o pior caso, que é «AUTO» e não «P»: um botão que encolhe e cresce com o texto
        // faria a fila saltar de sítio a cada troca de objectiva.
        direita.addView(modo, LinearLayout.LayoutParams(dp(64), dp(44)))
        // A mesma altura das outras duas. Ficara nos 38 dp de quando os botões eram rectângulos, e ao
        // lado de duas pastilhas de 44 lia-se como se estivesse encolhida.
        val lpTrocar = LinearLayout.LayoutParams(0, dp(44), 1f)
        lpTrocar.leftMargin = dp(4)
        direita.addView(trocar, lpTrocar)
        comandos.addView(direita, LinearLayout.LayoutParams(0, dp(80), 1f))

        // Pela mesma razão das pastilhas: em paisagem a fila desaparece e o disparador vai para a sua
        // faixa. Sem contentor, ao voltar a retrato a fila era acrescentada no fim — por baixo da linha
        // de estado, que é onde não é.
        caixaDosComandos = FrameLayout(this)
        val lpComandos = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(80))
        lpComandos.topMargin = dp(2)
        fundo.addView(caixaDosComandos, lpComandos)

        // As mensagens de acontecimentos ficam **junto ao disparador**, que é o que as provoca: «a
        // disparar…», o nome do ficheiro escrito, um erro. Não são estado, são resposta a uma acção.
        estado = TextView(this)
        estado.typeface = Typeface.MONOSPACE
        estado.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        estado.setTextColor(CINZA)
        // Começa **vazia**. Dizia «a abrir…» e ficava a dizê-lo depois de aberto, que é informação
        // falsa a ocupar espaço. Esta linha só fala quando há acontecimento: um disparo, um ficheiro
        // escrito, um erro.
        estado.text = ""
        // Uma linha sempre: se a caixa crescesse com o texto reiniciava a câmara pela mesma razão que
        // os avisos. Uma mensagem longa corta-se no fim — o que interessa está no princípio.
        estado.minLines = 1
        estado.maxLines = 1
        estado.ellipsize = android.text.TextUtils.TruncateAt.END
        estado.setPadding(0, dp(2), 0, 0)
        fundo.addView(estado)

        // Desde o Android 15 com targetSdk 35+ o conteúdo desenha por baixo das barras do sistema. As
        // margens vão à raiz, e não a cada peça: com faixas, chega.
        raiz.setOnApplyWindowInsetsListener { v, insets ->
            val barras = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout())
            v.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        coluna = LinearLayout(this)
        coluna.orientation = LinearLayout.VERTICAL
        // A coluna da esquerda em paisagem: a imagem, e por baixo dela a banda das pastilhas.
        colunaDoVisor = LinearLayout(this)
        colunaDoVisor.orientation = LinearLayout.VERTICAL
        faixaDoDisparo = FrameLayout(this)

        rotularAnel()
        aplicarAjudas()
        arrumar(resources.configuration.orientation)
        setContentView(raiz)

        // O registo de uso prolongado, se foi para isso que a sessão abriu.
        //
        // O `publicarPendente` corre sempre, e é o que interessa quando a medição não chega ao fim: se
        // o telefone se desligou por calor, o que se mediu até aí está no disco e sai agora.
        UsageLog(this).publicarPendente()?.let { dizer(it + " escrito") }
        if (intent?.getStringExtra("registar") == "uso") registoDeUso = UsageLog(this)


        val corpo = Body(this)
        // Só as traseiras: as frontais estão fora do âmbito, e uma lista que as incluísse punha o
        // utilizador a passar por elas de cada vez que trocasse de objectiva.
        objectivas = corpo.usable().filter { it.facing == 1 }.ifEmpty { corpo.usable() }
        recusadas = corpo.rejected().filter { it.facing == 1 }
        qualObjectiva = 0
        lente = objectivas.firstOrNull()
        // Agora que se sabe o mosaico, a caixa do visor pode tomar a proporção dele.
        aplicarProporcaoDoVisor()
        etiqueta = corpo.deviceLabel()
        maxDioptrias = lente?.minFocusDiopters ?: 0f

        // Já se sabe a objectiva e o modo guardado: pinta-se o ecrã com o estado verdadeiro **antes** de
        // ele aparecer. O botão da objectiva dizia «23 MM» escrito à mão, as pastilhas eram as do P e a
        // barra estava a zero até o primeiro relato chegar.
        lente?.let { l ->
            botaoDaObjectiva?.let {
                rotularBotaoDeMenu(it, l.equivalentFocalMm.toString() + " MM", false)
            }
            botaoDoModo?.let {
                rotularBotaoDeMenu(it, if (l.manualExposure) modoEmVigor().name else "AUTO", false)
            }
        }
        reporBarraDeGuardado()

        porqueFaltam()?.let { dizer(it) }

        // Permite o varrimento de foco por adb, sem tocar no ecrã, para a verificação ser repetível.
        if (intent?.getStringExtra("auto") == "varrerfoco") {
            ui.postDelayed({ render?.pedirVarrimento() }, 4000)
        }

        superficie.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                // Reiniciar a câmara custa cerca de um segundo e perde o estado do fotómetro. Só se faz
                // quando a superfície é mesmo outra: o `surfaceChanged` também chega por mudança de
                // formato, e mesmo com o *chrome* de altura fixa não se deve depender disso.
                if (holder.surface === superficieViva && w == larguraViva && h == alturaViva &&
                    render != null) {
                    return
                }
                parar()
                superficieViva = holder.surface
                larguraViva = w
                alturaViva = h
                val l = lente
                if (l == null) {
                    estado.text = "sem objectiva utilizável neste corpo"
                    return
                }
                val r = Render(this@ViewfinderActivity, l, holder.surface, w, h,
                    rotacaoDoCorpo, etiqueta, null,
                    { texto -> ui.post { dizer(texto); botaoDoDisparo?.ocupado = false } },
                    { t -> ui.post {
                        mostrar(t)
                        ajudas.definirHistograma(render?.histograma())
                    } })
                render = r
                r.start()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                parar()
            }
        })
    }

    /**
     * O bloco de cima: o que decide a fotografia.
     *
     * Em retrato os três numa linha, com o estado dos instrumentos no canto. Em paisagem dois por dois
     * — `modo`/`cortado` e `margem`/`vinhetagem` — porque numa coluna estreita três campos numa linha
     * é o mesmo que nenhum: lê-se «TEMPERATU / RA».
     */
    private fun montarTopo(paisagem: Boolean) {
        blocoTopo.removeAllViews()
        (vinhetaView.parent as? ViewGroup)?.removeView(vinhetaView)
        (fpsView.parent as? ViewGroup)?.removeView(fpsView)
        if (paisagem) {
            val l1 = LinearLayout(this)
            l1.orientation = LinearLayout.HORIZONTAL
            campoModo = campo(l1, "MODO", 1f)
            // A etiqueta, e não o valor: é ela que dá a linha por onde a imagem se alinha.
            etiquetaDoModo = (campoModo.parent as ViewGroup).getChildAt(0) as TextView
            campoCortado = campo(l1, "CORTADO", 1f)
            blocoTopo.addView(l1)

            val l2 = LinearLayout(this)
            l2.orientation = LinearLayout.HORIZONTAL
            l2.setPadding(0, dp(4), 0, 0)
            campoMargem = campo(l2, "MARGEM", 1f)
            val caixa = LinearLayout(this)
            caixa.orientation = LinearLayout.VERTICAL
            caixa.addView(vinhetaView)
            caixa.addView(fpsView)
            l2.addView(caixa, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            blocoTopo.addView(l2)
        } else {
            val l1 = LinearLayout(this)
            l1.orientation = LinearLayout.HORIZONTAL
            campoModo = campo(l1, "MODO", 0f)
            campoMargem = campo(l1, "MARGEM", 1f)
            campoCortado = campo(l1, "CORTADO", 1f)
            val canto = LinearLayout(this)
            canto.orientation = LinearLayout.VERTICAL
            canto.gravity = android.view.Gravity.END
            canto.addView(vinhetaView)
            canto.addView(fpsView)
            val lpCanto = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lpCanto.gravity = android.view.Gravity.TOP
            l1.addView(canto, lpCanto)
            blocoTopo.addView(l1)
        }
    }

    /**
     * A grelha, na ordem em que se pensa uma exposição: primeiro a luz — tempo, abertura, ISO,
     * compensação —, depois a interpretação — foco, temperatura, tinta.
     *
     * Quatro e três em retrato, **dois a dois** em paisagem: é o que dá largura para as etiquetas.
     *
     * A compensação teve de entrar aqui. Dos seis parâmetros que o comando mexe, cinco tinham campo e
     * ela não: ia escrita atrás do valor da tinta, e lia-se `+0.00  -1.1 EV` como se fosse um valor só.
     * Não era falta de largura, era falta de sítio.
     *
     * **Cada grupo reparte a sua fila**, e não as quatro colunas da primeira.
     *
     * Alinhar as duas filas parecia melhor e não é: com quatro colunas de 82 dp, «TEMPERATURA» a 9 sp
     * não cabe e sai «TEMPERATU…». O grupo da interpretação tem três campos e a largura toda para eles,
     * que dá 109 cada. Uma grelha em que as colunas não se correspondem lê-se bem; uma etiqueta cortada,
     * não.
     *
     * Em paisagem são dois por fila nos dois grupos, e aí a fila curta leva um espaçador — sem ele a
     * `tinta` sozinha esticava-se pelas duas colunas e saía de baixo do `foco`.
     */
    private fun montarGrelha(paisagem: Boolean) {
        grelha.removeAllViews()
        val luz = arrayOf("TEMPO", "ABERTURA", "ISO", "EV")
        val interpretacao = arrayOf("FOCO", "TEMPERATURA", "TINTA")
        val postos = arrayOfNulls<TextView>(7)
        var fila: LinearLayout? = null
        var naFila = 0
        var i = 0
        for (grupo in arrayOf(luz, interpretacao)) {
            val porFila = if (paisagem) 2 else grupo.size
            naFila = porFila
            for (nome in grupo) {
                if (fila == null || naFila == porFila) {
                    fila = novaFilaDaGrelha()
                    naFila = 0
                }
                postos[i] = campo(fila, nome, 1f)
                naFila++
                i++
            }
            // Fim do grupo: o que falta para fechar a fila fica em branco, e não repartido pelos outros.
            while (naFila > 0 && naFila < porFila) {
                // Com a mesma margem de um campo: sem ela o espaçador ficava 14 dp mais largo e as
                // colunas da fila curta saíam desalinhadas das da fila cheia por essa exacta medida.
                val lp = LinearLayout.LayoutParams(0, 0, 1f)
                lp.leftMargin = dp(14)
                fila!!.addView(View(this), lp)
                naFila++
            }
        }
        campoTempo = postos[0]!!
        campoAbertura = postos[1]!!
        campoIso = postos[2]!!
        campoEv = postos[3]!!
        campoFoco = postos[4]!!
        campoKelvin = postos[5]!!
        campoTinta = postos[6]!!
    }

    private fun novaFilaDaGrelha(): LinearLayout {
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        if (grelha.childCount > 0) fila.setPadding(0, dp(4), 0, 0)
        grelha.addView(fila)
        return fila
    }

    /**
     * Arruma as três faixas conforme o lado do ecrã.
     *
     * Em retrato ficam empilhadas — instrumentos, visor, comandos. Em paisagem o visor fica com o lado
     * comprido e o *chrome* junta-se numa **coluna à direita**: um mosaico 4:3 num ecrã 21:9 deitado
     * deixaria de sobrar altura para faixas, e o que sobra é largura.
     *
     * Reagrupa as mesmas vistas em vez de construir outras. Duas árvores paralelas para o mesmo ecrã
     * seriam duas árvores a divergir — e a corrigir cada defeito duas vezes.
     */
    private fun arrumar(orientacao: Int) {
        val paisagem = orientacao == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        emPaisagem = paisagem
        raiz.removeAllViews()
        coluna.removeAllViews()
        colunaDoVisor.removeAllViews()
        faixaDoDisparo.removeAllViews()
        caixaDePastilhas.removeAllViews()
        caixaDosComandos.removeAllViews()
        for (v in arrayOf<View?>(topo, caixaDoVisor, fundo, botaoDoDisparo, pastilhas)) {
            (v?.parent as? ViewGroup)?.removeView(v)
        }
        montarTopo(paisagem)
        montarGrelha(paisagem)
        // A mordida existe para encaixar no disparador. Em paisagem ele foi para o bordo, e uma mordida
        // sem nada do outro lado é uma pastilha com um pedaço a menos.
        // Em paisagem os dois continuam a encaixar no disparador — só que por cima e por baixo dele.
        // O que está acima é mordido na **base**, o que está abaixo é mordido no **topo**.
        botaoDeAjudas?.tag = if (paisagem) 4 else 2
        botaoDoModo?.tag = if (paisagem) 3 else 1
        assinaturaDoAnel = -1
        rotularAnel()
        botaoDeAjudas?.let { rotularBotaoDeMenu(it, textoDeAjudas(), ajuda != 0) }
        botaoDoModo?.let {
            rotularBotaoDeMenu(it, (it.text ?: "").toString().substringBefore("  "), false)
        }
        botaoDaObjectiva?.let {
            rotularBotaoDeMenu(it, (it.text ?: "").toString().substringBefore("  "), false)
        }

        // O disparador fica **onde a mão o deixou**, e os três botões continuam ao lado dele.
        //
        // Rodar para a esquerda leva a aresta de baixo para a direita: o dedo que carregava no círculo
        // continua nesse ponto do corpo. E o que em retrato o flanqueia — ajudas de um lado, modo e
        // objectiva do outro — passa a flanqueá-lo em coluna, acima e abaixo. É a mesma vizinhança vista
        // de lado, e não uma disposição nova para decorar.
        //
        // De caminho resolve o que faltava: saindo os três da coluna, ela ganha a altura que lhe faltava
        // para a segunda fila de pastilhas caber.
        for (v in arrayOf<View?>(botaoDeAjudas, botaoDoModo, botaoDaObjectiva, botaoDeDestinos)) {
            (v?.parent as? ViewGroup)?.removeView(v)
        }
        botaoDoDisparo?.let { d ->
            if (paisagem) {
                (comandosRef?.parent as? ViewGroup)?.removeView(comandosRef)
                val pilha = LinearLayout(this)
                pilha.orientation = LinearLayout.VERTICAL
                pilha.gravity = android.view.Gravity.CENTER_HORIZONTAL
                // O «ir» no topo da pilha, longe do disparador: é o que menos se toca e não deve estar
                // onde o polegar procura o botão de fotografar.
                botaoDeDestinos?.let {
                    val lp = LinearLayout.LayoutParams(dp(LUA_LARGURA), dp(44))
                    lp.bottomMargin = dp(10)
                    pilha.addView(it, lp)
                }
                // **A largura do disparador, e não mais.** É geometria e não gosto: a mordida é a
                // negativa de um círculo de 39 dp de raio, e um arco desses não atravessa uma aresta
                // maior do que 78 dp. Com 84 o arco cobria 56% da aresta e as duas pontas ficavam a
                // direito — que é o que se via, uma pastilha recta com um dente ao meio.
                //
                // A 76 dp o arco cobre a aresta toda, como em retrato: a curva é a do disparador, a
                // folga é de 2 dp de ponta a ponta, e as duas peças encaixam. A altura sobe para 64 dp
                // porque a concavidade come 28 — o texto vive nos 36 que sobram.
                botaoDeAjudas?.let {
                    val lp = LinearLayout.LayoutParams(dp(LUA_LARGURA), dp(LUA_ALTURA))
                    // O encaixe: o círculo que morde tem de ser o do disparador. A pastilha sobe até o
                    // centro dos dois coincidir, e a folga vem do raio — 39 contra os 37 desenhados.
                    lp.bottomMargin = dp(-LUA_FUNDO)
                    pilha.addView(it, lp)
                }
                pilha.addView(d, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                botaoDoModo?.let {
                    val lp = LinearLayout.LayoutParams(dp(LUA_LARGURA), dp(LUA_ALTURA))
                    lp.topMargin = dp(-LUA_FUNDO)
                    pilha.addView(it, lp)
                }
                botaoDaObjectiva?.let {
                    val lp = LinearLayout.LayoutParams(dp(LUA_LARGURA), dp(44))
                    lp.topMargin = dp(10)
                    pilha.addView(it, lp)
                }
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.gravity = android.view.Gravity.CENTER
                faixaDoDisparo.addView(pilha, lp)
            } else {
                comandosRef?.let { c ->
                    (c.parent as? ViewGroup)?.removeView(c)
                    caixaDosComandos.addView(c, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                celulaEsquerdaRef?.let { ce ->
                    // O «ir» encostado à margem e o «ajudas» ao disparador: quem tem a mordida tem de
                    // ficar ao lado do círculo, senão o encaixe não quer dizer nada.
                    botaoDeDestinos?.let {
                        ce.addView(it, LinearLayout.LayoutParams(dp(44), dp(44)))
                    }
                    botaoDeAjudas?.let {
                        val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
                        lp.leftMargin = dp(4)
                        ce.addView(it, lp)
                    }
                }
                direitaRef?.let { dd ->
                    botaoDoModo?.let { dd.addView(it, LinearLayout.LayoutParams(dp(64), dp(44))) }
                    botaoDaObjectiva?.let {
                        val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
                        lp.leftMargin = dp(4)
                        dd.addView(it, lp)
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.leftMargin = dp(-6)
                lp.rightMargin = dp(-6)
                comandosRef?.addView(d, 1, lp)
            }
        }
        espacadorDaBarra.visibility = if (paisagem) View.VISIBLE else View.GONE
        caixaDePastilhas.visibility = if (paisagem) View.GONE else View.VISIBLE
        caixaDosComandos.visibility = if (paisagem) View.GONE else View.VISIBLE
        if (paisagem) {
            raiz.orientation = LinearLayout.HORIZONTAL
            // A imagem **encostada ao topo**, alinhada com o `MODO`, e não centrada.
            //
            // Ideia do utilizador, e é a certa: a imagem é 4:3 numa faixa mais alta do que larga, e
            // centrá-la punha metade do preto que sobra acima dela — onde não serve para nada. Encostada
            // ao topo, todo o preto que sobra fica **numa só banda por baixo**, que é espaço utilizável.
            caixaDoVisor.setPadding(0, 0, 0, 0)
            caixaDoVisor.folgaEmBaixo = dp(39)
            aplicarProporcaoDoVisor()
            colunaDoVisor.addView(caixaDoVisor, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // As pastilhas na banda, à largura da imagem.
            //
            // A banda deixou de ser preto a mais: é a quarta zona do ecrã. E a largura dela dá as seis
            // pastilhas numa fila — na coluna estreita não cabiam três, e era por isso que iam a duas
            // filas a roubar altura ao visor.
            val lpFila = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lpFila.topMargin = dp(5)
            colunaDoVisor.addView(pastilhas, lpFila)
            // **De que lado fica a imagem depende de para que lado o telefone foi rodado.**
            //
            // A objectiva está num canto do corpo, e numa das duas paisagens esse canto cai do lado do
            // disparador — a mão que carrega tapa a lente. Foi o utilizador a dar por isso, e não há
            // disposição fixa que resolva: o que serve numa rotação estraga a outra. Espelha-se a fila
            // inteira, e o dedo continua a encontrar o círculo no mesmo ponto do corpo.
            val espelhado = rotacaoDoEcra() == 270
            rotacaoArrumada = rotacaoDoEcra()
            render?.definirRotacaoDoEcra(rotacaoArrumada)
            val lpVisor = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            val lpColuna = LinearLayout.LayoutParams(dp(272),
                ViewGroup.LayoutParams.MATCH_PARENT)
            val lpFaixa = LinearLayout.LayoutParams(dp(96),
                ViewGroup.LayoutParams.MATCH_PARENT)
            if (espelhado) raiz.addView(faixaDoDisparo, lpFaixa)
            if (espelhado) raiz.addView(coluna, lpColuna)
            raiz.addView(colunaDoVisor, lpVisor)
            alinharVisorAoTopo(true)
            alinharColunaPeloModo()
            coluna.addView(topo, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            coluna.addView(fundo, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // 272 dp na coluna, 96 na faixa do disparador: as pastilhas saíram para a banda e o que a
            // coluna tem de aguentar são dois campos por linha. Já foram 300 e 320, quando levava tudo.
            if (!espelhado) raiz.addView(coluna, lpColuna)
            if (!espelhado) raiz.addView(faixaDoDisparo, lpFaixa)
        } else {
            rotacaoArrumada = rotacaoDoEcra()
            render?.definirRotacaoDoEcra(rotacaoArrumada)
            aplicarProporcaoDoVisor()
            caixaDePastilhas.addView(pastilhas, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            colunaDoVisor.setPadding(0, 0, 0, 0)
            raiz.orientation = LinearLayout.VERTICAL
            raiz.addView(topo, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            alinharVisorAoTopo(false)
            raiz.addView(caixaDoVisor, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            raiz.addView(fundo, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * Dá à caixa do visor a proporção do mosaico, para as pastilhas ficarem encostadas à imagem.
     *
     * Fora do `arrumar` porque a objectiva chega **depois** dele: o `onCreate` arruma o ecrã e só a
     * seguir pergunta ao corpo que objectivas há. Feito só lá dentro, o primeiro arranque em paisagem
     * ficava com a caixa a ocupar a coluna toda e as pastilhas depois de um vão de preto — e só se
     * corrigia à primeira rotação. Chamam-no os dois sítios que sabem alguma coisa nova: o que arruma
     * e o que troca de objectiva.
     *
     * Em retrato é zero: lá a imagem é centrada no que sobra entre as faixas, como sempre foi.
     */
    private fun aplicarProporcaoDoVisor() {
        val m = lente?.rawSize
        caixaDoVisor.proporcao = if (!emPaisagem || m == null) 0f else {
            Math.max(m.width, m.height).toFloat() / Math.min(m.width, m.height)
        }
    }

    /**
     * Manda o enquadramento encostar a imagem ao topo em vez de a centrar.
     *
     * O `Present.fit` centra, que é o certo em retrato. Em paisagem sobra uma banda de preto e ela vale
     * mais toda junta por baixo do que repartida em duas.
     */
    private fun alinharVisorAoTopo(sim: Boolean) {
        render?.alinharAoTopo(sim)
        aoTopo = sim
    }

    private var aoTopo = false

    /**
     * Desce a coluna do visor até o topo da imagem cair na **linha do texto `MODO`**.
     *
     * Não é uma margem escolhida a olho: a etiqueta tem o preenchimento da faixa por cima e o vão que a
     * fonte deixa entre o topo da caixa de texto e o topo das letras. Somar isso à mão dava um número
     * que envelhecia à primeira mudança de tamanho. Aqui pergunta-se às vistas onde a letra está de
     * facto, depois de medidas.
     *
     * Conta-se antes de medir, e não com as vistas já postas no ecrã: mexer no preenchimento depois da
     * medida mudava a altura da caixa do visor, e mudar a altura da caixa **reinicia a câmara** — um
     * segundo perdido e o fotómetro a começar do zero, a cada rotação. São duas parcelas, e as duas
     * sabem-se sem medir nada: o preenchimento da faixa de cima, e o vão que a fonte deixa entre o topo
     * da caixa de texto e o topo das letras.
     *
     * Pressupõe que a etiqueta é a primeira coisa da faixa — é, e se deixar de ser o alinhamento
     * fica-se pelo preenchimento da faixa, que é erro de poucos píxeis e não de vistas trocadas.
     */
    private fun alinharColunaPeloModo() {
        val e = etiquetaDoModo ?: return
        val fm = e.paint.fontMetricsInt
        // `top` é o que a fonte reserva acima do apoio, `ascent` é onde as letras começam de facto. A
        // diferença é o vão, e é a ele que se deve o texto parecer sempre mais baixo do que a caixa.
        colunaDoVisor.setPadding(0, topo.paddingTop + (fm.ascent - fm.top), 0, 0)
    }

    override fun onConfigurationChanged(nova: android.content.res.Configuration) {
        super.onConfigurationChanged(nova)
        arrumar(nova.orientation)
    }

    /**
     * Virar o telefone ao contrário, dentro da mesma paisagem, **não muda a configuração**.
     *
     * O `onConfigurationChanged` só fala quando a orientação passa de retrato a paisagem ou ao
     * contrário; entre as duas paisagens — 90 e 270 — a configuração é a mesma e ninguém avisa. E é
     * justamente aí que a imagem tem de trocar de lado, para o disparador não cair sobre a lente.
     *
     * Só se arruma quando a rotação **mudou de facto**: refazer o ecrã redimensiona a superfície, e
     * isso reinicia a câmara.
     */
    private val ouvinteDoEcra = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}

        override fun onDisplayRemoved(id: Int) {}

        override fun onDisplayChanged(id: Int) {
            if (rotacaoDoEcra() == rotacaoArrumada) return
            // Em retrato não há nada a rearrumar — a disposição é a mesma de pé ou de cabeça para
            // baixo —, mas o visor tem de saber, senão continua a desenhar para o lado antigo.
            if (!emPaisagem) {
                rotacaoArrumada = rotacaoDoEcra()
                render?.definirRotacaoDoEcra(rotacaoArrumada)
                return
            }
            arrumar(resources.configuration.orientation)
        }
    }

    override fun onResume() {
        super.onResume()
        registoDeUso?.comecar()
        (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .registerDisplayListener(ouvinteDoEcra, ui)
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val acel = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        val ouvinte = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                // A orientação do corpo lê-se **sempre**, mesmo com o nível desligado: é ela que decide
                // se a fotografia sai deitada, e isso não pode depender de uma ajuda estar ligada.
                orientacaoDoCorpo(e.values[0], e.values[1])
                if (!ajudas.precisaDoNivel) return
                // Do vector da gravidade tiram-se as duas inclinações. Com o telefone em retrato, x é
                // o rolamento lateral e y a inclinação para a frente.
                val x = e.values[0]
                val y = e.values[1]
                val z = e.values[2]
                val rol = Math.toDegrees(Math.atan2(x.toDouble(), y.toDouble())).toFloat()
                val inc = Math.toDegrees(
                    Math.atan2(z.toDouble(), Math.hypot(x.toDouble(), y.toDouble()))).toFloat()
                ajudas.definirNivel(rol, inc)
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(ouvinte, acel, android.hardware.SensorManager.SENSOR_DELAY_UI)
        sensores = sm
        ouvinteDoNivel = ouvinte
    }

    /**
     * De que lado está o telefone, a partir da gravidade.
     *
     * A janela está travada em retrato de propósito — a UI não roda —, por isso o `display.rotation`
     * fica sempre a zero e não serve. Quem sabe é o acelerómetro, que já está ligado para o nível.
     *
     * **Zona morta de 20 graus** à volta de cada quadrante. Sem ela, um telefone a 45 graus alternava
     * entre dois valores dezenas de vezes por segundo, e cada alternância rodava a imagem no visor —
     * inutilizável. Com ela, a rotação só muda quando é intenção e não tremor.
     */
    private fun orientacaoDoCorpo(x: Float, y: Float) {
        // Deitado de todo — ecrã para cima ou para baixo — não há lado nenhum a deduzir da gravidade,
        // e mudar aí seria adivinhar. Fica onde estava.
        if (Math.hypot(x.toDouble(), y.toDouble()) < 4.0) return
        val angulo = Math.toDegrees(Math.atan2(-x.toDouble(), y.toDouble()))
        val novo = when {
            angulo > -25 && angulo < 25 -> 0
            // **O sinal é o contrário do intuitivo**, e foi medido.
            //
            // O `rotationFor` espera o que o Android chama rotação do *ecrã*: quanto o conteúdo tem de
            // rodar para compensar o corpo, e não quanto o corpo rodou. Deitar o telefone para a
            // esquerda dá `ROTATION_90`, e a gravidade nessa posição lê-se a −91 graus. Tinha-os
            // trocados, e o ficheiro saía com 180 — a fotografia de pernas para o ar.
            angulo > 65 && angulo < 115 -> 270
            angulo < -65 && angulo > -115 -> 90
            Math.abs(angulo) > 155 -> 180
            else -> return
        }
        if (novo == rotacaoDoCorpo) return
        rotacaoDoCorpo = novo
        render?.definirRotacaoDoCorpo(novo)
    }

    private var rotacaoDoCorpo = 0

    /**
     * A exposição com que a objectiva nova arranca: a luz da anterior, corrigida pela abertura.
     *
     * Duas aberturas diferentes precisam de tempos diferentes para a mesma cena. Passar o tempo e o ISO
     * tal e qual daria uma primeira imagem errada por 0,58 stops na troca deste corpo.
     */
    private fun exposicaoDeArranque(nova: LensProfile): io.github.bmcsilva.latente.model.Exposure? {
        val anterior = arranque ?: return null
        val fNova = nova.apertures.firstOrNull() ?: return anterior
        if (arranqueDeAbertura <= 0f || fNova <= 0f) return anterior
        // A luz varia com o quadrado da abertura: mais f, menos luz, e o tempo compensa.
        val factor = (fNova * fNova) / (arranqueDeAbertura * arranqueDeAbertura)
        return anterior.copy(
            exposureNs = Math.round(anterior.exposureNs * factor.toDouble())
                .coerceIn(nova.exposureMinNs, nova.exposureMaxNs))
    }

    /** Arranca um fio de render novo sobre a superfície que já existe. */
    private fun reiniciarRender() {
        val janela = superficieViva ?: return
        val l = lente ?: return
        parar()
        val r = Render(this, l, janela, larguraViva, alturaViva,
            rotacaoDoCorpo, etiqueta, exposicaoDeArranque(l),
            { texto -> ui.post { dizer(texto); botaoDoDisparo?.ocupado = false } },
            { t -> ui.post {
                mostrar(t)
                ajudas.definirHistograma(render?.histograma())
            } })
        render = r
        r.start()
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .unregisterDisplayListener(ouvinteDoEcra)
        // Fecha e publica: uma medição de uso prolongado acaba quando o visor sai de cena, que é
        // também quando o telefone deixa de aquecer por nossa causa.
        registoDeUso?.parar()?.let { dizer(it + " escrito") }
        ouvinteDoNivel?.let { sensores?.unregisterListener(it) }
        ouvinteDoNivel = null
        // A câmara larga-se ao sair de cena. Se não, o sistema revoga-a e o HAL fecha o dispositivo.
        parar()
    }

    private fun parar() {
        render?.let {
            // Antes de o fio morrer, fixa-se o que está escolhido — é o `onPause` da câmara. Sem isto,
            // com as escritas fora do laço, sair sem largar a barra perdia a última afinação.
            it.guardarEscolhas()
            it.parar()
            try {
                it.join(3000)
            } catch (t: InterruptedException) {
                // ignorar
            }
        }
        render = null
    }

    /**
     * Refaz a fila de pastilhas para o que a objectiva e o modo oferecem agora.
     *
     * Refaz-se em vez de se esconderem umas e mostrarem outras porque o que muda é a **lista**: trocar
     * de P para M acrescenta duas, trocar para a 66 mm tira três. E a altura da fila não muda nunca —
     * as pastilhas ficam lado a lado —, o que importa: uma faixa que crescesse reiniciava a câmara.
     */
    /**
     * A assinatura da fila: que posições estão disponíveis e qual delas está activa.
     *
     * Serve para não refazer seis vistas por segundo. A fila só muda quando o modo, a objectiva ou a
     * escolha mudam — mas **tem** de mudar aí, e essa era a falha: construía-se uma vez no fim do
     * `onCreate`, quando o fio de render ainda não existia e o modo lido era o de omissão. Ficava com
     * as quatro pastilhas do P por baixo de um `MODO M`.
     */
    private var assinaturaDoAnel = -1

    private fun rotularAnel() {
        if (!::pastilhas.isInitialized) return
        var assinatura = anel
        for (i in 0 until ANEL_POSICOES) {
            if (posicaoPermitida(i)) assinatura = assinatura or (1 shl (i + 8))
        }
        if (assinatura == assinaturaDoAnel) return
        assinaturaDoAnel = assinatura
        barra?.corDoNivel = corDoParametro(anel)
        pastilhas.removeAllViews()
        // Uma fila, nos dois sentidos.
        //
        // Em paisagem iam a três porque viviam na coluna estreita, onde seis davam 40 dp cada e
        // «KELVIN» saía cortado em «KELVI». Agora vivem na banda por baixo da imagem, que tem a largura
        // dela: as seis cabem, e a segunda fila deixa de roubar altura ao visor.
        val porFila = ANEL_POSICOES
        pastilhas.orientation = LinearLayout.VERTICAL
        var fila: LinearLayout? = null
        var quantas = 0
        for (i in 0 until ANEL_POSICOES) {
            if (!posicaoPermitida(i)) continue
            if (fila == null || quantas == porFila) {
                fila = LinearLayout(this)
                fila.orientation = LinearLayout.HORIZONTAL
                val lpFila = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
                if (pastilhas.childCount > 0) lpFila.topMargin = dp(5)
                pastilhas.addView(fila, lpFila)
                quantas = 0
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (quantas > 0) lp.leftMargin = dp(5)
            fila.addView(pastilha(i), lp)
            quantas++
        }
    }

    /**
     * O menu das ajudas: os quatro instrumentos, com os ligados acesos.
     *
     * Fica aberto entre escolhas, porque elas **combinam** — ver as zebras do corte com o histograma ao
     * lado é uso normal, e fechar a cada toque obrigava a reabrir para a segunda.
     */
    private fun menuDeAjudas(ancora: View) {
        val bits = intArrayOf(AJUDA_PICOS, AJUDA_ZEBRAS, AJUDA_HISTOGRAMA, AJUDA_NIVEL)
        val opcoes = ArrayList<Opcao>()
        for (b in bits) {
            opcoes.add(Opcao(b, nomeDaAjuda(b).uppercase(), detalheDaAjuda(b), (ajuda and b) != 0))
        }
        // O menu repinta-se onde está. Reabria-se para o ponto do lado mudar de estado à vista, e isso
        // empilhava uma janela por escolha: com as quatro ajudas ligadas eram cinco toques para fechar.
        PickerPopup.mostrar(this, ancora, opcoes, multipla = true,
            estaActiva = { bit -> (ajuda and bit) != 0 }) { bit ->
            ajuda = ajuda xor bit
            prefs?.aids = ajuda
            aplicarAjudas()
        }
    }

    /**
     * Para onde se pode ir a partir do visor.
     *
     * Dois destinos, e os dois são visitas: os negativos vêem-se depois de fotografar, as experiências
     * correm-se quando se está a medir alguma coisa. Nenhum é caminho de cada fotografia, e por isso
     * ficam atrás de um toque em vez de gastarem largura na fila do disparo.
     */
    private fun menuDeDestinos(ancora: View) {
        val opcoes = listOf(
            Opcao(0, "NEGATIVOS", "as fotografias e o que o HAL fez"),
            Opcao(1, "EXPERIÊNCIAS", "sondas, medições e certificado"))
        PickerPopup.mostrar(this, ancora, opcoes, multipla = false) { qual ->
            val destino = if (qual == 0) LibraryActivity::class.java else MainActivity::class.java
            startActivity(android.content.Intent(this, destino))
        }
    }

    private fun detalheDaAjuda(bit: Int): String = when (bit) {
        AJUDA_PICOS -> "arestas nítidas a ciano"
        AJUDA_ZEBRAS -> "onde o sensor já cortou"
        AJUDA_HISTOGRAMA -> "distribuição do verde do sensor"
        else -> "rolamento e inclinação"
    }

    /**
     * O menu das objectivas, **com as recusadas à vista**.
     *
     * É a razão principal de haver menu. Uma objectiva que o corpo tem e a aplicação não usa, se
     * simplesmente não aparecer, parece defeito nosso — e quem fotografa nunca sabe que ela existe nem
     * porque não está lá. Aqui aparece apagada, com o motivo por baixo.
     */
    private fun menuDeObjectivas(ancora: View) {
        val opcoes = ArrayList<Opcao>()
        for (i in objectivas.indices) {
            val l = objectivas[i]
            opcoes.add(Opcao(i, l.equivalentFocalMm.toString() + " MM", detalheDaObjectiva(l),
                l.cameraId == lente?.cameraId))
        }
        for (l in recusadas) {
            opcoes.add(Opcao(-1, l.equivalentFocalMm.toString() + " MM",
                "fora: " + razaoDaRecusa(l), activa = false, disponivel = false))
        }
        PickerPopup.mostrar(this, ancora, opcoes, multipla = false) { i ->
            if (i >= 0 && objectivas[i].cameraId != lente?.cameraId) trocarPara?.invoke(i)
        }
    }

    private fun detalheDaObjectiva(l: LensProfile): String {
        val sb = StringBuilder()
        l.apertures.firstOrNull()?.let {
            sb.append(String.format(java.util.Locale.US, "f/%.1f", it))
        }
        // A ordem importa: sem motor é **foco fixo**, e chamar-lhe «da câmara» dava a entender que há
        // um autofoco a trabalhar quando não há nada a mexer-se.
        if (!l.manualExposure) sb.append(" · exposição da câmara")
        else if (l.minFocusDiopters <= 0f) sb.append(" · foco fixo")
        else if (!l.manualFocus) sb.append(" · foco da câmara")
        return sb.toString()
    }

    private fun razaoDaRecusa(l: LensProfile): String =
        if (l.blocking.any { it.contains("RAW") }) "não dá RAW" else l.blocking.joinToString(", ")

    /**
     * O menu dos modos.
     *
     * Sem o A — a abertura destas objectivas é fixa e ele fazia o mesmo que o P. E numa objectiva onde
     * a exposição é da câmara os três aparecem apagados, com a razão: é mais honesto do que um botão
     * que não responde.
     */
    private fun menuDeModos(ancora: View) {
        val podeEscolher = lente?.manualExposure ?: true
        val actual = modoEmVigor()
        val opcoes = ArrayList<Opcao>()
        val ordem = arrayOf(
            ExposureProgram.Mode.P, ExposureProgram.Mode.S, ExposureProgram.Mode.M)
        val descricao = arrayOf(
            "a aplicação escolhe tempo e ISO",
            "escolhes o tempo, ela escolhe o ISO",
            "escolhes tempo e ISO; o fotómetro só aconselha")
        for (i in ordem.indices) {
            opcoes.add(Opcao(i, ordem[i].name, if (podeEscolher) {
                descricao[i]
            } else {
                "indisponível: a exposição é da câmara"
            }, podeEscolher && ordem[i] == actual, podeEscolher))
        }
        PickerPopup.mostrar(this, ancora, opcoes, multipla = false) { i ->
            render?.definirModo(ordem[i])
            // Mudar de modo muda quem possui o tempo e o ISO. Se o anel estava numa posição que o modo
            // novo não tem, sai dela — senão ficava um comando a mexer no que já não é do utilizador.
            if (!posicaoPermitida(anel)) {
                anel = proximaPosicao(anel)
                prefs?.dial = anel
            }
            reporBarra()
            rotularAnel()
        }
    }

    private fun textoDeAjudas(): String {
        var quantas = 0
        for (bit in intArrayOf(AJUDA_PICOS, AJUDA_ZEBRAS, AJUDA_HISTOGRAMA, AJUDA_NIVEL)) {
            if ((ajuda and bit) != 0) quantas++
        }
        return if (quantas == 0) "AJUDAS" else "AJUDAS $quantas"
    }

    private fun nomeDaAjuda(bit: Int): String = when (bit) {
        AJUDA_PICOS -> "realce de foco"
        AJUDA_ZEBRAS -> "zebras do corte"
        AJUDA_HISTOGRAMA -> "histograma"
        else -> "nível"
    }

    /** Põe o estado das ajudas onde ele tem de estar: no fio de render, na vista e nos botões. */
    private fun aplicarAjudas() {
        render?.definirPicos((ajuda and AJUDA_PICOS) != 0)
        render?.definirZebras((ajuda and AJUDA_ZEBRAS) != 0)
        ajudas.definirMostrar(
            (ajuda and AJUDA_HISTOGRAMA) != 0, (ajuda and AJUDA_NIVEL) != 0)
        // O botão conta as que estão ligadas: com o menu fechado, uma ajuda acesa que não se vê no
        // ecrã é uma ajuda esquecida.
        botaoDeAjudas?.let { rotularBotaoDeMenu(it, textoDeAjudas(), ajuda != 0) }
    }

    /**
     * Um botão que abre menu.
     *
     * Os três — ajudas, modo, objectiva — eram os últimos rectângulos cinzentos de um ecrã que já
     * falava por pastilhas. Ficam com a mesma forma do resto e com um **acento** à frente do nome, que
     * é o que distingue «isto abre uma lista» de «isto faz uma coisa». Sem ele, um botão que abre menu
     * é indistinguível de um botão que dispara.
     */
    /** @param lua 0 sem mordida, 1 mordido à esquerda, 2 mordido à direita. */
    private fun botaoDeMenu(texto: String, lua: Int = 0): TextView {
        val v = TextView(this)
        v.tag = lua
        v.gravity = android.view.Gravity.CENTER
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        v.letterSpacing = 0.02f
        v.maxLines = 1
        v.isClickable = true
        rotularBotaoDeMenu(v, texto, false)
        return v
    }

    /**
     * @param aceso o botão está a mandar em alguma coisa neste momento — usa-se nas ajudas, onde
     *   importa saber com o menu fechado que há instrumentos a trabalhar. O modo e a objectiva estão
     *   sempre a valer alguma coisa, e acendê-los seria acender o ecrã todo.
     */
    private fun rotularBotaoDeMenu(v: TextView, texto: String, aceso: Boolean) {
        // O acento é um triângulo **mais pequeno e apagado** do que o nome, e não um caracter do mesmo
        // tamanho ao lado dele. Assim lê-se como marca de «abre lista» e não como parte do valor —
        // «23 MM ⌄» dava a entender que o ⌄ dizia alguma coisa sobre a objectiva.
        // O acento volta ao texto, mais pequeno e apagado — era como estava e é como se prefere. Fica
        // parte do rótulo, e por isso o botão tem de ter largura para o nome **e** para ele: foi por
        // falta dela que o «23 MM» apareceu cortado em «23».
        val t = android.text.SpannableString("$texto  ▾")
        val i = texto.length + 2
        t.setSpan(android.text.style.RelativeSizeSpan(0.72f), i, t.length, 0)
        t.setSpan(android.text.style.ForegroundColorSpan(0xFF7A8085.toInt()), i, t.length, 0)
        v.text = t
        v.setTextColor(if (aceso) CIANO else Color.WHITE)
        val fundo = if (aceso) Palette.PASTILHA_ACESA else Palette.PASTILHA
        val contorno = if (aceso) CIANO else Palette.CONTORNO
        val lua = v.tag as? Int ?: 0
        // O preenchimento é feito **aqui** e não na criação do botão, porque o lado da mordida muda com
        // o ecrã: o mesmo «AJUDAS» é mordido à direita em retrato e na base em paisagem. Posto uma vez
        // só, ficava com o preenchimento do retrato e o texto sentava-se em cima da curva — e os dois
        // ramos verticais nunca chegavam a correr, porque nenhum botão nasce mordido em cima ou em
        // baixo.
        //
        // O texto centra-se no que sobra: a mordida come de um lado, o acento do outro. Apertado de
        // propósito — «66 MM» tem de caber, e cada dp de preenchimento é um dp que lhe falta.
        when (lua) {
            1 -> v.setPadding(dp(13), 0, dp(2), 0)
            2 -> v.setPadding(dp(2), 0, dp(13), 0)
            // Na vertical a concavidade come `LUA_FUNDO` a meio da aresta, que é onde o texto está. O
            // preenchimento tira-o de lá: o texto centra-se no que resta e não dentro da curva.
            3 -> v.setPadding(dp(4), dp(LUA_FUNDO + 2), dp(4), 0)
            4 -> v.setPadding(dp(4), 0, dp(4), dp(LUA_FUNDO + 2))
            else -> v.setPadding(dp(4), 0, dp(4), 0)
        }
        // A profundidade não é a mesma nos dois sentidos, e não é gosto: é a aresta que muda. Em
        // retrato morde-se a altura, 44 dp, e o círculo de 39 atravessa-a inteira com 8 de fundo. Em
        // paisagem morde-se a largura, 76 dp, e para o mesmo círculo atravessar uma aresta dessas tem
        // de entrar `LUA_FUNDO`. As duas curvas são a mesma — a do disparador.
        val fundura = when (lua) {
            0 -> 0f
            3, 4 -> dp(LUA_FUNDO).toFloat()
            else -> dp(LUA_MORDIDA).toFloat()
        }
        v.background = MoonBackground(fundo, contorno, dp(1).toFloat(),
            dp(LUA_RAIO).toFloat(), fundura, lado = lua)

    }

    /** Uma pastilha: o nome do parâmetro, e o fundo a dizer se é ela que o comando mexe. */
    private fun pastilha(i: Int): TextView {
        val activa = i == anel
        val v = TextView(this)
        v.text = when (i) {
            ANEL_FOCO -> "FOCO"
            ANEL_KELVIN -> "KELVIN"
            ANEL_TINTA -> "TINTA"
            ANEL_EV -> "EV"
            ANEL_TEMPO -> "TEMPO"
            else -> "ISO"
        }
        v.gravity = android.view.Gravity.CENTER
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        v.letterSpacing = 0.08f
        v.maxLines = 1
        // A activa em ciano e o resto apagado. Nesta aplicação ciano quer dizer «é isto que o
        // instrumento está a fazer» — é a cor do realce de foco, e usá-la aqui é a mesma frase.
        v.setTextColor(if (activa) PRETO else CINZA)
        val fundo = android.graphics.drawable.GradientDrawable()
        fundo.cornerRadius = dp(17).toFloat()
        fundo.setColor(if (activa) corDoParametro(i) else Palette.PASTILHA)
        v.background = fundo
        val margem = dp(3)
        v.setPadding(margem, 0, margem, 0)
        v.setOnClickListener {
            anel = i
            prefs?.dial = i
            reporBarra()
            rotularAnel()
        }
        // Toque longo na pastilha do parâmetro: repõe-no.
        //
        // Aqui e não noutro sítio porque é o botão que tem o nome dele escrito — quem carrega está a
        // olhar para «KELVIN» e sabe o que vai voltar ao sítio.
        v.setOnLongClickListener {
            anel = i
            reporValor()
            true
        }
        return v
    }

    /**
     * A posição do anel pertence ao modo em vigor?
     *
     * O tempo e o ISO só aparecem a quem os decide. Em P a aplicação escolhe os dois; em S o tempo é
     * do utilizador e o ISO é a resposta do fotómetro; em M são os dois do utilizador. Oferecer um
     * comando que o modo ignora seria a interface a fingir controlo.
     */
    /**
     * O modo em vigor — do fio de render se ele já existe, do disco se ainda não.
     *
     * No arranque o fio de render ainda não abriu a câmara, e cair no P por omissão fazia o ecrã pintar
     * as quatro pastilhas do P por baixo de um `MODO M` guardado. Corrigia-se um segundo depois, ao
     * primeiro relato — e um segundo de interface errada é interface errada.
     */
    private fun modoEmVigor(): ExposureProgram.Mode {
        render?.let { return it.modoActual() }
        val guardado = prefs?.mode ?: ExposureProgram.Mode.P
        return if (guardado == ExposureProgram.Mode.A) ExposureProgram.Mode.P else guardado
    }

    private fun posicaoPermitida(i: Int): Boolean {
        val m = modoEmVigor()
        // O foco: três estados, e só num deles há o que comandar. Sem motor não vai a lado nenhum;
        // com motor mas sem a chave `LENS_FOCUS_DISTANCE`, quem o move é a câmara.
        if (i == ANEL_FOCO && (maxDioptrias <= 0f || lente?.manualFocus == false)) return false
        // Numa objectiva onde a exposição é da câmara, nada que a mexa faz sentido: nem o tempo, nem o
        // ISO, nem a compensação, que desloca o alvo de um fotómetro que aqui não manda em nada. Ficam
        // o foco, a temperatura e a tinta — que são revelação, e essa continua a ser toda nossa.
        val exposicaoNossa = lente?.manualExposure ?: true
        if (!exposicaoNossa && (i == ANEL_TEMPO || i == ANEL_ISO || i == ANEL_EV)) return false
        if (i == ANEL_TEMPO) return m == ExposureProgram.Mode.S || m == ExposureProgram.Mode.M
        if (i == ANEL_ISO) return m == ExposureProgram.Mode.M
        return true
    }

    private fun proximaPosicao(actual: Int): Int {
        var i = actual
        for (passo in 1..ANEL_POSICOES) {
            i = (i + 1) % ANEL_POSICOES
            if (posicaoPermitida(i)) return i
        }
        return ANEL_KELVIN
    }

    /**
     * Repõe o parâmetro em que o anel está.
     *
     * Para o foco, a temperatura, a tinta e a compensação há um valor **neutro** evidente: infinito,
     * luz do dia, o locus de Planck, zero. Para o tempo e o ISO não há — o que faz sentido repor é o
     * que o fotómetro diz, que em manual é a pergunta que se faz mesmo: «e se eu deixasse a máquina
     * decidir isto?». Aplica-se a correcção que ele está a aconselhar e o outro eixo fica onde estava.
     */
    private fun reporValor() {
        val r = render ?: return
        when (anel) {
            ANEL_FOCO -> {
                r.pedirFoco(0f)
                dizer("reposto · foco em ∞")
            }
            ANEL_KELVIN -> {
                r.pedirKelvin(5500)
                dizer("reposto · 5500 K, luz do dia")
            }
            ANEL_TINTA -> {
                r.pedirTinta(0f)
                dizer("reposto · tinta 0.00")
            }
            ANEL_EV -> {
                r.pedirCompensacao(0f)
                dizer("reposto · compensação 0.0 EV")
            }
            ANEL_TEMPO -> dizer("reposto · " + r.tempoPeloFotometro())
            else -> dizer("reposto · " + r.isoPeloFotometro())
        }
        // O foco, o tempo e o ISO passam pelo laço de render antes de entrarem em vigor; a barra e a
        // gravação têm de esperar por eles, senão a barra saltava para o valor antigo e o disco ficava
        // com o valor que se acabou de substituir.
        ui.postDelayed({
            render?.guardarEscolhas()
            reporBarra()
            rotularAnel()
        }, 300)
    }

    /**
     * A barra no valor guardado, para o arranque.
     *
     * O `reporBarra` precisa do fio de render, que no arranque não existe — e a barra ficava a zero por
     * baixo de um Kelvin de 4251 guardado. Aqui os valores vêm do disco, que é de onde o fio de render
     * também os vai buscar.
     */
    private fun reporBarraDeGuardado() {
        val p = prefs ?: return
        val v = when (anel) {
            ANEL_FOCO -> if (maxDioptrias > 0f) {
                Math.round(1000f * p.focusDiopters / maxDioptrias)
            } else {
                0
            }
            ANEL_KELVIN -> Math.round(
                1000f * (1e6f / p.kelvin - MIRED_MIN) / (MIRED_MAX - MIRED_MIN))
            ANEL_TINTA -> Math.round(500f * (p.tint + 1f))
            ANEL_EV -> Math.round(1000f * (p.compensation + 3f) / 6f)
            ANEL_TEMPO -> lente?.let {
                inversoLog(p.manualExposureNs.toDouble(),
                    it.exposureMinNs.toDouble(), it.exposureMaxNs.toDouble())
            } ?: 0
            else -> lente?.let {
                inversoLog(p.manualIso.toDouble(), it.isoMin.toDouble(), it.isoMax.toDouble())
            } ?: 0
        }
        barra?.definirValor(v.coerceIn(0, 1000), false)
    }

    /** A barra salta para onde o parâmetro está agora, senão o primeiro toque dava um salto. */
    private fun reporBarra() {
        val r = render ?: return
        val l = lente
        val v = when (anel) {
            ANEL_FOCO -> Math.round(1000f * r.focoActual() / maxDioptrias)
            ANEL_KELVIN -> Math.round(
                1000f * (1e6f / r.kelvinActual() - MIRED_MIN) / (MIRED_MAX - MIRED_MIN))
            ANEL_TINTA -> Math.round(500f * (r.tintaActual() + 1f))
            ANEL_EV -> Math.round(1000f * (r.compensacaoActual() + 3f) / 6f)
            ANEL_TEMPO -> if (l == null) 0 else inversoLog(
                r.exposicaoActual().exposureNs.toDouble(),
                l.exposureMinNs.toDouble(), l.exposureMaxNs.toDouble())
            else -> if (l == null) 0 else inversoLog(
                r.exposicaoActual().iso.toDouble(), l.isoMin.toDouble(), l.isoMax.toDouble())
        }
        barra?.definirValor(v.coerceIn(0, 1000), false)
    }

    /** O curso da barra repartido em razão constante: cada passo vale a mesma fracção de stop. */
    private fun escalaLog(v: Int, minimo: Double, maximo: Double): Double =
        minimo * Math.pow(maximo / minimo, v / 1000.0)

    private fun inversoLog(valor: Double, minimo: Double, maximo: Double): Int =
        Math.round(1000.0 * Math.log(valor.coerceIn(minimo, maximo) / minimo) /
                Math.log(maximo / minimo)).toInt()

    /**
     * A telemetria em campos, e não numa cadeia formatada.
     *
     * Era uma cadeia com espaços a fazer de colunas, e isso tem dois preços: as colunas só alinham em
     * fonte monoespaçada, e quem mostra tem de a voltar a partir para lhe mudar a cor ou o tamanho.
     * Com campos, o fio de render diz **o que** mediu e o ecrã decide como se vê.
     */
    private class Telemetria {
        val avisos = ArrayList<String>()
        var modo = "—"
        var margem: String? = null
        var cortado: String? = null
        var fotometro: String? = null
        var tempo = "—"
        var abertura = "—"
        var iso = "—"
        var foco = "—"
        var kelvin = "—"
        var tinta = "—"
        var compensacao: String? = null
        var visor: String? = null
        var vinheta = ""
        var fps = ""
        /** O mesmo número, por medir e não por escrever: é o que o registo de uso prolongado guarda. */
        var fpsMedidos = 0.0
    }

    /** Põe no ecrã o que o fio de render mediu. Corre na thread principal. */
    private fun mostrar(t: Telemetria) {
        if (t.avisos.isEmpty()) {
            avisosView.visibility = View.INVISIBLE
        } else {
            avisosView.visibility = View.VISIBLE
            // **Só quando muda.** Escrever o mesmo texto reinicia o deslize, e como a telemetria se
            // refresca a cada segundo a linha ficava a saltar para o princípio sem nunca andar.
            val texto = t.avisos.joinToString("  ·  ")
            if (avisosView.text.toString() != texto) avisosView.text = texto
        }
        campoModo.text = t.fotometro?.let { t.modo + " " + it } ?: t.modo
        // O botão diz o mesmo que o campo. Antes escrevia-se só no clique, e ficava a mentir sempre
        // que o modo mudava por outra via — trocar para uma objectiva sem exposição nossa deixava lá
        // um «M» por baixo de um MODO AUTO.
        botaoDoModo?.let { rotularBotaoDeMenu(it, t.modo, false) }
        // O fio de render é a fonte do modo, e só existe depois do ecrã. Reavalia-se aqui, que é onde
        // se sabe a verdade; a assinatura garante que só se refaz quando alguma coisa mudou mesmo.
        rotularAnel()
        campoMargem.text = t.margem ?: "—"
        campoCortado.text = t.cortado ?: "—"
        campoTempo.text = t.tempo
        campoAbertura.text = t.abertura
        campoIso.text = t.iso
        campoFoco.text = t.foco
        campoKelvin.text = t.kelvin
        campoTinta.text = t.tinta
        campoEv.text = t.compensacao ?: "—"
        if (t.visor == null) {
            campoVisor.visibility = View.INVISIBLE
        } else {
            campoVisor.visibility = View.VISIBLE
            campoVisor.text = t.visor
        }
        vinhetaView.text = t.vinheta
        fpsView.text = t.fps
        registoDeUso?.fps = t.fpsMedidos
    }

    /**
     * A linha do fundo: o que a aplicação **acabou de fazer**.
     *
     * Não são dicas nem telemetria — é o registo do último acontecimento: um disparo, o ficheiro que se
     * escreveu, uma reposição, um erro. Apaga-se sozinha passados uns segundos, porque uma mensagem que
     * fica deixa de ser notícia e passa a ser decoração; e ao lado de campos que dizem sempre a verdade
     * do momento, uma linha velha lê-se como se ainda fosse verdade.
     */
    private fun dizer(texto: String) {
        estado.text = texto
        ui.removeCallbacks(apagarMensagem)
        ui.postDelayed(apagarMensagem, 5000)
    }

    private val apagarMensagem = Runnable { estado.text = "" }

    /**
     * Porque é que uma objectiva do corpo não está na lista.
     *
     * A razão do HAL por extenso — `MANUAL_SENSOR`, chaves — não diz nada a quem fotografa. O que diz
     * é a consequência: naquela objectiva a câmara não deixa escolher o tempo nem o ISO, e esta
     * aplicação existe para os escolher.
     */
    private fun porqueFaltam(): String? {
        if (recusadas.isEmpty()) return null
        val sb = StringBuilder()
        for (l in recusadas) {
            if (sb.isNotEmpty()) sb.append("  ·  ")
            sb.append(l.equivalentFocalMm).append(" mm fora: ")
            sb.append(
                if (l.blocking.any { it.contains("RAW") }) {
                    "sem RAW"
                } else {
                    "a câmara não deixa escolher tempo nem ISO"
                })
        }
        return sb.toString()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Um instrumento: etiqueta fixa em cima, valor por baixo.
     *
     * A etiqueta não muda nunca e o valor alinha sempre no mesmo sítio — é o que deixa ler um número
     * sem o procurar. A etiqueta é pequena e cinzenta de propósito: quem olha para uma câmara ao olho
     * já sabe o que são os campos, e quer o valor.
     */
    private fun campo(pai: LinearLayout, etiqueta: String, peso: Float): TextView {
        val caixa = LinearLayout(this)
        caixa.orientation = LinearLayout.VERTICAL

        val e = TextView(this)
        e.text = etiqueta
        e.setTextColor(CINZA)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (emPaisagem) 8f else 9f)
        e.letterSpacing = 0.12f
        // Uma linha, sempre. Sem isto a etiqueta parte-se quando a coluna aperta — «TEMPERATU / RA»,
        // «MAR / GEM» — e uma grelha cujas etiquetas mudam de altura desalinha os valores todos.
        e.maxLines = 1
        e.ellipsize = android.text.TextUtils.TruncateAt.END
        caixa.addView(e)

        val v = TextView(this)
        v.text = "—"
        v.setTextColor(Color.WHITE)
        // Mais pequeno em paisagem, e é aritmética e não gosto: cinco linhas de campos a 16 sp, mais o
        // aviso, a barra e as pastilhas, pedem mais altura do que a coluna tem.
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (emPaisagem) 14f else 16f)
        v.typeface = Typeface.MONOSPACE
        v.maxLines = 1
        caixa.addView(v)

        val lp = if (peso > 0f) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, peso)
        } else {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (pai.childCount > 0) lp.leftMargin = dp(14)
        pai.addView(caixa, lp)
        return v
    }

    /** A rotação do ecrã em graus. Portrait é 0. */
    private fun rotacaoDoEcra(): Int = when (display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    // -----------------------------------------------------------------------------------------

    /**
     * O fio de render: dono do contexto EGL, da sessão de câmara e do laço.
     *
     * Tudo numa thread só por obrigação do EGL, e é também o desenho certo — o laço bloqueia à espera
     * do frame seguinte, coisa que não se faz na thread principal.
     */
    private class Render(
        private val ctx: Context,
        private val lente: LensProfile,
        private val janela: Surface,
        private val larguraVista: Int,
        private val alturaVista: Int,
        private val rotacaoEcra: Int,
        private val etiquetaDoCorpo: String,
        private val exposicaoDeArranque: io.github.bmcsilva.latente.model.Exposure? = null,
        private val relatar: (String) -> Unit,
        /**
         * A telemetria, por um canal próprio.
         *
         * Separada das mensagens porque são coisas diferentes: a telemetria é o estado contínuo dos
         * instrumentos e substitui-se a cada segundo; uma mensagem é um acontecimento e fica. Pelo mesmo
         * canal, um «a disparar…» apagava a leitura toda.
         */
        private val mostrar: (Telemetria) -> Unit,
    ) : Thread("latente-visor") {

        @Volatile
        private var parado = false

        /**
         * Pedido de disparo, atendido pelo fio de render.
         *
         * O botão não dispara: **marca**. Quem dispara é o laço, entre dois frames, porque a sessão de
         * câmara pertence a este fio e chamá-la de fora seria pedir uma corrida.
         */
        @Volatile
        private var pedidoDeDisparo = false

        private var contador = 1

        /**
         * A orientação **física** do telefone, em graus, e a do sensor no corpo.
         *
         * Eram uma coisa só, lida no arranque, e enquanto o ecrã esteve travado em retrato isso nunca
         * se notou. Não é aceitável numa câmara: quem deita o telefone quer a fotografia deitada, e
         * quer vê-la de pé no visor enquanto a compõe.
         *
         * Vem do acelerómetro e não do `display.rotation`, porque a janela continua travada em retrato
         * — o sistema não roda, e é o corpo que se inclina. É a mesma leitura que alimenta o nível.
         */
        @Volatile
        private var rotacaoDoCorpo = rotacaoEcra

        /**
         * A rotação do **ecrã**, que é outra coisa e nem sempre coincide.
         *
         * Coincidem com a rotação automática ligada e o telefone de pé. Deixam de coincidir em dois
         * casos, e nos dois quem manda no que se desenha é o ecrã: com o telefone **pousado na mesa**,
         * onde a gravidade não diz lado nenhum e o corpo fica com a última leitura; e com a rotação
         * **travada**, onde o sistema não roda e virar o telefone tem mesmo de virar a cena.
         */
        @Volatile
        private var rotacaoDoEcra = rotacaoEcra

        private var orientacaoDoSensor = 90

        fun definirRotacaoDoCorpo(graus: Int) {
            rotacaoDoCorpo = graus
        }

        fun definirRotacaoDoEcra(graus: Int) {
            rotacaoDoEcra = graus
        }

        /**
         * Quanto é preciso rodar o mosaico para a cena aparecer de pé **no ecrã**.
         *
         * Seguia o acelerómetro, e havia um caso em que isso saía errado: telefone pousado na mesa, em
         * retrato, com a última leitura do corpo em paisagem — a imagem aparecia deitada, uma faixa
         * larga dentro de uma caixa alta. A `SurfaceView` está presa ao ecrã, e por isso é o ecrã que
         * decide como se desenha.
         */
        private fun rotacaoDoVisor(): Int =
            Present.rotationFor(orientacaoDoSensor, rotacaoDoEcra)

        /**
         * Quanto é preciso rodar o mosaico para a fotografia sair de pé **no ficheiro**.
         *
         * Esta continua a vir do acelerómetro, e é a diferença que interessa: com a rotação travada, o
         * ecrã fica em retrato e a fotografia tem de sair na posição em que o corpo estava. Os dois
         * caminhos mostram os mesmos píxeis; o que muda é a etiqueta de para que lado está o mundo.
         */
        private fun rotacaoDaImagem(): Int =
            Present.rotationFor(orientacaoDoSensor, rotacaoDoCorpo)

        /** O modo pedido pelo botão. Lido pelo laço, escrito pela thread principal. */
        private val prefs = Settings(ctx)

        // Um `A` guardado por uma versão anterior entra como P: é o que ele fazia, e ficaria preso
        // porque o ciclo dos modos já não passa por lá.
        @Volatile
        private var modo = if (prefs.mode == ExposureProgram.Mode.A) {
            ExposureProgram.Mode.P
        } else {
            prefs.mode
        }

        /** Compensação de exposição, em stops. Desloca o alvo do fotómetro, não o resultado. */
        @Volatile
        private var compensacao = prefs.compensation

        /** Limiar do peaking. Zero desliga. */
        @Volatile
        private var picos = if (prefs.peaking) LIMIAR_DE_PICOS else 0f

        @Volatile
        private var zebras = false

        /** Foco pedido, em dioptrias. 0 é infinito; o máximo é a distância mínima da objectiva. */
        @Volatile
        private var focoPedido = -1f

        @Volatile
        private var pedidoDeVarrimento = false

        // Os pedidos do comando **não** gravam: gravar aqui punha uma escrita em disco por tique da
        // barra, e um arrasto de ponta a ponta dá mil tiques. Quem grava é o `guardarEscolhas`, no fim
        // do gesto e ao sair de cena.
        fun pedirFoco(dioptrias: Float) {
            focoPedido = dioptrias
        }

        fun pedirKelvin(k: Int) {
            kelvin = k
        }

        fun pedirTinta(t: Float) {
            tinta = t
        }

        fun pedirCompensacao(ev: Float) {
            compensacao = ev
        }

        // Zero quer dizer «nada pedido». Como o foco: quem toca marca, quem aplica é o laço, porque a
        // sessão de câmara pertence ao fio de render e chamá-la de fora seria pedir uma corrida.
        @Volatile
        private var tempoPedido = 0L

        @Volatile
        private var isoPedido = 0

        fun pedirTempo(ns: Long) {
            tempoPedido = ns.coerceIn(lente.exposureMinNs, lente.exposureMaxNs)
        }

        fun pedirIso(iso: Int) {
            isoPedido = iso.coerceIn(lente.isoMin, lente.isoMax)
        }

        fun modoActual(): ExposureProgram.Mode = modo

        /**
         * O tempo que o fotómetro aconselha, com o ISO onde está.
         *
         * A correcção vem em stops e a luz é geométrica: dobrar o tempo é mais um stop. Não se toca no
         * ISO de propósito — quem está a repor o tempo escolheu o ISO de propósito.
         */
        fun tempoPeloFotometro(): String {
            val c = conselho ?: return "sem leitura do fotómetro"
            val t = Math.round(exposicao.exposureNs * Math.pow(2.0, c.correctionStops))
                .coerceIn(lente.exposureMinNs, lente.exposureMaxNs)
            pedirTempo(t)
            return "tempo do fotómetro: " + ExposureProgram.stops(c.correctionStops)
        }

        fun isoPeloFotometro(): String {
            val c = conselho ?: return "sem leitura do fotómetro"
            val i = Math.round(exposicao.iso * Math.pow(2.0, c.correctionStops)).toInt()
                .coerceIn(lente.isoMin, lente.isoMax)
            pedirIso(i)
            return "ISO do fotómetro: " + i
        }

        /**
         * Fixa no disco o que está escolhido.
         *
         * Uma vez por gesto e uma vez ao sair, em vez de continuamente. O laço de render chegava a fazer
         * cinquenta escritas por segundo em modo manual — e a gravar sempre o mesmo valor, porque em
         * manual a exposição não muda sozinha.
         */
        fun guardarEscolhas() {
            prefs.focusDiopters = exposicao.focusDiopters
            prefs.kelvin = kelvin
            prefs.tint = tinta
            prefs.compensation = compensacao
            // O tempo é escolha em S e em M; o ISO só em M. Em automático são resultado de medição, e
            // reabrir com os valores da última cena seria começar com a exposição de outra luz.
            if (modo == ExposureProgram.Mode.S || modo == ExposureProgram.Mode.M) {
                prefs.manualExposureNs = exposicao.exposureNs
            }
            if (modo == ExposureProgram.Mode.M) prefs.manualIso = exposicao.iso
        }

        fun compensacaoActual(): Float = compensacao

        fun exposicaoActual(): io.github.bmcsilva.latente.model.Exposure = exposicao

        fun kelvinActual(): Int = kelvin

        fun tintaActual(): Float = tinta

        fun focoActual(): Float = exposicao.focusDiopters

        @Volatile
        private var visorAoTopo = false

        fun alinharAoTopo(sim: Boolean) {
            visorAoTopo = sim
        }

        fun definirZebras(ligadas: Boolean) {
            zebras = ligadas
        }

        fun histograma(): IntArray? = leitura?.histogram

        fun definirPicos(ligados: Boolean) {
            picos = if (ligados) LIMIAR_DE_PICOS else 0f
            prefs.peaking = ligados
        }

        fun pedirVarrimento() {
            pedidoDeVarrimento = true
        }

        private val medicao = prefs.metering

        /** A exposição em vigor. Só o fio de render lhe mexe. */
        private var exposicao = lente.defaultExposure().copy(
            exposureNs = prefs.manualExposureNs,
            iso = prefs.manualIso,
            focusDiopters = prefs.focusDiopters,
            kelvin = prefs.kelvin,
            tint = prefs.tint)

        /** Os limites do disparo: o tecto todo, porque uma fotografia pode levar 1,8 s. */
        private val limitesDisparo = ExposureProgram.Limits(
            lente.exposureMinNs, lente.exposureMaxNs, lente.minFrameDurationNs,
            lente.isoMin, lente.isoMax, lente.maxAnalogIso)

        /**
         * Os limites do **visor**, com o tempo travado para o visor continuar vivo.
         *
         * Ver `VIEWFINDER_MAX_NS`: sem isto, escuro dava 0,6 fps e onze segundos de latência.
         */
        private val limitesVisor = ExposureProgram.Limits(
            lente.exposureMinNs,
            Math.min(lente.exposureMaxNs, ExposureProgram.VIEWFINDER_MAX_NS),
            lente.minFrameDurationNs,
            lente.isoMin, lente.isoMax, lente.maxAnalogIso)

        private val cfa = Demosaic.cfaFromArrangement(lente.cfa)
            ?: intArrayOf(Demosaic.G, Demosaic.B, Demosaic.R, Demosaic.G)

        private val pretoDoSensor = FloatArray(4) {
            lente.blackLevelPattern.getOrElse(it) { 0 }.toFloat()
        }

        /** A força da vinhetagem que a revelação usa, para o fotómetro medir o mesmo. Constante. */
        private val forcaDaVinhetagem = DevelopSettings().shadingStrength

        private var leitura: Meter.Reading? = null
        private var conselho: ExposureProgram.Result? = null
        private var paraDisparar: ExposureProgram.Result? = null
        private var arrefecimento = 0

        /** Ganho de apresentação em vigor, em stops, e as peças para o refazer. */
        private var ganhoDoVisor = 0f

        /**
         * Balanço de brancos escolhido. Vai ao visor **e** ao ficheiro.
         *
         * Ao visor pelos uniformes da revelação; ao ficheiro pelos `COLOR_CORRECTION_GAINS`, que
         * determinam o `SENSOR_NEUTRAL_COLOR_POINT` e por aí o `AsShotNeutral` do DNG. O mosaico fica
         * intocado nos dois casos — o balanço é interpretação, e a interpretação é do revelador.
         */
        @Volatile
        private var kelvin = prefs.kelvin

        @Volatile
        private var tinta = prefs.tint
        private var caracteristicas: android.hardware.camera2.CameraCharacteristics? = null
        private var perfilDeVinhetagem: ShadingProfile? = null
        private var glVisor: GlPreview? = null

        fun parar() {
            parado = true
        }

        fun pedirDisparo() {
            pedidoDeDisparo = true
        }

        /**
         * P → S → M → P. **Sem o A.**
         *
         * O A é prioridade à abertura, e estas objectivas têm abertura fixa: escolhia exactamente o
         * mesmo que o P, com outro nome. Um modo que faz o que o do lado faz ensina que os modos não
         * querem dizer nada. Fica na enumeração — se algum dia houver hardware com abertura variável,
         * volta ao ciclo sem se mexer em mais nada.
         *
         * A ordem é a da entrega de controlo: a aplicação decide tudo, decides o tempo, decides tudo.
         */
        fun definirModo(m: ExposureProgram.Mode) {
            modo = m
            prefs.mode = m
        }

        fun proximoModo(): ExposureProgram.Mode {
            modo = when (modo) {
                ExposureProgram.Mode.P -> ExposureProgram.Mode.S
                ExposureProgram.Mode.S -> ExposureProgram.Mode.M
                else -> ExposureProgram.Mode.P
            }
            prefs.mode = modo
            return modo
        }

        override fun run() {
            var sessao: CameraSession? = null
            var visor: GlPreview? = null
            try {
                sessao = CameraSession(ctx, lente.openId, lente.physicalId)
                sessao.open()?.let {
                    relatar("a câmara não abriu: $it")
                    return
                }
                sessao.configure(lente.rawSize)?.let {
                    relatar("a sessão não configurou: $it")
                    return
                }

                val settings = DevelopSettings()
                val perfil = ShadingProfile.forDevice(Build.MODEL, lente.cameraId)
                val uniformes = GlUniforms.fromCamera(
                    sessao.imageCharacteristics, settings.kelvin, 0f, perfil, settings)
                if (uniformes == null) {
                    relatar("faltam metadados de cor nesta câmara")
                    return
                }

                visor = GlPreview(lente.rawSize.width, lente.rawSize.height)
                visor.startOnSurface(janela)
                visor.setUniforms(uniformes)
                caracteristicas = sessao.imageCharacteristics
                perfilDeVinhetagem = perfil
                glVisor = visor

                orientacaoDoSensor =
                    sessao.imageCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                exposicao = Planner.plan(
                    lente, exposicaoDeArranque ?: exposicao).effective
                sessao.settle(exposicao)
                sessao.startStream()

                mostrar(descrever(perfil != null, 0.0))
                laco(sessao, visor, perfil != null)
            } catch (t: Throwable) {
                relatar(t.javaClass.simpleName + ": " + (t.message ?: ""))
            } finally {
                sessao?.stopStream()
                sessao?.close()
                visor?.close()
            }
        }

        private fun laco(
            sessao: CameraSession,
            visor: GlPreview,
            temPerfil: Boolean,
        ) {
            var desdeORelato = 0
            var marca = System.nanoTime()
            var vazios = 0

            while (!parado) {
                val f = focoPedido
                if (lente.manualFocus && f >= 0f &&
                    Math.abs(f - exposicao.focusDiopters) > 0.01f) {
                    focoPedido = -1f
                    val novo = exposicao.copy(focusDiopters = f)
                    if (sessao.updateRepeating(novo) == null) exposicao = novo
                }
                // O tempo e o ISO que o utilizador escolheu. Vão à câmara tal e qual: se pedir 1,8 s, o
                // visor passa a correr a 1,8 s por frame e fica lento — e é essa a verdade da definição.
                // Travá-lo aqui seria mostrar uma exposição e gravar outra.
                val tp = tempoPedido
                val ip = isoPedido
                if (tp > 0L || ip > 0) {
                    tempoPedido = 0L
                    isoPedido = 0
                    val t = if (tp > 0L) tp else exposicao.exposureNs
                    val novo = exposicao.copy(
                        exposureNs = t,
                        iso = if (ip > 0) ip else exposicao.iso,
                        frameDurationNs = HalClamp.frameDuration(t, lente.minFrameDurationNs))
                    if (sessao.updateRepeating(novo) == null) {
                        exposicao = novo
                        // Arrefecer também aqui, e não só quando é o fotómetro a mexer: os frames já em
                        // voo trazem a exposição antiga, e medi-los fazia o fotómetro «corrigir» o que
                        // o utilizador acabou de escolher. Em S via-se logo — o ISO saltava a seguir a
                        // cada mudança de tempo, e a escolha parecia não pegar.
                        arrefecimento = FRAMES_DE_ARREFECIMENTO
                    }
                }
                if (kelvin != exposicao.kelvin || tinta != exposicao.tint) {
                    // O balanço muda nos dois sítios ao mesmo tempo, senão o visor mostraria uma cor e
                    // o ficheiro levaria outra — que é exactamente o que este projeto existe para
                    // impedir.
                    val novo = exposicao.copy(kelvin = kelvin, tint = tinta)
                    if (sessao.updateRepeating(novo) == null) {
                        exposicao = novo
                        refazerUniformes()
                    }
                }
                if (pedidoDeVarrimento) {
                    pedidoDeVarrimento = false
                    varrerFoco(sessao)
                    marca = System.nanoTime()
                    desdeORelato = 0
                }
                if (pedidoDeDisparo) {
                    pedidoDeDisparo = false
                    disparar(sessao)
                    marca = System.nanoTime()
                    desdeORelato = 0
                }
                val img = sessao.nextImage(1000)
                if (img == null) {
                    vazios++
                    if (vazios > 5) {
                        relatar("o stream parou · " + sessao.streamDiagnostico())
                        return
                    }
                    continue
                }
                vazios = 0
                try {
                    val p = img.planes[0]
                    visor.upload(p.buffer, p.rowStride)
                    visor.draw()
                    visor.present(larguraVista, alturaVista, rotacaoDoVisor(), picos, zebras,
                        aoTopo = visorAoTopo)
                    medirEAjustar(sessao, p)
                } finally {
                    img.close()
                }

                desdeORelato++
                val agora = System.nanoTime()
                if (agora - marca >= 1_000_000_000L) {
                    val fps = desdeORelato * 1e9 / (agora - marca)
                    mostrar(descrever(temPerfil, fps))
                    desdeORelato = 0
                    marca = agora
                }
            }
        }

        /**
         * O fotómetro, e o ajuste que ele pede.
         *
         * Mede-se o **mosaico** do frame que acabou de ser desenhado, sub-amostrado. Custa poucos
         * milhares de leituras num frame de 12,5 milhões de píxeis.
         *
         * Duas salvaguardas contra oscilação, e ambas são necessárias:
         *
         * - **Arrefecimento.** Depois de mudar a exposição, os frames já em voo ainda trazem a antiga.
         *   Medir um desses faria corrigir duas vezes o mesmo erro. Saltam-se alguns frames, que a
         *   `updateRepeating` avisa serem precisos.
         * - **Zona morta de um terço de stop.** Sem ela, o ruído da medição faria a exposição tremer
         *   permanentemente, e um visor a piscar é pior do que um visor um terço de stop ao lado.
         */
        private fun medirEAjustar(sessao: CameraSession, plano: android.media.Image.Plane) {
            if (!lente.manualExposure) {
                // A exposição é da câmara. Mede-se na mesma — o fotómetro lê o RAW e diz a margem e o
                // corte, que é informação útil aqui como em qualquer lado —, mas não se pede nada: pedir
                // o que o corpo ignora encheria o `exposicao` de valores que o ficheiro não leva.
                //
                // O que se lê é o **resultado**: o tempo e o ISO que a câmara escolheu, para a
                // telemetria e o sidecar dizerem a verdade em vez do que sugerimos.
                medir(plano)
                sessao.ultimoResultado?.let { r ->
                    val t = r.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                    val i = r.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                    if (t != null && i != null) {
                        exposicao = exposicao.copy(exposureNs = t, iso = i)
                    }
                }
                ajustarGanhoDoVisor(0.0)
                conselho = ExposureProgram.apply(
                    ExposureProgram.Mode.M, medicao, exposicao, limitesVisor, leitura!!,
                    compensationStops = 0.0)
                paraDisparar = null
                return
            }
            if (arrefecimento > 0) {
                arrefecimento--
                return
            }
            medir(plano)

            val r = ExposureProgram.apply(modo, medicao, exposicao, limitesVisor, leitura!!,
                compensationStops = compensacao.toDouble())
            conselho = r

            // O que o disparo vai usar, com o tecto todo. Pode ser muito mais tempo do que o visor
            // aguenta, e é por isso que se calcula à parte em vez de se reutilizar o do visor.
            paraDisparar = ExposureProgram.apply(
                modo, medicao, exposicao, limitesDisparo, leitura!!,
                compensationStops = compensacao.toDouble())

            // O visor não consegue dar a luz toda, mas consegue **mostrar** o brilho que o ficheiro vai
            // ter: o que falta em exposição entra como ganho na revelação do visor. Fica com mais ruído,
            // que é o preço honesto de o mostrar depressa — e não fica a mentir sobre o brilho.
            if (modo == ExposureProgram.Mode.M) {
                // **Em manual não há ganho de apresentação.**
                //
                // O `residualStops` que o programa devolve em M é o conselho do fotómetro — luz que o
                // utilizador escolheu não dar. Compensá-la punha o visor a corrigir exactamente a
                // fotografia que ele existe para mostrar tal como vai ficar: escolhia-se um tempo mais
                // curto, a imagem no ecrã não escurecia, e parecia que o comando não tinha feito nada.
                // Nos modos automáticos o `residualStops` é outra coisa — luz que o **corpo** não
                // conseguiu dar —, e essa compensa-se, porque não foi escolha de ninguém.
                ajustarGanhoDoVisor(0.0)
                return
            }
            ajustarGanhoDoVisor(r.residualStops)
            if (Math.abs(r.correctionStops - r.residualStops) < 0.33) return

            val novo = exposicao.copy(
                exposureNs = r.exposureNs, iso = r.iso, frameDurationNs = r.frameDurationNs)
            val erro = sessao.updateRepeating(novo)
            if (erro != null) {
                relatar("a exposição não mudou: $erro")
                return
            }
            exposicao = novo
            arrefecimento = FRAMES_DE_ARREFECIMENTO
        }

        /** O fotómetro, sem mais nada. Mede sempre, mande ou não mande na exposição. */
        private fun medir(plano: android.media.Image.Plane) {
            leitura = Meter.measure(
                plano.buffer, lente.rawSize.width, lente.rawSize.height, plano.rowStride,
                cfa, lente.whiteLevel, pretoDoSensor,
                // O perfil de vinhetagem entra na medição porque entra na revelação. Sem ele o
                // fotómetro protegia o ficheiro e deixava o visor queimado.
                shading = perfilDeVinhetagem, shadingStrength = forcaDaVinhetagem)
        }

        /**
         * Varre o foco de infinito ao mínimo, disparando em cada passo.
         *
         * É o instrumento de verificação do foco, e existe pela mesma razão que a série de exposições
         * da F1: sem ele não há como saber se o que o visor realça é mesmo o que fica nítido no
         * ficheiro. Mede-se depois a nitidez de cada DNG e vê-se onde está o pico.
         */
        private fun varrerFoco(sessao: CameraSession) {
            val maximo = lente.minFocusDiopters
            val passos = 9
            for (i in 0 until passos) {
                if (parado) return
                val d = maximo * i / (passos - 1)
                relatar("varrimento de foco " + (i + 1) + "/" + passos +
                        String.format(java.util.Locale.US, " · %.2f dioptrias", d))
                val novo = exposicao.copy(focusDiopters = d)
                if (sessao.updateRepeating(novo) != null) return
                exposicao = novo
                // O foco é mecânico e leva tempo a assentar; sem esperar, o disparo apanha a lente a
                // meio do percurso e o varrimento não mede nada.
                sessao.settle(novo, frames = 3, timeoutMs = 2000)
                disparar(sessao)
            }
        }

        /**
         * Põe no visor o brilho que o ficheiro vai ter.
         *
         * Quando o tempo do visor está travado, faltam stops de luz — e em vez de mostrar uma imagem
         * escura que mente sobre o resultado, aplica-se o que falta como **ganho na revelação**. O visor
         * fica mais ruidoso e com o brilho certo, que é a troca honesta: o ruído vê-se e não engana, o
         * brilho errado engana.
         *
         * Refaz-se os uniformes, e não por frame: só quando o ganho muda mais de um quarto de stop.
         */
        private fun ajustarGanhoDoVisor(stopsQueFaltam: Double) {
            val querido = stopsQueFaltam.coerceIn(0.0, 8.0).toFloat()
            if (Math.abs(querido - ganhoDoVisor) < 0.25f) return
            ganhoDoVisor = querido
            refazerUniformes()
        }

        /**
         * Refaz os uniformes da revelação do visor com o que está escolhido agora.
         *
         * Uma vez por mudança de definições, nunca por frame. É aqui que o Kelvin e a tinta entram no
         * visor; no ficheiro entram pelo pedido, e as duas vias dão o mesmo ponto neutro a 0,07%.
         */
        private fun refazerUniformes() {
            val ch = caracteristicas ?: return
            val gl = glVisor ?: return
            val settings = DevelopSettings(exposureEv = ganhoDoVisor, kelvin = kelvin)
            val u = GlUniforms.fromCamera(ch, kelvin, tinta, perfilDeVinhetagem, settings) ?: return
            gl.setUniforms(u)
        }

        /**
         * A exposição que o **ficheiro** vai levar, num sítio só.
         *
         * Num sítio só porque a telemetria a anuncia e o disparo a usa: com duas cópias da regra, mexer
         * numa deixava o visor a dizer um valor e o ficheiro a levar outro. É precisamente o que esta
         * aplicação existe para não fazer, e estava a duas edições de acontecer.
         *
         * Em manual não há conselho a seguir: o que está escolhido é o que se grava.
         */
        private fun exposicaoDoDisparo(): io.github.bmcsilva.latente.model.Exposure {
            val d = paraDisparar
            if (d == null || modo == ExposureProgram.Mode.M) return exposicao
            return exposicao.copy(
                exposureNs = d.exposureNs, iso = d.iso, frameDurationNs = d.frameDurationNs)
        }

        /**
         * O disparo, a partir do visor.
         *
         * **Não se pára o pedido repetido**, ao contrário do disparo da F1. Parar o repetido apagaria o
         * visor durante a captura, e sobretudo já se sabe que parar e disparar logo a seguir faz o HAL
         * descartar o pedido. Intercala-se, e o frame certo identifica-se pelo timestamp do sensor.
         *
         * É isto que o critério de aceitação da F3 pede: o ficheiro tem de corresponder ao que estava
         * no visor. E corresponde por construção — vieram do mesmo stream, com os mesmos uniformes.
         */
        private fun disparar(sessao: CameraSession) {
            relatar("a disparar…")
            // A exposição do **disparo**, com o tecto todo — não a do visor, que está travada para o
            // visor não congelar. É esta a diferença permitida entre o que se vê e o que se grava, e é
            // compensada no visor por ganho de apresentação.
            val plano = Planner.plan(lente, exposicaoDoDisparo())
            val (frame, erro) = sessao.captureOne(plano.effective)
            if (frame == null) {
                relatar("o disparo falhou: " + (erro ?: "?"))
                return
            }
            try {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val nome = String.format(java.util.Locale.US, "LTNT_%04d_%s", contador, stamp)
                val sidecar = Sidecar.build(
                    nome, lente, plano, frame.result, frame.outcome, etiquetaDoCorpo,
                    frame.matchedByTimestamp, "F3 · visor",
                    // A receita para revelar **o ficheiro**, que não é a do visor.
                    //
                    // O `ganhoDoVisor` não entra aqui, e é o ponto que interessa: esse ganho existe
                    // porque o visor tem o tempo travado em 1/8 s, e o ficheiro levou a exposição
                    // inteira — 1,8 s no ensaio de hoje. Gravá-lo na receita contaria a mesma luz duas
                    // vezes e daria um TIFF 4,6 EV claro de mais.
                    //
                    // A tinta também não entra: vai nos ganhos de cor, portanto já está no
                    // `AsShotNeutral` do DNG e o revelador lê-a de lá.
                    DevelopSettings(kelvin = kelvin), rotacaoDaImagem())
                val resumo = "Latente · visor · " + lente.label + " · " + plano.effective.describe()
                // A mesma rotação que o visor usa para pôr a imagem de pé no ecrã vai como etiqueta
                // no ficheiro. Se divergissem, o visor mostrava direito e o ficheiro saía deitado.
                // Negativo e receita **num arquivo**, e não dois ficheiros soltos.
                //
                // Um negativo custa 24 MB e o *deflate* leva-o a 8 ou 9 sem tocar num bit da imagem —
                // os dez bits úteis viajam em palavras de dezasseis, e é o byte de cima, quase
                // constante, que comprime. A alternativa era comprimir a imagem em si, e essa mexia no
                // caminho da captura, que está certificado.
                val cru = frame.image.width.toLong() * frame.image.height * 2
                val bytes = Archive.escrever(
                    ctx, nome, sessao.imageCharacteristics, frame.result, frame.image, resumo,
                    Present.exifOrientation(rotacaoDaImagem()), Json.write(sidecar))
                contador++
                relatar(nome + "." + Archive.EXTENSAO + " · " + (bytes / 1024 / 1024) +
                        " MB de " + (cru / 1024 / 1024) +
                        (if (!frame.matchedByTimestamp) " · frame não emparelhado" else ""))
            } catch (t: Throwable) {
                relatar("a escrita falhou: " + t.javaClass.simpleName + ": " + (t.message ?: ""))
            } finally {
                frame.close()
            }
        }

        /**
         * O que o visor diz de si próprio.
         *
         * Inclui a margem até ao corte e a percentagem já cortada, que é a informação que decide uma
         * fotografia — e que nenhuma aplicação de fabricante mostra. Em manual mostra também o conselho
         * do fotómetro, que é o que faz um modo manual utilizável.
         */
        private companion object {
            /**
             * Frames a saltar depois de mudar a exposição.
             *
             * O `ImageReader` tem três lugares e o HAL tem pipeline próprio, portanto a exposição nova
             * só aparece uns frames à frente. Seis dá folga sem se notar: a menos de 30 fps é um quinto
             * de segundo.
             */
            const val FRAMES_DE_ARREFECIMENTO = 6

            /**
             * Limiar do peaking, no Laplaciano da luminância do visor.
             *
             * Escolhido a olho e por medir: acende nas arestas de um teclado a 40 cm quando focado e
             * apaga quando não. É um ponto de partida, não uma constante da natureza — se ficar
             * demasiado sensível, sobe-se.
             */
            const val LIMIAR_DE_PICOS = 0.08f
        }

        /**
         * A telemetria em campos etiquetados, não numa frase.
         *
         * Uma linha corrida obriga a ler tudo para encontrar um valor; uma grelha com etiquetas lê-se de
         * relance, que é o que se faz com uma câmara ao olho. As etiquetas são fixas e os valores
         * alinham por baixo delas — é a razão de a fonte ser monoespaçada.
         *
         * Os avisos vêm **primeiro** e só quando são verdade. Um aviso permanente deixa de ser aviso.
         */
        private fun descrever(temPerfil: Boolean, fps: Double): Telemetria {
            val t = Telemetria()
            val l0 = leitura
            val doDisparo = exposicaoDoDisparo()

            // --- avisos, só quando são verdade ---
            //
            // «Longa» e «ganho digital» são sobre a **fotografia**, não sobre o visor. Olhavam para a
            // exposição do visor, e viu-se no telefone: um disparo a 1/30 s aparecia com EXPOSIÇÃO LONGA
            // porque o visor estava a 1/20 s. O visor está travado no tempo de propósito e é mais ruidoso
            // de propósito — avisar sobre ele é avisar sobre uma coisa que não vai a ficheiro nenhum.
            // Por extenso. Couberam-lhe as abreviaturas por um build, porque a linha era partilhada com
            // o estado dos instrumentos; com a linha inteira para eles, cabem os nomes verdadeiros — e
            // «EXPOSIÇÃO LONGA» diz o que é a quem não decorou que «APOIAR» quer dizer isso.
            if (ganhoDoVisor > 0.05f) {
                t.avisos.add(String.format(
                    java.util.Locale.US, "VISOR AMPLIFICADO %+.1f EV", ganhoDoVisor))
            }
            if (doDisparo.exposureNs > ExposureProgram.HAND_HELD_LIMIT_NS) {
                t.avisos.add("EXPOSIÇÃO LONGA")
            }
            if (doDisparo.iso > lente.maxAnalogIso) t.avisos.add("GANHO DIGITAL")
            // «LIMITE DO SENSOR» quer dizer **o corpo não consegue dar mais luz**, e só nos modos
            // automáticos o `residualStops` quer dizer isso. Em manual, e numa objectiva onde a
            // exposição é da câmara, ele traz o conselho do fotómetro — que é outra coisa: não é o
            // corpo no limite, é a exposição estar longe do alvo. Acendia sempre que se afinava o
            // tempo, e um aviso que acende quando não é verdade gasta a atenção do que é.
            if (modo != ExposureProgram.Mode.M && lente.manualExposure) {
                conselho?.let {
                    if (Math.abs(it.residualStops) > 0.2) t.avisos.add("LIMITE DO SENSOR")
                }
            }
            if (l0 != null && l0.clipped > 0.001) t.avisos.add("CORTE NO SENSOR")

            // --- modo, margem e corte: o que decide a fotografia ---
            // «AUTO» e não o nome de um modo nosso: nenhum deles descreve o que ali se passa, e pôr um
            // P daria a entender que a linha de programa da aplicação escolheu — não escolheu nada.
            t.modo = if (lente.manualExposure) modo.name else "AUTO"
            if (l0 != null && l0.valid) {
                t.margem = String.format(java.util.Locale.US, "%+.1f EV", l0.headroomStops)
                t.cortado = String.format(java.util.Locale.US, "%.2f%%", l0.clipped * 100)
            }
            conselho?.let {
                // Em manual o fotómetro não mexe em nada, e por isso tem de **dizer** o que faria. Sem
                // isto, um modo manual é um modo às cegas. Numa objectiva sem exposição nossa vale o
                // mesmo, e por mais forte razão: ali ele nunca vai poder agir.
                if (modo == ExposureProgram.Mode.M || !lente.manualExposure) {
                    t.fotometro = ExposureProgram.stops(it.correctionStops)
                }
            }

            // --- o que vai para o ficheiro; o visor só quando difere ---
            t.tempo = tempoLegivel(doDisparo.exposureNs)
            t.abertura = doDisparo.aperture?.let {
                String.format(java.util.Locale.US, "f/%.1f", it)
            } ?: "—"
            t.iso = doDisparo.iso.toString()

            // O limiar é em **stops**, não em percentagem. A 10% de diferença mostrava-se «disparo
            // 1/25 s · visor 1/30 s», que é um terço de stop e não interessa a ninguém — e um aviso que
            // aparece quando não importa gasta a atenção que faz falta quando importa. Meio stop é o
            // ponto em que a diferença se vê na fotografia.
            val stopsDeDiferenca = Meter.log2(
                doDisparo.exposureNs.toDouble() / exposicao.exposureNs.coerceAtLeast(1L))
            if (Math.abs(stopsDeDiferenca) > 0.5) {
                t.visor = "VISOR " + tempoLegivel(exposicao.exposureNs) + " ISO " + exposicao.iso
            }

            val dio = exposicao.focusDiopters
            t.foco = when {
                // Sem motor: está na hiperfocal e não há distância a mostrar.
                lente.minFocusDiopters <= 0f -> "FIXO"
                // Com motor, mas quem o move é a câmara. Mostrar a distância que ela reporta seria
                // dar a entender que a escolhemos; «AUTO» diz o que se passa.
                !lente.manualFocus -> "AUTO"
                dio <= 0.02f -> "∞"
                else -> String.format(java.util.Locale.US, "%.2f m", 1f / dio)
            }
            t.kelvin = kelvin.toString() + " K"
            t.tinta = String.format(java.util.Locale.US, "%+.2f", tinta)
            // **Sempre**, e sem a unidade atrás: agora tem campo próprio com a etiqueta `EV` por cima, e
            // um campo que desaparece a zero é um campo que se procura. Escondia-se quando ia colada à
            // tinta, que é o contrário — ali, aparecer é que era a excepção.
            t.compensacao = String.format(java.util.Locale.US, "%+.1f", compensacao)

            // Só o que não está visível noutro sítio. A objectiva está escrita no botão que a troca, a
            // abertura tem campo na grelha, o foco fixo aparece no campo do foco — repeti-los aqui era
            // encher o canto com o que os olhos já leram.
            t.vinheta = if (temPerfil) "VINH OK" else "SEM VINH"
            if (picos > 0f) t.vinheta += " · PICOS"
            if (fps > 0) t.fps = String.format(java.util.Locale.US, "%.0f FPS", fps)
            t.fpsMedidos = fps
            return t
        }

        /**
         * O tempo como uma câmara o diz: fracção abaixo de um segundo, segundos acima.
         *
         * Separado do `Exposure.describe` porque na grelha o tempo tem campo próprio — juntar-lhe a
         * abertura e o ISO desalinharia as três colunas.
         */
        private fun tempoLegivel(ns: Long): String = if (ns >= 1_000_000_000L) {
            String.format(java.util.Locale.US, "%.1f s", ns / 1e9)
        } else {
            "1/" + Math.round(1e9 / ns) + " s"
        }
    }
}
