package br.com.hubweb

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import org.mozilla.geckoview.GeckoRuntime
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

    private var runtime: GeckoRuntime? = null
    private val sessoes = HashMap<String, Sessao>()
    private var atual: Sessao? = null

    private fun runtime(ctx: Context): GeckoRuntime = runtime ?: GeckoRuntime.create(
        ctx.applicationContext,
        GeckoRuntimeSettings.Builder().remoteDebuggingEnabled(BuildConfig.DEBUG).build(),
    ).also { runtime = it }

    private fun sessao(ctx: Context, item: AppItem, urlInicial: String): Sessao = sessoes.getOrPut(item.id) {
        val settings = GeckoSessionSettings.Builder().apply { item.userAgent?.let { userAgentOverride(it) } }.build()
        val s = GeckoSession(settings)
        val nova = Sessao(s)
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                nova.podeVoltar = canGoBack
            }
        }
        s.open(runtime(ctx))
        s.loadUri(urlInicial)
        nova
    }

    /** Volta uma página; false se não houver para onde voltar (aí o app fecha o sistema). */
    fun voltar(): Boolean {
        val a = atual ?: return false
        if (!a.podeVoltar) return false
        a.session.goBack()
        return true
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
                factory = { c -> GeckoView(c).apply { setSession(s.session) } },
                onRelease = { it.releaseSession() }, // a sessão continua viva para quando o sistema for aberto de novo
            )
        }
    }
}
