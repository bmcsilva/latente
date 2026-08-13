package io.github.bmcsilva.latente.diag

import android.content.Context
import android.os.Build
import io.github.bmcsilva.latente.camera.CameraSession
import io.github.bmcsilva.latente.camera.Planner
import io.github.bmcsilva.latente.export.DngReader
import io.github.bmcsilva.latente.export.Node
import io.github.bmcsilva.latente.model.LensProfile
import io.github.bmcsilva.latente.render.DevelopSettings
import io.github.bmcsilva.latente.render.GlPreview
import io.github.bmcsilva.latente.render.GlUniforms
import io.github.bmcsilva.latente.render.ShadingProfile

/**
 * A medição que decide a arquitectura da F3.
 *
 * A pergunta é simples e não tem resposta possível sem a fazer: **o visor pode ser alimentado pelo
 * stream RAW?** Neste dispositivo o RAW existe a um tamanho só, 12,5 Mpx, e cada frame são **25 MB
 * que têm de atravessar a fronteira CPU → GPU**. A 30 fps são 750 MB/s só de carregamento de textura.
 * Ou o barramento aguenta, ou o princípio de que o visor não mente tem de ser repensado.
 *
 * Mede-se antes de construir a UI, porque a resposta muda o que se constrói. É o mesmo método que
 * respondeu às nove perguntas da F1.
 *
 * Os uniformes vêm do **último DNG no disco**, e não do `CaptureResult`. É deliberado: a matriz de
 * cor, o mosaico e os níveis são estáticos por câmara, e o ponto neutro do ficheiro é o que a
 * aplicação escreveu. Assim mede-se o que interessa medir — a passagem dos dados — sem escrever
 * primeiro a plumbing de metadados ao vivo, que é trabalho da F3 propriamente dita.
 */
object PreviewProbe {

    /** Quantos frames se medem. Chega para a mediana estabilizar e não aquece o telefone. */
    private const val FRAMES = 60

