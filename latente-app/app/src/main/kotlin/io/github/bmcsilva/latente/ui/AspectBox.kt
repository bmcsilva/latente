package io.github.bmcsilva.latente.ui

import android.content.Context
import android.widget.FrameLayout

/**
 * A caixa do visor com a **proporção da imagem**, e não a altura que sobra.
 *
 * Em retrato a caixa fica com o que resta entre as duas faixas e a imagem é enquadrada lá dentro pelo
 * `Present.fit`, que centra — o preto que sobra reparte-se acima e abaixo e não incomoda ninguém.
 *
 * Em paisagem isso deixou de servir. A imagem encosta ao topo e o preto junta-se todo numa banda por
 * baixo, e essa banda é para **receber as pastilhas**. Só que a caixa não sabia onde a imagem acabava:
 * era alta como a coluna, e as pastilhas postas a seguir apareciam depois de um vão de preto.
 *
 * Aqui a caixa mede-se pela imagem — largura toda, altura igual à largura a dividir pela proporção —,
 * e assim o que vem a seguir no `LinearLayout` fica **encostado ao fundo da imagem**, sem contas na
 * actividade.
 *
 * @param proporcao largura/altura da imagem apresentada. Zero devolve o comportamento antigo: ocupa o
 *   que lhe derem.
 * @param folgaEmBaixo altura a deixar para quem vem a seguir. Sem ela, num ecrã de proporção estreita
 *   a caixa comia a coluna toda e as pastilhas ficavam de fora.
 */
class AspectBox(ctx: Context) : FrameLayout(ctx) {

    /** Muda de valor e pede medida nova: quem troca de objectiva não tem de se lembrar disso. */
    var proporcao = 0f
        set(v) {
            if (field == v) return
            field = v
            requestLayout()
        }

    var folgaEmBaixo = 0
        set(v) {
            if (field == v) return
            field = v
            requestLayout()
        }

    override fun onMeasure(larguraSpec: Int, alturaSpec: Int) {
        if (proporcao <= 0f) {
            super.onMeasure(larguraSpec, alturaSpec)
            return
        }
        val largura = MeasureSpec.getSize(larguraSpec)
        val pedida = Math.round(largura / proporcao)
        // Sem tecto declarado não há tecto nenhum: um `UNSPECIFIED` mede-se com o tamanho a zero, e
        // subtrair-lhe a folga dava uma caixa de altura negativa, ou seja um visor que desaparece.
        val altura = if (MeasureSpec.getMode(alturaSpec) == MeasureSpec.UNSPECIFIED) {
            pedida
        } else {
            Math.min(pedida, Math.max(MeasureSpec.getSize(alturaSpec) - folgaEmBaixo, 0))
        }
        val spec = MeasureSpec.makeMeasureSpec(altura, MeasureSpec.EXACTLY)
        super.onMeasure(larguraSpec, spec)
        setMeasuredDimension(largura, altura)
    }
}
