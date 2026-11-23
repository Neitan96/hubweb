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
import android.app.AlertDialog
import android.widget.EditText
import android.widget.Toast
import org.mozilla.geckoview.WebResponse
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
        var porta: WebExtension.Port? = null // canal com o relay.js da página atual (digitar o que o teclado não mapeia)
    }

    /** A MainActivity (arquivos, permissões do Android e avisos); definida por ela. */
    var activity: MainActivity? = null

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


    private fun aviso(msg: String) {
        activity?.let { a -> a.runOnUiThread { Toast.makeText(a, msg, Toast.LENGTH_LONG).show() } }
    }

    // ---- download: o Gecko entrega o corpo da resposta; o app grava em Downloads (os cookies do Gecko não existem no Android).
    private fun baixar(item: AppItem, r: WebResponse) {
        val a = activity ?: return
        if (!item.permitirDownload || !runCatching { item.allows(Uri.parse(r.uri)) }.getOrDefault(false)) {
            AppLog.registrar("download_bloqueado", item.nome, "permitirDownload desligado ou URL fora de urlPermitida", r.uri)
            aviso("Download bloqueado"); return
        }
        if (android.os.Build.VERSION.SDK_INT < 29 && !a.hasStoragePermission()) {
            a.runOnUiThread { a.requestStoragePermission() }
            aviso("Autorize o armazenamento e tente de novo"); return
        }
        val cab = r.headers.entries.associate { it.key.lowercase() to it.value }
        val mime = cab["content-type"]?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "application/octet-stream"
        val nome = android.webkit.URLUtil.guessFileName(r.uri, cab["content-disposition"], mime)
        aviso("Baixando $nome…")
        Thread {
            try {
                r.body?.use { entrada -> gravar(a, nome, mime, entrada) } ?: throw IllegalStateException("sem conteúdo")
                aviso("Salvo em Downloads: $nome")
            } catch (e: Exception) {
                aviso("Falha no download: ${e.message}")
            }
        }.start()
    }

    private fun gravar(a: Context, nome: String, mime: String, entrada: java.io.InputStream) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, nome)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = a.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("não foi possível criar o arquivo")
            a.contentResolver.openOutputStream(uri)!!.use { entrada.copyTo(it) }
        } else {
            val pasta = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            pasta.mkdirs()
            val base = nome.substringBeforeLast('.', nome)
            val ext = if ('.' in nome) "." + nome.substringAfterLast('.') else ""
            var f = java.io.File(pasta, nome)
            var n = 1
            while (f.exists()) f = java.io.File(pasta, "$base (${n++})$ext")
            f.outputStream().use { entrada.copyTo(it) }
            android.media.MediaScannerConnection.scanFile(a, arrayOf(f.absolutePath), null, null)
        }
    }

    // ---- upload e caixas de diálogo (alert/confirm/prompt) da página
    private fun prompts(item: AppItem) = object : GeckoSession.PromptDelegate {
        override fun onFilePrompt(session: GeckoSession, p: GeckoSession.PromptDelegate.FilePrompt):
            GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val a = activity
            if (a == null || !item.permitirUpload) {
                if (a != null) { aviso("Upload bloqueado"); AppLog.registrar("upload_bloqueado", item.nome, "permitirUpload desligado") }
                return GeckoResult.fromValue(p.dismiss())
            }
            val r = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            a.runOnUiThread {
                val multiplo = p.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
                a.pickFileGecko(p.mimeTypes ?: emptyArray(), multiplo) { uris ->
                    r.complete(if (uris == null || uris.isEmpty()) p.dismiss() else p.confirm(a, uris))
                }
            }
            return r
        }

        override fun onAlertPrompt(session: GeckoSession, p: GeckoSession.PromptDelegate.AlertPrompt):
            GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            dialogo(p.title, p.message, false, { p.dismiss() }, { p.dismiss() })

        override fun onButtonPrompt(session: GeckoSession, p: GeckoSession.PromptDelegate.ButtonPrompt):
            GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
            dialogo(p.title, p.message, true,
                { p.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE) },
                { p.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE) })

        override fun onTextPrompt(session: GeckoSession, p: GeckoSession.PromptDelegate.TextPrompt):
            GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val a = activity ?: return GeckoResult.fromValue(p.dismiss())
            val r = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            a.runOnUiThread {
                val campo = EditText(a).apply { setText(p.defaultValue.orEmpty()); setSingleLine() }
                val b = AlertDialog.Builder(a).setMessage(p.message).setView(campo)
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.complete(p.confirm(campo.text.toString())) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> r.complete(p.dismiss()) }
                    .setOnCancelListener { r.complete(p.dismiss()) }
                if (!p.title.isNullOrEmpty()) b.setTitle(p.title)
                b.show()
            }
            return r
        }
    }

    private fun dialogo(
        titulo: String?, msg: String?, comCancelar: Boolean,
        ok: () -> GeckoSession.PromptDelegate.PromptResponse,
        cancelar: () -> GeckoSession.PromptDelegate.PromptResponse,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val a = activity ?: return GeckoResult.fromValue(cancelar())
        val r = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        a.runOnUiThread {
            val b = AlertDialog.Builder(a).setMessage(msg).setPositiveButton(android.R.string.ok) { _, _ -> r.complete(ok()) }
            if (!titulo.isNullOrEmpty()) b.setTitle(titulo)
            if (comCancelar) b.setNegativeButton(android.R.string.cancel) { _, _ -> r.complete(cancelar()) }
            b.setOnCancelListener { r.complete(cancelar()) }
            b.show()
        }
        return r
    }

    // ---- câmera (getUserMedia) e permissões do Android pedidas pelo Gecko
    private fun permissoes(item: AppItem) = object : GeckoSession.PermissionDelegate {
        override fun onMediaPermissionRequest(
            session: GeckoSession, uri: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            val a = activity
            val cam = video?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA }
                ?: video?.firstOrNull()
            val liberado = a != null && cam != null && item.permitirCamera &&
                runCatching { item.allows(Uri.parse(uri)) }.getOrDefault(false)
            if (!liberado) { callback.reject(); return }
            a!!.runOnUiThread { a.withCameraPermission { ok -> if (ok) callback.grant(cam, null) else callback.reject() } }
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession, perms: Array<String>?, callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            val a = activity
            // Só a câmera é liberada (e só se o sistema tem permitirCamera); o resto é negado.
            if (a == null || perms == null || perms.any { it != android.Manifest.permission.CAMERA } || !item.permitirCamera) {
                callback.reject(); return
            }
            a.runOnUiThread { a.withCameraPermission { ok -> if (ok) callback.grant() else callback.reject() } }
        }

        override fun onContentPermissionRequest(
            session: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? = GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
    }

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

    private fun delegado(item: AppItem, nova: Sessao) = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            val href = port.sender.url
            if (!runCatching { item.allows(Uri.parse(href)) }.getOrDefault(false)) { port.disconnect(); return }
            nova.porta = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onDisconnect(port: WebExtension.Port) { if (nova.porta === port) nova.porta = null }
            })
        }

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

            override fun onLoadError(session: GeckoSession, uri: String?, error: org.mozilla.geckoview.WebRequestError):
                GeckoResult<String>? {
                AppLog.registrar("rede", item.nome, "erro ${error.code} (categoria ${error.category})", uri)
                return null
            }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) = baixar(item, response)
            override fun onCrash(session: GeckoSession) = AppLog.registrar("motor_crash", item.nome, "o processo do Gecko caiu")
            override fun onKill(session: GeckoSession) = AppLog.registrar("motor_crash", item.nome, "o Android encerrou o Gecko (pouca memória)")
        }
        s.promptDelegate = prompts(item)
        s.permissionDelegate = permissoes(item)
        s.open(runtime(ctx))
        // Só carrega a página depois que a extensão estiver instalada, para ela valer já na primeira página.
        comExtensao(ctx) {
            extensao?.let { s.webExtensionController.setMessageDelegate(it, delegado(item, nova), "hubweb") }
            s.loadUri(urlInicial)
        }
        nova
    }

    // A GeckoView que está na tela agora (a da tela ou a do popup): é nela que o leitor do coletor "digita".
    private var viewAtiva: GeckoView? = null
    private var sessaoAtiva: Sessao? = null

    /**
     * Digita o texto no campo focado da página, como um teclado (o leitor do coletor entrega o código por Ctrl+V,
     * que o app converte em teclas). Retorna false se não há página do Gecko na tela ou se o texto tem caracteres
     * que o teclado virtual não mapeia.
     */
    fun digitar(texto: String): Boolean {
        val v = viewAtiva ?: return false
        val eventos = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(texto.toCharArray())
        if (eventos != null) {
            v.requestFocus()
            eventos.forEach { v.dispatchKeyEvent(it) }
            return true
        }
        // Acentos e símbolos que o teclado virtual não mapeia: a extensão grava o texto no campo focado da página.
        val porta = sessaoAtiva?.porta ?: return false
        porta.postMessage(JSONObject().put("t", "digitar").put("texto", texto))
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
                        factory = { c -> GeckoView(c).apply { setSession(s.session); viewAtiva = this; sessaoAtiva = s } },
                        onRelease = { if (viewAtiva === it) { viewAtiva = null; sessaoAtiva = null }; it.releaseSession() },
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
                factory = { c -> GeckoView(c).apply { setSession(s.session); viewAtiva = this; sessaoAtiva = s } },
                onRelease = { if (viewAtiva === it) { viewAtiva = null; sessaoAtiva = null }; it.releaseSession() }, // a sessão continua viva para quando o sistema for aberto de novo
            )
        }
    }
}
