package io.github.bmcsilva.latente.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import io.github.bmcsilva.latente.render.DevelopSettings
import io.github.bmcsilva.latente.render.RawPipeline
import io.github.bmcsilva.latente.render.ShadingProfile
import java.io.File

/**
 * As miniaturas da biblioteca: reveladas do negativo, e guardadas em cache.
 *
 * **Saem da mesma revelação que o TIFF**, com a receita do sidecar, só que sobre um mosaico reduzido:
 * um quadrado em cada oito. Não é uma segunda imagem parecida com a fotografia — é a fotografia, em
 * ponto pequeno. Num projecto que promete que o visor mostra o resultado final, uma lista com
 * miniaturas do ISP seria a mentira mais visível de todas.
 *
 * Não vão dentro do negativo, e é decisão: o caminho da captura está certificado e verificado ao bit,
 * e uma comodidade da lista não é razão para lhe mexer. Também não são um quarto ficheiro por
 * fotografia — vivem na área privada da aplicação, e apagá-las não perde nada porque se refazem.
 *
 * Do lado de fora só há duas operações: pedir a que já existe, que é imediata, e mandar fazer a que
 * falta, que leva décimos de segundo e tem de correr fora da thread do ecrã.
 */
object Thumbnails {

    /**
     * Um quadrado do mosaico em cada oito.
     *
     * De 4080×3060 dá 510×382, e depois o *binning* 2×2 do revelador dá 255×191 — o tamanho de uma
     * miniatura de lista num ecrã de 3×. Menos do que isto começa a não haver o que ver; mais é ler o
     * ficheiro todo para o deitar fora.
     */
    private const val REDUCAO = 8

    /**
     * Para ver em grande: um quadrado em cada dois, que de 4080×3060 dá 1020×765 depois do *binning*.
     *
     * Chega para um ecrã de telemóvel e lê-se em pouco mais de um segundo. A resolução inteira exigiria
     * ler os 24 MB e revelar 12 megapíxeis para uma pré-visualização — e para isso existe o botão de
     * revelar, que escreve um ficheiro a sério.
     */
    private const val REDUCAO_DA_VISTA = 2

    /** A largura a que a lista mostra a miniatura, em píxeis do mosaico reduzido. */
    private const val LARGURA_DA_MINIATURA = 255

    private fun pasta(ctx: Context): File {
        val p = File(ctx.filesDir, "miniaturas")
        if (!p.exists()) p.mkdirs()
        return p
    }

    private fun ficheiro(ctx: Context, base: String) = File(pasta(ctx), "$base.png")

