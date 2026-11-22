package br.com.hubweb

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Um WebView vivo e o último erro de carregamento da página principal. */
class Entry(val item: AppItem) {
    lateinit var webView: WebView
    var error by mutableStateOf<String?>(null)

    /** Uma página (documento principal) está carregando; alimenta a tela de dicas. */
    var loading by mutableStateOf(false)

    /** A primeira página já terminou de carregar desde que este sistema foi aberto. */
    var firstLoadDone by mutableStateOf(false)

    /** A urlInicial (só variáveis estáveis) no último carregamento; mudou = volta à página inicial. */
    var chave: String? = null
}

/**
 * Mantém até `prefs.maxAlive` WebViews vivos (o menos recente é descartado primeiro).
 * Todos compartilham o mesmo CookieManager, então as sessões valem entre os sistemas.
 */
class WebPool(
    private val activity: MainActivity,
    private val prefs: Prefs,
    private val tracker: UserTracker,
) {
    private val entries = LinkedHashMap<String, Entry>() // mais antigo primeiro
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Script de detecção de usuário (res/raw/hubweb_user.js); o bloco "usuario" do item entra no lugar de __CFG__. */
    private val userScript: String by lazy {
        activity.resources.openRawResource(R.raw.hubweb_user).bufferedReader().use { it.readText() }
    }

    /**
     * Abre (ou reaproveita) o sistema. [abrirEm] (opcional, de `abrirAoEntrar.url`) é a página em que ele abre no lugar da
     * `urlInicial`; só vale se o item permitir esse endereço (`urlPermitida`), senão abre na `urlInicial`.
     */
    fun open(item: AppItem, abrirEm: String? = null): Entry {
        var entry = entries.remove(item.id)
        if (entry != null && entry.item != item) {
            destroy(entry)
            entry = null
        }
        val destino = abrirEm?.let { UrlVars.resolve(it, activity, item) }
            ?.takeIf { u -> runCatching { Uri.parse(u).let { item.allows(it) && item.schemeAllowed(it) } }.getOrDefault(false) }
        val e = entry ?: create(item, destino)
        if (entry != null && destino != null) e.webView.loadUrl(destino) // já estava aberto: vai para a página pedida
        entries[item.id] = e
        while (entries.size > prefs.maxAlive.coerceAtLeast(1)) {
            val oldest = entries.values.first { it !== e }
            entries.remove(oldest.item.id)
            destroy(oldest)
        }
        return e
    }

    /**
     * Para cada sistema com `recarregarAoMudar`: se alguma variável estável da urlInicial mudou (usuário, rede, nome...),
     * volta à urlInicial com os valores novos. Chamado a cada 15 s e ao voltar ao app.
     */
    fun verificarMudancas() {
        (entries.values + popups.values).forEach { e ->
            if (!e.item.recarregarAoMudar) return@forEach
            val nova = UrlVars.chaveEstavel(e.item, activity) ?: return@forEach
            if (nova == e.chave) return@forEach
            e.chave = nova
            val url = e.item.startUrl(activity)
            if (e.item.schemeAllowed(Uri.parse(url))) e.webView.loadUrl(url)
        }
    }

    /** Sistemas na memória e o estado de cada um (telemetria): carregando, primeira carga feita e último erro. */
    fun resumo(): org.json.JSONArray = org.json.JSONArray().apply {
        entries.values.forEach { e ->
            put(
                org.json.JSONObject().put("sistema", e.item.nome).put("carregando", e.loading)
                    .put("carregado", e.firstLoadDone).put("erro", e.error ?: org.json.JSONObject.NULL),
            )
        }
    }

    /** WebViews dos botões flutuantes (popup): separados do limite `maxAlive` dos sistemas. */
    private val popups = LinkedHashMap<String, Entry>()

    fun popup(item: AppItem): Entry {
        var entry = popups[item.id]
        if (entry != null && entry.item != item) {
            destroy(entry)
            popups.remove(item.id)
            entry = null
        }
        return entry ?: create(item).also { popups[item.id] = it }
    }

    fun setPopupActive(active: Entry?) {
        popups.values.forEach { if (it === active) it.webView.onResume() else it.webView.onPause() }
    }

    /** O ativo continua rodando; os demais ficam pausados (economiza bateria, mantém a página). */
    fun setActive(active: Entry?) {
        entries.values.forEach { if (it === active) it.webView.onResume() else it.webView.onPause() }
    }

    fun pauseAll() {
        setActive(null)
        setPopupActive(null)
    }

    fun destroyAll() {
        entries.values.forEach(::destroy)
        entries.clear()
        popups.values.forEach(::destroy)
        popups.clear()
    }

    private fun destroy(e: Entry) {
        (e.webView.parent as? ViewGroup)?.removeView(e.webView)
        e.webView.stopLoading()
        e.webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(item: AppItem, abrirEm: String? = null): Entry {
        val entry = Entry(item)
        val wv = WebView(activity)
        entry.webView = wv

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = item.zoom
            item.userAgent?.let { userAgentString = it }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        if (!item.permitirCopiarColar) {
            wv.isLongClickable = false
            wv.setOnLongClickListener { true } // sem seleção de texto / menu de contexto
        }

        // Identificação do usuário por script na página (todos os tipos, menos "cracha", que é feito pelo app).
        // Um script por detecção (um sistema pode ter várias); cada um carrega o seu bloco e o seu índice.
        val userJs: List<String> = item.usuarios.filter { it.tipo != "cracha" }
            .map { userScript.replace("__CFG__", it.json) }
        if (userJs.isNotEmpty()) wv.addJavascriptInterface(UserBridge(item, tracker, main), "HubWebUser")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val ok = item.allows(request.url)
                if (!ok) {
                    toast("Bloqueado: ${request.url.toString().substringBefore('?').take(120)}", long = true)
                }
                return !ok // true = bloqueia
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                entry.error = null
                entry.loading = true
                injectNoCopy(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                entry.loading = false
                entry.firstLoadDone = true
                injectNoCopy(view)
            }

            private fun injectNoCopy(view: WebView) {
                if (!item.permitirCopiarColar) view.evaluateJavascript(NO_COPY_JS, null)
                // Só em páginas permitidas do item (o script também recusa rodar em iframes).
                if (userJs.isNotEmpty() && item.allows(Uri.parse(view.url ?: return))) {
                    userJs.forEach { view.evaluateJavascript(it, null) }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    entry.error = error.description?.toString() ?: "Falha ao carregar"
                    entry.loading = false
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // nunca ignora erro de certificado
                if (error.url == view.url || error.url == view.originalUrl) entry.error = "Certificado inválido"
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                activity.runOnUiThread {
                    val wantsCamera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
                    if (!item.permitirCamera || !wantsCamera || !item.allows(request.origin)) {
                        request.deny()
                        return@runOnUiThread
                    }
                    activity.withCameraPermission { granted ->
                        if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        else request.deny()
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                if (!item.permitirUpload) {
                    callback.onReceiveValue(null)
                    toast("Upload bloqueado")
                    return true
                }
                return activity.pickFile(params, callback)
            }
        }

        wv.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mime, _ ->
            val uri = Uri.parse(url)
            if (!item.permitirDownload || !item.allows(uri)) {
                toast("Download bloqueado")
                return@DownloadListener
            }
            if (Build.VERSION.SDK_INT < 29 && !activity.hasStoragePermission()) {
                activity.requestStoragePermission()
                toast("Autorize o armazenamento e tente de novo")
                return@DownloadListener
            }
            try {
                val req = DownloadManager.Request(uri).apply {
                    setMimeType(mime)
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    addRequestHeader("User-Agent", userAgent)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mime),
                    )
                }
                (activity.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Baixando…")
            } catch (e: Exception) {
                toast("Falha no download: ${e.message}")
            }
        })

        entry.chave = UrlVars.chaveEstavel(item, activity)
        val startUrl = abrirEm ?: item.startUrl(activity) // aplica {nome}, {ip}, ... da urlInicial
        if (item.schemeAllowed(Uri.parse(startUrl))) wv.loadUrl(startUrl)
        else entry.error = "URL inicial usa HTTP, mas permitirHttp está desligado"
        return entry
    }

    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(activity, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private companion object {
        /** Bloqueia seleção, copiar, recortar, colar e menu de contexto (campos de texto continuam editáveis). */
        const val NO_COPY_JS = """(function(){
if(window.__hwNoCopy)return;window.__hwNoCopy=true;
var s=document.createElement('style');
s.textContent='*{-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important}input,textarea,[contenteditable=true]{-webkit-user-select:text!important;user-select:text!important}';
(document.head||document.documentElement).appendChild(s);
['copy','cut','paste','contextmenu','dragstart'].forEach(function(t){
document.addEventListener(t,function(e){e.preventDefault();e.stopPropagation();},true);});
})();"""
    }
}
