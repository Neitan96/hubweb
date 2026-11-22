package br.com.hubweb

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DeviceInfo {
    /** Variáveis aceitas no rodapé e na urlInicial ({atualizacao} só existe no rodapé). */
    private val URL_VARS = listOf("{nome}", "{ip}", "{modelo}", "{android}", "{app}", "{id}")

    /** Nome definido pelo usuário no Android; se vazio, nome do Bluetooth; por fim fabricante + modelo. */
    fun name(ctx: Context): String {
        val cr = ctx.contentResolver
        return listOf(
            runCatching { Settings.Global.getString(cr, "device_name") }.getOrNull(),
            runCatching { Settings.Secure.getString(cr, "bluetooth_name") }.getOrNull(),
        ).firstOrNull { !it.isNullOrBlank() } ?: model()
    }

    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Identificador estável do aparelho para este app (muda em reset de fábrica ou troca da chave de assinatura). */
    fun androidId(ctx: Context): String =
        runCatching { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty()

    /** IPv4 local (prefere o Wi-Fi); null se não houver rede. */
    fun ipv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun appVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    private fun value(key: String, ctx: Context, lastUpdate: Long, forUrl: Boolean): String = when (key) {
        "{nome}" -> name(ctx)
        "{ip}" -> ipv4() ?: if (forUrl) "" else "sem rede"
        "{modelo}" -> model()
        "{android}" -> Build.VERSION.RELEASE.orEmpty()
        "{app}" -> appVersion(ctx)
        "{id}" -> androidId(ctx)
        else -> if (lastUpdate > 0) SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(lastUpdate)) else "-"
    }

    /**
     * Corpo do POST da lista e das dicas: {"dispositivo": {nome, id, modelo, android, app}}, com os mesmos nomes e
     * valores da chave "dispositivo" da telemetria. Assim o servidor pode devolver uma lista diferente por aparelho
     * usando a mesma URL.
     */
    fun corpoPost(ctx: Context): ByteArray = org.json.JSONObject().put(
        "dispositivo",
        org.json.JSONObject()
            .put("nome", name(ctx))
            .put("id", androidId(ctx))
            .put("modelo", model())
            .put("android", Build.VERSION.RELEASE.orEmpty())
            .put("app", appVersion(ctx)),
    ).toString().toByteArray(Charsets.UTF_8)

    /** Rodapé: troca {nome} {ip} {modelo} {android} {app} {id} {atualizacao}; o resto fica como escrito. */
    fun render(template: String, ctx: Context, lastUpdate: Long): String {
        var out = template
        for (key in URL_VARS + "{atualizacao}") {
            if (key in out) out = out.replace(key, value(key, ctx, lastUpdate, forUrl = false))
        }
        return out
    }

    /**
     * urlInicial: troca {nome} {ip} {modelo} {android} {app} {id} já codificados para URL
     * (espaço vira %20 etc.). Sem rede, {ip} vira vazio. Feito a cada abertura/atualização,
     * então os valores são os atuais.
     */
    fun resolveUrl(url: String, ctx: Context): String {
        var out = url
        for (key in URL_VARS) {
            if (key in out) out = out.replace(key, Uri.encode(value(key, ctx, 0, forUrl = true)))
        }
        return out
    }
}

/** URL inicial do item com as variáveis já aplicadas: as do aparelho (DeviceInfo) e as extras (UrlVars). */
fun AppItem.startUrl(ctx: Context): String = UrlVars.resolve(urlInicial, ctx, this)

/**
 * Rodapé do launcher. `template` vem do JSON ("rodape"): ausente ou vazio = nada é mostrado.
 * Atualiza a cada 10 s enquanto a tela está aberta.
 */
@Composable
fun DeviceFooter(template: String?, lastUpdate: Long) {
    // Sem "rodape" na lista (ou vazio): não mostra nada.
    val modelo = template?.takeIf { it.isNotBlank() } ?: return
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(modelo, lastUpdate) {
        while (true) {
            text = withContext(Dispatchers.IO) { DeviceInfo.render(modelo, ctx, lastUpdate) }
            delay(10_000)
        }
    }
    Text(
        text,
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
