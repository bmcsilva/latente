package io.github.bmcsilva.latente.render

/**
 * Perfil radial de vinhetagem, por canal.
 *
 * `values[canal][k]` é a fracção da luz do centro que chega ao anel `k`, do centro (k=0) ao canto
 * (k=n−1). O ganho de correcção é o seu recíproco.
 *
 * **Tem de ser por canal.** Medido nas duas objectivas traseiras deste telefone, o vermelho cai
 * cerca de 15% mais do que o verde no canto — R/G de 0,855 numa e 0,859 na outra. Corrigir com um
 * ganho único deixaria as bordas com desvio de cor bem visível. Ser quase igual em duas ópticas
 * diferentes aponta para o filtro de cor do sensor, e não para as lentes: é por isso que a
 * especificação DNG define o `GainMap` com quatro canais e não com um.
 */
class ShadingProfile(
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
) {
    init {
        require(red.size == green.size && green.size == blue.size) {
            "os três canais têm de ter o mesmo número de anéis"
        }
        require(red.size >= 2) { "são precisos pelo menos dois anéis" }
    }

    val rings: Int get() = red.size

    fun channel(colour: Int): FloatArray = when (colour) {
        Demosaic.R -> red
        Demosaic.B -> blue
        else -> green
    }

    /**
     * Ganho a aplicar, para um raio normalizado de 0 (centro) a 1 (canto).
     *
     * Interpola linearmente entre anéis. Os anéis foram medidos nos seus **centros**, e é isso que
     * o mapeamento respeita: o anel `k` está em `(k + 0,5) / n`.
     */
    fun gain(colour: Int, radius: Float): Float {
        val v = channel(colour)
        val n = v.size
        val pos = radius * n - 0.5f
        val k = Math.floor(pos.toDouble()).toInt()
        val t = pos - k

        val a = v[if (k < 0) 0 else if (k > n - 1) n - 1 else k]
        val b = v[if (k + 1 < 0) 0 else if (k + 1 > n - 1) n - 1 else k + 1]
        val queda = a + (b - a) * (if (k < 0) 0f else if (k >= n - 1) 0f else t)
        return if (queda > 1e-4f) 1f / queda else 1f / 1e-4f
    }

    companion object {

        /**
         * Perfis medidos no dispositivo de referência (samsung SM-S942B).
         *
         * Difusor encostado à lente sobre ecrã branco, validados por simetria radial abaixo de 6%.
         * A objectiva principal em sete chapas de três sessões independentes; a
         * ultra-grande-angular numa chapa.
         *
         * Isto é calibração **por modelo de telefone**. Estar aqui em código é provisório: o lugar
         * próprio é um ficheiro de calibração, gerado por `tools/shading.py` e escolhido pelo
         * modelo. Enquanto houver um telefone só, é honesto e é simples.
         */
        val SM_S942B_ID0 = ShadingProfile(
            red = floatArrayOf(
                1.00000f, 0.96996f, 0.92330f, 0.86344f, 0.79527f, 0.72753f, 0.65893f, 0.59019f,
                0.52132f, 0.44429f, 0.37098f, 0.31786f, 0.27660f, 0.23925f, 0.20355f, 0.16567f),
            green = floatArrayOf(
                1.00000f, 0.97350f, 0.93082f, 0.87459f, 0.80518f, 0.72973f, 0.65226f, 0.58267f,
                0.52097f, 0.46072f, 0.40428f, 0.35855f, 0.31716f, 0.27641f, 0.23624f, 0.19382f),
            blue = floatArrayOf(
                1.00000f, 0.97537f, 0.93177f, 0.87528f, 0.81047f, 0.73840f, 0.66627f, 0.60159f,
                0.53827f, 0.46913f, 0.39427f, 0.33650f, 0.29301f, 0.25352f, 0.21874f, 0.18236f),
        )

        val SM_S942B_ID2 = ShadingProfile(
            red = floatArrayOf(
                1.00000f, 0.96338f, 0.90196f, 0.80553f, 0.68846f, 0.57102f, 0.47782f, 0.40074f,
                0.33812f, 0.28045f, 0.23439f, 0.19938f, 0.17292f, 0.15025f, 0.12766f, 0.11735f),
            green = floatArrayOf(
                1.00000f, 0.96596f, 0.90196f, 0.81965f, 0.72913f, 0.63443f, 0.54419f, 0.46199f,
                0.38601f, 0.32307f, 0.26812f, 0.22719f, 0.19558f, 0.17192f, 0.15084f, 0.13661f),
            blue = floatArrayOf(
                1.00000f, 0.96334f, 0.90199f, 0.81914f, 0.72554f, 0.61438f, 0.50728f, 0.42393f,
                0.35712f, 0.29956f, 0.25179f, 0.21318f, 0.18369f, 0.16258f, 0.14260f, 0.13034f),
        )

        /** Perfil conhecido para um modelo e uma câmara, ou nulo se não houver calibração. */
        fun forDevice(model: String, cameraId: String): ShadingProfile? {
            if (!model.equals("SM-S942B", ignoreCase = true)) return null
            return when (cameraId) {
                "0" -> SM_S942B_ID0
                "2" -> SM_S942B_ID2
                else -> null
            }
        }
    }
}

