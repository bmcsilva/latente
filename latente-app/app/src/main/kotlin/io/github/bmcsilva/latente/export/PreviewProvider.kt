package io.github.bmcsilva.latente.export

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * O que deixamos outra aplicação abrir: **só as pré-visualizações**, e só para leitura.
 *
 * A galeria do telefone é melhor visualizador do que qualquer um que eu escrevesse — tem zoom,
 * partilha, e é a que o utilizador já sabe usar. Mas para lhe dar uma imagem é preciso um caminho que
 * ela possa abrir, e os nossos ficheiros vivem na área privada da aplicação.
 *
 * O `FileProvider` do androidx fazia isto, e traria uma dependência: o projecto não tem nenhuma em
 * tempo de execução e não vai começar aqui por causa de quarenta linhas. Este serve um directório só —
 * `cache/pre-visualizacoes` — e nada mais: nem negativos, nem receitas, nem miniaturas.
 */
class PreviewProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    /**
     * O ficheiro pedido, se estiver mesmo na pasta das pré-visualizações.
     *
     * A comparação é feita sobre o caminho **canónico**: um nome com `..` pelo meio resolveria para
     * fora da pasta, e é assim que um fornecedor descuidado entrega o resto da aplicação.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = ficheiro(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** A galeria pergunta o nome e o tamanho antes de abrir. Sem isto, algumas recusam. */
    override fun query(
        uri: Uri,
        projeccao: Array<out String>?,
        selecao: String?,
        args: Array<out String>?,
        ordem: String?,
    ): Cursor? {
        val f = ficheiro(uri) ?: return null
        val colunas = projeccao ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(colunas)
        val linha = arrayOfNulls<Any>(colunas.size)
        for (i in colunas.indices) {
            linha[i] = when (colunas[i]) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        cursor.addRow(linha)
        return cursor
    }

    override fun insert(uri: Uri, valores: ContentValues?): Uri? = null

    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0

    private fun ficheiro(uri: Uri): File? {
        val ctx = context ?: return null
        val nome = uri.lastPathSegment ?: return null
        val pasta = pasta(ctx)
        val f = File(pasta, nome)
        if (f.canonicalPath != File(pasta, f.name).canonicalPath) return null
        return if (f.exists()) f else null
    }

    companion object {

        const val AUTORIDADE = "io.github.bmcsilva.latente.ficheiros"

        fun pasta(ctx: Context): File {
            val p = File(ctx.cacheDir, "pre-visualizacoes")
            if (!p.exists()) p.mkdirs()
            return p
        }

        fun uri(nome: String): Uri = Uri.parse("content://$AUTORIDADE/$nome")
    }
}
