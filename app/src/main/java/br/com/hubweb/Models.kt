package br.com.hubweb

import android.net.Uri

/**
 * Bloco "usuario" de um item: como descobrir quem está logado naquele sistema.
 * `json` é o bloco original, repassado ao script de detecção da página; as demais propriedades são o que o app
 * nativo precisa saber.
 */
data class UsuarioConfig(
    /** endpoint, elemento, cookie, storage, js, url, titulo, resposta, formulario, requisicao ou cracha. */
    val tipo: String,
    val json: String,
    /** cracha: desliga o usuário quando o timeout de inatividade volta ao launcher (aparelho compartilhado). */
    val limparNoTimeout: Boolean,
    /** cracha: sem identificação não abre o sistema. */
    val crachaObrigatorio: Boolean,
    /** cracha: formato aceito (expressão regular completa), ex.: ^\d{6}$ */
    val regex: String?,
    /**
     * true = detecção por evento (login/logout vão para o endpoint "eventos" na hora);
     * false = detecção contínua (o usuário só aparece na telemetria periódica).
     */
    val envioEvento: Boolean,
) {
    companion object {
        /** Tipos que, por padrão, enviam evento; os de consulta contínua vão na telemetria. Dá para mudar com "envio". */
        val EVENTO_PADRAO = setOf("formulario", "requisicao", "resposta", "cracha")

        val TIPOS = setOf(
            "endpoint", "elemento", "cookie", "storage", "js", "url", "titulo",
            "resposta", "formulario", "requisicao", "cracha",
        )
    }
}

/**
 * Onde um botão flutuante travado fica: um `canto` (inferior-direita, inferior-esquerda, superior-direita,
 * superior-esquerda, meio-direita, meio-esquerda) ou uma posição `x`/`y` em fração (0 a 1) da área livre da tela.
 */
class PosicaoFab(val canto: String? = null, val x: Float? = null, val y: Float? = null) {
    companion object {
        val CANTOS = setOf(
            "inferior-direita", "inferior-esquerda", "superior-direita", "superior-esquerda",
            "meio-direita", "meio-esquerda",
        )
    }
}

/** Um item do launcher, vindo do JSON remoto. */
data class AppItem(
    val nome: String,
    val urlInicial: String,
    // Globs (ex.: app.exemplo.com/ + asterisco) casados contra host + caminho.
    val urlPermitida: List<String>,
    val icone: String?,
    val permitirCopiarColar: Boolean,
    val permitirHttp: Boolean,
    val ordem: Int,
    val grupo: String?,
    /** "auto", "retrato" ou "paisagem". */
    val orientacao: String,
    val userAgent: String?,
    val zoom: Int,
    val permitirUpload: Boolean,
    val permitirDownload: Boolean,
    val permitirCamera: Boolean,
    /** Como identificar o usuário logado neste sistema; pode haver várias detecções (vazio = não identifica). */
    val usuarios: List<UsuarioConfig> = emptyList(),
    /** Barra do sistema (launcher, voltar, atualizar, início): aparece já visível ao abrir? E quais botões ela tem. */
    val barraVisivel: Boolean = true,
    val barraVoltar: Boolean = true,
    val barraAtualizar: Boolean = true,
    val barraInicio: Boolean = true,
    /** Botão flutuante travado: não pode ser arrastado e fica em `flutuantePosicao` (padrão: canto inferior direito). */
    val flutuanteTravado: Boolean = false,
    val flutuantePosicao: PosicaoFab? = null,
    /** Botão flutuante opcional: ligado ou não na primeira vez que o item aparece na lista (depois vale a escolha do usuário). */
    val flutuanteInicial: Boolean = false,
    /** Mostra um botão para ler código de barras/QR pela câmera e digitá-lo no campo focado da página. */
    val leitorCamera: Boolean = false,
    /** O que enviar depois do código: "enter" (padrão), "tab" ou "" (nada). */
    val leitorSufixo: String = "enter",
    /** Aparece como cartão no launcher (hub). */
    val hub: Boolean = true,
    /** Também (ou só) vira um botão flutuante com popup. */
    val flutuante: Boolean = false,
    /** Para itens flutuantes: true = botão sempre ligado (o usuário não pode desligar); false = o usuário escolhe. */
    val obrigatorio: Boolean = false,
    /** Se a urlInicial tem variáveis (usuário, rede, nome do aparelho...), volta à urlInicial quando uma delas muda (as que mudam o tempo todo são ignoradas). */
    val recarregarAoMudar: Boolean = true,
) {
    val id: String get() = "$nome|$urlInicial"

    private val matchers: List<Regex> by lazy { urlPermitida.map(::globToRegex) }

    /** O esquema é aceito e o host+caminho casa com algum glob permitido? */
    // Só o host de cada glob (sem esquema nem caminho), para validar a origem de URLs blob:.
    private val hostMatchers: List<Regex> by lazy {
        urlPermitida.map { globToRegex(it.trim().replace(SCHEME_PREFIX, "").substringBefore('/')) }
    }

    fun allows(uri: Uri): Boolean {
        if (uri.scheme.equals("blob", ignoreCase = true)) return allowsBlob(uri)
        if (!schemeAllowed(uri)) return false
        val host = uri.host ?: return false
        val path = uri.encodedPath.orEmpty().ifEmpty { "/" }
        val semPorta = host + path
        val comPorta = if (uri.port != -1) "$host:${uri.port}$path" else semPorta
        return matchers.any { it.matches(semPorta) || it.matches(comPorta) }
    }

    /** blob:https://host/uuid — o caminho é um UUID, então só a origem (host) precisa ser permitida. */
    private fun allowsBlob(uri: Uri): Boolean {
        val inner = Uri.parse(uri.schemeSpecificPart)
        if (!schemeAllowed(inner)) return false
        val host = inner.host ?: return false
        val hostPort = if (inner.port != -1) "$host:${inner.port}" else host
        return hostMatchers.any { it.matches(host) || it.matches(hostPort) }
    }

    fun schemeAllowed(uri: Uri): Boolean {
        val s = uri.scheme?.lowercase()
        return s == "https" || (s == "http" && permitirHttp)
    }
}

private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/** "*" = qualquer coisa, "?" = um caractere; o resto é literal. Sem diferenciar maiúsculas. */
fun globToRegex(glob: String): Regex {
    val g = glob.trim().replace(SCHEME_PREFIX, "")
    val sb = StringBuilder()
    for (c in g) when (c) {
        '*' -> sb.append(".*")
        '?' -> sb.append('.')
        else -> sb.append(Regex.escape(c.toString()))
    }
    return Regex(sb.toString(), RegexOption.IGNORE_CASE)
}
