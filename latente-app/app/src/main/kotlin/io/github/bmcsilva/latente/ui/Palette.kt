package io.github.bmcsilva.latente.ui

/**
 * A paleta, num sítio só.
 *
 * Estava dentro da actividade do visor, que era onde tinha nascido, e a biblioteca não lhe chegava —
 * dois ecrãs da mesma aplicação a escolher cinzentos cada um por si é como as interfaces se desfazem.
 *
 * Preto verdadeiro porque o *chrome* continua a imagem: numa moldura cinzenta a vista adapta-se ao
 * cinzento e julga mal o brilho do que está no visor.
 */
object Palette {

    const val PRETO = 0xFF000000.toInt()
    const val CINZA = 0xFF9AA0A6.toInt()

    /** O mesmo ciano do realce de foco: nesta aplicação, ciano quer dizer «o instrumento falou». */
    const val CIANO = 0xFF00FFF2.toInt()
    const val AMBAR = 0xFFFFB300.toInt()

    /** O fundo e o contorno de uma pastilha apagada, e o fundo da que está acesa. */
    const val PASTILHA = 0xFF1F2226.toInt()
    const val CONTORNO = 0xFF33383E.toInt()
    const val PASTILHA_ACESA = 0xFF10292E.toInt()

    /** O cinzento do que existe mas não está a valer nada — um papel que falta, um botão apagado. */
    const val APAGADO = 0xFF5F656B.toInt()
}