    fun run(ctx: Context, lens: LensProfile, progress: (String) -> Unit): Node {
        val n = Node("Latente · viabilidade do visor RAW")
        n.put("dispositivo", Build.MANUFACTURER + " " + Build.MODEL)
        n.put("objectiva", lens.label)
        n.put("tamanho RAW", lens.rawSize.width.toString() + "x" + lens.rawSize.height)
        n.put("MB por frame", lens.rawSize.width.toLong() * lens.rawSize.height * 2 / 1024 / 1024)
        n.put("piso de frame do HAL ms", lens.minFrameDurationNs / 1e6)

        progress("a ler os uniformes do último DNG…")
        val dng = GpuCheck.ultimoDng(ctx)
        if (dng == null) {
            n.put("ERRO", "não há nenhum DNG em Downloads/Latente. Disparar primeiro, para haver " +
                    "de onde tirar a matriz de cor")
            return n
        }
        val reader = try {
            DngReader.open(dng)
        } catch (t: Throwable) {
            n.put("ERRO", "o DNG não abriu: " + t.message)
            return n
        }
        n.put("uniformes vindos de", dng.name)
        if (reader.width != lens.rawSize.width || reader.height != lens.rawSize.height) {
            n.put("AVISO", "o DNG é " + reader.width + "x" + reader.height + " e o stream é " +
                    lens.rawSize.width + "x" + lens.rawSize.height + " — trocar de objectiva e " +
                    "disparar outra vez")
            return n
        }

        val perfil = ShadingProfile.forDevice(Build.MODEL, lens.cameraId)
        val doFicheiro = GlUniforms.from(reader, perfil, DevelopSettings())

        var sessao: CameraSession? = null
        var visor: GlPreview? = null
        try {
            progress("a abrir a câmara…")
            sessao = CameraSession(ctx, lens.openId, lens.physicalId)
            sessao.open()?.let {
                n.put("ERRO", "abertura falhou: $it")
                return n
            }
            sessao.configure(lens.rawSize)?.let {
                n.put("ERRO", "configuração falhou: $it")
                return n
            }

            // Os uniformes ao vivo, vindos da câmara. O DNG serve agora de **referência**, não de
            // fonte: se os dois discordarem, o visor não mostra o que o ficheiro vai ter.
            val settings = DevelopSettings()
            val ambos = GlUniforms.fromCamera(
                sessao.imageCharacteristics, settings.kelvin, 0f, perfil, settings)
            if (ambos == null) {
                n.put("ERRO", "faltam metadados de cor nas características da câmara")
                return n
            }
            compararUniformes(n, ambos, doFicheiro)

            progress("a arrancar o GL…")
            visor = GlPreview(lens.rawSize.width, lens.rawSize.height)
            visor.start()
            n.put("GPU", visor.renderer)
            n.put("saída do visor", visor.width.toString() + "x" + visor.height)
            if (visor.missingUniforms.isNotEmpty()) {
                n.put("uniformes que não resolveram", visor.missingUniforms.joinToString(", "))
            }
            visor.setUniforms(ambos)

            progress("a assentar o sensor…")
            val plano = Planner.plan(lens, lens.defaultExposure())
            n.put("exposição", plano.effective.describe())
            val assentou = sessao.settle(plano.effective)
            if (assentou.error != null) n.put("aviso no assentamento", assentou.error)

            sessao.startStream()
            verificarAmostras(n, sessao)

            // Cinco fases, com o controlo repetido no início e no fim.
            //
            // A primeira versão tinha só quatro e tirava o culpado da diferença entre o controlo e a
            // produção. Deu um custo **negativo** — fazer mais era mais rápido do que fazer nada —
            // porque os intervalos melhoravam com a *ordem* das fases e não com a carga: subida de
            // relógios, com a primeira fase a correr com o telefone frio.
            //
            // A correcção tem duas partes. A espera pela câmara mede-se **dentro** de cada fase, o que
            // é uma comparação interna e imune à ordem; e o controlo repete-se no fim, o que torna a
            // deriva visível em vez de a deixar contaminar a conclusão.
            val cam = fase(n, "1 · câmara sozinha", sessao, progress) { _, _ -> }
            val carregar = fase(n, "2 · carregamento, com glFinish", sessao, progress) { plano, _ ->
                visor.upload(plano.buffer, plano.rowStride)
                visor.finish()
            }
            fase(n, "3 · carregamento e desenho, com glFinish", sessao, progress) { plano, _ ->
                visor.upload(plano.buffer, plano.rowStride)
                visor.draw()
                visor.finish()
            }
            val producao = fase(n, "4 · pipeline completo, sem glFinish", sessao, progress) { plano, _ ->
                visor.upload(plano.buffer, plano.rowStride)
                visor.draw()
            }
            visor.finish()
            // O controlo repete-se no fim, com o telefone já quente, para se saber quanto a própria
            // medição derivou entre a primeira fase e a última.
            val camFim = fase(n, "5 · câmara sozinha, no fim", sessao, progress) { _, _ -> }

            // A largura de banda torna o número interpretável: 19 ms para 23 MB é 1,2 GB/s, e daí
            // sabe-se se está perto do que a memória do dispositivo dá ou se há dobras a eliminar.
            val bytes = lens.rawSize.width.toLong() * lens.rawSize.height * 2
            if (carregar.trabalhoMs > 0) {
                n.put("largura de banda do carregamento MB/s",
                    bytes / 1024.0 / 1024.0 / (carregar.trabalhoMs / 1000.0))
            }

            n.put("diagnóstico do stream", sessao.streamDiagnostico())
            veredicto(n, cam, producao, camFim, sessao)
        } catch (t: Throwable) {
            n.put("EXCEPÇÃO", t.javaClass.simpleName + ": " + (t.message ?: ""))
            n.put("stack", t.stackTraceToString())
        } finally {
            sessao?.stopStream()
            sessao?.close()
            visor?.close()
        }
        return n
    }