    /** A miniatura que já existe, ou nulo. Não revela nada: pode chamar-se da thread do ecrã. */
    fun cached(ctx: Context, base: String): Bitmap? {
        val f = ficheiro(ctx, base)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    /**
     * Revela a miniatura se ela faltar, e devolve-a. **Lenta**: só de uma thread de trabalho.
     *
     * Devolve nulo quando o negativo não vem do `MediaStore` ou o ficheiro não se deixa ler. Um erro
     * aqui não é motivo para nada: a lista mostra o lugar vazio e a fotografia continua toda lá.
     */
    fun ensure(ctx: Context, shot: Library.Shot): Bitmap? {
        cached(ctx, shot.baseName)?.let { return it }
        // Sem negativo, a miniatura sai da cópia revelada.
        //
        // Apareceu com o «apagar»: quem apaga o negativo e fica com o JPEG continua a ver a fotografia
        // na lista, e uma linha com o lugar da imagem vazio ao lado de um JPEG que existe parece
        // defeito. Não é a nossa revelação — é uma cópia dela —, mas para reconhecer a fotografia
        // chega, e é tudo o que resta.
        if (shot.orphan) return daCopia(ctx, shot)

        // Prefixo próprio na cache: o botão de revelar traz o mesmo negativo com o nome de sempre, e as
        // duas thread apagavam-se o ficheiro uma à outra a meio da leitura.
        val dng = Library.negativo(ctx, shot, "mini-") ?: return null
        try {
            // A receita daquele dia, como no botão de revelar: uma miniatura que não obedecesse ao
            // sidecar mostraria uma revelação que não existe em ficheiro nenhum.
            val texto = Library.receita(ctx, shot, "mini-")
            val settings = SidecarRead.develop(texto)
            val reader = DngReader.open(dng)
            val perfil = ShadingProfile.forDevice(Build.MODEL, SidecarRead.cameraId(texto) ?: "")
            val linear = RawPipeline.develop(
                reader, perfil, DevelopSettings(
                    exposureEv = settings.exposureEv,
                    kelvin = settings.kelvin,
                    shadingStrength = settings.shadingStrength,
                    rolloff = settings.rolloff,
                    output = settings.output,
                    // O caminho barato do *demosaicing*, que é o do visor. Numa miniatura, a diferença
                    // entre Malvar e o *binning* não tem onde se ver.
                    halfResolution = true),
                reducao = REDUCAO)

            val bitmap = RgbBitmap.encode(linear)
            val direita = RgbBitmap.rotate(bitmap, SidecarRead.rotationDegrees(texto))
            ficheiro(ctx, shot.baseName).outputStream().use { out ->
                direita.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            return direita
        } catch (t: Throwable) {
            return null
        } finally {
            dng.delete()
        }
    }

    /**
     * Revela o negativo para um JPEG na cache, para a galeria do telefone o poder mostrar.
     *
     * Menos reduzido do que a miniatura — quem manda ver uma fotografia quer vê-la —, e revelado com a
     * receita dela, como tudo o resto. É a **nossa** revelação: mandar a galeria abrir o DNG mostraria
     * a ideia que o fabricante tem do mosaico, que é o contrário do que esta aplicação promete.
     *
     * O ficheiro fica na cache e é substituído à próxima vez. **Lenta**: só de uma thread de trabalho.
     */
    fun previsualizacao(ctx: Context, shot: Library.Shot): File? {
        val dng = Library.negativo(ctx, shot, "vista-") ?: return null
        try {
            val texto = Library.receita(ctx, shot, "vista-")
            val settings = SidecarRead.develop(texto)
            val reader = DngReader.open(dng)
            val perfil = ShadingProfile.forDevice(Build.MODEL, SidecarRead.cameraId(texto) ?: "")
            val linear = RawPipeline.develop(
                reader, perfil, DevelopSettings(
                    exposureEv = settings.exposureEv,
                    kelvin = settings.kelvin,
                    shadingStrength = settings.shadingStrength,
                    rolloff = settings.rolloff,
                    output = settings.output,
                    halfResolution = true),
                reducao = REDUCAO_DA_VISTA)
            val direita = RgbBitmap.rotate(
                RgbBitmap.encode(linear), SidecarRead.rotationDegrees(texto))
            val destino = File(PreviewProvider.pasta(ctx), shot.baseName + ".jpg")
            destino.outputStream().use { out ->
                direita.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            return destino
        } catch (t: Throwable) {
            return null
        } finally {
            dng.delete()
        }
    }

    /**
     * A miniatura tirada do JPEG revelado, para as fotografias que já não têm negativo.
     *
     * Com `inSampleSize`: descodificar 12 megapíxeis para mostrar 255 píxeis de largura seriam 48 MB
     * por linha da lista. A potência de dois mais próxima chega e custa quase nada.
     */
    private fun daCopia(ctx: Context, shot: Library.Shot): Bitmap? {
        val id = shot.jpgId ?: return null
        val copia = Library.fetch(ctx, id, "mini-" + shot.baseName + ".jpg") ?: return null
        try {
            val medida = BitmapFactory.Options()
            medida.inJustDecodeBounds = true
            BitmapFactory.decodeFile(copia.absolutePath, medida)
            val opcoes = BitmapFactory.Options()
            var passo = 1
            while (medida.outWidth / (passo * 2) >= LARGURA_DA_MINIATURA) passo *= 2
            opcoes.inSampleSize = passo
            val b = BitmapFactory.decodeFile(copia.absolutePath, opcoes) ?: return null
            ficheiro(ctx, shot.baseName).outputStream().use { out ->
                b.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            return b
        } catch (t: Throwable) {
            return null
        } finally {
            copia.delete()
        }
    }

    /** Apaga a miniatura de uma fotografia, para ela se refazer da próxima vez. */
    fun invalidate(ctx: Context, base: String) {
        ficheiro(ctx, base).delete()
    }

}
