package br.com.hubweb

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * PROTÓTIPO do motor GeckoView (Firefox embutido), para aparelhos cujo WebView do sistema é antigo demais.
 * Por enquanto: abre a urlInicial, navega, volta, atualiza e mantém uma sessão por sistema (o login fica; os cookies
 * também sobrevivem ao fechar o app). Ainda NÃO tem: detecção de usuário, leitor de código, upload, download, câmera,
 * botões flutuantes nem barra configurável.
 */
object MotorGecko {
    // O Gecko embutido é só arm64-v8a: em outros aparelhos (32 bits, emulador x86) o item abre no WebView.
    val disponivel: Boolean = android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")

    private class Sessao(val session: GeckoSession) {
        var podeVoltar by mutableStateOf(false)
    }

    /** Quem recebe os usuários detectados (definido pela MainActivity). */
    var tracker: UserTracker? = null

    private var runtime: GeckoRuntime? = null
    private var extensao: WebExtension? = null
    private var extensaoPronta = false
    private val aguardando = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val sessoes = HashMap<String, Sessao>()
    private var atual: Sessao? = null

    private fun runtime(ctx: Context): GeckoRuntime = runtime ?: GeckoRuntime.create(
        ctx.applicationContext,
        GeckoRuntimeSettings.Builder().remoteDebuggingEnabled(BuildConfig.DEBUG).build(),
    ).also { runtime = it }

    /** Instala (uma vez) a extensão interna que detecta o usuário; quem chamar espera ela ficar pronta (ou falhar). */
    private fun comExtensao(ctx: Context, acao: () -> Unit) {
        if (extensaoPronta) { acao(); return }
        val primeira = aguardando.isEmpty()
        aguardando += acao
        if (!primeira) return
        fun liberar() {
            extensaoPronta = true
            val l = ArrayList(aguardando); aguardando.clear(); l.forEach { it() }
        }
        runtime(ctx).webExtensionController
            .ensureBuiltIn("resource://android/assets/hubweb-ext/", "hubweb@hubweb.com.br")
            .accept({ ext -> extensao = ext; liberar() }, { _ -> liberar() })
    }

    private fun delegado(item: AppItem) = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val m = message as? JSONObject ?: return null
            val href = m.optString("href")
            if (!runCatching { item.allows(Uri.parse(href)) }.getOrDefault(false)) return null
            when (m.optString("t")) {
                "cfg" -> {
                    val arr = JSONArray()
                    item.usuarios.filter { it.tipo != "cracha" }.forEach { arr.put(JSONObject(it.json)) }
                    return GeckoResult.fromValue(JSONObject().put("cfgs", arr) as Any)
                }
                "report" -> {
                    val idx = m.optInt("idx", -1)
                    val cfg = item.usuarios.getOrNull(idx) ?: return null
                    val login = m.optString("user").trim().take(200)
                    val origem = m.optString("fonte").ifEmpty { cfg.tipo }
                    val novo = m.optBoolean("novo", false)
                    main.post { tracker?.report(item, idx, login, origem, novo) }
                }
            }
            return null
        }
    }

    // O popup (botão flutuante) tem sessão própria: o mesmo sistema pode estar aberto na tela e no popup ao mesmo tempo.
    private fun sessao(ctx: Context, item: AppItem, urlInicial: String, popup: Boolean = false): Sessao =
        sessoes.getOrPut((if (popup) "popup:" else "") + item.id) {
        val settings = GeckoSessionSettings.Builder().apply { item.userAgent?.let { userAgentOverride(it) } }.build()
        val s = GeckoSession(settings)
        val nova = Sessao(s)
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                nova.podeVoltar = canGoBack
            }
        }
        s.open(runtime(ctx))
        // Só carrega a página depois que a extensão estiver instalada, para ela valer já na primeira página.
        comExtensao(ctx) {
            extensao?.let { s.webExtensionController.setMessageDelegate(it, delegado(item), "hubweb") }
            s.loadUri(urlInicial)
        }
        nova
    }

    // A GeckoView que está na tela agora (a da tela ou a do popup): é nela que o leitor do coletor "digita".
    private var viewAtiva: GeckoView? = null

    /**
     * Digita o texto no campo focado da página, como um teclado (o leitor do coletor entrega o código por Ctrl+V,
     * que o app converte em teclas). Retorna false se não há página do Gecko na tela ou se o texto tem caracteres
     * que o teclado virtual não mapeia.
     */
    fun digitar(texto: String): Boolean {
        val v = viewAtiva ?: return false
        val eventos = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(texto.toCharArray()) ?: return false
        v.requestFocus()
        eventos.forEach { v.dispatchKeyEvent(it) }
        return true
    }

    /** Volta uma página; false se não houver para onde voltar (aí o app fecha o sistema). */
    fun voltar(): Boolean {
        val a = atual ?: return false
        if (!a.podeVoltar) return false
        a.session.goBack()
        return true
    }

    /** Popup do botão flutuante no Gecko: Voltar navega para trás e só fecha sem histórico. */
    @Composable
    fun Popup(item: AppItem, urlInicial: String, onClose: () -> Unit) {
        val ctx = LocalContext.current
        val s = remember(item.id) { sessao(ctx, item, urlInicial, popup = true) }
        Dialog(
            onDismissRequest = { if (s.podeVoltar) s.session.goBack() else onClose() },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        ) {
            Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f), shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).height(44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.nome, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        if (item.barraAtualizar) IconButton(onClick = { s.session.reload() }) { Icon(Icons.Filled.Refresh, "Atualizar página") }
                        if (item.barraInicio) IconButton(onClick = { s.session.loadUri(urlInicial) }) { Icon(Icons.Filled.Home, "Página inicial") }
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Fechar") }
                    }
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { c -> GeckoView(c).apply { setSession(s.session); viewAtiva = this } },
                        onRelease = { if (viewAtiva === it) viewAtiva = null; it.releaseSession() },
                    )
                }
            }
        }
    }

    @Composable
    fun Tela(item: AppItem, urlInicial: String, onLauncher: () -> Unit) {
        val ctx = LocalContext.current
        val s = remember(item.id) { sessao(ctx, item, urlInicial) }
        DisposableEffect(s) {
            atual = s
            onDispose { if (atual === s) atual = null }
        }
        // A mesma barra do WebView (posição, botões e visibilidade vêm do item).
        TelaComBarra(
            item = item,
            onLauncher = onLauncher,
            onBack = { if (s.podeVoltar) s.session.goBack() },
            onReload = { s.session.reload() },
            onHome = { s.session.loadUri(urlInicial) },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c -> GeckoView(c).apply { setSession(s.session); viewAtiva = this } },
                onRelease = { if (viewAtiva === it) viewAtiva = null; it.releaseSession() }, // a sessão continua viva para quando o sistema for aberto de novo
            )
        }
    }
}
