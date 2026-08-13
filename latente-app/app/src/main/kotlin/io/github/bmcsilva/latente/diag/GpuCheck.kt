package io.github.bmcsilva.latente.diag

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import io.github.bmcsilva.latente.export.DngReader
import io.github.bmcsilva.latente.export.MediaStoreOut
import io.github.bmcsilva.latente.export.Node
import io.github.bmcsilva.latente.export.SidecarRead
import io.github.bmcsilva.latente.export.Tiff16Writer
import io.github.bmcsilva.latente.render.DevelopSettings
import io.github.bmcsilva.latente.render.GlDeveloper
import io.github.bmcsilva.latente.render.RawPipeline
import io.github.bmcsilva.latente.render.Rgb
import io.github.bmcsilva.latente.render.ShadingProfile
import java.io.File

/**
 * O critério de aceitação da F2, como botão.
 *
 * Revela o mesmo DNG pelos dois caminhos — CPU em Kotlin e GPU em GLSL — e mede a diferença. Como
 * os dois estão no telefone, a verificação não precisa de mais nada nem de ninguém.
 *
 * A matemática dos dois lados foi feita idêntica de propósito, incluindo a ordem em que a
 * vinhetagem é interpolada e invertida. **Logo qualquer diferença acima do arredondamento de oito
 * bits é bug de canalização**, e não dúvida sobre cor. É essa a razão de tanto cuidado antes de
 * chegar aqui.
 */
object GpuCheck {

    /** Diferença de uma unidade em oito bits é arredondamento, não desacordo. */
    private const val TOLERANCIA = 1

