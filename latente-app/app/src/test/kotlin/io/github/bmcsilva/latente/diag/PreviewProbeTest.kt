package io.github.bmcsilva.latente.diag

import io.github.bmcsilva.latente.render.GlPreview
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A parte da medição do visor que se pode correr sem câmara nem GPU.
 *
 * É pouco, mas é a parte que decide o veredicto: se a mediana estiver errada, a conclusão sobre a
 * viabilidade do visor fica errada com ela.
 */
class PreviewProbeTest {

    /**
     * A mediana, e não a média, porque um frame perdido à espera da câmara arrasta a média e não diz
     * nada sobre o custo normal de um frame.
     */
    @Test
    fun theMedianIgnoresAnOutlier() {
        val v = doubleArrayOf(10.0, 11.0, 12.0, 11.0, 500.0)
        assertEquals("a mediana devia ignorar o frame perdido", 11.0,
            PreviewProbe.mediana(v, 5), 1e-9)
    }

    @Test
    fun theMedianOfAnEvenCountIsTheMiddlePairAveraged() {
        assertEquals(11.5, PreviewProbe.mediana(doubleArrayOf(10.0, 11.0, 12.0, 13.0), 4), 1e-9)
    }

    /** Só os primeiros `quantos` contam: o resto do array são zeros não preenchidos. */
    @Test
    fun onlyTheFilledPartOfTheArrayCounts() {
        val v = DoubleArray(10)
        v[0] = 30.0
        v[1] = 33.0
        v[2] = 36.0
        assertEquals(33.0, PreviewProbe.mediana(v, 3), 1e-9)
        // Contar os dez daria zero, porque sete são zeros.
        assertEquals(0.0, PreviewProbe.mediana(v, 10), 1e-9)
    }

    @Test
    fun anEmptyRunHasNoMedianInsteadOfCrashing() {
        assertEquals(0.0, PreviewProbe.mediana(DoubleArray(0), 0), 1e-9)
    }

    /**
     * O visor agrupa cada quadrado 2×2 do mosaico num pixel — um quarto dos píxeis, e nenhuma cor
     * inventada. Este é o contrato com o `PREVIEW_FRAGMENT`, que avança de dois em dois.
     */
    @Test
    fun thePreviewIsHalfTheMosaicInEachDirection() {
        val p = GlPreview(4080, 3060)
        assertEquals(2040, p.width)
        assertEquals(1530, p.height)
    }
}
