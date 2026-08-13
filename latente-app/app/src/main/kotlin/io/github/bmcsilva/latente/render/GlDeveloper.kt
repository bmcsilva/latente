package io.github.bmcsilva.latente.render

import android.opengl.GLES20
import android.opengl.GLES30
import io.github.bmcsilva.latente.export.DngReader
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Revelação na GPU, fora de ecrã.
 *
 * Contexto EGL próprio com uma superfície mínima, porque o desenho vai todo para um *framebuffer*
 * fora de ecrã. Serve para a F2 — revelar um DNG do disco — e é a mesma máquina que a F3 vai usar
 * para o visor, mudando apenas o alvo.
 *
 * A matemática não está aqui: está no `GlslSource`, traduzida do Kotlin. Aqui só há canalização —
 * criar contexto, carregar texturas, compilar, desenhar e ler de volta. É deliberado: o que se pode
 * testar sem GPU foi todo empurrado para fora desta classe.
 */
class GlDeveloper : Closeable {

    private val egl = EglContext()
    private var programa = 0

    val renderer: String? get() = egl.renderer

    /**
     * Uniformes que o programa não resolveu.
     *
     * Vazio é o esperado. Um nome aqui é ou erro de escrita, ou um uniforme que o compilador
     * eliminou por não ser usado — e nos dois casos o valor que se enviou foi ignorado sem erro de
     * GL, o que é a razão de isto ser reportado em vez de silenciado.
     */
    val missingUniforms = ArrayList<String>()

    // -----------------------------------------------------------------------------------------

    private fun iniciar(fonteFragmento: String) {
        egl.startOffscreen()
        programa = Gl.compile(GlslSource.VERTEX, fonteFragmento)
    }

    // -----------------------------------------------------------------------------------------

    /**
     * Revela e devolve RGBA de oito bits, na mesma ordem de linhas do ficheiro.
     *
     * O `glReadPixels` devolve de baixo para cima nas coordenadas do GL, e o shader indexa o
     * mosaico com o `gl_FragCoord` sem inverter o Y — as duas inversões cancelam-se e as linhas
     * saem na ordem certa. É subtil e é de propósito: inverter num sítio só daria uma imagem ao
     * contrário, que é fácil de ver, mas inverter nos dois daria uma imagem certa por acaso.
     *
     * Devolve o `ByteBuffer` directo onde o GL escreveu, e não uma copia para `ByteArray`: numa
     * imagem de 12 Mpx a cópia são 50 MB no monte da JVM, que é onde a memória é escassa. O buffer
     * directo vive em memória nativa e o `ByteBuffer.get` chega para o ler.
     */
    fun develop(
        reader: DngReader,
        profile: ShadingProfile?,
        settings: DevelopSettings = DevelopSettings(),
    ): ByteBuffer {
        val meia = settings.halfResolution
        val largura = if (meia) reader.width / 2 else reader.width
        val altura = if (meia) reader.height / 2 else reader.height

        iniciar(if (meia) GlslSource.PREVIEW_FRAGMENT else GlslSource.DEVELOP_FRAGMENT)

        val u = GlUniforms.from(reader, profile, settings)
        if (u.shadingRings > GlslSource.MAX_RINGS) {
            throw GlFalha("o perfil tem " + u.shadingRings + " anéis e o shader só reserva " +
                    GlslSource.MAX_RINGS)
        }
        val texturas = IntArray(2)
        val fbos = IntArray(1)
        try {
            // --- mosaico como inteiros de 16 bits, sem interpolação ---
            texturas[0] = Gl.createMosaicTexture(reader.width, reader.height)
            val amostras = reader.readRawSamples()
            val buf = ByteBuffer.allocateDirect(amostras.size * 2).order(ByteOrder.nativeOrder())
            buf.asShortBuffer().put(amostras)
            buf.rewind()
            GLES30.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, reader.width, reader.height,
                GLES30.GL_RED_INTEGER, GLES20.GL_UNSIGNED_SHORT, buf)

            // --- alvo fora de ecrã ---
            GLES20.glGenTextures(1, texturas, 1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texturas[1])
            GLES30.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, largura, altura, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

            GLES20.glGenFramebuffers(1, fbos, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texturas[1], 0)
            val estado = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (estado != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw GlFalha("framebuffer incompleto: $estado")
            }

            // --- uniformes ---
            GLES20.glUseProgram(programa)
            val (locais, faltam) = Gl.uniformLocations(programa, GlslSource.UNIFORM_NAMES)
            missingUniforms.addAll(faltam)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texturas[0])
            GLES20.glUniform1i(locais["uMosaico"]!!, 0)
            Gl.bindUniforms(locais, u, reader.width, reader.height)

            // --- desenho: um triângulo que cobre tudo, sem vértices em memória ---
            GLES20.glViewport(0, 0, largura, altura)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            Gl.check("desenho")

            val saida = ByteBuffer.allocateDirect(largura * altura * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, largura, altura, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, saida)
            saida.rewind()
            return saida
        } finally {
            GLES20.glDeleteFramebuffers(1, fbos, 0)
            GLES20.glDeleteTextures(2, texturas, 0)
        }
    }

    override fun close() {
        if (programa != 0) GLES20.glDeleteProgram(programa)
        programa = 0
        egl.close()
    }
}
