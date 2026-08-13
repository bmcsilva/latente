package io.github.bmcsilva.latente.probe

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Escreve o relatório em Downloads/Latente, via MediaStore.
 *
 * Sem permissões de armazenamento: em API 29+ a aplicação pode escrever as suas próprias
 * entradas em colecções partilhadas.
 */
class ReportWriter(private val ctx: Context) {

    class Result(val txtUri: Uri?, val jsonUri: Uri?, val baseName: String, val error: String?)

    fun write(root: Node): Result {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val model = (Build.MANUFACTURER + "-" + Build.MODEL)
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-')
            .lowercase(Locale.US)
        val base = "latente-sonda-$model-$stamp"

        return try {
            val txt = put("$base.txt", "text/plain", Txt.write(root))
            val json = put("$base.json", "application/json", Json.write(root))
            Result(txt, json, base, null)
        } catch (e: Throwable) {
            Result(null, null, base, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    private fun put(name: String, mime: String, content: String): Uri {
        val values = ContentValues()
        values.put(MediaStore.Downloads.DISPLAY_NAME, name)
        values.put(MediaStore.Downloads.MIME_TYPE, mime)
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Latente")
        values.put(MediaStore.Downloads.IS_PENDING, 1)

        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore recusou criar $name")

        resolver.openOutputStream(uri).use { out ->
            if (out == null) throw IllegalStateException("sem stream de escrita para $name")
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