    /**
     * Os uniformes ao vivo contra os do ficheiro.
     *
     * É a versão para o visor da mesma pergunta que fechou a F2: **o que se vê é o que se grava?** As
     * matrizes, o mosaico e os níveis vêm dos dois lados da mesma fonte — as características da câmara
     * — portanto têm de ser iguais ao bit. O ponto neutro é a excepção legítima: ao vivo é calculado do
     * Kelvin escolhido, no ficheiro é o que o HAL reportou depois dos `COLOR_CORRECTION_GAINS`, e
     * mediu-se que as duas vias concordam a 0,07%.
     */
    private fun compararUniformes(n: Node, camara: GlUniforms, ficheiro: GlUniforms) {
        val bloco = n.child("uniformes ao vivo contra os do ficheiro")

        var piorMatriz = 0.0
        for (i in 0 until 9) {
            val d = Math.abs(camara.colourMatrix[i] - ficheiro.colourMatrix[i]).toDouble()
            if (d > piorMatriz) piorMatriz = d
        }
        bloco.put("matriz de cor, diferença máxima", piorMatriz)

        bloco.put("mosaico igual", camara.cfa.contentEquals(ficheiro.cfa))
        bloco.put("mosaico ao vivo", camara.cfa.toList())
        bloco.put("nível de branco igual", camara.whiteLevel == ficheiro.whiteLevel)
        bloco.put("nível de preto igual", camara.blackLevel.contentEquals(ficheiro.blackLevel))

        // O balanço traz a exposição multiplicada, mas é a mesma nos dois; a razão isola o neutro.
        var piorBalanco = 0.0
        for (i in 0 until 3) {
            if (ficheiro.whiteBalance[i] == 0f) continue
            val r = Math.abs(camara.whiteBalance[i] / ficheiro.whiteBalance[i] - 1f).toDouble()
            if (r > piorBalanco) piorBalanco = r
        }
        bloco.put("balanço de brancos, desvio relativo máximo %", piorBalanco * 100)

        val coerente = piorMatriz < 1e-6 &&
                camara.cfa.contentEquals(ficheiro.cfa) &&
                camara.whiteLevel == ficheiro.whiteLevel &&
                camara.blackLevel.contentEquals(ficheiro.blackLevel)
        bloco.put("VEREDICTO", when {
            coerente && piorBalanco < 0.01 ->
                "CONCORDAM. Matriz, mosaico e níveis idênticos; balanço a " +
                        fmt(piorBalanco * 100) + "% — o visor mostra o que o ficheiro vai ter"
            coerente ->
                "matriz, mosaico e níveis idênticos, mas o balanço difere " +
                        fmt(piorBalanco * 100) + "%. Provável: o DNG foi disparado com outro Kelvin"
            else ->
                "DISCORDAM no que devia ser idêntico. Os dois lados leem a mesma fonte, portanto é " +
                        "bug de extracção — suspeitar da tabela do mosaico e da ordem da matriz"
        })
    }

    // -----------------------------------------------------------------------------------------

    /** O que uma fase mediu. */
    private class Fase(
        val nome: String,
        val frames: Int,
        val perdidos: Int,
        /** Mediana do intervalo entre frames consecutivos, em ms. É o que manda. */
        val intervaloMs: Double,
        /** Mediana do tempo passado dentro do trabalho, sem a espera pela câmara. */
        val trabalhoMs: Double,
        /**
         * Mediana do tempo bloqueado à espera do frame seguinte.
         *
         * É **este** o instrumento que diz quem é o travão, e não a diferença entre fases. Se este
         * número for grande, estamos à espera da câmara; se for zero, havia frames na fila e o travão
         * somos nós. Mede-se por frame, dentro da mesma fase, e por isso é imune à ordem das fases.
         */
        val esperaMs: Double,
    ) {
        val fps: Double get() = if (intervaloMs > 0) 1000.0 / intervaloMs else 0.0
    }

