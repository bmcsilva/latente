package io.github.bmcsilva.latente.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import java.io.Closeable

/** Falha de GL ou de EGL, com a mensagem que o driver deu. */
class GlFalha(mensagem: String) : Exception(mensagem)

/**
 * O contexto EGL, separado de quem o usa.
 *
 * Existe porque há dois consumidores com necessidades diferentes: a revelação de um DNG, que abre
 * contexto, desenha uma vez e fecha; e o visor, que mantém contexto e texturas vivos entre frames.
 * A alternativa era duplicar cinquenta linhas de EGL, e o EGL é precisamente o género de código onde
 * duas cópias divergem sem ninguém notar.
 *
 * Uma nota que não é óbvia: o contexto pertence à **thread** que o torna corrente. Quem usar isto
 * tem de fazer tudo — criar, desenhar, fechar — na mesma thread.
 */
class EglContext : Closeable {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    var renderer: String? = null
        private set

    /**
     * Contexto com uma superfície mínima, para desenhar em *framebuffers* fora de ecrã.
     *
     * A superfície de 1×1 nunca é desenhada: o EGL exige uma para tornar o contexto corrente, e o
     * alvo verdadeiro é sempre um FBO. Poupa-se assim a memória de um pbuffer do tamanho da imagem.
     */
    fun startOffscreen() {
        criarContexto(EGL14.EGL_PBUFFER_BIT)
        surface = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        tornarCorrente()
    }

    /**
     * Contexto ligado a uma superfície de janela — a do visor.
     *
     * A diferença face ao `startOffscreen` é só o tipo de superfície. É por isso que este ficheiro
     * existe: o visor e a revelação de um DNG precisam do mesmo contexto com alvos diferentes, e
     * duplicar o EGL para essa diferença seria pedir divergência.
     */
    fun startOnSurface(janela: android.view.Surface) {
        criarContexto(EGL14.EGL_WINDOW_BIT)
        surface = EGL14.eglCreateWindowSurface(
            display, config, janela, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) throw GlFalha("eglCreateWindowSurface falhou")
        tornarCorrente()
    }

    /** Entrega o que foi desenhado ao compositor. Sem isto, nada aparece. */
    fun swap() {
        if (!EGL14.eglSwapBuffers(display, surface)) {
            throw GlFalha("eglSwapBuffers falhou: " + EGL14.eglGetError())
        }
    }

    private fun criarContexto(tipoDeSuperficie: Int) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw GlFalha("sem display EGL")

        val versao = IntArray(2)
        if (!EGL14.eglInitialize(display, versao, 0, versao, 1)) {
            throw GlFalha("eglInitialize falhou")
        }

        val atributos = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, tipoDeSuperficie,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val quantas = IntArray(1)
        if (!EGL14.eglChooseConfig(display, atributos, 0, configs, 0, 1, quantas, 0) ||
            quantas[0] == 0
        ) {
            throw GlFalha("nenhuma configuração EGL com ES 3")
        }
        config = configs[0]

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw GlFalha("eglCreateContext falhou")
    }

    private fun tornarCorrente() {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw GlFalha("eglMakeCurrent falhou: " + EGL14.eglGetError())
        }
        renderer = GLES20.glGetString(GLES20.GL_RENDERER)
    }

    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        config = null
    }

    companion object {
        /** `EGL_OPENGL_ES3_BIT_KHR`. O EGL14 não o expõe como constante. */
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}

/** Compilação de programas e os enganos de GL que não dão erro. */
object Gl {

    fun compile(vertice: String, fragmento: String): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vertice)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fragmento)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        // Os shaders largam-se antes de decidir, senão o caminho de erro deixava-os pendurados.
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        if (ok[0] == 0) throw GlFalha("programa não ligou: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun shader(tipo: Int, fonte: String): Int {
        val s = GLES20.glCreateShader(tipo)
        GLES20.glShaderSource(s, fonte)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw GlFalha("shader não compilou: $log")
        }
        return s
    }

    /**
     * As localizações dos uniformes, e quais não resolveram.
     *
     * Um `glUniform*` para uma localização de -1 **não é erro de GL**: passa em silêncio e o shader
     * trabalha com zeros. Resolver tudo de uma vez e dar conta do que faltou é a única forma de isso
     * não passar em claro.
     */
    fun uniformLocations(programa: Int, nomes: List<String>): Pair<Map<String, Int>, List<String>> {
        val locais = HashMap<String, Int>()
        val faltam = ArrayList<String>()
        for (nome in nomes) {
            val l = GLES20.glGetUniformLocation(programa, nome)
            locais[nome] = l
            if (l < 0) faltam.add(nome)
        }
        return locais to faltam
    }

    /**
     * Entrega ao programa os valores preparados pelo `GlUniforms`.
     *
     * Num só sítio de propósito: a revelação de um DNG e o visor usam os mesmos shaders e têm de os
     * alimentar exactamente da mesma maneira, senão o visor deixa de mostrar o que o ficheiro vai
     * ter — que é a promessa central do produto.
     *
     * @param tamanho as dimensões do **mosaico**, não do alvo. O shader precisa delas para refletir
     *   nas bordas e para o raio da vinhetagem.
     */
    fun bindUniforms(locais: Map<String, Int>, u: GlUniforms, larguraRaw: Int, alturaRaw: Int) {
        fun loc(nome: String) = locais[nome] ?: -1
        GLES20.glUniformMatrix3fv(loc("uMatrizCor"), 1, false, u.colourMatrix, 0)
        GLES20.glUniform3fv(loc("uBalanco"), 1, u.whiteBalance, 0)
        GLES20.glUniform4i(loc("uCfa"), u.cfa[0], u.cfa[1], u.cfa[2], u.cfa[3])
        GLES20.glUniform4f(loc("uPreto"),
            u.blackLevel[0], u.blackLevel[1], u.blackLevel[2], u.blackLevel[3])
        GLES20.glUniform1f(loc("uBranco"), u.whiteLevel)
        GLES20.glUniform3fv(loc("uVinhetagem"), u.shadingRings, u.shadingLut, 0)
        GLES20.glUniform1i(loc("uAneis"), u.shadingRings)
        GLES20.glUniform1f(loc("uForca"), u.shadingStrength)
        GLES20.glUniform1f(loc("uRolloff"), u.rolloffWhitePoint)
        GLES20.glUniform2i(loc("uTamanho"), larguraRaw, alturaRaw)
    }

    /** Uma textura de inteiros de 16 bits: um canal, sem filtro, sem normalização. */
    fun createMosaicTexture(largura: Int, altura: Int): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R16UI, largura, altura, 0,
            GLES30.GL_RED_INTEGER, GLES20.GL_UNSIGNED_SHORT, null)
        // Texturas de inteiros só admitem amostragem sem filtro. Pedir GL_LINEAR é erro de GL.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    fun check(onde: String) {
        val erro = GLES20.glGetError()
        if (erro != GLES20.GL_NO_ERROR) throw GlFalha("erro de GL em $onde: $erro")
    }
}
