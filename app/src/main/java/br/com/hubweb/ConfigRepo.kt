package br.com.hubweb

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.random.Random

/** Config "telemetria" da lista: POST periódico enquanto o app está aberto. */
class TelemetriaConfig(
    val url: String,
    val frequenciaSegundos: Int,
    /** Token próprio; se ausente, usa o da lista quando for o mesmo servidor. */
    val token: String?,
    val localizacao: Boolean,
)

/**
 * Config "eventos" da lista: para onde vão, na hora, os logins/logouts das detecções por evento
 * (formulário, requisição, resposta, crachá). Com `url` é um endpoint **próprio**; sem `url`, vai para o mesmo
 * endpoint da telemetria (o envelope `tipo` diferencia). `ativo: false` desliga os eventos.
 */
class EventosConfig(
    val url: String?,
    /** Token do endpoint próprio; se ausente, usa o da telemetria (mesmo endpoint) ou o da lista (mesmo servidor). */
    val token: String?,
    val ativo: Boolean = true,
)

/**
 * Config "calculadora" da lista: controla o botão flutuante da calculadora.
 * `habilitada` = o usuário pode ter a calculadora (false esconde o botão e tira da lista de escolha);
 * `obrigatoria` = sempre ligada, o usuário não desliga; `inicial` = ligada ou não na primeira vez que a lista chega
 * (só vale quando é opcional; depois manda a escolha do usuário). `inicial` null = não define (desligada).
 */
class CalculadoraConfig(
    val habilitada: Boolean = true,
    val obrigatoria: Boolean = false,
    val inicial: Boolean? = null,
    /** Botão travado: o usuário não arrasta; fica em `posicao` (padrão: canto inferior direito). */
    val travada: Boolean = false,
    val posicao: PosicaoFab? = null,
)

/**
 * Bloco "aparencia" da lista: personaliza o cabeçalho do hub.
 * `nome` troca o título "HubWeb"; `logoUrl` põe uma imagem no lugar do nome (baixada só quando a URL muda);
 * os `ocultar*` escondem botões e a linha de última atualização; `toquesSecreto` é quantos toques rápidos no
 * título/logo abrem as configurações (o jeito de entrar quando a engrenagem está oculta); `colunas` fixa o número
 * de colunas do grid do hub (0 ou ausente = automático).
 */
class Aparencia(
    val nome: String? = null,
    val logoUrl: String? = null,
    val logoAltura: Int = 32,
    val ocultarAtualizar: Boolean = false,
    val ocultarConfiguracoes: Boolean = false,
    val ocultarUltimaAtualizacao: Boolean = false,
    val toquesSecreto: Int = 7,
    /** Colunas do grid do hub (1 a 8); 0 = automático, conforme a largura da tela. */
    val colunas: Int = 0,
)

/**
 * Quando e com que frequência buscar um JSON (a lista ou as dicas).
 * `intervaloSegundos` é o mínimo entre duas buscas automáticas (10 a 86400); `quando` diz o que dispara uma busca:
 * `abrir` (o app foi aberto ou trazido para a frente) e/ou `hub` (o usuário voltou ao launcher); vazio = só pelo botão;
 * `espalharSegundos` adia cada busca automática por um tempo aleatório de 0 a N s, para centenas de aparelhos não
 * baterem no servidor ao mesmo tempo.
 */
class RegraAtualizacao(
    val intervaloSegundos: Int = 60,
    val quando: Set<String> = setOf("abrir"),
    val espalharSegundos: Int = 0,
)

/** Bloco "atualizacao" da lista: uma regra para a lista e outra para as dicas. */
class Atualizacao(
    val lista: RegraAtualizacao = RegraAtualizacao(),
    val dicas: RegraAtualizacao = RegraAtualizacao(),
)

