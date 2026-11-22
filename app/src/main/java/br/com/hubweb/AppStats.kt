package br.com.hubweb

import android.os.SystemClock

/** Uso por sistema desde o último envio de telemetria bem-sucedido (tempo em ms e número de aberturas). */
class Uso(val periodoMs: Long, val ms: Map<String, Long>, val aberturas: Map<String, Int>)

/**
 * Uso de cada sistema: quanto tempo ficou aberto na tela (com o app em primeiro plano) e quantas vezes foi aberto.
 * Fica só na memória do app; a telemetria retira o acumulado a cada envio e, se o envio falhar, devolve (assim nada
 * se perde por uma falha de rede). Chamado sempre na thread principal.
 */
object AppStats {
    private var sistema: String? = null // o sistema aberto na tela (null = launcher/configurações)
    private var visivel = false // o app está em primeiro plano
    private var desde = SystemClock.elapsedRealtime()
    private var periodoDesde = SystemClock.elapsedRealtime()
    private var ultimo: String? = null // último sistema aberto
    private var penultimo: String? = null // o aberto antes dele
    private val ms = LinkedHashMap<String, Long>()
    private val aberturas = LinkedHashMap<String, Int>()

    private fun acumular() {
        val agora = SystemClock.elapsedRealtime()
        sistema?.takeIf { visivel }?.let { ms[it] = (ms[it] ?: 0L) + (agora - desde) }
        desde = agora
    }

    fun abrirSistema(nome: String) {
        acumular()
        sistema = nome
        if (nome != ultimo) {
            penultimo = ultimo
            ultimo = nome
        }
        aberturas[nome] = (aberturas[nome] ?: 0) + 1
    }

    /** O sistema aberto antes de [de] (a urlInicial usa em {sistemaAnterior}); null se não houve outro. */
    fun anterior(de: String): String? = if (ultimo != de) ultimo else penultimo

    fun fecharSistema() {
        acumular()
        sistema = null
    }

    /** O app foi para o primeiro plano (true) ou saiu dele (false). */
    fun visivel(v: Boolean) {
        acumular()
        visivel = v
    }

    /** Tira o acumulado até agora e recomeça do zero. */
    fun retirarUso(): Uso {
        acumular()
        val agora = SystemClock.elapsedRealtime()
        val uso = Uso(agora - periodoDesde, LinkedHashMap(ms), LinkedHashMap(aberturas))
        ms.clear()
        aberturas.clear()
        periodoDesde = agora
        return uso
    }

    /** O envio falhou: soma de volta o que foi retirado, para ir no próximo. */
    fun devolverUso(u: Uso) {
        u.ms.forEach { (k, v) -> ms[k] = (ms[k] ?: 0L) + v }
        u.aberturas.forEach { (k, v) -> aberturas[k] = (aberturas[k] ?: 0) + v }
        periodoDesde -= u.periodoMs // o período devolvido volta a contar
    }
}
