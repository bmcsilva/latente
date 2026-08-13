package io.github.bmcsilva.latente.export

import io.github.bmcsilva.latente.render.Mosaic
import java.io.File
import java.io.RandomAccessFile

/**
 * Leitor de DNG, o suficiente para revelar.
 *
 * Um DNG é um TIFF: cabeçalho, tabela de etiquetas, dados. Não é preciso biblioteca nenhuma, e é
 * por isso que não se usa nenhuma — a mesma razão que levou a escrever o `tools/dngcheck.py` em
 * Python puro.
 *
 * O `DngCreator` do Android escreve a imagem do mosaico **no próprio IFD0**, sem SubIFD, sem
 * compressão, com uma tira por linha. Este leitor trata o caso geral na medida do razoável, mas é
 * esse o formato que produz e que interessa reler.
 */
class DngReader private constructor(
    val width: Int,
    val height: Int,
    val whiteLevel: Int,
    val blackLevel: FloatArray,
    val cfa: IntArray,
    val asShotNeutral: DoubleArray,
    val forwardMatrix1: DoubleArray?,
    val forwardMatrix2: DoubleArray?,
    val illuminant1: Int,
    val illuminant2: Int,
    private val stripOffsets: LongArray,
    private val stripCounts: LongArray,
    private val rowsPerStrip: Int,
    private val littleEndian: Boolean,
    private val path: File,
) {

    /**
     * Lê o mosaico e normaliza-o: preto a 0, branco a 1.
     *
     * É o primeiro passo da §6.1, e o único que lê do disco. Daqui em diante tudo é aritmética.
     */
    fun readMosaic(): Mosaic {
        val data = FloatArray(width * height)
        RandomAccessFile(path, "r").use { f ->
            val buf = ByteArray(width * 2)
            var y = 0
            for (s in stripOffsets.indices) {
                f.seek(stripOffsets[s])
                val linhas = minOf(rowsPerStrip, height - y)
                for (i in 0 until linhas) {
                    val querido = minOf(buf.size.toLong(), stripCounts[s]).toInt()
                    f.readFully(buf, 0, querido)
                    val base = y * width
                    for (x in 0 until width) {
                        val lo = buf[x * 2].toInt() and 0xFF
                        val hi = buf[x * 2 + 1].toInt() and 0xFF
                        val v = if (littleEndian) (hi shl 8) or lo else (lo shl 8) or hi
                        // O nível de preto vem por posição do CFA, não é um valor só.
                        val preto = blackLevel[(y and 1) * 2 + (x and 1)]
                        data[base + x] = ((v - preto) / (whiteLevel - preto)).toFloat()
                    }
                    y++
                    if (y >= height) break
                }
                if (y >= height) break
            }
        }
        return Mosaic(data, width, height, cfa)
    }

    /**
     * As amostras cruas de 16 bits, sem normalizar.
     *
     * É o que vai para a GPU: o shader faz a normalização de níveis, e enviar inteiros em vez de
     * vírgula flutuante poupa metade da largura de banda e não perde nada — os dez bits úteis
     * cabem folgadamente.
     */
    fun readRawSamples(): ShortArray {
        val out = ShortArray(width * height)
        RandomAccessFile(path, "r").use { f ->
            val buf = ByteArray(width * 2)
            var y = 0
            for (s in stripOffsets.indices) {
                f.seek(stripOffsets[s])
                val linhas = minOf(rowsPerStrip, height - y)
                for (i in 0 until linhas) {
                    f.readFully(buf, 0, minOf(buf.size.toLong(), stripCounts[s]).toInt())
                    val base = y * width
                    for (x in 0 until width) {
                        val lo = buf[x * 2].toInt() and 0xFF
                        val hi = buf[x * 2 + 1].toInt() and 0xFF
                        out[base + x] = (if (littleEndian) (hi shl 8) or lo
                        else (lo shl 8) or hi).toShort()
                    }
                    y++
                    if (y >= height) break
                }
                if (y >= height) break
            }
        }
        return out
    }

    companion object {

        private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)

        fun open(file: File): DngReader {
            val bytes = file.readBytes()
            require(bytes.size > 8) { "ficheiro demasiado pequeno para ser um TIFF" }
            val little = when {
                bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> true
                bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> false
                else -> throw IllegalArgumentException("não é TIFF/DNG")
            }
            val r = Cursor(bytes, little)
            require(r.u16(2) == 42) { "magia TIFF inesperada" }

            val ifd = r.ifd(r.u32(4).toInt())

            // O DngCreator põe o mosaico no IFD0. Se algum dia vier num SubIFD, procura-se lá.
            var tags = ifd
            if (photometric(ifd) != 32803) {
                val subs = ifd[330]?.longs(r)
                if (subs != null) {
                    for (off in subs) {
                        val sub = r.ifd(off.toInt())
                        if (photometric(sub) == 32803) {
                            tags = sub
                            break
                        }
                    }
                }
            }
            require(photometric(tags) == 32803) { "o DNG não tem imagem de mosaico" }

            val width = tags[256]!!.longs(r)[0].toInt()
            val height = tags[257]!!.longs(r)[0].toInt()
            val white = tags[50717]?.longs(r)?.get(0)?.toInt() ?: 1023

            val pretoRaw = tags[50714]?.doubles(r)
            val preto = FloatArray(4)
            for (i in 0 until 4) {
                preto[i] = (pretoRaw?.getOrNull(i % (pretoRaw.size.coerceAtLeast(1))) ?: 0.0).toFloat()
            }

            val cfaRaw = tags[33422]?.bytes(r)
            val cfa = IntArray(4) { cfaRaw?.getOrNull(it)?.toInt() ?: 1 }

            val neutro = ifd[50728]?.doubles(r) ?: tags[50728]?.doubles(r)
            ?: doubleArrayOf(1.0, 1.0, 1.0)

            return DngReader(
                width = width,
                height = height,
                whiteLevel = white,
                blackLevel = preto,
                cfa = cfa,
                asShotNeutral = neutro,
                forwardMatrix1 = (ifd[50964] ?: tags[50964])?.doubles(r),
                forwardMatrix2 = (ifd[50965] ?: tags[50965])?.doubles(r),
                illuminant1 = (ifd[50778] ?: tags[50778])?.longs(r)?.get(0)?.toInt() ?: 21,
                illuminant2 = (ifd[50779] ?: tags[50779])?.longs(r)?.get(0)?.toInt() ?: 17,
                stripOffsets = tags[273]!!.longs(r),
                stripCounts = tags[279]!!.longs(r),
                rowsPerStrip = tags[278]?.longs(r)?.get(0)?.toInt() ?: height,
                littleEndian = little,
                path = file,
            )
        }

        private fun photometric(ifd: Map<Int, Entry>): Int =
            ifd[262]?.inlineFirst ?: -1
    }

    /** Uma entrada da tabela de etiquetas. */
    class Entry(val type: Int, val count: Int, val offset: Int, val inlineFirst: Int) {

        fun longs(c: Cursor): LongArray {
            val size = TYPE_SIZE.getOrElse(type) { 1 } * count
            val base = if (size <= 4) offset else c.u32(offset).toInt()
            val at = if (size <= 4) offset else base
            return LongArray(count) { i ->
                when (type) {
                    1, 6 -> (c.bytes[at + i].toInt() and 0xFF).toLong()
                    3, 8 -> c.u16(at + i * 2).toLong()
                    else -> c.u32(at + i * 4)
                }
            }
        }

        fun bytes(c: Cursor): ByteArray {
            val size = count
            val at = if (size <= 4) offset else c.u32(offset).toInt()
            return ByteArray(count) { c.bytes[at + it] }
        }

        fun doubles(c: Cursor): DoubleArray {
            val size = TYPE_SIZE.getOrElse(type) { 1 } * count
            val at = if (size <= 4) offset else c.u32(offset).toInt()
            return DoubleArray(count) { i ->
                when (type) {
                    5 -> {
                        val n = c.u32(at + i * 8)
                        val d = c.u32(at + i * 8 + 4)
                        if (d != 0L) n.toDouble() / d else 0.0
                    }
                    10 -> {
                        val n = c.i32(at + i * 8)
                        val d = c.i32(at + i * 8 + 4)
                        if (d != 0) n.toDouble() / d else 0.0
                    }
                    11 -> java.lang.Float.intBitsToFloat(c.i32(at + i * 4)).toDouble()
                    12 -> java.lang.Double.longBitsToDouble(
                        (c.u32(at + i * 8) shl 32) or c.u32(at + i * 8 + 4))
                    3, 8 -> c.u16(at + i * 2).toDouble()
                    else -> c.u32(at + i * 4).toDouble()
                }
            }
        }
    }

    /** Leitura de inteiros respeitando a ordem de bytes do ficheiro. */
    class Cursor(val bytes: ByteArray, val little: Boolean) {

        fun u16(at: Int): Int {
            val a = bytes[at].toInt() and 0xFF
            val b = bytes[at + 1].toInt() and 0xFF
            return if (little) (b shl 8) or a else (a shl 8) or b
        }

        fun i32(at: Int): Int = u32(at).toInt()

        fun u32(at: Int): Long {
            val a = (bytes[at].toInt() and 0xFF).toLong()
            val b = (bytes[at + 1].toInt() and 0xFF).toLong()
            val c = (bytes[at + 2].toInt() and 0xFF).toLong()
            val d = (bytes[at + 3].toInt() and 0xFF).toLong()
            return if (little) (d shl 24) or (c shl 16) or (b shl 8) or a
            else (a shl 24) or (b shl 16) or (c shl 8) or d
        }

        fun ifd(at: Int): Map<Int, Entry> {
            val n = u16(at)
            val out = LinkedHashMap<Int, Entry>(n)
            for (i in 0 until n) {
                val p = at + 2 + i * 12
                val tag = u16(p)
                val type = u16(p + 2)
                val count = u32(p + 4).toInt()
                val size = TYPE_SIZE.getOrElse(type) { 1 } * count
                val primeiro = when {
                    size > 4 -> -1
                    type == 3 || type == 8 -> u16(p + 8)
                    type == 1 || type == 6 || type == 2 || type == 7 -> bytes[p + 8].toInt() and 0xFF
                    else -> u32(p + 8).toInt()
                }
                out[tag] = Entry(type, count, p + 8, primeiro)
            }
            return out
        }
    }
}
