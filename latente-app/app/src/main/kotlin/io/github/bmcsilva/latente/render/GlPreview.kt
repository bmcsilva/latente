package io.github.bmcsilva.latente.render

import android.opengl.GLES20
import android.opengl.GLES30
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * O motor do visor: o mesmo pipeline da revelação, com estado vivo entre frames.
 *
 * A diferença face ao `GlDeveloper` não é matemática nenhuma — é o ciclo de vida. Aqui o contexto, o
 * programa e a textura são criados **uma vez** e reaproveitados a cada frame; só o conteúdo da
 * textura muda. Criar uma textura de 25 MB trinta vezes por segundo seria insustentável, e é essa a
 * única razão de esta classe existir separada.
 *
 * Que os dois usem os mesmos `GlslSource` e o mesmo `Gl.bindUniforms` não é conveniência: é o que faz
 * o visor mostrar o que o ficheiro vai ter. Se divergirem, o visor mente.
 *
 * O alvo é um FBO fora de ecrã. Ligar isto a uma `SurfaceView` muda o alvo e mais nada.
 */
class GlPreview(
    /** As dimensões do **mosaico** que a câmara entrega. */
    val rawWidth: Int,
    val rawHeight: Int,
) : Closeable {

    /** O visor agrupa cada quadrado 2×2 do mosaico num pixel: um quarto dos píxeis, cor sem inventar. */
    val width = rawWidth / 2
    val height = rawHeight / 2

    // Preguiçoso de propósito: construir um `GlPreview` não deve tocar no EGL. Os campos do
    // `EglContext` são inicializados a partir de constantes do EGL14, que numa JVM sem Android são
    // nulas — e sem isto nem as dimensões, que são aritmética, se podiam testar.
    private val egl by lazy { EglContext() }
    private var programa = 0
    private var mosaico = 0
    private var alvo = 0
    private var fbo = 0
    private var locais: Map<String, Int> = emptyMap()
    private var arrancou = false
    private var apresentacao = 0
    private var uImagem = -1
    private var uAlvo = -1
    private var uEscala = -1
    private var uDesvio = -1
    private var uRotacao = -1
    private var uPicos = -1
    private var uZebras = -1

    val renderer: String? get() = egl.renderer
    var missingUniforms: List<String> = emptyList()
        private set

    // -----------------------------------------------------------------------------------------

    /** Arranca sem ecrã, para medir e para verificar. */
    fun start() {
        egl.startOffscreen()
        arrancar()
    }

    /**
     * Arranca ligado à superfície do visor.
     *
     * O passe de revelação continua a desenhar para a textura, igual ao caso sem ecrã; só se acrescenta
     * o passe de apresentação. É isso que garante que ligar um ecrã não muda a imagem.
     */
    fun startOnSurface(janela: android.view.Surface) {
        egl.startOnSurface(janela)
        arrancar()
        apresentacao = Gl.compile(GlslSource.VERTEX, GlslSource.PRESENT_FRAGMENT)
        val (l, faltam) = Gl.uniformLocations(apresentacao, GlslSource.PRESENT_UNIFORM_NAMES)
        if (faltam.isNotEmpty()) {
            missingUniforms = missingUniforms + faltam.map { "apresentação/$it" }
        }
        // As localizações são fixas desde a ligação do programa. Guardadas em campos, o `present` deixa
        // de fazer sete pesquisas numa tabela de cadeias por frame.
        uImagem = l["uImagem"] ?: -1
        uAlvo = l["uAlvo"] ?: -1
        uEscala = l["uEscala"] ?: -1
        uDesvio = l["uDesvio"] ?: -1
        uRotacao = l["uRotacao"] ?: -1
        uPicos = l["uPicos"] ?: -1
        uZebras = l["uZebras"] ?: -1
    }

    private fun arrancar() {
        arrancou = true
        programa = Gl.compile(GlslSource.VERTEX, GlslSource.PREVIEW_FRAGMENT)
        val (l, faltam) = Gl.uniformLocations(programa, GlslSource.UNIFORM_NAMES)
        locais = l
        missingUniforms = faltam

        mosaico = Gl.createMosaicTexture(rawWidth, rawHeight)

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        alvo = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, alvo)
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        // Os parâmetros do alvo ficam definidos **aqui**, e não a cada apresentação: estado de textura é
        // pegajoso, e repô-lo por frame eram quatro chamadas de GL para não mudar nada.
        //
        // Filtro linear é legítimo neste alvo — é escala de uma imagem RGBA, não amostragem de um
        // mosaico. Na textura do mosaico seria erro de GL.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, alvo, 0)
        val estado = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (estado != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw GlFalha("framebuffer do visor incompleto: $estado")
        }

        GLES20.glUseProgram(programa)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mosaico)
        GLES20.glUniform1i(locais["uMosaico"]!!, 0)
        Gl.check("arranque do visor")
    }

    /**
     * Os parâmetros de revelação. Uma vez por mudança de definições, não por frame.
     */
    fun setUniforms(u: GlUniforms) {
        if (u.shadingRings > GlslSource.MAX_RINGS) {
            throw GlFalha("o perfil tem " + u.shadingRings + " anéis e o shader só reserva " +
                    GlslSource.MAX_RINGS)
        }
        GLES20.glUseProgram(programa)
        Gl.bindUniforms(locais, u, rawWidth, rawHeight)
        Gl.check("uniformes do visor")
    }

    /**
     * Carrega o mosaico da câmara para a textura.
     *
     * @param plane o buffer directo que a câmara entregou, sem cópia pelo meio.
     * @param rowStrideBytes o passo de linha do plano. **Não é necessariamente `largura * 2`** — o HAL
     *   pode alinhar as linhas, e ignorar isso dava uma imagem inclinada, com a cor a rodar de linha
     *   para linha por o padrão do mosaico desalinhar. Resolve-se com `GL_UNPACK_ROW_LENGTH`, que
     *   diz ao GL o passo em píxeis, em vez de copiar linha a linha na CPU.
     *
     * Não há `glFinish` aqui: quem quiser atribuir tempos a etapas chama o `finish` de propósito, e
     * quem quiser desempenho não chama nada. A política de medição é de quem mede, não desta classe.
     */
    fun upload(plane: ByteBuffer, rowStrideBytes: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mosaico)
        val passoPixeis = rowStrideBytes / 2
        if (passoPixeis != rawWidth) {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, passoPixeis)
        }
        plane.position(0)
        GLES30.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, rawWidth, rawHeight,
            GLES30.GL_RED_INTEGER, GLES20.GL_UNSIGNED_SHORT, plane)
        if (passoPixeis != rawWidth) {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        }
    }

    /** Revela a textura para o alvo. */
    fun draw() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glUseProgram(programa)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    }

    /**
     * Espera que a GPU acabe.
     *
     * Serve para medir, e distorce o que mede: impedir o encavalitamento faz o total por frame
     * parecer maior do que é em produção. Em troca, dá um número **atribuível** a uma etapa.
     */
    fun finish() {
        GLES20.glFinish()
    }

    /**
     * Mostra no ecrã o que o passe de revelação desenhou.
     *
     * @param rotation 0, 90, 180 ou 270 — o `Present.rotationFor` calcula-o do sensor e do ecrã.
     * @param peaking limiar do realce de foco; 0 desliga. Vive neste passe de propósito: uma ajuda de
     *   visor não pode ter caminho nenhum até ao ficheiro.
     */
    fun present(
        viewWidth: Int,
        viewHeight: Int,
        rotation: Int,
        peaking: Float = 0f,
        zebras: Boolean = false,
    ) {
        if (apresentacao == 0) throw GlFalha("o visor não foi arrancado com superfície")

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glUseProgram(apresentacao)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, alvo)

        val enquadramento = Present.fit(viewWidth, viewHeight, width, height, rotation)
        GLES20.glUniform1i(uImagem, 0)
        GLES20.glUniform2i(uAlvo, viewWidth, viewHeight)
        GLES20.glUniform2f(uEscala, enquadramento[0], enquadramento[1])
        GLES20.glUniform2f(uDesvio, enquadramento[2], enquadramento[3])
        GLES20.glUniform1i(uRotacao, rotation)
        GLES20.glUniform1f(uPicos, peaking)
        GLES20.glUniform1i(uZebras, if (zebras) 1 else 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        egl.swap()
    }

    /**
     * Fecha o que chegou a existir.
     *
     * A guarda é o `arrancou`, e não «há programa?»: se o contexto abrir e a compilação falhar, não
     * há programa nenhum mas há contexto a libertar. Guardar pelos recursos deixava-o pendurado
     * exactamente no caminho de erro.
     */
    override fun close() {
        if (!arrancou) return
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (mosaico != 0 || alvo != 0) {
            GLES20.glDeleteTextures(2, intArrayOf(mosaico, alvo), 0)
        }
        if (programa != 0) GLES20.glDeleteProgram(programa)
        if (apresentacao != 0) GLES20.glDeleteProgram(apresentacao)
        fbo = 0
        mosaico = 0
        alvo = 0
        programa = 0
        apresentacao = 0
        arrancou = false
        egl.close()
    }
}
