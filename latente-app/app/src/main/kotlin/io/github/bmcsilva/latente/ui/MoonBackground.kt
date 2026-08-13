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
    /**
     * De que lado entra a mordida: 1 esquerda, 2 direita, 3 topo, 4 base.
     *
     * O topo e a base existem para a paisagem, onde o disparador tem os botões **acima e abaixo** e não
     * aos lados. Constrói-se sempre a mordida à esquerda e roda-se o caminho: uma forma e quatro
     * matrizes, em vez de quatro contas parecidas a poderem divergir.
     */
    private val lado: Int = 1,
    /**
     * O triângulo de «abre lista», desenhado no fundo e **não escrito no texto**.
     *
     * Estava no texto e era o que desalinhava tudo: quem centra um `TextView` centra «AUTO ▾», não
     * «AUTO», e a palavra ficava metade de um triângulo à esquerda do meio. Aqui é marca do controlo,
     * fica encostado ao lado que a mordida não ocupa, e o texto centra-se sozinho.
     */
    private val corDoAcento: Int = 0,
) : Drawable() {

    private val tintaDeFundo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintaDoContorno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caminho = Path()
    private val oval = RectF()
    private val acento = Path()
    private val tintaDoAcento = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        tintaDeFundo.style = Paint.Style.FILL
        tintaDeFundo.color = corDeFundo
        tintaDoContorno.style = Paint.Style.STROKE
        tintaDoContorno.color = corDoContorno
        tintaDoContorno.strokeWidth = espessura
        tintaDoAcento.style = Paint.Style.FILL
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        // Vertical: constrói-se na caixa trocada e roda-se noventa graus. A mordida da esquerda passa a
        // ser a do topo ou a da base, conforme o sentido da rotação.
        if (lado == 3 || lado == 4) {
            construir(h, w)
            val m = android.graphics.Matrix()
            if (lado == 3) {
                m.setRotate(90f)
                m.postTranslate(w, 0f)
            } else {
                m.setRotate(-90f)
                m.postTranslate(0f, h)
            }
            caminho.transform(m)
        } else {
            construir(w, h)
        }
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
        if (profundidade <= 0f) {
            oval.set(meia, topo, direita, base)
            caminho.addRoundRect(oval, r, r, Path.Direction.CW)
            return
        }
        // **Entalhe, e não aresta inteira.**
        //
        // A primeira versão fazia o arco atravessar a aresta de uma ponta à outra, e isso obriga o raio a
        // ser maior do que metade dela. Numa aresta de 80 dp com o raio de 39 do disparador era
        // impossível: o `asin` saturava, o arco degenerava num semicírculo, saía da caixa e era cortado —
        // a aresta aparecia recta.
        //
        // Aqui o arco vive só onde o círculo entra de facto no botão, e o resto da aresta fica a direito.
        // Assim a curva **é** a do disparador — mesmo raio — em arestas de qualquer comprimento, e a
        // mordida fica igual em retrato e em paisagem, que é o que se quer: o encaixe é o mesmo.
        val raio = raioDaMordida
        val cx = profundidade + meia - raio
        val cy = h / 2f
        // Onde o círculo cruza a aresta, e até onde o arco vai. Limitado à aresta: se o círculo for
        // maior do que ela, o entalhe passa a ocupá-la toda, que é o caso do retrato.
        val meiaCorda = if (raio > Math.abs(cx)) {
            Math.min(Math.sqrt((raio * raio - cx * cx).toDouble()).toFloat(), altura / 2f)
        } else {
            0f
        }
        oval.set(cx - raio, cy - raio, cx + raio, cy + raio)
        caminho.moveTo(meia, topo)
        if (meiaCorda > 0f) {
            caminho.lineTo(meia, cy - meiaCorda)
            val a = Math.toDegrees(
                Math.atan2(meiaCorda.toDouble(), (meia - cx).toDouble())).toFloat()
            caminho.arcTo(oval, -a, 2f * a)
            caminho.lineTo(meia, base)
        } else {
            caminho.lineTo(meia, base)
        }

        caminho.lineTo(direita - r, base)
        oval.set(direita - 2f * r, base - 2f * r, direita, base)
        caminho.arcTo(oval, 90f, -90f)
        caminho.lineTo(direita, topo + r)
        oval.set(direita - 2f * r, topo, direita, topo + 2f * r)
        caminho.arcTo(oval, 0f, -90f)
        caminho.close()

        if (lado == 2) {
            val espelho = android.graphics.Matrix()
            espelho.setScale(-1f, 1f, w / 2f, 0f)
            caminho.transform(espelho)
        }
    }

    override fun draw(canvas: Canvas) {
        if (caminho.isEmpty) onBoundsChange(bounds)
        canvas.drawPath(caminho, tintaDeFundo)
        if (espessura > 0f) canvas.drawPath(caminho, tintaDoContorno)
        if (corDoAcento == 0) return

        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val lado = h * 0.085f
        // Do lado que a mordida não come. Encostado, mas não na borda: um triângulo colado ao contorno
        // lê-se como defeito do contorno.
        val cx = w - lado * 2.2f
        val cy = h / 2f
        acento.reset()
        acento.moveTo(cx - lado, cy - lado * 0.55f)
        acento.lineTo(cx + lado, cy - lado * 0.55f)
        acento.lineTo(cx, cy + lado * 0.75f)
        acento.close()
        tintaDoAcento.color = corDoAcento
        canvas.drawPath(acento, tintaDoAcento)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(filtro: ColorFilter?) {}

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