    /**
     * Corre uma fase e mede-a.
     *
     * Três tempos por frame: a **espera** pela câmara, o **trabalho** que o `corpo` leva, e o
     * **intervalo** entre frames, que é praticamente a soma dos dois.
     *
     * A primeira versão disto media só trabalho e intervalo, e tirava o culpado da diferença entre a
     * fase de controlo e a de produção. Deu um custo **negativo** — fazer mais era mais rápido do que
     * fazer nada — porque os intervalos melhoravam com a *ordem* das fases e não com a carga: subida
     * de relógios, com a primeira fase a correr com o telefone frio. Medir a espera dentro de cada
     * fase resolve isso: é uma comparação interna, não entre corridas em condições diferentes.
     */
    private fun fase(
        n: Node,
        nome: String,
        sessao: CameraSession,
        progress: (String) -> Unit,
        corpo: (android.media.Image.Plane, Int) -> Unit,
    ): Fase {
        progress("fase $nome…")

        val trabalho = DoubleArray(FRAMES)
        val intervalo = DoubleArray(FRAMES)
        val espera = DoubleArray(FRAMES)
        var medidos = 0
        var perdidos = 0
        var anterior = 0L

        while (medidos < FRAMES) {
            val antesDaEspera = System.nanoTime()
            val img = sessao.nextImage(2000)
            val t0 = System.nanoTime()
            if (img == null) {
                perdidos++
                if (perdidos > 5) break
                continue
            }
            try {
                val plano = img.planes[0]
                corpo(plano, medidos)
                val t1 = System.nanoTime()
                espera[medidos] = (t0 - antesDaEspera) / 1e6
                trabalho[medidos] = (t1 - t0) / 1e6
                if (anterior != 0L) intervalo[medidos] = (t1 - anterior) / 1e6
                anterior = t1
                medidos++
            } finally {
                img.close()
            }
        }

        // O primeiro intervalo não existe: começa-se a contar do segundo frame.
        val intervalos = DoubleArray(if (medidos > 1) medidos - 1 else 0)
        for (i in 1 until medidos) intervalos[i - 1] = intervalo[i]

        val f = Fase(nome, medidos, perdidos,
            mediana(intervalos, intervalos.size), mediana(trabalho, medidos),
            mediana(espera, medidos))

        val bloco = n.child(nome)
        bloco.put("frames", medidos)
        if (perdidos > 0) bloco.put("frames em falta no prazo", perdidos)
        estatistica(bloco, "espera pela câmara", espera, medidos)
        estatistica(bloco, "trabalho", trabalho, medidos)
        if (intervalos.isNotEmpty()) {
            estatistica(bloco, "intervalo entre frames", intervalos, intervalos.size)
            bloco.put("fps", f.fps)
        }
        return f
    }

    /**
     * O mosaico chega com os bytes na ordem certa?
     *
     * Se estivessem trocados, os valores espalhavam-se pelos 16 bits em vez de ficarem nos úteis, e a
     * imagem sairia com ruído em vez de cena. É verificação de um frame só, e não custa nada.
     */
    private fun verificarAmostras(n: Node, sessao: CameraSession) {
        val img = sessao.nextImage(2000)
        if (img == null) {
            n.put("amostras", "nenhum frame para verificar")
            return
        }
        try {
            val b = img.planes[0].buffer
            b.position(0)
            b.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val s = b.asShortBuffer()
            var minimo = Int.MAX_VALUE
            var maximo = 0
            var soma = 0.0
            var conta = 0L
            var i = 0
            while (i < s.limit()) {
                val v = s.get(i).toInt() and 0xFFFF
                if (v < minimo) minimo = v
                if (v > maximo) maximo = v
                soma += v
                conta++
                i += 97
            }
            n.put("passo de linha bytes", img.planes[0].rowStride)
            n.put("amostra mínima", minimo)
            n.put("amostra máxima", maximo)
            n.put("amostra média", soma / conta)
            n.put("ordem de bytes", if (maximo <= 4095) {
                "certa — os valores ficam nos bits úteis"
            } else {
                "SUSPEITA: valores acima de 4095 num sensor de 10 bits. Bytes trocados?"
            })
        } finally {
            img.close()
        }
    }

    // -----------------------------------------------------------------------------------------

