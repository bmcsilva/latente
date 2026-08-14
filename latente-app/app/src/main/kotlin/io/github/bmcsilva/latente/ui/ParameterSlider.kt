package io.github.bmcsilva.latente.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * O comando do parâmetro: menos à esquerda, mais à direita, e a barra a encher pelo meio.
 *
 * Substitui a `SeekBar` do sistema por duas razões práticas, e nenhuma é estética.
 *
 * A primeira é **precisão**. Uma barra de arrastar num ecrã de 1080 px reparte o curso todo por mil
 * posições, e o dedo tapa o sítio onde está a pousar: acertar num terço de stop é sorte. Os botões das
 * pontas dão o passo miúdo e repetível, que é como se afina uma câmara — carregar quatro vezes é uma
 * intenção, arrastar 4% do ecrã é um acidente.
 *
 * A segunda é o **manípulo**. O da `SeekBar` é um alvo pequeno com uma zona morta à volta, e obriga a
 * agarrá-lo antes de o mover. Aqui não há manípulo: toca-se onde se quer e o valor vai lá — como a
 * barra de luminosidade de um telefone, que toda a gente já sabe usar sem instruções.
 *
 * Desenhado à mão, sem imagens: dois cantos redondos e um rectângulo não justificam ficheiros, e assim
 * não há resolução errada em ecrã nenhum.
 */
class ParameterSlider(ctx: Context) : View(ctx) {

    /** Mil posições, como a barra que substitui: os cálculos de quem a usa não mudaram. */
    var valor = 0
        private set

    /**
     * O passo dos botões das pontas, em milésimos do curso.
     *
     * Vinte é cerca de 2% da gama. Nas escalas logarítmicas do tempo e do ISO isso dá perto de um
     * terço de stop; no Kelvin, cerca de 240 K a meio da gama. É o passo de quem afina, não o de quem
     * procura.
     */
    var passo = 20

    /**
     * A cor do nível, que é a do parâmetro escolhido.
     *
     * Com a máquina ao olho não se lê a pastilha: vê-se a cor da barra e já se sabe o que o dedo vai
     * mexer. É a mesma informação que a pastilha acesa dá, dita onde o olhar já está.
     */
    var corDoNivel: Int = 0xFF00FFF2.toInt()
        set(v) {
            field = v
            tintaDoNivel.color = v
            invalidate()
        }

    var aoMudar: ((Int, Boolean) -> Unit)? = null
    var aoLargar: (() -> Unit)? = null

    private val tintaDoFundo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintaDaCalha = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintaDoNivel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintaDoSinal = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintaDaSeparacao = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caixa = RectF()

    private var aArrastar = false
    private var repeticao: Runnable? = null

    init {
        tintaDoFundo.color = Palette.PASTILHA
        tintaDaCalha.color = 0xFF3A3F45.toInt()
        tintaDoNivel.color = 0xFF00FFF2.toInt()
        tintaDoSinal.color = 0xFFE8EAED.toInt()
        tintaDoSinal.style = Paint.Style.STROKE
        tintaDoSinal.strokeCap = Paint.Cap.ROUND
        tintaDaSeparacao.color = 0xFF000000.toInt()
        isClickable = true
    }

    fun definirValor(v: Int, doUtilizador: Boolean) {
        val novo = v.coerceIn(0, 1000)
        if (novo == valor) return
        valor = novo
        invalidate()
        aoMudar?.invoke(valor, doUtilizador)
    }

    override fun onMeasure(larguraSpec: Int, alturaSpec: Int) {
        val altura = Math.round(46 * resources.displayMetrics.density)
        setMeasuredDimension(
            resolveSize(Math.round(280 * resources.displayMetrics.density), larguraSpec),
            resolveSize(altura, alturaSpec))
    }

    /** A largura de cada tecla das pontas. Quadrada, para o dedo a encontrar sem olhar. */
    private fun ladoDaTecla(): Float = height.toFloat()

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val h = height.toFloat()
        val w = width.toFloat()
        val raio = h / 2f

        caixa.set(0f, 0f, w, h)
        canvas.drawRoundRect(caixa, raio, raio, tintaDoFundo)

        val tecla = ladoDaTecla()
        canvas.drawRect(tecla - 0.5f * d, 0f, tecla + 0.5f * d, h, tintaDaSeparacao)
        canvas.drawRect(w - tecla - 0.5f * d, 0f, w - tecla + 0.5f * d, h, tintaDaSeparacao)

        // Os sinais. Um traço e uma cruz, desenhados e não escritos: uma fonte poria o «−» e o «+» com
        // pesos e alturas diferentes, e aqui têm de ser gémeos.
        tintaDoSinal.strokeWidth = 2.5f * d
        val braco = 7f * d
        canvas.drawLine(tecla / 2f - braco, h / 2f, tecla / 2f + braco, h / 2f, tintaDoSinal)
        val cx = w - tecla / 2f
        canvas.drawLine(cx - braco, h / 2f, cx + braco, h / 2f, tintaDoSinal)
        canvas.drawLine(cx, h / 2f - braco, cx, h / 2f + braco, tintaDoSinal)

        // A calha, e o nível a enchê-la da esquerda. Sem manípulo, como a barra da luminosidade.
        val esquerda = tecla + 8f * d
        val direita = w - tecla - 8f * d
        val meia = 6f * d
        caixa.set(esquerda, h / 2f - meia, direita, h / 2f + meia)
        canvas.drawRoundRect(caixa, meia, meia, tintaDaCalha)

        val cheio = esquerda + (direita - esquerda) * valor / 1000f
        if (cheio > esquerda + 1f) {
            caixa.set(esquerda, h / 2f - meia, cheio, h / 2f + meia)
            canvas.drawRoundRect(caixa, meia, meia, tintaDoNivel)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tecla = ladoDaTecla()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                when {
                    e.x < tecla -> comecarRepeticao(-passo)
                    e.x > width - tecla -> comecarRepeticao(passo)
                    else -> {
                        aArrastar = true
                        definirValor(valorEm(e.x), true)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (aArrastar) definirValor(valorEm(e.x), true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pararRepeticao()
                if (aArrastar) {
                    aArrastar = false
                    aoLargar?.invoke()
                }
            }
        }
        return true
    }

    private fun valorEm(x: Float): Int {
        val d = resources.displayMetrics.density
        val esquerda = ladoDaTecla() + 8f * d
        val direita = width - ladoDaTecla() - 8f * d
        if (direita <= esquerda) return valor
        return Math.round(1000f * (x - esquerda) / (direita - esquerda))
    }

    /**
     * Carregar dá um passo; manter carregado repete.
     *
     * Sem a repetição, atravessar a gama a passos de 2% eram cinquenta toques. Com ela, o botão faz o
     * que a barra faz e continua a acertar no fim.
     */
    private fun comecarRepeticao(delta: Int) {
        definirValor(valor + delta, true)
        val r = object : Runnable {
            override fun run() {
                definirValor(valor + delta, true)
                postDelayed(this, 60)
            }
        }
        repeticao = r
        postDelayed(r, 400)
    }

    private fun pararRepeticao() {
        repeticao?.let { removeCallbacks(it) }
        if (repeticao != null) {
            repeticao = null
            aoLargar?.invoke()
        }
    }
}
