package io.github.bmcsilva.latente.export

import android.graphics.Bitmap
import io.github.bmcsilva.latente.render.ColorScience
import io.github.bmcsilva.latente.render.Rgb

/**
 * Do linear de oito bytes por píxel para o `Bitmap` de quatro.
 *
 * A codificação é a mesma do TIFF — sRGB —, e é um passo separado da revelação de propósito: quem
 * escreve dezasseis bits e quem desenha oito partem do mesmo sítio, que é o linear. Existe num sítio
 * só porque tem dois clientes que **têm** de concordar: a miniatura da biblioteca e a cópia em JPEG.
 */
object RgbBitmap {

    /**
     * @param linear a imagem revelada, ainda linear.
     *
     * O `Bitmap` sai sempre em sRGB. Enquanto a revelação não sair de sRGB isto é exacto; no dia em
     * que sair, quem quiser um JPEG noutro espaço tem de lhe pôr o perfil ICC — e o TIFF, que já o
     * leva, continua a ser o caminho certo para arquivo.
     */
    fun encode(linear: Rgb): Bitmap {
        // **Linha a linha**, e não a imagem toda num `IntArray`.
        //
        // Numa revelação de 12 megapíxeis o vector inteiro são 48 MB, que se somavam aos 150 MB do
        // linear e aos 48 do `Bitmap` — 250 MB de pico para escrever um JPEG. Com uma linha de cada
        // vez o vector passa a 16 kB e o pico desce 48 MB, que num sítio onde já houve falta de
        // memória não é pouco.
        val bitmap = Bitmap.createBitmap(linear.width, linear.height, Bitmap.Config.ARGB_8888)
        val linha = IntArray(linear.width)
        var i = 0
        for (y in 0 until linear.height) {
            for (x in 0 until linear.width) {
                val r = (ColorScience.srgbEncode(linear.data[i].toDouble()) * 255.0 + 0.5).toInt()
                val g = (ColorScience.srgbEncode(linear.data[i + 1].toDouble()) * 255.0 + 0.5).toInt()
                val b = (ColorScience.srgbEncode(linear.data[i + 2].toDouble()) * 255.0 + 0.5).toInt()
                linha[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i += 3
            }
            bitmap.setPixels(linha, 0, linear.width, 0, y, linear.width, 1)
        }
        return bitmap
    }

    /**
     * A imagem de pé.
     *
     * O negativo sai na orientação do sensor e leva a rotação como **etiqueta**, que é o que faz o DNG
     * e o TIFF aparecerem direitos em quem os lê. Um `Bitmap` não tem etiqueta nenhuma e o
     * `Bitmap.compress` não escreve EXIF: ou se rodam os píxeis, ou as fotografias de retrato saem
     * deitadas — que é exactamente o defeito que a F1 teve nos ficheiros.
     */
    fun rotate(b: Bitmap, graus: Int): Bitmap {
        if (graus % 360 == 0) return b
        val m = android.graphics.Matrix()
        m.postRotate(graus.toFloat())
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}