    /**
     * A conclusão, agora com culpado medido em vez de inferido.
     *
     * A fase 1 é o tecto da câmara com a GPU a fazer nada. A fase 4 é o que se obtém a fazer o
     * trabalho todo. A diferença entre as duas é **o preço real do nosso pipeline**, e é esse número
     * que decide se há alguma coisa a optimizar ou se o tecto é do dispositivo.
     */
    private fun veredicto(
        n: Node,
        camaraInicio: Fase,
        producao: Fase,
        camaraFim: Fase,
        sessao: CameraSession,
    ) {
        // Distinguir «não é viável» de «a medição estragou-se» não é preciosismo: a corrida anterior
        // deu «não viável» quando o que se passou foi o stream ter parado por um bug nosso. Um
        // veredicto errado sobre o dispositivo manda o trabalho na direcção errada.
        if (producao.frames == 0) {
            n.put("MEDIÇÃO INVÁLIDA", if (camaraInicio.frames > 0) {
                "a fase de controlo correu " + camaraInicio.frames + " frames e a de produção " +
                        "nenhum: o stream parou a meio. Não é conclusão sobre o dispositivo, é " +
                        "falha da medição"
            } else {
                "nenhuma fase recebeu frames"
            })
            n.put("câmara viva no fim", sessao.isAlive)
            sessao.deathReason?.let { n.put("a câmara morreu porque", it) }
            return
        }
        if (producao.perdidos > 0 || camaraFim.frames < camaraInicio.frames) {
            n.put("AVISO", "houve frames em falta: a medição correu, mas o stream não esteve estável " +
                    "de ponta a ponta")
            n.put("câmara viva no fim", sessao.isAlive)
            sessao.deathReason?.let { n.put("a câmara morreu porque", it) }
        }

        n.put("visor completo fps", producao.fps)
        n.put("espera pela câmara ms", producao.esperaMs)
        n.put("trabalho na GPU ms", producao.trabalhoMs)

        // O par de controlos, um antes e outro depois, mede a deriva da própria medição. Se a câmara
        // sozinha der ritmos diferentes no início e no fim, comparar fases corridas em momentos
        // diferentes não vale — foi assim que a primeira versão concluiu um custo negativo.
        n.put("controlo, câmara sozinha no início fps", camaraInicio.fps)
        n.put("controlo, câmara sozinha no fim fps", camaraFim.fps)
        val deriva = camaraFim.fps - camaraInicio.fps
        n.put("deriva do controlo fps", deriva)
        if (Math.abs(deriva) > camaraInicio.fps * 0.05) {
            n.put("AVISO sobre a deriva", "a câmara sozinha mudou " + fmt(deriva) + " fps entre o " +
                    "início e o fim, ou seja " +
                    fmt(Math.abs(deriva) / camaraInicio.fps * 100) + "% — provavelmente subida de " +
                    "relógios. Comparar fases entre si não é fiável; vale o que se mede dentro de " +
                    "cada uma")
        }

        n.put("QUEM É O TRAVÃO", when {
            producao.esperaMs > producao.trabalhoMs * 2 ->
                "a câmara, com folga. Passamos " + fmt(producao.esperaMs) + " ms bloqueados à " +
                        "espera do frame e " + fmt(producao.trabalhoMs) + " ms a trabalhar. " +
                        "Optimizar a GPU não daria um frame a mais"
            producao.esperaMs > producao.trabalhoMs ->
                "a câmara: " + fmt(producao.esperaMs) + " ms de espera contra " +
                        fmt(producao.trabalhoMs) + " ms de trabalho. Há margem na GPU"
            producao.esperaMs > 1.0 ->
                "os dois: " + fmt(producao.esperaMs) + " ms de espera e " +
                        fmt(producao.trabalhoMs) + " ms de trabalho. Cortar no trabalho ainda dá " +
                        "frames, mas com retorno decrescente"
            else ->
                "nós. Não se espera pela câmara — há sempre frame na fila — e o trabalho leva " +
                        fmt(producao.trabalhoMs) + " ms. É aí que há tudo a ganhar"
        })

        n.put("VEREDICTO", when {
            producao.fps >= 24.0 ->
                "VIÁVEL. " + fmt(producao.fps) + " fps com o pipeline completo. O visor pode ser " +
                        "alimentado pelo stream RAW e mostrar o resultado final"
            producao.fps >= 15.0 ->
                "viável com ressalva: " + fmt(producao.fps) + " fps. Serve para compor e focar, " +
                        "mas não é fluido. Ver o travão acima antes de optimizar o que não é o problema"
            else ->
                "NÃO VIÁVEL a este ritmo: " + fmt(producao.fps) + " fps"
        })
    }

    private fun estatistica(n: Node, nome: String, v: DoubleArray, quantos: Int) {
        if (quantos == 0) return
        var soma = 0.0
        var pior = 0.0
        for (i in 0 until quantos) {
            soma += v[i]
            if (v[i] > pior) pior = v[i]
        }
        n.put("$nome ms (mediana)", mediana(v, quantos))
        n.put("$nome ms (média)", soma / quantos)
        n.put("$nome ms (pior)", pior)
    }

    /**
     * A mediana, não a média: um frame perdido a esperar pela câmara arrasta a média e não diz nada
     * sobre o custo normal.
     */
    internal fun mediana(v: DoubleArray, quantos: Int): Double {
        if (quantos == 0) return 0.0
        val c = DoubleArray(quantos)
        System.arraycopy(v, 0, c, 0, quantos)
        java.util.Arrays.sort(c)
        return if (quantos % 2 == 1) c[quantos / 2] else (c[quantos / 2 - 1] + c[quantos / 2]) / 2
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
