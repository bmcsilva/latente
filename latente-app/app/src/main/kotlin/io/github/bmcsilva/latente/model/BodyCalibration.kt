package io.github.bmcsilva.latente.model

/**
 * O que se mediu num corpo e o HAL declara mal.
 *
 * Segue o mesmo padrão do `ShadingProfile.forDevice`: calibração **por modelo**, e nulo — ou seja, o
 * valor declarado — quando não há medição. Não se inventa: um dispositivo não calibrado fica com o que
 * o fabricante diz, mesmo sabendo que muitos fabricantes dizem mal.
 *
 * Estar em código é provisório, tal como nos perfis de vinhetagem. O lugar próprio é um ficheiro de
 * calibração escolhido pelo modelo; enquanto houver um telefone só, é honesto e é simples.
 */
object BodyCalibration {

    /**
     * O tecto de exposição que o HAL **honra**, que não é o que declara.
     *
     * No dispositivo de referência o `SENSOR_INFO_EXPOSURE_TIME_RANGE` declara 100 ms e o HAL aceita
     * **1750 ms** — 17,5× mais. Medido na experiência 1 da F1, com o valor confirmado no
     * `CaptureResult`.
     *
     * Isto não é uma optimização cosmética: sem ela a linha de programa esgota o tempo 17,5× mais cedo
     * e vai ao **ganho digital** quando ainda havia quatro stops de tempo disponíveis. Viu-se numa
     * corrida real — 100 ms a ISO 787, quando 1750 ms teria mantido o ISO analógico.
     *
     * **O limite é um limite.** Acima do tecto real o HAL não corta: o frame falha e o dispositivo
     * **fecha-se**. É por isso que este valor tem de vir de medição e nunca de suposição.
     *
     * **E vale para a objectiva onde foi medido, não para o corpo todo.** Mediu-se na id 0. Estendê-lo
     * às outras era a mesma suposição que a medição existe para evitar — e ficou à vista quando a 66 mm
     * entrou: o sidecar dela anunciava «declarado 100 ms, medido 1750 ms» sobre uma objectiva onde nem
     * sequer se pode pedir o tempo.
     */
    fun exposureCeilingNs(model: String?, cameraId: String, declaredNs: Long): Long =
        if (model == "SM-S942B" && cameraId == "0") 1_750_000_000L else declaredNs

    /** Houve medição para este corpo, ou está-se a usar o que o fabricante declara? */
    fun ceilingIsMeasured(model: String?, cameraId: String, declaredNs: Long): Boolean =
        exposureCeilingNs(model, cameraId, declaredNs) != declaredNs
}
