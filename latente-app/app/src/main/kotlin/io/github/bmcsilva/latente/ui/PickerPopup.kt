package io.github.bmcsilva.latente.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Uma opção do menu flutuante.
 *
 * O `motivo` é o que distingue este menu de uma lista de botões: uma opção **indisponível** continua a
 * aparecer, apagada e com a razão à frente. Uma objectiva que o corpo tem e a aplicação não usa, se
 * simplesmente não aparecer, parece defeito nosso — e quem fotografa fica sem saber que ela existe.
 */
class Opcao(
    val id: Int,
    val texto: String,
    val detalhe: String? = null,
    val activa: Boolean = false,
    val disponivel: Boolean = true,
)

/**
 * O menu que se abre por cima de um botão.
 *
 * Existe para tirar do rodapé o que não se usa a cada fotografia. Um botão por objectiva, um por modo
 * e quatro por ajuda enchiam a faixa de comandos e obrigavam a percorrer estados às cegas — carregar
 * três vezes para chegar ao nível, e nunca ver o que havia sem passar por tudo.
 *
 * Com um menu, o que existe **mostra-se todo de uma vez**, com o que está escolhido aceso e o que não
 * se pode usar apagado com a razão. É mais toques para mudar e muito menos para saber, que é a troca
 * certa: mudar de objectiva faz-se de vez em quando, perceber o que a máquina tem faz-se uma vez.
 *
 * Feito com `PopupWindow` e vistas desenhadas à mão, sem XML nem dependências, como o resto do ecrã.
 */
object PickerPopup {

    private const val FUNDO = 0xFF16181B.toInt()
    private const val ACESO = 0xFF00FFF2.toInt()
    private const val APAGADO = 0xFF9AA0A6.toInt()

    /**
     * @param multipla o menu fica aberto entre escolhas. Para as ajudas, que se combinam; as
     *   objectivas e os modos escolhem-se uma vez e o menu fecha.
     * @param estaActiva num menu de escolha múltipla, quem sabe o estado de cada opção **depois** da
     *   escolha. Sem isto o menu não tinha como se actualizar, e quem chamava resolvia-o a reabri-lo —
     *   que empilhava uma janela nova por cima da velha. Com quatro ajudas ligadas ficavam cinco
     *   janelas, e cinco toques para as fechar. O menu passa a repintar-se onde está.
     */
    fun mostrar(
        act: Activity,
        ancora: View,
        opcoes: List<Opcao>,
        multipla: Boolean,
        estaActiva: ((Int) -> Boolean)? = null,
        aoEscolher: (Int) -> Unit,
    ) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = Math.round(v * d)

        val painel = LinearLayout(act)
        painel.orientation = LinearLayout.VERTICAL
        painel.setPadding(dp(6), dp(6), dp(6), dp(6))
        val moldura = GradientDrawable()
        moldura.cornerRadius = dp(14).toFloat()
        moldura.setColor(FUNDO)
        moldura.setStroke(dp(1), Palette.CONTORNO)
        painel.background = moldura

        val janela = PopupWindow(painel,
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)

        // Cada linha deixa aqui a sua maneira de se repintar, e uma escolha repinta-as todas: mudar uma
        // ajuda pode mudar o que outra diz, e a lista é de quatro.
        val repintar = ArrayList<() -> Unit>()
        for (o in opcoes) {
            painel.addView(linha(act, o, janela, multipla, estaActiva, repintar, aoEscolher),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }

        janela.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        janela.isOutsideTouchable = true
        janela.elevation = 12f * d

        // Por cima do botão, e não por baixo: em baixo ficava debaixo do polegar que o abriu, e nos
        // botões do fundo do ecrã nem caberia.
        painel.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val altura = painel.measuredHeight
        janela.showAsDropDown(ancora, 0, -(altura + ancora.height + dp(8)), Gravity.START)
    }

    private fun linha(
        act: Activity,
        o: Opcao,
        janela: PopupWindow,
        multipla: Boolean,
        estaActiva: ((Int) -> Boolean)?,
        repintar: ArrayList<() -> Unit>,
        aoEscolher: (Int) -> Unit,
    ): View {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = Math.round(v * d)

        val caixa = LinearLayout(act)
        caixa.orientation = LinearLayout.VERTICAL
        caixa.gravity = Gravity.CENTER_VERTICAL
        caixa.setPadding(dp(14), 0, dp(14), 0)

        val realce = GradientDrawable()
        realce.cornerRadius = dp(9).toFloat()

        val t = TextView(act)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        t.typeface = Typeface.MONOSPACE
        t.maxLines = 1
        caixa.addView(t)

        // O estado da linha num sítio só, para ser o mesmo ao abrir e depois de cada escolha.
        val pintar = {
            val activa = estaActiva?.invoke(o.id) ?: o.activa
            realce.setColor(if (activa) 0xFF23282D.toInt() else Color.TRANSPARENT)
            caixa.background = realce
            // O ponto aceso à frente do nome diz o estado sem depender da cor de fundo, que num menu
            // pequeno se lê mal.
            t.text = (if (activa) "● " else "○ ") + o.texto
            t.setTextColor(when {
                !o.disponivel -> 0xFF5F6368.toInt()
                activa -> ACESO
                else -> Color.WHITE
            })
        }
        pintar()
        repintar.add(pintar)

        o.detalhe?.let {
            val sub = TextView(act)
            sub.text = it
            sub.setTextColor(if (o.disponivel) APAGADO else 0xFF5F6368.toInt())
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            sub.maxLines = 1
            caixa.addView(sub)
        }

        if (o.disponivel) {
            caixa.setOnClickListener {
                aoEscolher(o.id)
                if (multipla) {
                    for (p in repintar) p()
                } else {
                    janela.dismiss()
                }
            }
        }
        return caixa
    }
}
