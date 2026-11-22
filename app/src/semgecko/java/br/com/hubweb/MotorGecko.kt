package br.com.hubweb

import androidx.compose.runtime.Composable

/**
 * Substituto vazio do motor GeckoView: esta build do HubWeb não o inclui (é a build compilada com -PsemGecko).
 * Os itens com "motor": "gecko" abrem no WebView normal.
 */
object MotorGecko {
    const val disponivel = false
    var tracker: UserTracker? = null

    @Composable
    fun Popup(item: AppItem, urlInicial: String, onClose: () -> Unit) {
    }

    @Composable
    fun Tela(item: AppItem, urlInicial: String, onLauncher: () -> Unit) {
    }

    /** Volta uma página; false se não houver para onde voltar (aí o app fecha o sistema). */
    fun voltar(): Boolean = false
}
