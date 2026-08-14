package io.github.bmcsilva.latente.diag

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.github.bmcsilva.latente.export.MediaStoreOut
import java.io.File

/**
 * O registo de **uso prolongado**: o que o telefone faz ao fim de meia hora de visor.
 *
 * Nunca foi medido, e é a diferença entre uma prova e uma câmara que se leva à rua. Numa medição de
 * trinta segundos os relógios do dispositivo derivaram 15%; meia hora é outra coisa — o corpo aquece,
 * a plataforma baixa as frequências, e o visor que dava 22 fps ao princípio pode dar metade sem que
 * nada no código tenha mudado.
 *
 * O que se regista, de dez em dez segundos:
 *
 * - **minuto** desde o arranque, para se poder cruzar com o que se estava a fazer;
 * - **bateria**, em percentagem, e o que se gastou desde o princípio;
 * - **temperatura da bateria**, que é o único termómetro que a plataforma dá a uma aplicação sem
 *   privilégios. Não é a do sensor de imagem nem a do SoC, e por isso escreve-se o que ela é;
 * - **estado térmico** do `PowerManager`, que é a plataforma a dizer se já está a estrangular;
 * - **fps** do visor, tal como o fio de render o relata.
 *
 * Escreve-se a cada amostra para a área privada da aplicação, e só se publica em `Downloads/Latente`
 * no fim. É de propósito: se o telefone se desligar por calor — que é um dos resultados possíveis
 * desta medição — o que já foi medido fica no disco, e publica-se na sessão seguinte.
 */
class UsageLog(private val ctx: Context) {

    private val ui = Handler(Looper.getMainLooper())
    private val linhas = StringBuilder()
    private var arranque = 0L
    private var bateriaInicial = -1
    private var aCorrer = false

    /** O último fps relatado pelo fio de render. Zero enquanto ele não falar. */
    @Volatile
    var fps = 0.0

    private val tique = object : Runnable {
        override fun run() {
            if (!aCorrer) return
            amostrar()
            ui.postDelayed(this, INTERVALO_MS)
        }
    }

    fun comecar() {
        if (aCorrer) return
        aCorrer = true
        arranque = System.currentTimeMillis()
        bateriaInicial = -1
        linhas.setLength(0)
        linhas.append("# Latente · uso prolongado\n")
        linhas.append("# modelo: ").append(android.os.Build.MODEL)
            .append(" · Android ").append(android.os.Build.VERSION.RELEASE).append("\n")
        linhas.append("# início: ").append(carimbo()).append("\n")
        linhas.append("# a temperatura é a da BATERIA, que é o único termómetro que a plataforma dá\n")
        linhas.append("#\n")
        linhas.append("min\tbateria%\tgasto%\ttemp°C\ttérmico\tfps\n")
        amostrar()
        ui.postDelayed(tique, INTERVALO_MS)
    }

    /**
     * Fecha o registo e publica-o. Devolve o nome do ficheiro, ou nulo se não houvesse nada a dizer.
     *
     * Uma sessão de meio minuto não se publica: enche a pasta de ficheiros que não respondem à
     * pergunta, e a pergunta é o que acontece **ao fim de vinte minutos**.
     */
    fun parar(): String? {
        if (!aCorrer) return null
        aCorrer = false
        ui.removeCallbacks(tique)
        val duracao = System.currentTimeMillis() - arranque
        if (duracao < MINIMO_MS) {
            rascunho().delete()
            return null
        }
        linhas.append("# fim: ").append(carimbo())
            .append(" · ").append(duracao / 60000).append(" min\n")
        rascunho().writeText(linhas.toString())
        return publicar()
    }

    /**
     * Publica o que tiver ficado por publicar de uma sessão anterior.
     *
     * Existe para o caso que interessa: o telefone desligou-se por calor a meio da medição. O que se
     * mediu até aí é justamente o resultado, e perdê-lo seria perder a resposta.
     */
    fun publicarPendente(): String? {
        if (!rascunho().exists()) return null
        return publicar()
    }

    // -----------------------------------------------------------------------------------------

    private fun publicar(): String? {
        val f = rascunho()
        if (!f.exists()) return null
        return try {
            val nome = "latente-uso-" + carimbo().replace(":", "").replace(" ", "-") + ".txt"
            MediaStoreOut(ctx).writeText(nome, "text/plain", f.readText())
            f.delete()
            nome
        } catch (t: Throwable) {
            null
        }
    }

    private fun rascunho() = File(ctx.filesDir, "uso-em-curso.txt")

    private fun amostrar() {
        val estado = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val nivel = estado?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val escala = estado?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percentagem = if (nivel < 0) -1 else Math.round(100f * nivel / escala)
        // A plataforma dá a temperatura em décimos de grau.
        val decimos = estado?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (bateriaInicial < 0) bateriaInicial = percentagem

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val termico = nomeDoEstadoTermico(pm.currentThermalStatus)

        linhas.append((System.currentTimeMillis() - arranque) / 60000).append('\t')
            .append(percentagem).append('\t')
            .append(if (bateriaInicial < 0) 0 else bateriaInicial - percentagem).append('\t')
            .append(if (decimos < 0) "—" else String.format(
                java.util.Locale.US, "%.1f", decimos / 10.0)).append('\t')
            .append(termico).append('\t')
            .append(String.format(java.util.Locale.US, "%.1f", fps)).append('\n')

        // A cada amostra, e não só no fim: o resultado que mais interessa é aquele em que o telefone
        // não chega ao fim.
        try {
            rascunho().writeText(linhas.toString())
        } catch (t: Throwable) {
            // Um registo que não se escreve não é motivo para parar a câmara.
        }
    }

    /**
     * O nome que a plataforma dá ao aperto térmico.
     *
     * Escreve-se por extenso e não o número: um registo que é preciso decifrar com a documentação ao
     * lado deixa de ser lido.
     */
    private fun nomeDoEstadoTermico(estado: Int): String = when (estado) {
        PowerManager.THERMAL_STATUS_NONE -> "nenhum"
        PowerManager.THERMAL_STATUS_LIGHT -> "leve"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderado"
        PowerManager.THERMAL_STATUS_SEVERE -> "severo"
        PowerManager.THERMAL_STATUS_CRITICAL -> "crítico"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergência"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "desligar"
        else -> "?"
    }

    private fun carimbo(): String = java.text.SimpleDateFormat(
        "yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

    private companion object {
        const val INTERVALO_MS = 10_000L

        /**
         * Abaixo de dois minutos não se publica nada.
         *
         * Abrir e fechar o visor é coisa que se faz vinte vezes por dia, e vinte ficheiros de três
         * linhas em `Downloads/Latente` não respondem à pergunta — respondem que se abriu o visor.
         */
        const val MINIMO_MS = 120_000L
    }
}
