package br.com.hubweb

import android.content.Context
import android.content.RestrictionsManager

/**
 * Configuração gerenciada (Android Enterprise): o MDM envia ao app a URL da lista e o token, para o aparelho já sair
 * configurado. As chaves estão declaradas em res/xml/app_restrictions.xml, de onde o console do MDM monta o formulário.
 * Quando o MDM define a URL (e/ou o token), ela vale no lugar da digitada nas Configurações, que ficam travadas.
 */
object ManagedConfig {
    const val CHAVE_URL = "configUrl"
    const val CHAVE_TOKEN = "token"

    /** Lê o que o MDM definiu e guarda em [Prefs]. Retorna true se mudou em relação ao que já estava guardado. */
    fun apply(ctx: Context, prefs: Prefs): Boolean {
        val b = try {
            ctx.getSystemService(RestrictionsManager::class.java)?.applicationRestrictions
        } catch (_: Exception) {
            null
        }
        val url = b?.getString(CHAVE_URL)?.trim().orEmpty()
            .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }.orEmpty()
        val token = b?.getString(CHAVE_TOKEN)?.trim().orEmpty()
        val mudou = url != prefs.managedUrl || token != prefs.managedToken
        prefs.managedUrl = url
        prefs.managedToken = token
        return mudou
    }
}
