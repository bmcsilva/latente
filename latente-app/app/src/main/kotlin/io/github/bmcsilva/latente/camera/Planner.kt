package io.github.bmcsilva.latente.camera

import io.github.bmcsilva.latente.model.Exposure
import io.github.bmcsilva.latente.model.LensProfile

/**
 * O que se pediu contra o que se vai pedir, antes de o HAL sequer ver o pedido.
 *
 * Há dois cortes distintos e ambos interessam: o nosso, feito com os limites declarados, e o do
 * HAL, que só se conhece lendo o `CaptureResult`. Guardam-se os dois.
 */
class RequestPlan(
    val wanted: Exposure,
    val effective: Exposure,
    val time: Applied<Long>,
    val iso: Applied<Int>,
    val focus: Applied<Float>,
    val aperture: Applied<Float>?,
) {
    val anyClamped: Boolean
        get() = time.clamped || iso.clamped || focus.clamped || (aperture?.clamped == true)
}

object Planner {

    fun plan(profile: LensProfile, wanted: Exposure): RequestPlan {
        val time = HalClamp.exposure(wanted.exposureNs, profile.exposureMinNs, profile.exposureMaxNs)
        val iso = HalClamp.iso(wanted.iso, profile.isoMin, profile.isoMax)
        val focus = HalClamp.focus(wanted.focusDiopters, profile.minFocusDiopters)
        val aperture = HalClamp.aperture(wanted.aperture, profile.apertures)
        val frameDuration = HalClamp.frameDuration(time.applied, profile.minFrameDurationNs)

        val effective = wanted.copy(
            exposureNs = time.applied,
            iso = iso.applied,
            frameDurationNs = frameDuration,
            focusDiopters = focus.applied,
            aperture = aperture?.applied,
        )
        return RequestPlan(wanted, effective, time, iso, focus, aperture)
    }

    /**
     * Pedido deliberadamente fora dos limites, para se ver o HAL a cortar.
     *
     * Usado na experiência do tecto de exposição: pede-se o valor cru, sem o nosso corte, para
     * descobrir se o HAL aceita mais do que declara.
     */
    fun raw(wanted: Exposure): Exposure = wanted
}
