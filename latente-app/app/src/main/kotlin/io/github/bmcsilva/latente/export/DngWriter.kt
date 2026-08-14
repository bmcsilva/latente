package io.github.bmcsilva.latente.export

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri

/**
 * Escrita de DNG.
 *
 * `DngCreator` é o único caminho: não existe equivalente no NDK, e é isso que ancora a captura no
 * JVM. Escreve `ColorMatrix1/2`, `ForwardMatrix1/2`, `AsShotNeutral`, `NoiseProfile` e o mapa de
 * shading como opcode, a partir do `CaptureResult`.
 *
 * Limitação a conhecer: **não se pode definir `AsShotNeutral`.** O `DngCreator` deriva-o de
 * `SENSOR_NEUTRAL_COLOR_POINT` do resultado. Ou seja, o balanço de brancos escolhido pelo
 * utilizador não vai para o DNG por esta via — vai no sidecar, e é o nosso revelador que o aplica.
 * Corrigir isto exigiria escrever o DNG à mão.
 */
object DngWriter {

    class Result(val uri: Uri, val bytes: Long, val error: String?)

    /**
     * Texto em ASCII de 7 bits, que é o que o TIFF define para os campos de texto.
     *
     * O `DngCreator` codifica em UTF-8, e o «·» que se usava nas descrições saía como dois bytes num
     * campo que a especificação diz ser de 7 bits. Tudo o que abriu os ficheiros tolerou, mas fora da
     * especificação é fora da especificação — e o custo de a cumprir é esta função.
     */
    private fun ascii(t: String): String {
        val sb = StringBuilder(t.length)
        for (c in t) {
            sb.append(when (c) {
                '·' -> '-'
                'á', 'à', 'â', 'ã' -> 'a'
                'é', 'ê' -> 'e'
                'í' -> 'i'
                'ó', 'ô', 'õ' -> 'o'
                'ú' -> 'u'
                'ç' -> 'c'
                else -> if (c.code in 32..126) c else '?'
            })
        }
        return sb.toString()
    }

    fun write(
        ctx: Context,
        ch: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        name: String,
        description: String,
        /**
         * Orientação do TIFF, do `Present.exifOrientation`. Por omissão «normal», que é o que se
         * escrevia antes — e punha todas as fotografias de retrato viradas de lado.
         */
        orientation: Int = 1,
    ): Result {
        var written = 0L
        val out = MediaStoreOut(ctx)
        val uri = out.write(name, "image/x-adobe-dng") { stream ->
            escrever(ch, result, image, description, orientation, stream)
            // 2 bytes por pixel, sem compressão
            written = image.width.toLong() * image.height * 2
        }
        return Result(uri, written, null)
    }

    /**
     * O negativo para um fluxo qualquer, sem saber onde ele acaba.
     *
     * Existe para o arquivo poder pôr o DNG **dentro de um zip** sem que nada disto mude: os mesmos
     * pedidos ao `DngCreator`, pela mesma ordem, com os mesmos metadados. O que muda é o destino, e o
     * destino nunca foi assunto do negativo.
     */
    fun escrever(
        ch: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        description: String,
        orientation: Int,
        stream: java.io.OutputStream,
    ) {
        val creator = DngCreator(ch, result)
        try {
            creator.setOrientation(orientation)
            creator.setDescription(ascii(description))
            creator.writeImage(stream, image)
        } finally {
            creator.close()
        }
    }
}
