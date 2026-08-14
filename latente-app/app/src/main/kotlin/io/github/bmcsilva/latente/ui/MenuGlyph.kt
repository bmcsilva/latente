package io.github.bmcsilva.latente.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * As três linhas de «abre um menu».
 *
 * Desenhado e não uma imagem, como o disparador e a mordida: um `Canvas` com três rectângulos não tem
 * dependência, não tem resolução errada em ecrã nenhum, e muda de cor com o estado.
 *
 * Substituiu a palavra «IR», que dizia menos do que ocupava. Três linhas são o sinal que toda a gente
 * já sabe ler, e num botão de 44 dp a palavra roubava o espaço que a mão quer para acertar.
 *
 * @param comprimento largura da linha mais comprida, em píxeis.
 */
class MenuGlyph(
    private val cor: Int,
    private val comprimento: Float,
    private val espessura: Float,
) : Drawable() {

    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barra = RectF()

    init {
        tinta.style = Paint.Style.FILL
        tinta.color = cor
    }

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        // O espaço entre linhas é o dobro da espessura: mais apertado lê-se como um bloco, mais
        // aberto deixa de se ler como um símbolo e passa a ser três riscos.
        val passo = espessura * 2f
        for (i in -1..1) {
            val y = cy + i * passo
            barra.set(cx - comprimento / 2f, y - espessura / 2f,
                cx + comprimento / 2f, y + espessura / 2f)
            canvas.drawRoundRect(barra, espessura / 2f, espessura / 2f, tinta)
        }
    }

    /**
     * **Sem tamanho próprio, de propósito.**
     *
     * Como frente de uma vista, um desenho com tamanho próprio é posicionado pela gravidade — e o
     * `setForegroundGravity` acrescenta `START` a quem não traga gravidade relativa, com o `START` a
     * ganhar ao centro. Resultado: as três linhas coladas ao bordo esquerdo da pastilha, medido em
     * píxeis no ecrã. Sem tamanho próprio, a vista entrega a caixa inteira e o glifo centra-se nela,
     * que é o que o `draw` já faz.
     */
    override fun getIntrinsicWidth(): Int = -1

    override fun getIntrinsicHeight(): Int = -1

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(filtro: ColorFilter?) {}

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
