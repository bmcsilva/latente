package io.github.bmcsilva.latente.model

import android.content.Context

/**
 * O que a câmara se lembra entre sessões.
 *
 * Uma câmara a sério não volta às definições de fábrica cada vez que se liga: fica onde a deixámos. Sem
 * isto, cada abertura do visor punha o Kelvin em 5500, o modo em P, a tinta em zero e o foco em duas
 * metros — e quem estava a trabalhar com uma luz difícil tinha de a reencontrar de cada vez.
 *
 * Guarda-se o que o utilizador **escolheu**, e nunca o que o fotómetro decidiu. O tempo e o ISO em modo
 * automático são resultado de medição, não escolha: reabrir com os valores da última cena seria começar
 * com uma exposição de outra luz.
 */
class Settings(ctx: Context) {

    private val p = ctx.getSharedPreferences("latente", Context.MODE_PRIVATE)

    var mode: ExposureProgram.Mode
        get() = try {
            ExposureProgram.Mode.valueOf(p.getString(MODO, "P") ?: "P")
        } catch (t: IllegalArgumentException) {
            ExposureProgram.Mode.P
        }
        set(v) = p.edit().putString(MODO, v.name).apply()

    var metering: ExposureProgram.Metering
        get() = try {
            ExposureProgram.Metering.valueOf(p.getString(MEDICAO, "HIGHLIGHTS") ?: "HIGHLIGHTS")
        } catch (t: IllegalArgumentException) {
            ExposureProgram.Metering.HIGHLIGHTS
        }
        set(v) = p.edit().putString(MEDICAO, v.name).apply()

    var kelvin: Int
        get() = p.getInt(KELVIN, 5500).coerceIn(1500, 15000)
        set(v) = p.edit().putInt(KELVIN, v).apply()

    var tint: Float
        get() = p.getFloat(TINTA, 0f).coerceIn(-1f, 1f)
        set(v) = p.edit().putFloat(TINTA, v).apply()

    /** Compensação de exposição em stops. Desloca o alvo do fotómetro, não o resultado. */
    var compensation: Float
        get() = p.getFloat(COMPENSACAO, 0f).coerceIn(-5f, 5f)
        set(v) = p.edit().putFloat(COMPENSACAO, v).apply()

    var focusDiopters: Float
        get() = p.getFloat(FOCO, 0.5f)
        set(v) = p.edit().putFloat(FOCO, v).apply()

    /** Em manual, o tempo e o ISO **são** escolha, e por isso guardam-se. */
    var manualExposureNs: Long
        get() = p.getLong(TEMPO_MANUAL, 8_000_000L)
        set(v) = p.edit().putLong(TEMPO_MANUAL, v).apply()

    var manualIso: Int
        get() = p.getInt(ISO_MANUAL, 50)
        set(v) = p.edit().putInt(ISO_MANUAL, v).apply()

    var peaking: Boolean
        get() = p.getBoolean(PICOS, false)
        set(v) = p.edit().putBoolean(PICOS, v).apply()

    /**
     * Que ajudas estão ligadas, em bits: 1 picos, 2 zebras, 4 histograma, 8 nível.
     *
     * Máscara e não índice, porque elas **combinam**: nada impede ver as zebras do corte com o
     * histograma ao lado, e obrigar a escolher uma era limitação da interface e não do instrumento.
     * Chave nova de propósito — a antiga guardava um índice de 0 a 3 e lê-lo como bits daria ajudas
     * ligadas ao acaso.
     */
    var aids: Int
        get() = p.getInt(AJUDAS, 0).coerceIn(0, 15)
        set(v) = p.edit().putInt(AJUDAS, v).apply()

    /** O que o comando único mexe: 0 foco, 1 Kelvin, 2 tinta, 3 compensação. */
    var dial: Int
        get() = p.getInt(ANEL, 0).coerceIn(0, 3)
        set(v) = p.edit().putInt(ANEL, v).apply()

    private companion object {
        const val MODO = "modo"
        const val MEDICAO = "medicao"
        const val KELVIN = "kelvin"
        const val TINTA = "tinta"
        const val COMPENSACAO = "compensacao"
        const val FOCO = "foco"
        const val TEMPO_MANUAL = "tempoManual"
        const val ISO_MANUAL = "isoManual"
        const val PICOS = "picos"
        const val AJUDAS = "ajudasBits"
        const val ANEL = "anel"
    }
}
