package io.github.bmcsilva.latente.render

/**
 * Onde a imagem revelada aparece no ecrã, e virada para que lado.
 *
 * Isto é aritmética e nada mais, e está fora do shader de propósito: orientação e enquadramento são
 * exactamente o género de coisa que sai errada em silêncio e que se pode fixar com testes. O shader
 * recebe uma escala, um desvio e uma rotação já resolvidos.
 *
 * A apresentação é **separada da revelação** por decisão de desenho. A revelação desenha para uma
 * textura, com a mesma matemática do ficheiro; a apresentação leva essa textura ao ecrã. Assim, mudar
 * como se mostra não pode mudar o que se mostra.
 *
 * O enquadramento é **por dentro**, com barras: num visor de fotografia tem de se ver o quadro todo.
 * Cortar para preencher o ecrã esconderia parte do que vai ser gravado, e um visor que esconde é a
 * mesma família de mentira que este projeto existe para evitar.
 */
object Present {

    /**
     * A rotação a aplicar à imagem para ficar de pé no ecrã.
     *
     * O `SENSOR_ORIENTATION` é o ângulo, no sentido dos ponteiros do relógio, que a saída do sensor
     * precisa de rodar para ficar de pé quando o dispositivo está na sua orientação natural. Subtrai-se
     * a rotação do ecrã para acompanhar o telefone.
     *
     * Só para câmaras traseiras. As frontais precisariam de soma e de espelhamento, e estão fora do
     * âmbito.
     */
    fun rotationFor(sensorOrientation: Int, displayRotationDegrees: Int): Int =
        ((sensorOrientation - displayRotationDegrees) % 360 + 360) % 360

    /**
     * A rotação em graus como etiqueta de orientação de TIFF/EXIF.
     *
     * O mosaico é sempre entregue na orientação do sensor, que é deitada; o ficheiro sai deitado e é a
     * etiqueta que diz ao visualizador como o pôr de pé. Escrever `1` — «normal» — como se fazia até
     * aqui punha **todas** as fotografias tiradas em retrato viradas de lado em qualquer visualizador.
     *
     * Os valores são os da especificação do TIFF, e a etiqueta é a mesma no DNG e no TIFF revelado: os
     * dois saem na orientação do sensor, portanto os dois precisam da mesma correcção.
     */
    fun exifOrientation(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        90 -> 6      // rodar 90° no sentido dos ponteiros para ficar de pé
        180 -> 3
        270 -> 8
        else -> 1    // já está de pé
    }

    /**
     * Escala e desvio para caber a imagem no ecrã sem cortar e sem distorcer.
     *
     * Devolve `[escalaX, escalaY, desvioX, desvioY]`, em coordenadas de ecrã normalizadas de 0 a 1 com
     * a origem no canto superior esquerdo. O shader faz `(tela - desvio) / escala` e pinta preto fora
     * de 0 a 1 — daí as barras.
     *
     * @param rotation 0, 90, 180 ou 270. A 90 e 270 os lados da imagem trocam, e é isso que decide
     *   qual dos eixos limita.
     */
    fun fit(
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        rotation: Int,
    ): FloatArray {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return floatArrayOf(1f, 1f, 0f, 0f)
        }

        // Depois de rodar 90 ou 270, o que era largura passa a altura.
        val deitada = rotation % 180 == 0
        val larguraApresentada = if (deitada) imageWidth else imageHeight
        val alturaApresentada = if (deitada) imageHeight else imageWidth

        val aspectoImagem = larguraApresentada.toDouble() / alturaApresentada
        val aspectoEcra = viewWidth.toDouble() / viewHeight

        var escalaX = 1.0
        var escalaY = 1.0
        if (aspectoImagem > aspectoEcra) {
            // A imagem é mais larga do que o ecrã: encosta aos lados e sobram barras acima e abaixo.
            escalaY = aspectoEcra / aspectoImagem
        } else {
            escalaX = aspectoImagem / aspectoEcra
        }

        return floatArrayOf(
            escalaX.toFloat(), escalaY.toFloat(),
            ((1.0 - escalaX) / 2).toFloat(), ((1.0 - escalaY) / 2).toFloat())
    }
}
