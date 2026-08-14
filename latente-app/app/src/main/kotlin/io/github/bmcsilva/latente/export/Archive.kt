package io.github.bmcsilva.latente.export

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * O arquivo de uma fotografia: o negativo e a receita **dentro de um zip**.
 *
 * Um negativo custa 24 MB e comprime para 8 ou 9 — medido em três negativos reais, entre 34% e 44% do
 * original. Comprime tanto porque os dez bits úteis do sensor viajam em palavras de dezasseis: o byte
 * de cima é quase sempre o mesmo, e é isso que o *deflate* come.
 *
 * **Não se toca na imagem.** Foi a decisão do utilizador e é a certa: o `deflate` é reversível ao bit —
 * verificado com `md5` sobre um negativo de 24 999 600 bytes, que volta idêntico —, ao contrário de um
 * DNG comprimido com JPEG sem perdas, que obrigaria a escrever um codificador no caminho da captura,
 * que está certificado. Aqui o caminho da captura continua a produzir exactamente os mesmos bytes; o
 * que muda é o saco onde eles vão.
 *
 * O que se perde, e fica dito: um `.zip` **não abre no darktable**. Descomprime-se primeiro — a
 * aplicação faz isso sozinha para revelar e para as miniaturas, e no computador é um comando.
 */
object Archive {

    const val EXTENSAO = "zip"

    /**
     * Escreve o arquivo de uma fotografia: `nome.dng` e `nome.json` dentro de `nome.zip`.
     *
     * Os dois no mesmo saco porque são a mesma fotografia — o negativo sem a receita revela-se com as
     * omissões, e a receita sem o negativo não revela nada. Separá-los é como perder um dos dois.
     *
     * @return o tamanho do zip em bytes, ou zero se o `MediaStore` não o souber dizer.
     */
    fun escrever(
        ctx: Context,
        nome: String,
        ch: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        descricao: String,
        orientacao: Int,
        sidecar: String,
    ): Long {
        val uri = MediaStoreOut(ctx).write("$nome.$EXTENSAO", "application/zip") { saida ->
            val zip = ZipOutputStream(saida)
            zip.setLevel(NIVEL)
            zip.putNextEntry(ZipEntry("$nome.dng"))
            DngWriter.escrever(ch, result, image, descricao, orientacao, zip)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$nome.json"))
            zip.write(sidecar.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.finish()
        }
        return try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (t: Throwable) {
            0L
        }
    }

    /**
     * Tira o negativo do arquivo para um ficheiro que se possa ler às saltas.
     *
     * O leitor de DNG precisa de acesso aleatório — salta entre tiras —, e uma entrada de zip é um
     * fluxo. Descomprime-se para a cache, como já se copiava o negativo solto para lá; a diferença é
     * que agora se lêem 9 MB do disco em vez de 24.
     */
    fun extrairNegativo(arquivo: File, destino: File): File? = extrair(arquivo, ".dng", destino)

    /** A receita, que é pequena e se lê de uma vez. */
    fun lerReceita(arquivo: File): String? {
        return try {
            ZipFile(arquivo).use { zip ->
                val e = zip.entries().toList().firstOrNull { it.name.endsWith(".json") }
                    ?: return null
                zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun extrair(arquivo: File, sufixo: String, destino: File): File? {
        return try {
            ZipFile(arquivo).use { zip ->
                val e = zip.entries().toList().firstOrNull { it.name.endsWith(sufixo) } ?: return null
                zip.getInputStream(e).use { entrada ->
                    destino.outputStream().use { saida -> entrada.copyTo(saida) }
                }
            }
            destino
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Nível 4, e é **medido** em quatro negativos reais, não escolhido por ser o meio da escala.
     *
     * | negativo | nível 1 | nível 4 | nível 6 |
     * |---|---|---|---|
     * | canto de parede | 36,0% · 0,22 s | **32,9% · 0,31 s** | 34,5% · 1,21 s |
     * | folha 4000 K | 46,7% | **44,5%** | 44,4% · 1,16 s |
     * | chapa plana | 37,5% | **34,5% · 0,30 s** | 36,3% · 1,21 s |
     * | receita | 52,5% | **50,9%** | 50,9% · 0,96 s |
     *
     * O nível 4 **nunca é pior** do que o 6 — em dois dos quatro é 1,5 a 1,8 pontos melhor — e custa um
     * quarto do tempo. Não é anomalia: a partir do 4 o zlib passa a *lazy matching*, e nestes dados,
     * que são ruído com um byte alto quase constante, as cadeias curtas do 4 acertam melhor do que as
     * longas do 6. No telefone o nível 4 leva cerca de meio segundo por negativo.
     */
    private const val NIVEL = 4
}