/**
 * Correcção de vinhetagem sobre o mosaico.
 *
 * Aplica-se **antes** do balanço de brancos e do *demosaicing*, logo a seguir à normalização dos
 * níveis: é uma propriedade da óptica e do sensor, e tem de sair antes de qualquer interpolação.
 *
 * Este passo não estava na §6.1 da especificação. Foi acrescentado depois de se medir que o RAW
 * **não vem corrigido**, apesar de o HAL declarar `SENSOR_INFO_LENS_SHADING_APPLIED = true` e de
 * devolver um mapa de correcção que é exactamente 1,0000 em todas as posições. Sem este passo, cada
 * fotografia sai com os cantos a 20% do centro — e isso não é fidelidade, é um defeito.
 */
object LensShading {

    /**
     * Correcção total, e é a omissão.
     *
     * É a fisicamente correcta, e é o que um revelador a sério faz. Mas o custo em ruído é real e
     * visível — medido nesta objectiva, o canto corrigido fica com 2,4 vezes o grão do centro —,
     * e por isso a força é um controlo do utilizador e não uma decisão escondida.
     *
     * Detalhe que vale a pena saber: a correcção **não piora a relação sinal-ruído**. Medido numa
     * fotografia de interior, o ruído relativo no canto até desce de 15,1% para 11,7%, porque o
     * sinal sobe com o ganho enquanto o ruído de leitura, que é fixo, se dilui. O grão que aparece
     * já lá estava — estava escondido pela escuridão do canto. A correcção só o traz à luz.
     */
    const val FULL = 1.0f

    /**
     * Corrige o mosaico no lugar e devolve-o.
     *
     * O raio é normalizado pela **meia-diagonal**, que é como o perfil foi medido: 0 no centro,
     * 1 nos cantos.
     *
     * @param strength de 0 (sem correcção) a 1 (total). A força aplica-se em **stops**, não
     *   linearmente: metade da força é metade dos stops de correcção, que é o que a palavra
     *   significa para quem fotografa. Um ganho de 6× a meia força dá 2,45× e não 3,5×.
     */
    fun correct(m: Mosaic, profile: ShadingProfile, strength: Float = FULL): Mosaic {
        if (strength <= 0f) return m
        val cx = m.width / 2.0f
        val cy = m.height / 2.0f
        val rmax = Math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()
        if (rmax <= 0f) return m

        for (y in 0 until m.height) {
            val dy = y + 0.5f - cy
            val base = y * m.width
            for (x in 0 until m.width) {
                val dx = x + 0.5f - cx
                val r = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / rmax
                val g = profile.gain(m.colourAt(x, y), r)
                m.data[base + x] *= if (strength >= 1f) g else applyStrength(g, strength)
            }
        }
        return m
    }

    /**
     * Ganho atenuado pela força, em stops.
     *
     * `g^s`: a zero dá 1 (nenhuma correcção), a um dá `g` (total), e a meio dá metade dos stops.
     */
    fun applyStrength(gain: Float, strength: Float): Float {
        val s = if (strength < 0f) 0f else if (strength > 1f) 1f else strength
        if (gain <= 1f) return 1f
        return Math.pow(gain.toDouble(), s.toDouble()).toFloat()
    }

    /** Ganho máximo do perfil, para avisar o utilizador do ruído que a correcção amplifica. */
    fun maxGain(profile: ShadingProfile, strength: Float = FULL): Float {
        var pior = 1f
        for (c in intArrayOf(Demosaic.R, Demosaic.G, Demosaic.B)) {
            val g = applyStrength(profile.gain(c, 1f), strength)
            if (g > pior) pior = g
        }
        return pior
    }

    /**
     * Quantos stops de amplificação o canto vai levar. É o número a mostrar ao utilizador,
     * porque é o que prevê o ruído que vai aparecer.
     */
    fun stopsAtCorner(profile: ShadingProfile, strength: Float = FULL): Float =
        (Math.log(maxGain(profile, strength).toDouble()) / Math.log(2.0)).toFloat()
}
