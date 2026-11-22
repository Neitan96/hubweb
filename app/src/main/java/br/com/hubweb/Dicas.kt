package br.com.hubweb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random

/** Bloco "dicas" da lista: de onde o app baixa as dicas (um endpoint próprio, só leitura). */
class DicasConfig(
    val url: String,
    /** Token próprio; se ausente, usa o da lista quando for o mesmo servidor. */
    val token: String?,
)

class Dica(
    val id: String,
    val titulo: String?,
    val texto: String,
    /** Nomes dos sistemas em que a dica aparece; vazio = qualquer um (e o hub). */
    val sistemas: List<String>,
    /** Peso no sorteio (1 a 100). */
    val peso: Int,
)

/** "configuracoes" do JSON de dicas. */
class DicasSettings(
    val habilitado: Boolean = true,
    /** `abertura` (carga inicial de um sistema), `navegacao` (toda página lenta) e/ou `hub` (faixa no launcher). */
    val mostrarEm: Set<String> = setOf("abertura"),
    /** `aleatoria` (com pesos) ou `sequencial`. */
    val ordem: String = "aleatoria",
    val titulo: String = "Dica",
    /** Só mostra a tela de dica se o carregamento passar disso (evita piscar em páginas rápidas). */
    val atrasoMs: Int = 400,
    /** Depois de aparecer, fica pelo menos isso na tela para dar tempo de ler. */
    val duracaoMinimaMs: Int = 1200,
    /** Troca a dica a cada N segundos enquanto durar o carregamento (0 = não troca). */
    val trocarACadaSegundos: Int = 8,
    /** Cores da tela de dica durante o carregamento. */
    val corFundo: String? = null,
    val corTexto: String? = null,
    /** Cores da caixa de dica no hub; se ausentes, valem `corFundo`/`corTexto`. */
    val hubCorFundo: String? = null,
    val hubCorTexto: String? = null,
)

/**
 * Baixa o JSON de dicas (endpoint do bloco "dicas" da lista), guarda em cache (filesDir/dicas.json) e sorteia
 * a próxima dica. Se o endpoint cair ou o JSON for inválido, vale o último cache.
 */
class TipsRepo(private val ctx: Context, private val prefs: Prefs, private val repo: ConfigRepo) {
    private val file = File(ctx.filesDir, "dicas.json")
    private val main = Handler(Looper.getMainLooper())
    private var agendado = false // há uma busca esperando o atraso aleatório (espalharSegundos)
    private var seq = 0

    var settings by mutableStateOf(DicasSettings()); private set
    var dicas by mutableStateOf<List<Dica>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var lastUpdate by mutableStateOf(0L); private set

    /** Há dicas para mostrar: a lista aponta para um endpoint, as dicas estão habilitadas e existe ao menos uma. */
    val ativo: Boolean get() = repo.dicas != null && settings.habilitado && dicas.isNotEmpty()

    fun loadCache() {
        if (!file.exists()) return
        try {
            val (s, d) = parse(file.readText())
            settings = s
            dicas = d
            lastUpdate = file.lastModified()
        } catch (_: Exception) {
        }
    }