class Config(
    val items: List<AppItem>,
    val fundoUrl: String?,
    val senhaSha256: String?,
    /** null = a lista não define; o app usa a configuração local. */
    val modoKiosk: Boolean?,
    /** Texto do rodapé do launcher; null ou vazio = não mostra. */
    val rodape: String? = null,
    val telemetria: TelemetriaConfig? = null,
    val eventos: EventosConfig? = null,
    /** Controle da calculadora flutuante; null = não define (disponível, opcional, desligada). */
    val calculadora: CalculadoraConfig? = null,
    val aparencia: Aparencia? = null,
    val dicas: DicasConfig? = null,
    val atualizacao: Atualizacao? = null,
    val atualizacaoApp: AtualizacaoApp? = null,
)

/**
 * Busca o JSON da lista em segundo plano e guarda em cache (filesDir/apps.json).
 * Se a URL estiver indisponível ou o JSON for inválido, o último cache válido continua valendo.
 * A imagem de fundo (`fundoUrl`) só é baixada de novo quando a URL muda.
 */
class ConfigRepo(ctx: Context, private val prefs: Prefs) {
    private val file = File(ctx.filesDir, "apps.json")
    private val bgFile = File(ctx.filesDir, "fundo.img")
    private val logoFile = File(ctx.filesDir, "logo.img")
    private var logoLoading = false
    private val main = Handler(Looper.getMainLooper())
    private var agendado = false // há uma busca esperando o atraso aleatório (espalharSegundos)
    private var bgLoading = false

    var items by mutableStateOf<List<AppItem>>(emptyList()); private set
    var floating by mutableStateOf<List<AppItem>>(emptyList()); private set
    var rodape by mutableStateOf<String?>(null); private set
    var telemetria by mutableStateOf<TelemetriaConfig?>(null); private set
    var eventos by mutableStateOf<EventosConfig?>(null); private set
    var calculadora by mutableStateOf(CalculadoraConfig()); private set
    var lastUpdate by mutableStateOf(0L); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var background by mutableStateOf<ImageBitmap?>(null); private set
    var logo by mutableStateOf<ImageBitmap?>(null); private set
    var aparencia by mutableStateOf(Aparencia()); private set
    var dicas by mutableStateOf<DicasConfig?>(null); private set
    var atualizacao by mutableStateOf(Atualizacao()); private set
    var atualizacaoApp by mutableStateOf<AtualizacaoApp?>(null); private set

    /** `modoKiosk` da última lista aplicada (null = a lista não define). Observável pela UI. */
    var modoKiosk by mutableStateOf(prefs.kioskRemote.let { if (it < 0) null else it == 1 }); private set

    /** Chamado na thread principal depois que uma lista nova foi aplicada. */
    var onApplied: (() -> Unit)? = null

    fun loadCache() {
        if (!file.exists()) return
        try {
            val cfg = parseConfig(file.readText())
            items = cfg.items
            floating = cfg.items.filter { it.flutuante }
            rodape = cfg.rodape
            telemetria = cfg.telemetria
            eventos = cfg.eventos
            calculadora = cfg.calculadora ?: CalculadoraConfig()
            aparencia = cfg.aparencia ?: Aparencia()
            dicas = cfg.dicas
            atualizacao = cfg.atualizacao ?: Atualizacao()
            atualizacaoApp = cfg.atualizacaoApp
            lastUpdate = file.lastModified()
            cfg.senhaSha256?.let { prefs.passSha256 = it }
            prefs.kioskRemote = cfg.modoKiosk?.let { if (it) 1 else 0 } ?: -1
            modoKiosk = cfg.modoKiosk
            syncBackground(cfg.fundoUrl)
            syncLogo(cfg.aparencia?.logoUrl)
        } catch (_: Exception) {
        }
    }

