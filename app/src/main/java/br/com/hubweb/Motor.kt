package br.com.hubweb

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Escolha do motor de navegação de cada sistema. "webview" e "gecko" são fixos; "auto" usa o Gecko só quando o WebView
 * do aparelho é mais antigo que `webviewMinimo` (versão principal do Chrome; padrão 80) e o Gecko existe nesta build
 * e neste aparelho (arm64). Se a versão do WebView não puder ser lida, fica no WebView.
 */
object Motor {
    const val MINIMO_PADRAO = 80

    private var lida = false
    private var versao: String = ""

    /** Versão completa do WebView do sistema (ex.: "66.0.3359.158"); vazio se não der para saber. */
    fun versaoWebView(ctx: Context): String {
        if (lida) return versao
        versao = runCatching {
            val pkg = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName else null
            pkg?.takeIf { it.isNotBlank() }
                ?: Regex("""Chrome/([\d.]+)""").find(WebSettings.getDefaultUserAgent(ctx))?.groupValues?.get(1).orEmpty()
        }.getOrDefault("")
        lida = true
        return versao
    }

    fun principalWebView(ctx: Context): Int =
        versaoWebView(ctx).takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

    fun usaGecko(ctx: Context, item: AppItem): Boolean {
        if (!MotorGecko.disponivel) return false
        return when (item.motor) {
            "gecko" -> true
            "auto" -> principalWebView(ctx) < item.webviewMinimo
            else -> false
        }
    }
}
