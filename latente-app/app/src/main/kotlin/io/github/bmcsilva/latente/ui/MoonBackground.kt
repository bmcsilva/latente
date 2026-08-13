package io.github.bmcsilva.latente.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * O fundo em lua: côncavo do lado esquerdo, redondo do direito.
 *
 * Nasceu de encostar um botão ao disparador. Com as pontas redondas ficava entre os dois uma fresta em
 * forma de lua que se lê como erro de alinhamento; com o lado cortado a direito, o botão parecia
 * partido. A forma certa é a **negativa do círculo**: o lado que dá para o disparador curva para
 * dentro com o raio dele, e as duas peças encaixam como as de um puzzle.
 *
 * Tem de ser um `Path` — um `GradientDrawable` sabe arredondar cantos e não sabe morder um lado.
 *
 * @param raioDaMordida o raio do círculo que morde, em píxeis. É o do disparador mais uma folga; se
 *   fosse igual, as duas curvas encostavam e a folga desaparecia consoante o arredondamento.
 * @param profundidade quanto a mordida entra no botão, a meio da altura.
 */
class MoonBackground(
    private val corDeFundo: Int,
    private val corDoContorno: Int,
    private val espessura: Float,
    private val raioDaMordida: Float,
    private val profundidade: Float,
    /**
     * De que lado entra a mordida.
     *
     * O botão que fica à direita do disparador é mordido à esquerda; o que fica à esquerda é mordido à
     * direita. É a mesma forma espelhada, e é por isso que se constrói uma e se vira, em vez de haver
     * duas contas parecidas a poderem divergir.
     */
    private val mordidaAEsquerda: Boolean = true,
) : Drawable() {

    private val tintaDeFundo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintaDoContorno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caminho = Path()
    private val oval = RectF()

    init {
        tintaDeFundo.style = Paint.Style.FILL
        tintaDeFundo.color = corDeFundo
        tintaDoContorno.style = Paint.Style.STROKE
        tintaDoContorno.color = corDoContorno
        tintaDoContorno.strokeWidth = espessura
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        construir(bounds.width().toFloat(), bounds.height().toFloat())
    }

    private fun construir(w: Float, h: Float) {
        caminho.reset()
        if (w <= 0f || h <= 0f) return
        val meia = espessura / 2f
        val topo = meia
        val base = h - meia
        val direita = w - meia
        val altura = base - topo
        val r = Math.min(altura / 2f, w / 2f)

        // O círculo que morde: centro à esquerda do botão, de modo que o seu bordo direito entre
        // `profundidade` píxeis a meio da altura.
        val raio = Math.max(raioDaMordida, altura / 2f + 1f)
        val cx = profundidade + meia - raio
        val cy = h / 2f
        // O ângulo onde esse círculo cruza o topo e a base do botão.
        val seno = Math.min(1.0, (altura / 2f) / raio.toDouble())
        val a = Math.toDegrees(Math.asin(seno)).toFloat()

        oval.set(cx - raio, cy - raio, cx + raio, cy + raio)
        // Do topo para a base pelo lado direito do círculo: é a curva que entra no botão.
        caminho.arcTo(oval, -a, 2f * a, true)

        caminho.lineTo(direita - r, base)
        oval.set(direita - 2f * r, base - 2f * r, direita, base)
        caminho.arcTo(oval, 90f, -90f)
        caminho.lineTo(direita, topo + r)
        oval.set(direita - 2f * r, topo, direita, topo + 2f * r)
        caminho.arcTo(oval, 0f, -90f)
        caminho.close()

        if (!mordidaAEsquerda) {
            val espelho = android.graphics.Matrix()
            espelho.setScale(-1f, 1f, w / 2f, 0f)
            caminho.transform(espelho)
        }
    }

    override fun draw(canvas: Canvas) {
        if (caminho.isEmpty) construir(bounds.width().toFloat(), bounds.height().toFloat())
        canvas.drawPath(caminho, tintaDeFundo)
        if (espessura > 0f) canvas.drawPath(caminho, tintaDoContorno)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(filtro: ColorFilter?) {}

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
