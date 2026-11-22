package br.com.hubweb

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Variáveis extras da `urlInicial` (além de {nome} {ip} {modelo} {android} {app} {id}, que são do [DeviceInfo]):
 * usuário, bateria, rede, memória, hora, localização... Os valores são os do momento em que a página inicial é
 * carregada, já codificados para URL; o que o app não souber vira vazio. Nome desconhecido fica como escrito.
 */
object UrlVars {
    // Guardados só para a urlInicial poder consultar o usuário e a lista; a MainActivity liga e limpa (veja ligar/limpar).
    private var tracker: UserTracker? = null
    private var repo: ConfigRepo? = null
    private var prefs: Prefs? = null

    fun ligar(t: UserTracker, r: ConfigRepo, p: Prefs) {
        tracker = t
        repo = r
        prefs = p
    }

    fun limpar() {
        tracker = null
        repo = null
        prefs = null
    }

    private val NOMES = setOf(
        "usuario", "usuarioDesde", "fabricante", "bateria", "carregando", "ssid", "redeTipo", "sinalDbm",
        "ramLivreMb", "armazenamentoLivreMb", "versaoLista", "sistemaAnterior", "hora", "data",
        "deviceOwner", "kiosk", "lat", "lon", "urmoboNome", "urmoboId", "urmoboSerial", "urmoboImei", "urmoboDado",
    )

    // Variáveis do aparelho (DeviceInfo) e {atualizacao} (só no rodapé).
    private val DISPOSITIVO = setOf("nome", "ip", "modelo", "android", "app", "id", "atualizacao")

    /** Nome sem maiúsculas nem "_" -> nome oficial; assim {urmobo_nome} e {urmoboNome} são a mesma variável. */
    private val CANON: Map<String, String> by lazy { (NOMES + DISPOSITIVO).associateBy { it.replace("_", "").lowercase() } }

    /**
     * `{a}`, `{a:argumento}` ou uma cadeia `{a|b|"texto"}`: vale o primeiro valor não vazio; um texto entre aspas encerra
     * a cadeia. O argumento só serve para `usuario`, `usuarioDesde` e `urmoboDado`.
     */
    // As chaves precisam de barra invertida: o regex do Android (ICU) rejeita "}" solta, e isso derrubava o app ao abrir.
    // Lazy: um erro aqui nunca pode acontecer na inicialização do app.
    private val BLOCO by lazy { Regex("""\{([^\{\}]+)\}""") }

    // Mudam o tempo todo: não contam para "recarregarAoMudar".
    private val VOLATEIS = setOf(
        "bateria", "carregando", "sinalDbm", "ramLivreMb", "armazenamentoLivreMb", "hora", "data", "lat", "lon", "sistemaAnterior",
    )

    /** A urlInicial resolvida só com as variáveis estáveis (as que mudam o tempo todo ficam vazias); serve para detectar mudança. */
    fun chaveEstavel(item: AppItem, ctx: Context): String? {
        if ('{' !in item.urlInicial) return null
        return try {
            expandir(item.urlInicial, ctx, item, paraUrl = true, ignorar = VOLATEIS)
        } catch (_: Exception) {
            null
        }
    }

    fun resolve(url: String, ctx: Context, item: AppItem): String {
        if ('{' !in url) return url
        return try {
            expandir(url, ctx, item, paraUrl = true)
        } catch (_: Exception) {
            DeviceInfo.resolveUrl(url, ctx) // sem as variáveis extras, mas a página inicial abre
        }
    }

