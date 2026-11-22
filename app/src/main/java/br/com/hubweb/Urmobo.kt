package br.com.hubweb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/**
 * Dados do aparelho no Urmobo MDM (nome do cadastro, ID, serial, IMEI e dados auxiliares). O app pergunta por broadcast ao
 * app `com.urmobo.mdm` e ele responde para o [UrmoboReceiver]; a última resposta fica guardada. Sem o Urmobo instalado
 * (ou sem resposta) não há dados e nada disso aparece. O token da entidade, que também vem na resposta, não é guardado.
 */
object UrmoboInfo {
    private const val PACOTE = "com.urmobo.mdm"
    private const val ACTION_PEDIDO = "com.urmobo.mdm.GET_DEVICE_DATA"
    private const val ACTION_RESPOSTA = "br.com.hubweb.URMOBO_RESPOSTA"
    private const val PREFS = "urmobo"
    private var ultimoPedido = 0L
    private var pedidoEm = 0L // SystemClock.elapsedRealtime() do último pedido (mede o tempo de resposta)

    /** O Urmobo já respondeu desde que este processo começou. */
    @Volatile var respondeu = false
        private set

    /** Quem espera a primeira resposta (a telemetria de abertura) é avisado aqui, na thread principal. */
    @Volatile var aoResponder: (() -> Unit)? = null

    /** Urmobo instalado e nenhuma resposta guardada nem recebida ainda: vale esperar um pouco pela primeira. */
    fun deveEsperar(ctx: Context): Boolean {
        val app = ctx.applicationContext
        return !respondeu && !sp(app).contains("id") && instalado(app)
    }

    class Dados(val id: String, val nome: String, val serial: String, val imei: String, val dadosAuxiliares: Map<String, String>)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pede os dados ao Urmobo (no máximo uma vez por minuto); a resposta chega depois no [UrmoboReceiver]. */
    fun pedir(ctx: Context) {
        val agora = System.currentTimeMillis()
        if (agora - ultimoPedido < 60_000) return
        ultimoPedido = agora
        pedidoEm = android.os.SystemClock.elapsedRealtime()
        try {
            val app = ctx.applicationContext
            if (!instalado(app)) return
            val resposta = Intent(app, UrmoboReceiver::class.java).setAction(ACTION_RESPOSTA)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pendente = PendingIntent.getBroadcast(app, 0, resposta, flags)
            val pedido = Intent(ACTION_PEDIDO)
                .setComponent(ComponentName(PACOTE, "$PACOTE.service.receivers.InformationRequestReceiver"))
                .putExtra("UrmoboReply", pendente)
            app.sendBroadcast(pedido)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun instalado(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PACOTE, 0) }.isSuccess

    fun guardar(ctx: Context, i: Intent) {
        // Tempo entre o pedido e a resposta; 0 se o processo reiniciou no meio (o pedido anterior se perdeu).
        val ms = if (pedidoEm > 0) (android.os.SystemClock.elapsedRealtime() - pedidoEm).coerceAtLeast(0) else 0L
        pedidoEm = 0
        sp(ctx).edit()
            .putLong("respostaMs", ms)
            .putString("id", i.getStringExtra("UrmoboID").orEmpty())
            .putString("nome", i.getStringExtra("DeviceName").orEmpty())
            .putString("serial", i.getStringExtra("SerialNumber").orEmpty())
            .putString("imei", i.getStringExtra("IMEI").orEmpty())
            .putString("dados", i.getStringExtra("CustomData").orEmpty())
            .apply()
        respondeu = true
        aoResponder?.let { aoResponder = null; it() }
    }

    /** A última resposta do Urmobo; null se nunca houve (ou se o Urmobo foi desinstalado). */
    fun dados(ctx: Context): Dados? {
        val app = ctx.applicationContext
        val s = sp(app)
        if (!s.contains("id") || !instalado(app)) return null
        return Dados(
            id = s.getString("id", "").orEmpty(),
            nome = s.getString("nome", "").orEmpty(),
            serial = s.getString("serial", "").orEmpty(),
            imei = s.getString("imei", "").orEmpty(),
            dadosAuxiliares = parse(s.getString("dados", "").orEmpty()),
        )
    }

    /** "descrição 1:valor 1;descrição 2:valor 2" -> mapa. */
    fun parse(texto: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        texto.split(';').forEach { par ->
            val i = par.indexOf(':')
            if (i > 0) out[par.substring(0, i).trim()] = par.substring(i + 1).trim()
        }
        return out
    }

    /** Valor de um dado auxiliar pela descrição (sem diferenciar maiúsculas). */
    fun dado(d: Dados?, descricao: String?): String =
        d?.dadosAuxiliares?.entries?.firstOrNull { it.key.equals(descricao?.trim(), ignoreCase = true) }?.value.orEmpty()

    /** Chaves `urmobo_*` para juntar ao objeto "dispositivo" (telemetria e POST da lista); nada se não houver dados. */
    fun acrescentar(ctx: Context, alvo: JSONObject) {
        val d = dados(ctx) ?: return
        alvo.put("urmobo_nome", d.nome)
        alvo.put("urmobo_id", d.id)
        alvo.put("urmobo_serial", d.serial)
        alvo.put("urmobo_imei", d.imei)
        alvo.put("urmobo_dados", JSONObject().apply { d.dadosAuxiliares.forEach { (k, v) -> put(k, v) } })
        // Quanto o Urmobo levou para responder à última pergunta (ms); some se a medição se perdeu.
        sp(ctx).getLong("respostaMs", 0L).takeIf { it > 0 }?.let { alvo.put("urmobo_resposta_ms", it) }
    }
}

/** Recebe a resposta do Urmobo MDM (veja [UrmoboInfo]). */
class UrmoboReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { UrmoboInfo.guardar(context, intent) }
    }
}