    /**
     * Busca as dicas. Mesmas regras da lista, com a regra `atualizacao.dicas`: `force` ignora o intervalo e os gatilhos;
     * `gatilho` é `"abrir"`, `"hub"` ou `"config"` (a lista acabou de chegar: só baixa se o endereço mudou ou se ainda
     * não há nada baixado dele). Endereço novo baixa já; senão vale o intervalo mínimo, guardado no aparelho.
     */
    fun refresh(force: Boolean = false, gatilho: String = "abrir") {
        val cfg = repo.dicas
        if (cfg == null) { // o bloco "dicas" saiu da lista: desliga e apaga o cache
            if (file.exists()) file.delete()
            if (dicas.isNotEmpty()) dicas = emptyList()
            settings = DicasSettings()
            error = null
            prefs.dicasUrlBaixada = ""
            prefs.dicasUrlTentada = ""
            return
        }
        if (loading || agendado) return
        val now = System.currentTimeMillis()
        val last = prefs.dicasTentativaEm
        val regra = repo.atualizacao.dicas
        val urlNova = cfg.url != prefs.dicasUrlTentada
        val semCache = !file.exists() || cfg.url != prefs.dicasUrlBaixada
        fun recente(ms: Long) = last != 0L && (now - last) in 0 until ms // relógio atrasado = não é recente
        when {
            force -> if (recente(5_000)) return
            urlNova -> {} // endereço novo: baixa já
            semCache -> if (recente(regra.intervaloSegundos * 1000L)) return // nada baixado: tenta em qualquer gatilho
            !regra.ativo -> return // verificação automática desligada pela lista (o botão ainda busca)
            gatilho == "config" -> return // a lista chegou e nada mudou nas dicas
            gatilho !in regra.quando -> return
            recente(regra.intervaloSegundos * 1000L) -> return
        }
        prefs.dicasTentativaEm = now
        prefs.dicasUrlTentada = cfg.url
        val atraso = if (force || urlNova || semCache || regra.espalharSegundos <= 0) 0L
        else Random.nextLong(regra.espalharSegundos * 1000L + 1)
        agendado = true
        val token = cfg.token ?: prefs.token.takeIf { it.isNotBlank() && sameHost(cfg.url, prefs.configUrl) }
        thread(name = "hubweb-dicas") {
            if (atraso > 0) try { Thread.sleep(atraso) } catch (_: InterruptedException) {}
            main.post { agendado = false; loading = true }
            try {
                val text = fetch(cfg.url, token)
                val parsed = parse(text) // lança exceção se inválido: o cache antigo fica
                val tmp = File(file.parentFile, "dicas.json.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.delete(); tmp.renameTo(file)
                }
                main.post {
                    settings = parsed.first
                    dicas = parsed.second
                    lastUpdate = System.currentTimeMillis()
                    prefs.dicasUrlBaixada = cfg.url
                    error = null
                    loading = false
                }
            } catch (e: Exception) {
                main.post {
                    error = e.message ?: e.javaClass.simpleName
                    loading = false
                }
            }
        }
    }
    /** Resumo para a tela de configurações. */
    fun resumo(): String = when {
        repo.dicas == null -> "Dicas: desligadas (a lista não define \"dicas\")."
        error != null -> "Dicas: ${dicas.size} em cache · erro: $error"
        lastUpdate > 0 ->
            "Dicas: ${dicas.size} · atualizadas em " +
                SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(lastUpdate))
        loading -> "Dicas: baixando…"
        else -> "Dicas: aguardando o primeiro download."
    }

    /** Próxima dica para um sistema (null = hub); só as que valem para ele. */
    fun next(sistema: String?): Dica? {
        val pool = dicas.filter { d ->
            d.sistemas.isEmpty() || (sistema != null && d.sistemas.any { it.equals(sistema, ignoreCase = true) })
        }
        if (pool.isEmpty()) return null
        if (settings.ordem == "sequencial") return pool[seq++ % pool.size]
        var r = Random.nextInt(pool.sumOf { it.peso })
        for (d in pool) {
            r -= d.peso
            if (r < 0) return d
        }
        return pool.last()
    }

    private fun fetch(url: String, token: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            // POST com os dados do aparelho (veja ConfigRepo): dicas por aparelho na mesma URL.
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!token.isNullOrBlank() && url.startsWith("https://", ignoreCase = true)) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(DeviceInfo.corpoPost(ctx)) }
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

    private fun sameHost(a: String, b: String) =
        runCatching { URL(a).host.equals(URL(b).host, ignoreCase = true) }.getOrDefault(false)

    private companion object {
        const val MAX_BYTES = 2_000_000

        /** Aceita `{"configuracoes": {...}, "dicas": [...]}` ou só a lista de dicas `[...]`. */
        fun parse(text: String): Pair<DicasSettings, List<Dica>> {
            val t = text.trim().removePrefix("﻿")
            val root = if (t.startsWith("[")) null else JSONObject(t)
            val arr = if (root == null) JSONArray(t) else (root.optJSONArray("dicas") ?: JSONArray())
            val c = root?.optJSONObject("configuracoes")

            fun JSONObject.txt(k: String): String? = if (isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }
            fun JSONObject.lista(k: String): List<String> = when (val v = opt(k)) {
                is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
                is String -> v.split(Regex("[,;]+")).map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }

            val mostrarEm = c?.lista("mostrarEm").orEmpty().map { it.lowercase() }
                .filter { it in setOf("abertura", "navegacao", "hub") }.toSet()
            val trocar = c?.optInt("trocarACadaSegundos", 8) ?: 8
            val settings = DicasSettings(
                habilitado = if (c == null || c.isNull("habilitado")) true else c.optBoolean("habilitado", true),
                mostrarEm = mostrarEm.ifEmpty { setOf("abertura") },
                ordem = if (c?.txt("ordem")?.lowercase() == "sequencial") "sequencial" else "aleatoria",
                titulo = c?.txt("titulo") ?: "Dica",
                atrasoMs = (c?.optInt("atrasoMs", 400) ?: 400).coerceIn(0, 5_000),
                duracaoMinimaMs = (c?.optInt("duracaoMinimaMs", 1_200) ?: 1_200).coerceIn(0, 10_000),
                trocarACadaSegundos = if (trocar <= 0) 0 else trocar.coerceIn(3, 120),
                corFundo = c?.txt("corFundo"),
                corTexto = c?.txt("corTexto"),
                hubCorFundo = c?.txt("hubCorFundo"),
                hubCorTexto = c?.txt("hubCorTexto"),
            )

            val dicas = ArrayList<Dica>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val texto = o.txt("texto") ?: continue
                if (!o.isNull("ativa") && !o.optBoolean("ativa", true)) continue
                dicas += Dica(
                    id = o.txt("id") ?: "d$i",
                    titulo = o.txt("titulo"),
                    texto = texto,
                    sistemas = o.lista("sistemas"),
                    peso = o.optInt("peso", 1).coerceIn(1, 100),
                )
            }
            return settings to dicas
        }
    }
}