    /**
     * Troca os `{...}` de [texto] pelos valores. [paraUrl]: codifica cada valor para URL (rodapé: texto puro, e `{ip}` sem
     * rede vira "sem rede"). [ignorar]: variáveis tratadas como vazias (usado para detectar mudança). Um bloco sem nenhum
     * nome conhecido fica como escrito; um bloco conhecido sem valor nenhum vira vazio.
     */
    fun expandir(
        texto: String, ctx: Context, item: AppItem?, paraUrl: Boolean,
        ultimaAtualizacao: Long = 0, ignorar: Set<String> = emptySet(),
    ): String {
        if ('{' !in texto) return texto
        val app = ctx.applicationContext
        return BLOCO.replace(texto) { m ->
            var conhecido = false
            var primeira: String? = null
            for (parte in dividir(m.groupValues[1])) {
                val t = parte.trim()
                if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) { // texto fixo: encerra a cadeia
                    val lit = t.substring(1, t.length - 1)
                    return@replace if (paraUrl) Uri.encode(lit) else lit
                }
                val nome = CANON[t.substringBefore(':').trim().replace("_", "").lowercase()] ?: continue
                conhecido = true
                if (primeira == null) primeira = nome
                if (nome in ignorar) continue
                val arg = if (':' in t) t.substringAfter(':').trim() else null
                val v = runCatching { valor(nome, arg, app, item, ultimaAtualizacao) }.getOrNull().orEmpty()
                if (v.isNotEmpty()) return@replace if (paraUrl) Uri.encode(v) else v
            }
            when {
                !conhecido -> m.value
                !paraUrl && primeira == "ip" -> "sem rede"
                else -> ""
            }
        }
    }

    /** Divide por "|" sem cortar dentro de aspas. */
    private fun dividir(s: String): List<String> {
        val out = ArrayList<String>()
        val atual = StringBuilder()
        var aspas = false
        for (c in s) {
            if (c == '"') aspas = !aspas
            if (c == '|' && !aspas) { out += atual.toString(); atual.clear() } else atual.append(c)
        }
        out += atual.toString()
        return out
    }

    private fun valor(nome: String, arg: String?, ctx: Context, item: AppItem?, ultimaAtualizacao: Long): String = when (nome) {
        "nome", "ip", "modelo", "android", "app", "id" -> DeviceInfo.valorDe(nome, ctx).orEmpty()
        "atualizacao" -> if (ultimaAtualizacao > 0) SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ultimaAtualizacao)) else "-"
        "usuario" -> usuario(arg ?: item?.nome.orEmpty())?.login.orEmpty()
        "usuarioDesde" -> usuario(arg ?: item?.nome.orEmpty())?.let { DeviceStats.iso(it.desde) }.orEmpty()
        "fabricante" -> Build.MANUFACTURER.orEmpty()
        "bateria" -> bateria(ctx)?.first?.toString().orEmpty()
        "carregando" -> bateria(ctx)?.second?.toString().orEmpty()
        "ssid" -> rede(ctx).ssid.orEmpty()
        "redeTipo" -> rede(ctx).tipo
        "sinalDbm" -> rede(ctx).sinalDbm?.toString().orEmpty()
        "ramLivreMb" -> {
            val mi = ActivityManager.MemoryInfo()
            ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            (mi.availMem / (1024 * 1024)).toString()
        }
        "armazenamentoLivreMb" -> (StatFs(Environment.getDataDirectory().path).availableBytes / (1024 * 1024)).toString()
        "versaoLista" -> repo?.versaoLista?.toString().orEmpty()
        "sistemaAnterior" -> item?.let { AppStats.anterior(it.nome) }.orEmpty()
        "hora" -> SimpleDateFormat("HH:mm", Locale.US).format(Date())
        "data" -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        "deviceOwner" -> ctx.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(ctx.packageName).toString()
        "kiosk" -> (prefs?.kiosk ?: false).toString()
        "urmoboNome" -> UrmoboInfo.dados(ctx)?.nome.orEmpty()
        "urmoboId" -> UrmoboInfo.dados(ctx)?.id.orEmpty()
        "urmoboSerial" -> UrmoboInfo.dados(ctx)?.serial.orEmpty()
        "urmoboImei" -> UrmoboInfo.dados(ctx)?.imei.orEmpty()
        "urmoboDado" -> UrmoboInfo.dado(UrmoboInfo.dados(ctx), arg)
        "lat" -> ultimaPosicao(ctx)?.let { "%.6f".format(Locale.US, it.latitude) }.orEmpty()
        "lon" -> ultimaPosicao(ctx)?.let { "%.6f".format(Locale.US, it.longitude) }.orEmpty()
        else -> ""
    }

    /** O login detectado mais recentemente no sistema de nome [sistema] (várias detecções: vale a mais nova). */
    private fun usuario(sistema: String): UserState? =
        tracker?.states?.values?.filter { it.sistema.equals(sistema.trim(), ignoreCase = true) }?.maxByOrNull { it.desde }

    /** Nível (0 a 100) e se está carregando; null se o Android não informa. */
    private fun bateria(ctx: Context): Pair<Int, Boolean>? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val escala = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (nivel < 0 || escala <= 0) return null
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return nivel * 100 / escala to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    }

    private class Rede(val tipo: String, val sinalDbm: Int?, val ssid: String?)

    private fun rede(ctx: Context): Rede {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val tipo = when {
            caps == null -> "nenhuma"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "celular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "outra"
        }
        var sinal: Int? = null
        var ssid: String? = null
        if (tipo == "wifi") {
            @Suppress("DEPRECATION")
            val info = runCatching { ctx.getSystemService(WifiManager::class.java).connectionInfo }.getOrNull()
            sinal = info?.rssi?.takeIf { it > -127 }
            // O nome da rede só vem com a permissão de localização; sem ela o Android devolve "<unknown ssid>".
            if (temLocalizacao(ctx)) {
                ssid = info?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            sinal = caps?.signalStrength?.takeIf { it != Int.MIN_VALUE }
        }
        return Rede(tipo, sinal, ssid)
    }

    private fun temLocalizacao(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Última posição conhecida do aparelho (a mais recente entre GPS, rede e passiva); null sem permissão ou sem posição. */
    private fun ultimaPosicao(ctx: Context): Location? {
        if (!temLocalizacao(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }
}
