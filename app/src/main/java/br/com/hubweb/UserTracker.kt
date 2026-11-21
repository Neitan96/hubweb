package br.com.hubweb

import android.net.Uri
import android.os.Handler
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/** Quem uma detecção encontrou logado em um sistema. `evento` = a detecção envia eventos; senão, só telemetria. */
class UserState(
    val sistema: String,
    val login: String,
    val desde: Long,
    val fonte: String,
    val evento: Boolean,
)

/**
 * Guarda o usuário que cada detecção encontrou (um sistema pode ter várias) e avisa o servidor:
 * detecções por evento mandam login/logout/troca na hora para o endpoint "eventos"; as contínuas
 * (cookie, endpoint, elemento...) só aparecem na telemetria periódica. Vale até o aparelho reiniciar.
 */
class UserTracker(private val prefs: Prefs) {
    /** Chave = "<id do item>#<índice da detecção>". Observável pela interface. */
    val states = mutableStateMapOf<String, UserState>()

    var telemetry: Telemetry? = null

    init {
        runCatching {
            val o = JSONObject(prefs.usersJson.ifEmpty { "{}" })
            for (k in o.keys()) {
                if ('#' !in k) continue // formato antigo (um usuário por item)
                val u = o.getJSONObject(k)
                states[k] = UserState(
                    u.getString("sistema"), u.getString("login"), u.getLong("desde"),
                    u.optString("fonte"), u.optBoolean("evento", false),
                )
            }
        }
    }

    private fun key(item: AppItem, idx: Int) = "${item.id}#$idx"

    fun get(item: AppItem, idx: Int): UserState? = states[key(item, idx)]

    /** Usuário identificado mais recentemente (em qualquer sistema e detecção). */
    fun current(): UserState? = states.values.maxByOrNull { it.desde }

    /**
     * `user` vazio = a detecção não vê ninguém logado. `idx` é a posição da detecção em `item.usuarios`.
     * Chamar na thread principal.
     */
    fun report(item: AppItem, idx: Int, user: String, fonte: String, novo: Boolean = false) {
        val cfg = item.usuarios.getOrNull(idx) ?: return
        val k = key(item, idx)
        val prev = states[k]
        if (user.isEmpty()) {
            if (prev == null) return
            states.remove(k)
            save()
            if (cfg.envioEvento) telemetry?.sendEvent("logout", item.nome, prev.login, fonte, null)
            return
        }
        if (prev?.login == user) {
            if (!novo) return
            // Mesmo usuário entrando de novo, por uma ação de login confirmada: é um novo login. Renova o "desde"
            // e envia o evento outra vez (sem isso, logout e login do mesmo usuário nunca chegariam ao servidor).
            states[k] = UserState(item.nome, user, System.currentTimeMillis(), fonte, cfg.envioEvento)
            save()
            if (cfg.envioEvento) telemetry?.sendEvent("login", item.nome, user, fonte, null)
            return
        }
        states[k] = UserState(item.nome, user, System.currentTimeMillis(), fonte, cfg.envioEvento)
        save()
        if (cfg.envioEvento) {
            telemetry?.sendEvent(if (prev == null) "login" else "troca", item.nome, user, fonte, prev?.login)
        }
    }

    /** Timeout de inatividade: desliga os usuários por crachá que pedem isso (aparelho compartilhado). */
    fun onTimeout(items: List<AppItem>) {
        for (item in items) {
            item.usuarios.forEachIndexed { idx, u ->
                if (u.tipo == "cracha" && u.limparNoTimeout) report(item, idx, "", "timeout")
            }
        }
    }

    private fun save() {
        val o = JSONObject()
        for ((k, s) in states) {
            o.put(
                k,
                JSONObject().put("sistema", s.sistema).put("login", s.login).put("desde", s.desde)
                    .put("fonte", s.fonte).put("evento", s.evento),
            )
        }
        prefs.usersJson = o.toString()
    }
}

/**
 * Ponte entre os scripts de detecção da página e o app (exposta como `HubWebUser`). Só aceita chamadas
 * de páginas cujo endereço casa com o `urlPermitida` do item. Nunca recebe senha: o script só envia o login.
 */
class UserBridge(
    private val item: AppItem,
    private val tracker: UserTracker,
    private val main: Handler,
) {
    @JavascriptInterface
    fun report(user: String?, fonte: String?, href: String?, idx: Int, novo: Boolean) {
        if (!allowed(href)) return
        val cfg = item.usuarios.getOrNull(idx) ?: return
        val login = user.orEmpty().trim().take(200)
        val origem = fonte.orEmpty().ifEmpty { cfg.tipo }
        main.post { tracker.report(item, idx, login, origem, novo) }
    }

    /** Valor de um cookie (inclusive HttpOnly, que a página não enxerga) do endereço informado. */
    @JavascriptInterface
    fun cookie(url: String?, name: String?): String {
        if (!allowed(url) || name.isNullOrEmpty()) return ""
        val raw = CookieManager.getInstance().getCookie(url) ?: return ""
        val hit = raw.split(";").map { it.trim() }.firstOrNull { it.startsWith("$name=") } ?: return ""
        return Uri.decode(hit.substringAfter("="))
    }

    private fun allowed(href: String?) =
        href != null && runCatching { item.allows(Uri.parse(href)) }.getOrDefault(false)
}