/**
 * Histórico das dicas mostradas numa caixa, para o usuário avançar e voltar. Avançar no fim do histórico sorteia
 * (ou pega a próxima, no modo sequencial) uma dica nova; voltar revê as anteriores. [versao] muda a cada
 * navegação, para a troca automática reiniciar a contagem. Não tem fim nos dois sentidos.
 */
private class DicaNav(private val tips: TipsRepo, private val sistema: String?) {
    private val hist = mutableStateListOf<Dica>()
    private var idx by mutableStateOf(-1)
    var versao by mutableStateOf(0); private set

    val atual: Dica? get() = hist.getOrNull(idx)

    /** Recomeça o histórico com uma dica nova. */
    fun recomecar() {
        hist.clear(); idx = -1
        avancar(manual = false)
    }

    fun avancar(manual: Boolean = true) {
        if (idx < hist.lastIndex) idx++
        else {
            val n = tips.next(sistema) ?: return
            hist += n
            if (hist.size > 30) hist.removeAt(0)
            idx = hist.lastIndex
        }
        if (manual) versao++
    }

    /** Volta uma dica; no começo do histórico sorteia uma nova (sem fim, como o avançar). */
    fun voltar() {
        if (idx > 0) idx--
        else {
            val n = tips.next(sistema) ?: return
            hist.add(0, n)
            if (hist.size > 30) hist.removeAt(hist.lastIndex)
            idx = 0
        }
        versao++
    }
}

/** Deslizar para a esquerda mostra a próxima dica; para a direita, a anterior. */
private fun Modifier.deslizarDicas(nav: DicaNav): Modifier = pointerInput(nav) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            if (total < -60f) nav.avancar() else if (total > 60f) nav.voltar()
            total = 0f
        },
        onDragCancel = { total = 0f },
    ) { _, dx -> total += dx }
}

