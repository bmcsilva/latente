package io.github.bmcsilva.latente.export

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * Os negativos que estão no disco, agrupados por fotografia.
 *
 * Uma fotografia são **três ficheiros** com o mesmo nome de base e papéis distintos: o `.dng` é o
 * negativo e é imutável, o `.json` é a receita que diz como foi revelado, e o `.tif` é a cópia
 * revelada, que pode não existir ainda. Apresentá-los como três linhas soltas numa lista seria
 * esconder que são a mesma coisa.
 *
 * Lê-se pelo `MediaStore` porque foi por lá que foram escritos, e não por caminho directo: o caminho
 * directo exigiria permissões de armazenamento que esta aplicação não pede.
 */
object Library {

    class Shot(
        val baseName: String,
        val dngId: Long,
        val jsonId: Long?,
        val tifId: Long?,
        val dateSeconds: Long,
        val sizeBytes: Long,
    ) {
        val hasRecipe: Boolean get() = jsonId != null
        val developed: Boolean get() = tifId != null
    }

    /**
     * As fotografias, da mais recente para a mais antiga.
     *
     * @param limit quantas trazer. Uma biblioteca com centenas de negativos não cabe num ecrã nem
     *   precisa de caber: o que interessa é o que se acabou de fazer.
     */
    fun shots(ctx: Context, limit: Int = 60): List<Shot> {
        val porBase = LinkedHashMap<String, Array<Any?>>()
        val projeccao = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.SIZE)

        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projeccao,
            MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
            arrayOf("%" + MediaStoreOut.FOLDER + "%"),
            MediaStore.Downloads.DATE_ADDED + " DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val iNome = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val iData = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            val iTam = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (c.moveToNext()) {
                val nome = c.getString(iNome) ?: continue
                val ponto = nome.lastIndexOf('.')
                if (ponto <= 0) continue
                val base = nome.substring(0, ponto)
                val ext = nome.substring(ponto + 1).lowercase()
                // Só interessam os negativos e o que os acompanha. Os relatórios de diagnóstico
                // partilham a pasta e não são fotografias.
                if (!base.startsWith("LTNT_")) continue

                val e = porBase.getOrPut(base) { arrayOfNulls(5) }
                when (ext) {
                    "dng" -> {
                        e[0] = c.getLong(iId)
                        e[3] = c.getLong(iData)
                        e[4] = c.getLong(iTam)
                    }
                    "json" -> e[1] = c.getLong(iId)
                    "tif", "tiff" -> e[2] = c.getLong(iId)
                }
                if (porBase.size >= limit * 2) break
            }
        }

        val out = ArrayList<Shot>()
        for ((base, e) in porBase) {
            val dng = e[0] as? Long ?: continue      // sem negativo não há fotografia
            out.add(Shot(base, dng, e[1] as? Long, e[2] as? Long,
                e[3] as? Long ?: 0L, e[4] as? Long ?: 0L))
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * Traz um ficheiro do `MediaStore` para a cache.
     *
     * Copia-se porque o leitor de DNG precisa de **acesso aleatório** — salta entre tiras — e um fluxo
     * de leitura não serve para isso.
     */
    fun fetch(ctx: Context, id: Long, nome: String): File? {
        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        val destino = File(ctx.cacheDir, nome)
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { entrada ->
                destino.outputStream().use { saida -> entrada.copyTo(saida) }
            } ?: return null
            destino
        } catch (t: Throwable) {
            null
        }
    }
}
