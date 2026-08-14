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
        /**
         * O negativo, solto ou dentro do arquivo. Um dos dois existe sempre — sem negativo não há
         * fotografia —, e quem lê passa pelo `Library.negativo`, que trata dos dois casos.
         */
        val dngId: Long?,
        val zipId: Long?,
        val jsonId: Long?,
        val tifId: Long?,
        val jpgId: Long?,
        val dateSeconds: Long,
        val sizeBytes: Long,
    ) {
        /** Dentro do arquivo vai sempre a receita ao lado do negativo; solta, pode faltar. */
        val hasRecipe: Boolean get() = jsonId != null || zipId != null

        /** Guardada em arquivo comprimido, e não como negativo solto. */
        val archived: Boolean get() = zipId != null

        /** O negativo já não existe: o que resta são revelações órfãs. */
        val orphan: Boolean get() = dngId == null && zipId == null
        /** Revelada é revelada, seja para arquivo em TIFF ou para partilhar em JPEG. */
        val developed: Boolean get() = tifId != null || jpgId != null
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

                val e = porBase.getOrPut(base) { arrayOfNulls(7) }
                when (ext) {
                    "dng" -> {
                        e[0] = c.getLong(iId)
                        e[3] = c.getLong(iData)
                        e[4] = c.getLong(iTam)
                    }
                    "json" -> e[1] = c.getLong(iId)
                    "tif", "tiff" -> e[2] = c.getLong(iId)
                    "jpg", "jpeg" -> e[5] = c.getLong(iId)
                    // O arquivo traz o negativo e a receita lá dentro, e é ele que diz o tamanho.
                    "zip" -> {
                        e[6] = c.getLong(iId)
                        e[3] = c.getLong(iData)
                        e[4] = c.getLong(iTam)
                    }
                }
                if (porBase.size >= limit * 2) break
            }
        }

        val out = ArrayList<Shot>()
        for ((base, e) in porBase) {
            val dng = e[0] as? Long
            val zip = e[6] as? Long
            // **Sem negativo continua a ser uma entrada.** Quem apagou o negativo e ficou com as
            // revelações tem de as ver na lista para as poder apagar também — de outro modo ficavam
            // no disco sem aparecerem em lado nenhum, que é a pior maneira de ocupar espaço.
            if (dng == null && zip == null &&
                e[2] == null && e[5] == null) continue
            out.add(Shot(base, dng, zip, e[1] as? Long, e[2] as? Long, e[5] as? Long,
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
    /**
     * O negativo pronto a ler, venha ele solto ou de dentro do arquivo.
     *
     * Quem lê um negativo não tem de saber em que formato ele foi guardado — e há os dois no disco: as
     * fotografias antigas são `.dng` soltos, as novas são `.zip`. Devolve um ficheiro na cache, que é
     * o que o leitor precisa: acesso aleatório, porque salta entre tiras.
     *
     * @param prefixo para quem quiser cache própria. A miniatura e o botão de revelar correm em fios
     *   diferentes, e sem nomes distintos apagam o ficheiro um ao outro a meio da leitura.
     */
    fun negativo(ctx: Context, shot: Shot, prefixo: String = ""): File? {
        shot.dngId?.let { return fetch(ctx, it, prefixo + shot.baseName + ".dng") }
        val zip = shot.zipId?.let { fetch(ctx, it, prefixo + shot.baseName + ".zip") } ?: return null
        val destino = File(ctx.cacheDir, prefixo + shot.baseName + ".dng")
        val dng = Archive.extrairNegativo(zip, destino)
        zip.delete()
        return dng
    }

    /** A receita, do `.json` solto ou de dentro do arquivo. Nula quando não há. */
    fun receita(ctx: Context, shot: Shot, prefixo: String = ""): String? {
        shot.jsonId?.let { id ->
            val f = fetch(ctx, id, prefixo + shot.baseName + ".json") ?: return null
            val texto = f.readText()
            // Lida, sai da cache: são quatro quilobytes, mas um por fotografia e por visita.
            f.delete()
            return texto
        }
        val zip = shot.zipId?.let { fetch(ctx, it, prefixo + shot.baseName + ".zip") } ?: return null
        val texto = Archive.lerReceita(zip)
        zip.delete()
        return texto
    }

    /**
     * Apaga ficheiros de uma fotografia. Devolve quantos saíram.
     *
     * **Apaga mesmo**, não manda para a reciclagem. Quem apaga um negativo de 7 MB quer o espaço agora,
     * e um ficheiro na reciclagem ocupa disco durante trinta dias — seria dar por feito o que não está.
     *
     * @param soCopias deixa o negativo e a receita, leva o TIFF e o JPEG. É a limpeza que faz sentido
     *   fazer a olhos fechados: o que se apaga volta a fazer-se a partir do negativo.
     */
    fun apagar(ctx: Context, shot: Shot, soCopias: Boolean): Int {
        val ids = ArrayList<Long>()
        shot.tifId?.let { ids.add(it) }
        shot.jpgId?.let { ids.add(it) }
        if (!soCopias) {
            shot.zipId?.let { ids.add(it) }
            shot.dngId?.let { ids.add(it) }
            shot.jsonId?.let { ids.add(it) }
        }
        var apagados = 0
        for (id in ids) {
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            try {
                if (ctx.contentResolver.delete(uri, null, null) > 0) apagados++
            } catch (t: Throwable) {
                // Um ficheiro que a plataforma recusa apagar não pára os outros. Acontece quando o
                // dono se perdeu numa reinstalação, e aí quem o apaga é o gestor de ficheiros.
            }
        }
        return apagados
    }

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