    /**
     * Busca a lista no servidor. `force` é uma ação do usuário (botão de atualizar, salvar configurações): ignora o
     * intervalo mínimo e os gatilhos (só segura um segundo toque em menos de 5 s). Sem `force`, `gatilho` diz o que
     * provocou a busca, `"abrir"` (o app foi aberto) ou `"hub"` (voltou ao launcher), e ela só acontece se a lista
     * permitir esse gatilho (`atualizacao.lista.quando`) e se já passou o intervalo mínimo desde a última tentativa
     * (guardada no aparelho, então fechar e abrir o app não zera). Sem lista em cache, busca em qualquer gatilho.
     */
    fun refresh(force: Boolean = false, gatilho: String = "abrir") {
        val url = prefs.configUrl
        if (url.isBlank() || loading || agendado) return
        val now = System.currentTimeMillis()
        val last = prefs.listaTentativaEm
        val regra = atualizacao.lista
        val semCache = !file.exists()
        fun recente(ms: Long) = last != 0L && (now - last) in 0 until ms // relógio atrasado = não é recente
        if (force) {
            if (recente(5_000)) return
        } else if (semCache) {
            if (recente(regra.intervaloSegundos * 1000L)) return // servidor fora do ar: respeita o intervalo
        } else {
            if (gatilho !in regra.quando) return
            if (recente(regra.intervaloSegundos * 1000L)) return
        }
        prefs.listaTentativaEm = now
        // Espalha a carga: cada aparelho espera um tempo aleatório antes de buscar (não vale para o botão).
        val atraso = if (force || semCache || regra.espalharSegundos <= 0) 0L
        else Random.nextLong(regra.espalharSegundos * 1000L + 1)
        agendado = true
        val token = prefs.token
        thread(name = "hubweb-config") {
            if (atraso > 0) try { Thread.sleep(atraso) } catch (_: InterruptedException) {}
            main.post { agendado = false; loading = true }
            try {
                val text = fetch(url, token)
                val cfg = parseConfig(text) // lança exceção se inválido: o cache antigo fica
                val tmp = File(file.parentFile, "apps.json.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.delete(); tmp.renameTo(file)
                }
                main.post {
                    items = cfg.items
                    floating = cfg.items.filter { it.flutuante }
                    rodape = cfg.rodape
                    telemetria = cfg.telemetria
                    eventos = cfg.eventos
                    calculadora = cfg.calculadora ?: CalculadoraConfig()
                    aparencia = cfg.aparencia ?: Aparencia()
                    dicas = cfg.dicas
                    atualizacao = cfg.atualizacao ?: Atualizacao()
            atualizacaoApp = cfg.atualizacaoApp
                    lastUpdate = System.currentTimeMillis()
                    error = null
                    loading = false
                    // Sem senha no JSON, mantém a última: uma lista incompleta não destrava as configurações.
                    cfg.senhaSha256?.let { prefs.passSha256 = it }
                    // Lista sem "modoKiosk" devolve o controle para a configuração do app.
                    prefs.kioskRemote = cfg.modoKiosk?.let { if (it) 1 else 0 } ?: -1
                    modoKiosk = cfg.modoKiosk
                    syncBackground(cfg.fundoUrl)
                    syncLogo(cfg.aparencia?.logoUrl)
                    onApplied?.invoke()
                }
            } catch (e: Exception) {
                main.post {
                    error = e.message ?: e.javaClass.simpleName
                    loading = false
                }
            }
        }
    }
    private fun fetch(url: String, token: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            // O token só viaja por HTTPS.
            if (token.isNotBlank() && url.startsWith("https://", ignoreCase = true)) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            if (conn.contentLengthLong > MAX_BYTES) throw IllegalStateException("JSON muito grande")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (text.length > MAX_BYTES) throw IllegalStateException("JSON muito grande")
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ imagem de fundo

    /** Chamar na thread principal. Baixa só se a URL difere da que já está salva em disco. */
    private fun syncBackground(url: String?) {
        if (url == null) {
            bgFile.delete()
            prefs.fundoUrl = ""
            background = null
            return
        }
        // Enquanto a nova não chega (ou se o download falhar), continua a imagem anterior.
        if (background == null && bgFile.exists()) background = decodeBackground()
        if (url == prefs.fundoUrl && bgFile.exists()) return
        if (bgLoading) return
        bgLoading = true
        val token = prefs.token
        val configHost = runCatching { URL(prefs.configUrl).host }.getOrNull()
        thread(name = "hubweb-fundo") {
            try {
                val tmp = File(bgFile.parentFile, "fundo.img.tmp")
                downloadImage(url, token, configHost, tmp)
                if (!tmp.renameTo(bgFile)) {
                    bgFile.delete(); tmp.renameTo(bgFile)
                }
                val bmp = decodeBackground()
                main.post {
                    prefs.fundoUrl = url
                    background = bmp
                    bgLoading = false
                }
            } catch (_: Exception) {
                main.post { bgLoading = false } // tenta de novo no próximo refresh
            }
        }
    }

    private fun downloadImage(url: String, token: String, configHost: String?, to: File) {
        val u = URL(url)
        val conn = u.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            // O token só vai se a imagem estiver no mesmo servidor da lista e por HTTPS.
            if (token.isNotBlank() && u.protocol == "https" && u.host.equals(configHost, ignoreCase = true)) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            if (conn.contentLengthLong > MAX_IMAGE) throw IllegalStateException("Imagem muito grande")
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMAGE) throw IllegalStateException("Imagem muito grande")
                        out.write(buf, 0, n)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(to.path, bounds)
            if (bounds.outWidth <= 0) {
                to.delete()
                throw IllegalStateException("Imagem inválida")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Reduz para no máx. ~2048px no maior lado, para não estourar a memória. */
    private fun decodeBackground(): ImageBitmap? = decodeImage(bgFile, 2048)

    private fun decodeImage(file: File, maxPx: Int): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------ logo do cabeçalho

    /** Igual ao fundo: chamar na thread principal; só baixa de novo se a URL mudar. Sem URL, remove a logo. */
    private fun syncLogo(url: String?) {
        if (url == null) {
            logoFile.delete()
            prefs.logoUrl = ""
            logo = null
            return
        }
        if (logo == null && logoFile.exists()) logo = decodeImage(logoFile, 512)
        if (url == prefs.logoUrl && logoFile.exists()) return
        if (logoLoading) return
        logoLoading = true
        val token = prefs.token
        val configHost = runCatching { URL(prefs.configUrl).host }.getOrNull()
        thread(name = "hubweb-logo") {
            try {
                val tmp = File(logoFile.parentFile, "logo.img.tmp")
                downloadImage(url, token, configHost, tmp)
                if (!tmp.renameTo(logoFile)) {
                    logoFile.delete(); tmp.renameTo(logoFile)
                }
                val bmp = decodeImage(logoFile, 512)
                main.post {
                    prefs.logoUrl = url
                    logo = bmp
                    logoLoading = false
                }
            } catch (_: Exception) {
                main.post { logoLoading = false } // tenta de novo no próximo refresh
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 10_000_000
        private const val MAX_IMAGE = 15_000_000L

        /** Aceita `{"fundoUrl":"...","apps":[...]}` ou diretamente `[...]` (sem fundo). */
        fun parseConfig(text: String): Config {
            val t = text.trim().removePrefix("﻿")
            val root = if (t.startsWith("[")) null else JSONObject(t)
            val apps = parseApps(root?.getJSONArray("apps") ?: JSONArray(t))
            // Formato antigo: array "flutuantes" na raiz (itens só flutuantes). Ainda aceito.
            val legados = root?.optJSONArray("flutuantes")?.let { parseApps(it, legado = true) }.orEmpty()
            return finishConfig(root, (apps + legados).distinctBy { it.id })
        }

        /**
         * Itens de "apps". Cada um aparece no hub (`hub`, padrão true) e/ou como botão flutuante
         * (`flutuante`, padrão false); `flutuanteObrigatorio` trava o botão ligado.
         */
        private fun parseApps(arr: JSONArray, legado: Boolean = false): List<AppItem> {
            val out = ArrayList<AppItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val nome = o.str("nome")?.trim().orEmpty()
                val url = o.str("urlInicial")?.trim().orEmpty()
                if (nome.isEmpty() || url.isEmpty()) continue
                val host = Uri.parse(url).host ?: continue
                val orient = when (o.str("orientacao")?.trim()?.lowercase()) {
                    "retrato", "portrait" -> "retrato"
                    "paisagem", "landscape" -> "paisagem"
                    else -> "auto"
                }
                out += AppItem(
                    nome = nome,
                    urlInicial = url,
                    urlPermitida = o.globs("urlPermitida").ifEmpty { listOf("$host/*") },
                    icone = o.str("icone")?.takeIf { it.isNotBlank() },
                    permitirCopiarColar = o.bool("permitirCopiarColar", false),
                    permitirHttp = o.bool("permitirHttp", false),
                    ordem = o.optInt("ordem", 0),
                    grupo = o.str("grupo")?.trim()?.takeIf { it.isNotEmpty() },
                    orientacao = orient,
                    userAgent = o.str("userAgent")?.takeIf { it.isNotBlank() },
                    zoom = o.optInt("zoom", 100).coerceIn(50, 300),
                    permitirUpload = o.bool("permitirUpload", false),
                    permitirDownload = o.bool("permitirDownload", false),
                    permitirCamera = o.bool("permitirCamera", false),
                    usuarios = parseUsuarios(o.opt("usuario")),
                    // "barra": {"visivel": true, "voltar": true, "atualizar": true, "inicio": true}
                    barraVisivel = o.optJSONObject("barra")?.bool("visivel", true) ?: true,
                    barraVoltar = o.optJSONObject("barra")?.bool("voltar", true) ?: true,
                    barraAtualizar = o.optJSONObject("barra")?.bool("atualizar", true) ?: true,
                    barraInicio = o.optJSONObject("barra")?.bool("inicio", true) ?: true,
                    flutuanteInicial = o.bool("flutuanteInicial", false),
                    flutuanteTravado = o.bool("flutuanteTravado", false),
                    flutuantePosicao = parsePosicao(o.opt("flutuantePosicao")),
                    leitorCamera = o.bool("leitorCamera", false),
                    leitorSufixo = when (o.str("leitorSufixo")?.trim()?.lowercase()) {
                        "tab" -> "tab"
                        "", "nenhum", "none" -> ""
                        else -> "enter"
                    },
                    hub = if (legado) false else o.bool("hub", true),
                    flutuante = if (legado) true else o.bool("flutuante", false),
                    obrigatorio = o.bool("flutuanteObrigatorio", o.bool("obrigatorio", false)),
                )
            }
            return out.distinctBy { it.id }
        }

        /**
         * "usuario" de um item: um objeto ou um array de objetos (várias detecções no mesmo sistema).
         * O índice na lista resultante identifica cada detecção (vai no script como `__idx`).
         */
        private fun parseUsuarios(v: Any?): List<UsuarioConfig> {
            val blocos: List<JSONObject> = when (v) {
                is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
                is JSONObject -> listOf(v)
                else -> emptyList()
            }
            val out = ArrayList<UsuarioConfig>()
            for (u in blocos) {
                val tipo = u.str("tipo")?.trim()?.lowercase().orEmpty()
                if (tipo !in UsuarioConfig.TIPOS) continue
                u.put("__idx", out.size)
                // "envio": "evento" (vai na hora para "eventos") ou "telemetria" (só no ciclo da telemetria);
                // se ausente, depende do tipo.
                val envioEvento = when (u.str("envio")?.trim()?.lowercase()) {
                    "evento", "eventos" -> true
                    "telemetria" -> false
                    else -> tipo in UsuarioConfig.EVENTO_PADRAO
                }
                out += UsuarioConfig(
                    tipo = tipo,
                    json = u.toString(),
                    limparNoTimeout = u.bool("limparNoTimeout", true),
                    crachaObrigatorio = u.bool("crachaObrigatorio", true),
                    regex = u.str("regex")?.takeIf { it.isNotBlank() },
                    envioEvento = envioEvento,
                )
            }
            return out
        }

        /** "inferior-direita" (texto) ou {"canto": "..."} ou {"x": 0.9, "y": 0.8} (fração da área livre). */
        private fun parsePosicao(v: Any?): PosicaoFab? = when (v) {
            is String -> v.trim().lowercase().takeIf { it in PosicaoFab.CANTOS }?.let { PosicaoFab(canto = it) }
            is JSONObject -> {
                val canto = v.str("canto")?.trim()?.lowercase()?.takeIf { it in PosicaoFab.CANTOS }
                if (!v.isNull("x") && !v.isNull("y")) {
                    PosicaoFab(x = v.optDouble("x").toFloat().coerceIn(0f, 1f), y = v.optDouble("y").toFloat().coerceIn(0f, 1f))
                } else canto?.let { PosicaoFab(canto = it) }
            }
            else -> null
        }

        /**
         * {"intervaloMinimoSegundos": 300, "quando": ["abrir", "hub"], "espalharSegundos": 30}.
         * "quando" ausente = ["abrir"]; presente e vazio = nenhuma busca automática (só o botão).
         */
        private fun parseRegra(o: JSONObject?): RegraAtualizacao {
            if (o == null) return RegraAtualizacao()
            val quando = if (o.isNull("quando")) {
                setOf("abrir")
            } else {
                val lista = when (val v = o.opt("quando")) {
                    is JSONArray -> (0 until v.length()).map { v.optString(it) }
                    is String -> v.split(Regex("[,;\\s]+"))
                    else -> emptyList()
                }
                lista.map { it.trim().lowercase() }.filter { it == "abrir" || it == "hub" }.toSet()
            }
            return RegraAtualizacao(
                intervaloSegundos = o.optInt("intervaloMinimoSegundos", 60).coerceIn(10, 86_400),
                quando = quando,
                espalharSegundos = o.optInt("espalharSegundos", 0).coerceIn(0, 600),
            )
        }

        private fun finishConfig(root: JSONObject?, items: List<AppItem>): Config {
            val fundo = root?.str("fundoUrl")?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            // "senhaSha256" (hex, recomendado) ou "senha" (texto); o app só guarda o hash.
            val hex = root?.str("senhaSha256")?.trim()?.lowercase()?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
            val hash = hex ?: root?.str("senha")?.takeIf { it.isNotEmpty() }?.let(::sha256Hex)
            // "modoKiosk": true/false obriga; ausente, null ou vazio deixa a escolha com o app.
            val kiosk = when (val k = root?.opt("modoKiosk")) {
                is Boolean -> k
                is String -> when (k.trim().lowercase()) { "true" -> true; "false" -> false; else -> null }
                else -> null
            }
            // "rodape": texto com {nome} {ip} {modelo} {android} {app} {atualizacao}; ausente ou "" = não mostra nada.
            val rodape = if (root != null && root.has("rodape") && !root.isNull("rodape")) root.optString("rodape") else null
            // "telemetria": {"url": "https://...", "frequenciaSegundos": 60, "token": "...", "localizacao": true}
            val telemetria = root?.optJSONObject("telemetria")?.let { t ->
                val url = t.str("url")?.trim().orEmpty()
                if (url.isEmpty()) null
                else TelemetriaConfig(
                    url = url,
                    frequenciaSegundos = t.optInt("frequenciaSegundos", 60).coerceIn(10, 86_400),
                    token = t.str("token")?.trim()?.takeIf { it.isNotEmpty() },
                    localizacao = t.bool("localizacao", true),
                )
            }
            // "eventos": {"url": "https://...", "token": "...", "ativo": true}. Sem "url" os eventos usam o endpoint da telemetria.
            val eventos = root?.optJSONObject("eventos")?.let { e ->
                EventosConfig(
                    url = e.str("url")?.trim()?.takeIf { it.isNotEmpty() },
                    token = e.str("token")?.trim()?.takeIf { it.isNotEmpty() },
                    ativo = e.bool("ativo", true),
                )
            }
            // "calculadora": {"habilitada": true, "obrigatoria": false, "inicial": false}.
            // O antigo "calculadoraInicial" na raiz equivale só ao "inicial".
            val calcBloco = root?.optJSONObject("calculadora")
            val calcLegado = if (root != null && root.has("calculadoraInicial") && !root.isNull("calculadoraInicial")) {
                root.optBoolean("calculadoraInicial", false)
            } else null
            val calculadora = when {
                calcBloco != null -> CalculadoraConfig(
                    habilitada = calcBloco.bool("habilitada", true),
                    obrigatoria = calcBloco.bool("obrigatoria", false),
                    inicial = if (calcBloco.isNull("inicial")) calcLegado else calcBloco.optBoolean("inicial", false),
                    travada = calcBloco.bool("travada", false),
                    posicao = parsePosicao(calcBloco.opt("posicao")),
                )
                calcLegado != null -> CalculadoraConfig(inicial = calcLegado)
                else -> null
            }
            // "aparencia": nome, logoUrl, logoAltura, ocultarAtualizar, ocultarConfiguracoes, ocultarUltimaAtualizacao, toquesSecreto, colunas.
            val aparencia = root?.optJSONObject("aparencia")?.let { a ->
                Aparencia(
                    nome = a.str("nome")?.trim()?.takeIf { it.isNotEmpty() },
                    logoUrl = a.str("logoUrl")?.trim()
                        ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) },
                    logoAltura = a.optInt("logoAltura", 32).coerceIn(16, 96),
                    ocultarAtualizar = a.bool("ocultarAtualizar", false),
                    ocultarConfiguracoes = a.bool("ocultarConfiguracoes", false),
                    ocultarUltimaAtualizacao = a.bool("ocultarUltimaAtualizacao", false),
                    toquesSecreto = a.optInt("toquesSecreto", 7).coerceIn(3, 20),
                    colunas = a.optInt("colunas", 0).let { if (it <= 0) 0 else it.coerceIn(1, 8) },
                )
            }
            // "dicas": {"url": "https://...", "token": "..."}: endpoint (GET) com as configurações e a lista de dicas.
            val dicas = root?.optJSONObject("dicas")?.let { d ->
                val url = d.str("url")?.trim().orEmpty()
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                    DicasConfig(url, d.str("token")?.trim()?.takeIf { it.isNotEmpty() })
                } else null
            }
            // "atualizacao": {"lista": {...}, "dicas": {...}}: intervalo mínimo e gatilhos das buscas automáticas.
            val atualizacao = root?.optJSONObject("atualizacao")?.let { a ->
                Atualizacao(lista = parseRegra(a.optJSONObject("lista")), dicas = parseRegra(a.optJSONObject("dicas")))
            }
            // "atualizacaoApp": {"versionCode": 4, "versao": "1.0.3", "url": "https://...apk", "sha256": "...", "token": null,
            // "obrigatoria": false, "notas": "..."}: a versão do app que os aparelhos devem ter. Exige url https e sha256.
            val atualizacaoApp = root?.optJSONObject("atualizacaoApp")?.let { u ->
                val codigo = u.optLong("versionCode", 0)
                val url = u.str("url")?.trim().orEmpty()
                val sha = u.str("sha256")?.trim()?.lowercase().orEmpty()
                if (codigo <= 0 || !url.startsWith("https://", true) || !Regex("[0-9a-f]{64}").matches(sha)) null
                else AtualizacaoApp(
                    versionCode = codigo,
                    versao = u.str("versao")?.trim()?.takeIf { it.isNotEmpty() },
                    url = url,
                    sha256 = sha,
                    token = u.str("token")?.trim()?.takeIf { it.isNotEmpty() },
                    obrigatoria = u.bool("obrigatoria", false),
                    notas = u.str("notas")?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
            return Config(items, fundo, hash, kiosk, rodape, telemetria, eventos, calculadora, aparencia, dicas, atualizacao, atualizacaoApp)
        }

        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)

        private fun JSONObject.bool(k: String, def: Boolean) = if (isNull(k)) def else optBoolean(k, def)

        // Aceita uma string (globs separados por vírgula, ponto e vírgula ou espaço) ou um array de strings.
        private fun JSONObject.globs(k: String): List<String> = when (val v = opt(k)) {
            is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
            is String -> v.split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }
}