private fun parseCor(hex: String?): Color? = try {
    hex?.let { Color(android.graphics.Color.parseColor(it)) }
} catch (_: Exception) {
    null
}

/**
 * Tela de dica sobre um sistema que está carregando (como as dicas das telas de carregamento de jogos).
 * Só aparece se o carregamento passar de `atrasoMs`, fica ao menos `duracaoMinimaMs` e some quando a página
 * termina. Tocar nela a dispensa para aquele carregamento. Colocar dentro do Box que contém o WebView.
 */
@Composable
fun TipOverlay(tips: TipsRepo, entry: Entry) {
    val s = tips.settings
    var visible by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val nav = remember(entry.item.nome) { DicaNav(tips, entry.item.nome) }
    var shownAt by remember { mutableStateOf(0L) }

    LaunchedEffect(entry.loading) {
        if (entry.loading) {
            val quer = tips.ativo && ("navegacao" in s.mostrarEm || ("abertura" in s.mostrarEm && !entry.firstLoadDone))
            if (!quer) return@LaunchedEffect
            dismissed = false
            delay(s.atrasoMs.toLong())
            if (entry.loading) {
                nav.recomecar()
                visible = nav.atual != null
                shownAt = SystemClock.elapsedRealtime()
            }
        } else if (visible) {
            val resto = s.duracaoMinimaMs - (SystemClock.elapsedRealtime() - shownAt)
            if (resto > 0) delay(resto)
            visible = false
        }
    }

    // Enquanto durar o carregamento, troca a dica de tempos em tempos.
    LaunchedEffect(visible, nav.versao) {
        while (visible && s.trocarACadaSegundos > 0) {
            delay(s.trocarACadaSegundos * 1000L)
            if (visible) nav.avancar(manual = false)
        }
    }

    val d = nav.atual
    if (visible && !dismissed && d != null) {
        val fundo = parseCor(s.corFundo) ?: Color(0xFF1A237E)
        val texto = parseCor(s.corTexto) ?: Color.White
        Box(
            Modifier.fillMaxSize().background(fundo).deslizarDicas(nav).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { dismissed = true },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Abrindo ${entry.item.nome}", color = texto.copy(alpha = 0.7f), fontSize = 13.sp)
                Text(d.titulo ?: s.titulo, color = texto, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(d.texto, color = texto, fontSize = 20.sp, textAlign = TextAlign.Center, lineHeight = 28.sp)
                CircularProgressIndicator(Modifier.size(22.dp), color = texto, strokeWidth = 2.dp)
                Text("Deslize para ver outras dicas · toque para ver a página", color = texto.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Caixa com uma dica no hub, acima do rodapé (quando `mostrarEm` inclui `hub`); só dicas sem restrição de sistema. */
@Composable
fun TipBanner(tips: TipsRepo) {
    val s = tips.settings
    if (!tips.ativo || "hub" !in s.mostrarEm) return
    val nav = remember(tips.dicas) { DicaNav(tips, null).also { it.recomecar() } }
    LaunchedEffect(tips.dicas, s.trocarACadaSegundos, nav.versao) {
        while (s.trocarACadaSegundos > 0) {
            delay(s.trocarACadaSegundos * 1000L)
            nav.avancar(manual = false)
        }
    }
    nav.atual?.let { d ->
        // Caixa destacada de cantos arredondados; cores do hub, senão as da tela de carregamento, senão o padrão.
        val fundo = parseCor(s.hubCorFundo) ?: parseCor(s.corFundo) ?: Color(0xFF1A237E)
        val texto = parseCor(s.hubCorTexto) ?: parseCor(s.corTexto) ?: Color.White
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .background(fundo, RoundedCornerShape(12.dp))
                .deslizarDicas(nav)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(d.titulo ?: s.titulo, color = texto, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(d.texto, color = texto, fontSize = 14.sp)
        }
    }
}
