package io.github.bmcsilva.latente.model

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * O corpo e as suas objectivas fixas.
 *
 * Enumera as lógicas e as físicas escondidas dentro delas, e guarda quais é que abrem — porque na
 * F0 descobriu-se que uma câmara física pode ser legível e não ser abrível.
 */
class Body(ctx: Context) {

    val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    val lenses = ArrayList<LensProfile>()
    val physicalOnly = HashSet<String>()
    var error: String? = null

    init {
        try {
            val logicals = manager.cameraIdList
            val seen = LinkedHashSet<String>()
            for (id in logicals) seen.add(id)

            for (id in logicals) {
                characteristics(id)?.let { ch ->
                    LensProfile.from(id, ch)?.let { lenses.add(it) }
                    for (pid in ch.physicalCameraIds) {
                        if (!seen.contains(pid)) {
                            seen.add(pid)
                            physicalOnly.add(pid)
                            characteristics(pid)?.let { pch ->
                                // A física guarda a lógica por onde se abre. É o que a torna
                                // alcançável: sozinha não abre, pela lógica abre.
                                LensProfile.from(pid, pch, logicalId = id)?.let { lenses.add(it) }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            error = e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    fun characteristics(id: String): CameraCharacteristics? = try {
        manager.getCameraCharacteristics(id)
    } catch (e: Throwable) {
        null
    }

    /**
     * Objectivas que servem e que se consegue alcançar.
     *
     * Uma física que só existe dentro de uma lógica não abre com um `openCamera` directo — foi o que
     * aconteceu com as 5 e 6 do dispositivo de referência. **Alcança-se pela lógica**, prendendo a
     * saída à física, e está medido que este corpo o aceita: as duas entregaram RAW e o frame veio
     * depois do pedido, não de cache. Por isso deixaram de ser excluídas por princípio.
     *
     * Fora ficam as que **duplicam** uma objectiva já na lista: a física 5 declara a mesma distância
     * focal e o mesmo tamanho RAW da id 0, porque é o mesmo sensor visto de outro lado. Oferecer as
     * duas seria dar a escolher entre uma coisa e ela própria.
     */
    fun usable(): List<LensProfile> {
        val servem = lenses.filter { it.serves }
        val directas = servem.filter { it.logicalId == null }
        val resultado = ArrayList<LensProfile>(directas)
        for (l in servem) {
            if (l.logicalId == null) continue
            val duplica = directas.any {
                it.focalMm == l.focalMm && it.rawSize == l.rawSize && it.facing == l.facing
            }
            if (!duplica) resultado.add(l)
        }
        return resultado
    }

    fun rejected(): List<LensProfile> = lenses.filter { !it.serves }

    fun deviceLabel(): String =
        Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE +
                " · API " + Build.VERSION.SDK_INT
}
