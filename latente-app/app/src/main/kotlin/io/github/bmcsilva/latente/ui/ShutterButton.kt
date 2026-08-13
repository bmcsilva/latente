package io.github.bmcsilva.latente.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * O disparador, redondo como o de uma máquina.
 *
 * Não é enfeite. Um botão de disparo tem de se encontrar **sem olhar** — com a máquina ao olho a mão
 * procura uma forma, e um rectângulo entre outros rectângulos não tem forma nenhuma. É por isso que
 * todas as câmaras do mundo têm ali um círculo, e é a razão de este ecrã passar a ter um também.
 *
 * Desenhado à mão em vez de imagem: um `Canvas` com dois círculos não tem dependência, não tem
 * resolução errada em ecrã nenhum e muda de cor conforme o estado sem se refazerem ficheiros.
 *
 * O anel exterior é a moldura fixa; o núcleo encolhe ao toque, que é a resposta que a mão espera.
 */
class ShutterButton(ctx: Context) : View(ctx) {

    private val tintaDoAnel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintaDoNucleo = Paint(Paint.ANTI_ALIAS_FLAG)

    /** O preto do *chrome*: é o que se vê entre o anel e o núcleo. */
    private val tintaDoVazio = Paint(Paint.ANTI_ALIAS_FLAG)
    private var carregado = false

    /** Vermelho enquanto a captura corre: o disparo pode levar 1,8 s e a mão precisa de o saber. */
    var ocupado = false
        set(v) {
            field = v
            invalidate()
        }

    init {
        tintaDoAnel.color = 0xFF6B7075.toInt()
        tintaDoNucleo.style = Paint.Style.FILL
        tintaDoVazio.color = 0xFF000000.toInt()
        tintaDoVazio.style = Paint.Style.FILL
        isClickable = true
        isFocusable = true
    }

    /**
     * Meia-lua: metade de cima de um círculo, com o lado direito assente no fundo do ecrã.
     *
     * Um círculo inteiro no meio da fila empurra os vizinhos e deixa metade da sua altura a não fazer
     * nada — a parte de baixo cai na margem do sistema, onde o polegar já não vai. Cortado ao meio
     * ocupa metade da altura e **o dobro da largura útil**: o alvo fica maior onde a mão chega e a
     * fila deixa de andar aos empurrões.
     */
    var meiaLua = false
        set(v) {
            field = v
            requestLayout()
        }

    private val arco = android.graphics.RectF()

    override fun onMeasure(larguraSpec: Int, alturaSpec: Int) {
        val d = resources.displayMetrics.density
        val largura = Math.round((if (meiaLua) 96 else 76) * d)
        val altura = Math.round((if (meiaLua) 50 else 76) * d)
        setMeasuredDimension(
            resolveSize(largura, larguraSpec), resolveSize(altura, alturaSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        tintaDoNucleo.color = when {
            ocupado -> 0xFFE53935.toInt()
            carregado -> 0xFFBDC1C6.toInt()
            else -> Color.WHITE
        }
        // O núcleo encolhe ao toque em vez de mudar de cor: é a mesma resposta de um obturador
        // mecânico, e vê-se pelo canto do olho com a máquina ao rosto.
        val folga = if (carregado) 7f * d else 5f * d

        if (meiaLua) {
            val raio = Math.min(width / 2f, height.toFloat()) - d
            val cx = width / 2f
            val cy = height - d
            // Desenha-se disco sobre disco em vez de traço: um `STROKE` num arco fechado deixa a
            // linha recta da base com espessura a dobrar onde as duas metades se encontram.
            // Três meios-discos, do maior para o menor: o anel, o vazio entre ele e o núcleo, e o
            // núcleo. O do meio tem de ser pintado do fundo e não deixado por pintar — num `Canvas`
            // não há como «apagar» o que já lá está sem um `PorterDuff`, e o disco de baixo cobria-o.
            //
            // Sem esse meio, o anel e o núcleo ficavam encostados e o encolher ao toque não se via: era
            // o defeito da primeira versão, que desenhava o núcleo duas vezes e o vazio nenhuma.
            arco.set(cx - raio, cy - raio, cx + raio, cy + raio)
            tintaDoAnel.style = Paint.Style.FILL
            canvas.drawArc(arco, 180f, 180f, true, tintaDoAnel)
            val vazio = raio - 2.5f * d
            arco.set(cx - vazio, cy - vazio, cx + vazio, cy + vazio)
            canvas.drawArc(arco, 180f, 180f, true, tintaDoVazio)
            val nucleo = raio - folga - 2.5f * d
            arco.set(cx - nucleo, cy - nucleo, cx + nucleo, cy + nucleo)
            canvas.drawArc(arco, 180f, 180f, true, tintaDoNucleo)
            return
        }

        val cx = width / 2f
        val cy = height / 2f
        val raio = Math.min(width, height) / 2f - d
        tintaDoAnel.style = Paint.Style.STROKE
        tintaDoAnel.strokeWidth = 2.5f * d
        canvas.drawCircle(cx, cy, raio - tintaDoAnel.strokeWidth / 2f, tintaDoAnel)
        canvas.drawCircle(cx, cy, raio - folga, tintaDoNucleo)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                carregado = true
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                carregado = false
                invalidate()
            }
        }
        return super.onTouchEvent(e)
    }
}
