package br.com.hubweb

import org.json.JSONObject

/**
 * Registro de falhas do app (rede, certificado, URL bloqueada, upload/download bloqueado, crash, motor): cada uma vai
 * como evento `erro` para o mesmo endpoint dos eventos de usuário (bloco `eventos` da lista; `eventos.erros: false` desliga).
 * Sem fila: sem rede o envio se perde. Para não inundar o servidor, a mesma falha no mesmo sistema só se repete a cada
 * 10 minutos e há um teto de 40 por hora.
 */
object AppLog {
    var telemetry: Telemetry? = null
    var repo: ConfigRepo? = null

    private val ultimas = HashMap<String, Long>()
    private var janelaInicio = 0L
    private var naJanela = 0

    private const val REPETE_MS = 10 * 60_000L
    private const val JANELA_MS = 60 * 60_000L
    private const val TETO = 40

    /** Tira a query (pode ter token, CPF...) do endereço antes de enviar. */
    fun limpa(url: String?): String? = url?.substringBefore('?')?.substringBefore('#')?.take(200)

    @Synchronized
    fun registrar(categoria: String, sistema: String?, detalhe: String?, url: String? = null) {
        val t = telemetry ?: return
        if (repo?.eventos?.erros == false) return
        val agora = System.currentTimeMillis()
        val chave = "$categoria|$sistema|${limpa(url)}|${detalhe?.take(60)}"
        if (agora - (ultimas[chave] ?: 0L) < REPETE_MS) return
        if (agora - janelaInicio > JANELA_MS) { janelaInicio = agora; naJanela = 0 }
        if (naJanela >= TETO) return
        naJanela++
        ultimas[chave] = agora
        if (ultimas.size > 200) ultimas.entries.removeAll { agora - it.value > REPETE_MS }
        val extra = JSONObject().put("categoria", categoria).put("detalhe", detalhe?.take(500) ?: JSONObject.NULL)
            .put("url", limpa(url) ?: JSONObject.NULL)
        t.sendEvent("erro", sistema.orEmpty(), "", categoria, null, extra)
    }

    /** Crash do app (exceção não tratada): guarda para enviar na próxima abertura, pois nessa hora o processo morre. */
    fun instalarCrash(prefs: Prefs) {
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            runCatching {
                val pilha = e.stackTraceToString().lines().take(14).joinToString("\n").take(1800)
                prefs.crashJson = JSONObject().put("quando", System.currentTimeMillis()).put("thread", th.name)
                    .put("excecao", e.javaClass.name).put("mensagem", e.message ?: JSONObject.NULL)
                    .put("pilha", pilha).toString()
            }
            anterior?.uncaughtException(th, e)
        }
    }

    /** Envia (uma vez) o crash guardado na execução anterior. */
    fun enviarCrashPendente(prefs: Prefs) {
        val bruto = prefs.crashJson.ifEmpty { return }
        prefs.crashJson = ""
        val c = runCatching { JSONObject(bruto) }.getOrNull() ?: return
        val t = telemetry ?: return
        if (repo?.eventos?.erros == false) return
        val extra = JSONObject().put("categoria", "crash").put("detalhe", c.optString("excecao") + ": " + c.optString("mensagem"))
            .put("url", JSONObject.NULL).put("pilha", c.optString("pilha"))
            .put("quando", DeviceStats.iso(c.optLong("quando")))
        t.sendEvent("erro", "", "", "crash", null, extra)
    }
}