    fun run(ctx: Context, settings: DevelopSettings, progress: (String) -> Unit): Node {
        val n = Node("Latente · CPU contra GPU")
        n.put("dispositivo", Build.MANUFACTURER + " " + Build.MODEL)
        n.put("exposição de revelação EV", settings.exposureEv)
        n.put("força da vinhetagem", settings.shadingStrength)
        n.put("rolloff", settings.rolloff)
        n.put("espaço de saída", settings.output.name)

        progress("a procurar o DNG mais recente…")
        val ficheiro = copiarUltimoDng(ctx)
        if (ficheiro == null) {
            n.put("ERRO", "não há nenhum DNG em Downloads/Latente. Disparar primeiro")
            return n
        }
        n.put("ficheiro", ficheiro.name)

        val reader = try {
            DngReader.open(ficheiro)
        } catch (t: Throwable) {
            n.put("ERRO", "o DNG não abriu: " + t.message)
            return n
        }
        n.put("dimensões", reader.width.toString() + "x" + reader.height)
        n.put("mosaico", reader.cfa.toList())
        n.put("nível de branco", reader.whiteLevel)

        // A objectiva vem do sidecar, não de um valor fixo: os perfis de vinhetagem são por câmara, e
        // aplicar o da principal a uma foto da ultra-grande-angular corrigiria a queda errada — 4,7×
        // no canto contra 8,5×. Sem sidecar não se adivinha: não se corrige.
        val idCamara = lerIdDaCamara(ficheiro)
        n.put("id da câmara", idCamara ?: "desconhecido — sidecar em falta")
        val perfil = if (idCamara != null) {
            ShadingProfile.forDevice(Build.MODEL, idCamara)
        } else {
            null
        }
        n.put("perfil de vinhetagem", if (perfil != null) {
            perfil.rings.toString() + " anéis, calibrados para a câmara " + idCamara
        } else {
            "nenhum — não se corrige"
        })

        // A GPU vem primeiro, e não é indiferente: a imagem do CPU ocupa 150 MB numa de 12 Mpx, e o
        // tecto do monte é 256 MB. Revelar na GPU enquanto o monte está livre é a diferença entre
        // funcionar e morrer sem memória — foi exactamente assim que morreu à primeira tentativa.
        progress("a revelar na GPU…")
        val t1 = System.currentTimeMillis()
        var gpu: java.nio.ByteBuffer? = null
        val gl = GlDeveloper()
        try {
            gpu = gl.develop(reader, perfil, settings)
        } catch (t: Throwable) {
            n.put("ERRO na GPU", t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            n.put("GPU", gl.renderer)
            if (gl.missingUniforms.isNotEmpty()) {
                n.put("uniformes que não resolveram", gl.missingUniforms.joinToString(", "))
            }
            gl.close()
        }
        val msGpu = System.currentTimeMillis() - t1
        if (gpu == null) return n
        n.put("GPU ms", msGpu)

        progress("a revelar no CPU…")
        val t0 = System.currentTimeMillis()
        val linear = RawPipeline.develop(reader, perfil, settings)
        val msCpu = System.currentTimeMillis() - t0
        n.put("CPU ms", msCpu)
        if (msGpu > 0) n.put("GPU mais rápida", String.format(java.util.Locale.US, "%.1f×",
            msCpu.toDouble() / msGpu))

        progress("a comparar…")
        comparar(n, linear, gpu)

        progress("a escrever o TIFF…")
        escreverTiff(ctx, n, linear, settings)
        return n
    }

    /**
     * Compara canal a canal, codificando o lado do CPU à medida.
     *
     * Não se materializa a imagem do CPU em oito bits: seriam mais 37 MB ao lado dos 150 MB que a
     * versão em vírgula flutuante já ocupa. A codificação é a mesma que o `encode8` usa, amostra a
     * amostra, portanto compara-se contra o que o ficheiro levaria e não contra uma segunda versão
     * da codificação.
     *
     * O CPU dá RGB entrelaçado, a GPU dá RGBA — o alfa ignora-se.
     */
    private fun comparar(n: Node, linear: Rgb, gpu: java.nio.ByteBuffer) {
        val largura = linear.width
        val total = largura * linear.height
        if (gpu.capacity() < total * 4 || linear.data.size < total * 3) {
            n.put("ERRO", "tamanhos incompatíveis: CPU " + linear.data.size +
                    ", GPU " + gpu.capacity())
            return
        }

        var pior = 0
        var soma = 0L
        var acima = 0L
        var piorEm = -1
        var piorCanal = -1

        for (i in 0 until total) {
            for (c in 0 until 3) {
                val a = RawPipeline.encodeSample8(linear.data[i * 3 + c])
                val b = gpu.get(i * 4 + c).toInt() and 0xFF
                val d = Math.abs(a - b)
                soma += d
                if (d > TOLERANCIA) acima++
                if (d > pior) {
                    pior = d
                    piorEm = i
                    piorCanal = c
                }
            }
        }

        val amostras = total.toLong() * 3
        n.put("diferença máxima", pior)
        n.put("diferença média", soma.toDouble() / amostras)
        n.put("amostras acima de $TOLERANCIA", acima)
        n.put("percentagem acima de $TOLERANCIA", acima.toDouble() / amostras * 100)
        if (piorEm >= 0) {
            n.put("pior em", "(" + (piorEm % largura) + ", " + (piorEm / largura) + ") canal " +
                    "RGB"[piorCanal])
        }

        n.put("VEREDICTO", when {
            pior <= TOLERANCIA ->
                "CONCORDAM. A diferença máxima é de $pior em 255, que é arredondamento de oito " +
                        "bits. O shader e o Kotlin calculam o mesmo"
            acima.toDouble() / amostras < 0.001 ->
                "quase — $pior de diferença máxima, mas só em " +
                        String.format(java.util.Locale.US, "%.3f%%", acima.toDouble() / amostras * 100) +
                        " das amostras. Verificar se são bordas"
            else ->
                "DISCORDAM: $pior de diferença máxima em " +
                        String.format(java.util.Locale.US, "%.1f%%", acima.toDouble() / amostras * 100) +
                        " das amostras. É bug de canalização — a matemática é a mesma nos dois lados"
        })
    }

    private fun escreverTiff(ctx: Context, n: Node, linear: Rgb, settings: DevelopSettings) {
        try {
            val nome = "LTNT_revelado_" +
                    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
            val icc = Tiff16Writer.iccFor(settings.output)
            MediaStoreOut(ctx).write("$nome.tif", "image/tiff") { out ->
                // Escreve da imagem linear, codificando linha a linha: um `ShortArray` completo
                // seriam mais 75 MB que não há.
                Tiff16Writer.write(out, linear, icc,
                    "Latente · revelação própria, sem processamento computacional")
            }
            n.put("TIFF escrito", "$nome.tif")
            n.put("tamanho aproximado MB", linear.width.toLong() * linear.height * 6 / 1024 / 1024)
            n.put("perfil ICC", settings.output.name + ", " + icc.size + " bytes")
        } catch (t: Throwable) {
            n.put("ERRO ao escrever o TIFF", t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    /**
     * Copia para a cache o DNG mais recente que a aplicação escreveu, e o sidecar ao lado dele.
     *
     * Passa pelo `MediaStore` porque foi por lá que os ficheiros foram criados, e copia-se porque o
     * leitor precisa de acesso aleatório — um fluxo de leitura não serve para saltar entre tiras.
     */
    internal fun ultimoDng(ctx: Context): File? = copiarUltimoDng(ctx)

    private fun copiarUltimoDng(ctx: Context): File? {
        val projeccao = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val onde = MediaStore.Downloads.RELATIVE_PATH + " LIKE ? AND " +
                MediaStore.Downloads.DISPLAY_NAME + " LIKE '%.dng'"
        val argumentos = arrayOf("%" + MediaStoreOut.FOLDER + "%")

        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projeccao, onde, argumentos,
            MediaStore.Downloads.DATE_ADDED + " DESC"
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val nome = c.getString(c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
            val destino = copiar(ctx,
                c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID)), nome) ?: return null
            copiarSidecar(ctx, nome.substringBeforeLast('.') + ".json")
            return destino
        }
        return null
    }

    private fun copiar(ctx: Context, id: Long, nome: String): File? {
        val uri = android.content.ContentUris.withAppendedId(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        val destino = File(ctx.cacheDir, nome)
        ctx.contentResolver.openInputStream(uri)?.use { entrada ->
            destino.outputStream().use { saida -> entrada.copyTo(saida) }
        } ?: return null
        return destino
    }

    private fun copiarSidecar(ctx: Context, nome: String) {
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            MediaStore.Downloads.DISPLAY_NAME + " = ?", arrayOf(nome), null
        )?.use { c ->
            if (c.moveToFirst()) copiar(ctx, c.getLong(0), nome)
        }
    }

    /**
     * O id da câmara, lido do sidecar do disparo. Sem ele não se corrige a vinhetagem — que é o
     * comportamento seguro, e o relatório di-lo em vez de a app adivinhar a objectiva.
     */
    private fun lerIdDaCamara(dng: File): String? {
        val sidecar = File(dng.parentFile, dng.name.substringBeforeLast('.') + ".json")
        if (!sidecar.isFile) return null
        return try {
            SidecarRead.cameraId(sidecar.readText())
        } catch (t: Throwable) {
            null
        }
    }
}
