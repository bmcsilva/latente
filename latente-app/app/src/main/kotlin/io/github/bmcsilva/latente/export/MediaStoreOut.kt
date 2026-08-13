package io.github.bmcsilva.latente.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

/**
 * Escrita em `Downloads/Latente`, sem permissões de armazenamento.
 *
 * Na F1 tudo vai para Downloads porque é onde é trivial ir buscar por adb ou por MTP para
 * verificar com `exiftool` e `darktable`. Quando houver biblioteca (F5), as imagens passam para
 * `DCIM/Latente`.
 */
class MediaStoreOut(private val ctx: Context) {

    companion object {
        const val FOLDER = "Latente"
    }

    fun write(name: String, mime: String, body: (OutputStream) -> Unit): Uri {
        val values = ContentValues()
        values.put(MediaStore.Downloads.DISPLAY_NAME, name)
        values.put(MediaStore.Downloads.MIME_TYPE, mime)
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
        values.put(MediaStore.Downloads.IS_PENDING, 1)

        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore recusou criar $name")

        try {
            resolver.openOutputStream(uri).use { out ->
                if (out == null) throw IllegalStateException("sem stream de escrita para $name")
                body(out)
                out.flush()
            }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun writeText(name: String, mime: String, text: String): Uri =
        write(name, mime) { it.write(text.toByteArray(Charsets.UTF_8)) }
}
