package br.com.hubweb

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.webkit.WebView
import org.json.JSONObject

/**
 * Digita um texto no campo focado da página como um teclado (igual a um leitor de código de barras em
 * modo teclado): eventos de tecla reais, para as páginas que escutam keydown/keypress, e depois Enter ou Tab.
 *
 * Alguns campos (numéricos, máscaras) ignoram teclas simuladas. Por isso o app confere o valor do campo
 * depois de digitar e, se nada mudou, grava o texto direto no campo e dispara os eventos input/change.
 */
object KeyInjector {
    /** Valor do campo focado (ou texto, se editável); "null" quando não há campo focado. */
    private const val READ_JS =
        "(function(){var e=document.activeElement;if(!e)return null;if('value' in e)return String(e.value);" +
            "return e.isContentEditable?e.textContent:null})()"

    fun type(webView: WebView, raw: String, suffix: String) {
        // Quebras de linha e outros caracteres de controle (comuns no fim de QR codes) não são digitados.
        val text = raw.filterNot { it.isISOControl() }.trim()
        if (text.isEmpty()) return
        webView.requestFocus()
        webView.evaluateJavascript(READ_JS) { before ->
            val canVerify = before != null && before != "null"
            val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            if (events == null) {
                // Caracteres que o teclado virtual não mapeia (acentos, símbolos raros): grava direto.
                insertValue(webView, text)
                sendSuffix(webView, suffix, 150)
                return@evaluateJavascript
            }
            events.forEach { webView.dispatchKeyEvent(it) }
            webView.postDelayed({
                if (!canVerify) {
                    sendSuffix(webView, suffix, 0)
                    return@postDelayed
                }
                webView.evaluateJavascript(READ_JS) { after ->
                    var wait = 0L
                    if (after == before) { // as teclas não alteraram o campo
                        insertValue(webView, text)
                        wait = 150
                    }
                    sendSuffix(webView, suffix, wait)
                }
            }, 250)
        }
    }

    private fun insertValue(webView: WebView, text: String) {
        webView.evaluateJavascript(
            """(function(t){var e=document.activeElement;if(!e)return;
if(e.tagName==='INPUT'||e.tagName==='TEXTAREA'){
var p=e.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;
Object.getOwnPropertyDescriptor(p,'value').set.call(e,t);
e.dispatchEvent(new Event('input',{bubbles:true}));
e.dispatchEvent(new Event('change',{bubbles:true}));
}else if(e.isContentEditable){document.execCommand('insertText',false,t);}
})(${JSONObject.quote(text)})""",
            null,
        )
    }

    private fun sendSuffix(webView: WebView, suffix: String, delayMs: Long) {
        val code = when (suffix) {
            "enter" -> KeyEvent.KEYCODE_ENTER
            "tab" -> KeyEvent.KEYCODE_TAB
            else -> return
        }
        webView.postDelayed({
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }, delayMs)
    }
}
