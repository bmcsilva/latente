package io.github.bmcsilva.latente.model

import android.hardware.camera2.CameraMetadata

/**
 * Tudo o que o utilizador decide num disparo. Nada aqui é automático.
 *
 * `kelvin` não vai para o sensor: serve para os ganhos de balanço de brancos e para o sidecar.
 * O RAW é gravado sem balanço aplicado — o balanço é interpretação, e a interpretação é do
 * revelador.
 */
data class Exposure(
    val exposureNs: Long,
    val iso: Int,
    val frameDurationNs: Long,
    val focusDiopters: Float,
    val aperture: Float?,
    val oisMode: Int = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
    val kelvin: Int = 5500,
    /**
     * Tinta, de −1 (magenta) a +1 (verde). Zero é o locus de Planck.
     *
     * O Kelvin sozinho só move o ponto neutro **ao longo** do locus do corpo negro, e por isso
     * nunca neutraliza uma luz que esteja ao lado dele. Medido em doze chapas sob LED de interior:
     * R e B ficavam a 0,90 do previsto, ou seja a luz tinha ~10% de verde a mais. Sem este eixo,
     * uma lâmpada dessas não tem correcção possível.
     */
    val tint: Float = 0f,
) {

    fun describe(): String {
        val t = if (exposureNs >= 1_000_000_000L) {
            String.format(java.util.Locale.US, "%.1f s", exposureNs / 1e9)
        } else {
            "1/" + Math.round(1e9 / exposureNs) + " s"
        }
        val f = if (aperture != null) String.format(java.util.Locale.US, " f/%.1f", aperture) else ""
        return t + f + " ISO " + iso
    }
}
