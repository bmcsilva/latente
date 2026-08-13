package io.github.bmcsilva.latente.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * As ajudas desenhadas por cima do visor: histograma e nível.
 *
 * Vive numa `View` separada da `SurfaceView`, e não no shader, por uma razão simples: são linhas e
 * texto, coisas que o `Canvas` faz bem e que num shader dariam trabalho para nada. A regra que
 * interessa é a mesma das zebras e do realce — **uma ajuda de visor não tem caminho nenhum até ao
 * ficheiro**. Aqui isso é evidente: isto desenha noutra superfície.
 */
class AidsView(ctx: Context) : View(ctx) {

    private val traco = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fundo = Paint()

    /** Histograma do **verde do sensor**, tal como o fotómetro o mediu. */
    @Volatile
    private var histograma: IntArray? = null

    /** Inclinação lateral e para a frente, em graus. */
    @Volatile
    private var rolamento = 0f

    @Volatile
    private var inclinacao = 0f

    @Volatile
    private var mostrarHistograma = false

    @Volatile
    private var mostrarNivel = false

    init {
        fundo.color = Color.argb(120, 0, 0, 0)
        traco.strokeWidth = 2f
    }

    fun definirHistograma(h: IntArray?) {
        histograma = h
        postInvalidate()
    }

    fun definirNivel(rolamentoGraus: Float, inclinacaoGraus: Float) {
        rolamento = rolamentoGraus
        inclinacao = inclinacaoGraus
        if (mostrarNivel) postInvalidate()
    }

    fun definirMostrar(histograma: Boolean, nivel: Boolean) {
        mostrarHistograma = histograma
        mostrarNivel = nivel
        postInvalidate()
    }

    val precisaDoNivel: Boolean get() = mostrarNivel

    override fun onDraw(canvas: Canvas) {
        if (mostrarHistograma) desenharHistograma(canvas)
        if (mostrarNivel) desenharNivel(canvas)
    }

    /**
     * O histograma é do **verde do sensor**, não do que está no ecrã.
     *
     * Numa câmara RAW é essa a informação que decide: mostra o que o sensor captou, antes de balanço,
     * de matriz e da correcção de vinhetagem. O histograma do render diria onde a *nossa* revelação
     * corta, que se recupera baixando a exposição na revelação; este diz onde o **sensor** cortou, que
     * não se recupera de maneira nenhuma.
     *
     * Escala logarítmica na vertical: o histograma de uma cena real tem picos que esmagariam tudo o
     * resto numa escala linear, e o que interessa ver são as caudas.
     */
    private fun desenharHistograma(canvas: Canvas) {
        val h = histograma ?: return
        val largura = width * 0.45f
        val altura = height * 0.10f
        val x0 = width - largura - 24f
        // Abaixo da barra de título do sistema. Desenhar em y=24 punha-o por trás dela — invisível, e
        // com ar de estar em falta em vez de estar tapado.
        val y0 = height * 0.13f

        canvas.drawRect(x0, y0, x0 + largura, y0 + altura, fundo)

        var pico = 1
        for (v in h) if (v > pico) pico = v
        val logPico = Math.log((pico + 1).toDouble())

        traco.color = Color.argb(220, 255, 255, 255)
        val colunas = 128
        val passo = h.size / colunas
        for (c in 0 until colunas) {
            var soma = 0
            for (i in c * passo until (c + 1) * passo) soma += h[i]
            val f = (Math.log((soma + 1).toDouble()) / logPico).toFloat()
            val x = x0 + largura * c / colunas
            canvas.drawLine(x, y0 + altura, x, y0 + altura * (1f - f), traco)
        }

        // A marca do corte, no extremo direito, é o que interessa não passar.
        traco.color = Color.argb(220, 255, 60, 0)
        canvas.drawLine(x0 + largura - 1f, y0, x0 + largura - 1f, y0 + altura, traco)
    }

    /**
     * O nível: uma linha que fica horizontal quando a câmara está direita.
     *
     * Verde dentro de meio grau, que é a tolerância a que um horizonte torto deixa de se ver numa
     * fotografia.
     */
    private fun desenharNivel(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val raio = width * 0.30f

        val direito = Math.abs(rolamento) < 0.5f && Math.abs(inclinacao) < 0.5f
        traco.color = if (direito) Color.argb(230, 60, 255, 90) else Color.argb(200, 255, 255, 255)

        val rad = Math.toRadians(rolamento.toDouble())
        val dx = (raio * Math.cos(rad)).toFloat()
        val dy = (raio * Math.sin(rad)).toFloat()
        // O deslocamento vertical mostra a inclinação para a frente, que é o que faz as verticais
        // convergirem numa fotografia de arquitectura.
        val desvio = (inclinacao.coerceIn(-30f, 30f) / 30f) * height * 0.12f
        canvas.drawLine(cx - dx, cy - dy + desvio, cx + dx, cy + dy + desvio, traco)

        traco.color = Color.argb(90, 255, 255, 255)
        canvas.drawLine(cx - raio * 0.15f, cy, cx + raio * 0.15f, cy, traco)
    }
}
