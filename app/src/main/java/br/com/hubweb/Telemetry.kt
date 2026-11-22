package br.com.hubweb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Envia um POST JSON periódico (config "telemetria" da lista) com dados do aparelho, rede, bateria e
 * localização. Só roda com o app em primeiro plano (entre onResume e onPause).
 */
class Telemetry(
    private val ctx: Context,
    private val prefs: Prefs,
    private val repo: ConfigRepo,
    /** Pede a permissão de localização (a Activity abre o diálogo do sistema). */
    private val askLocation: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private var running = false
    private var sentSinceStart = false // já enviou nesta entrada no app
    private var lastSendAt = 0L // SystemClock.elapsedRealtime() do último envio
    private var askedPermission = false
    private var listeningEvery = 0 // 0 = sem escuta de localização
    private var best: Location? = null

    /** Usuários identificados por sistema; entram no payload e geram eventos. */
    var tracker: UserTracker? = null

    /**
     * Estado do app para a telemetria (sistema aberto, sistemas na memória, erros, lista e dicas); quem monta é a
     * MainActivity, que conhece essas partes. Cada chave do objeto vira uma chave do payload ("app", "lista", "dicas").
     */
    var estadoApp: (() -> JSONObject)? = null

    /** Medição da conexão com o servidor da telemetria (feita em segundo plano a cada envio; vai no envio seguinte). */
    private class Sonda(val latenciaMs: Long?, val falhas: Int, val total: Int, val em: Long)
    @Volatile private var sonda: Sonda? = null
    private var sondando = false

    /** Resultado do último envio, para mostrar nas configurações. */
    var status by mutableStateOf<String?>(null); private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (better(location)) best = location
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        // Necessário nas APIs antigas (sem implementação padrão na interface).
        @Deprecated("Só para APIs antigas")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val cfg = repo.telemetria
            var delayMs = 30_000L
            if (cfg != null) {
                delayMs = cfg.frequenciaSegundos * 1000L
                if (cfg.localizacao) ensureLocation(cfg.frequenciaSegundos) else stopLocation()
                send(cfg)
            } else {
                stopLocation()
            }
            handler.postDelayed(this, delayMs)
        }
    }

    /**
     * Ao entrar no app (abrir ou voltar a ele) já envia uma telemetria na hora, sem esperar o intervalo; a localização
     * vai com a última posição conhecida do aparelho. Entradas seguidas em menos de 10 s (voltar do leitor de
     * código ou de um diálogo de permissão) não repetem o envio: o primeiro tick espera o resto desse intervalo.
     */
    fun start() {
        running = true
        sentSinceStart = false
        handler.removeCallbacks(tick)
        val espera = (MIN_ENTRE_ENVIOS_MS - (SystemClock.elapsedRealtime() - lastSendAt)).coerceAtLeast(0)
        handler.postDelayed(tick, espera)
    }

    /**
     * A lista (com o bloco "telemetria") chegou. Se o app já está aberto e ainda não enviou nada nesta entrada, o que
     * acontece na primeira abertura, sem lista em cache, envia agora em vez de esperar a próxima checagem.
     */
    fun onConfigApplied() {
        handler.post {
            if (running && !sentSinceStart && repo.telemetria != null) {
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        stopLocation()
    }

    /** Chamado depois que o usuário responde ao pedido de permissão. */
    fun kick() {
        handler.post {
            repo.telemetria?.takeIf { it.localizacao }?.let { ensureLocation(it.frequenciaSegundos) }
        }
    }

    // ------------------------------------------------------------ localização

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun better(l: Location): Boolean {
        val b = best ?: return true
        val newer = l.time - b.time
        return newer >= 0 && (l.accuracy <= b.accuracy || newer > 60_000)
    }

    private fun ensureLocation(everySec: Int) {
        if (!hasPermission()) {
            if (!askedPermission) {
                askedPermission = true
                askLocation()
            }
            return
        }
        if (listeningEvery == everySec) return
        stopLocation()
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, everySec * 1000L, 0f, listener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { if (better(it)) best = it }
                    any = true
                }
            } catch (_: SecurityException) { // GPS exige permissão precisa; a rede funciona só com a aproximada
            } catch (_: IllegalArgumentException) {
            }
        }
        if (any) listeningEvery = everySec
    }

    private fun stopLocation() {
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        listeningEvery = 0
    }

    // ------------------------------------------------------------ envio

    private fun send(cfg: TelemetriaConfig) {
        sentSinceStart = true
        lastSendAt = SystemClock.elapsedRealtime()
        if (!cfg.url.startsWith("https://", ignoreCase = true)) {
            status = "URL precisa ser https"
            return
        }
        val uso = AppStats.retirarUso() // uso por sistema desde o último envio; volta se este envio falhar
        val body = try {
            envelope("telemetria", payload(cfg, uso))
        } catch (e: Exception) {
            AppStats.devolverUso(uso)
            status = "erro ao montar: ${e.message}"
            return
        }
        sondar(cfg.url)
        post(cfg.url, tokenFor(cfg.url, cfg.token), body) { result ->
            if (result != "OK") AppStats.devolverUso(uso)
            val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            status = "$hora · $result"
        }
    }

    /**
     * Mede a conexão com o servidor da telemetria: 5 conexões TCP seguidas (limite de 3 s cada), de onde saem a latência
     * (mediana) e as falhas. O resultado entra no próximo envio, para a medição não atrasar este.
     */
    private fun sondar(url: String) {
        if (sondando) return
        val u = runCatching { URL(url) }.getOrNull() ?: return
        val host = u.host
        val porta = if (u.port > 0) u.port else 443
        sondando = true
        thread(name = "hubweb-sonda") {
            val tempos = ArrayList<Long>()
            var falhas = 0
            val total = 5
            repeat(total) {
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    Socket().use { it.connect(InetSocketAddress(host, porta), 3_000) }
                    tempos += SystemClock.elapsedRealtime() - t0
                } catch (_: Exception) {
                    falhas++
                }
            }
            sonda = Sonda(tempos.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }, falhas, total, System.currentTimeMillis())
            handler.post { sondando = false }
        }
    }

    /** Token próprio do bloco; senão o da lista, se a URL for do mesmo servidor. */
    private fun tokenFor(url: String, own: String?): String? =
        own ?: prefs.token.takeIf { it.isNotBlank() && sameHost(url, prefs.configUrl) }

    /**
     * Evento de login/logout/troca de usuário (detecções por evento): vai na hora para o endpoint
     * **"eventos"** da lista, que é diferente do da telemetria. Sem esse bloco no JSON, nada é enviado.
     */
    fun sendEvent(evento: String, sistema: String, login: String, fonte: String, anterior: String?) {
        val ev = repo.eventos
        if (ev != null && !ev.ativo) return
        val tel = repo.telemetria
        // Endpoint próprio ("eventos.url") ou, sem ele, o mesmo da telemetria; o envelope "tipo" diferencia.
        val url = ev?.url ?: tel?.url ?: return
        if (!url.startsWith("https://", ignoreCase = true)) return
        val body = try {
            envelope("evento", JSONObject().apply {
                put("evento", evento)
                put("timestamp", isoNow())
                put("dispositivo", deviceJson())
                put("sistema", sistema)
                put("usuario", login)
                put("fonte", fonte)
                put("usuarioAnterior", anterior ?: JSONObject.NULL)
            })
        } catch (_: Exception) {
            return
        }
        // Token: o do endpoint próprio; se for o mesmo da telemetria, o dela; senão o da lista (mesmo servidor).
        val own = ev?.token ?: if (ev?.url == null) tel?.token else null
        post(url, tokenFor(url, own), body) { }
    }

    /** Todo POST do app tem só duas chaves: `tipo` ("telemetria", "evento") e `payload` (o conteúdo). */
    private fun envelope(tipo: String, payload: JSONObject): String =
        JSONObject().put("tipo", tipo).put("payload", payload).toString()

    /** POST JSON em outra thread; `onDone` recebe "OK", "HTTP nnn" ou "erro: ..." na thread principal. */
    private fun post(url: String, token: String?, body: String, onDone: (String) -> Unit) {
        thread(name = "hubweb-telemetria") {
            val result = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
                    if (code in 200..299) "OK" else "HTTP $code"
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                "erro: ${e.message ?: e.javaClass.simpleName}"
            }
            handler.post { onDone(result) }
        }
    }

    private fun sameHost(a: String, b: String) =
        runCatching { URL(a).host.equals(URL(b).host, ignoreCase = true) }.getOrDefault(false)

    // ------------------------------------------------------------ payload

    private fun payload(cfg: TelemetriaConfig, uso: Uso): JSONObject {
        return JSONObject().apply {
            put("timestamp", isoNow())
            put("dispositivo", deviceJson())
            put("rede", network())
            put("bateria", battery())
            put("localizacao", if (cfg.localizacao) location() else JSONObject.NULL)
            put("hardware", DeviceStats.hardware(ctx))
            put("seguranca", DeviceStats.seguranca(ctx, prefs.kiosk))
            put("uso", usoJson(uso))
            // Estado do app: chaves "app", "lista" e "dicas" (veja MainActivity).
            runCatching { estadoApp?.invoke() }.getOrNull()?.let { e -> e.keys().forEach { k -> put(k, e.get(k)) } }
            // Quem está logado em cada sistema (sistema -> login), e o identificado mais recentemente.
            val t = tracker
            put("usuarios", JSONArray().apply {
                t?.states?.values?.sortedBy { it.sistema }?.forEach {
                    put(
                        JSONObject().put("sistema", it.sistema).put("login", it.login)
                            .put("desde", isoAt(it.desde)).put("fonte", it.fonte)
                            .put("modo", if (it.evento) "evento" else "continuo"),
                    )
                }
            })
            put(
                "usuarioAtual",
                t?.current()?.let { JSONObject().put("sistema", it.sistema).put("login", it.login) } ?: JSONObject.NULL,
            )
        }
    }

    private fun usoJson(u: Uso) = JSONObject().apply {
        put("periodoSegundos", u.periodoMs / 1000)
        put("sistemas", JSONArray().apply {
            (u.ms.keys + u.aberturas.keys).distinct().sorted().forEach { nome ->
                put(
                    JSONObject().put("sistema", nome).put("segundos", (u.ms[nome] ?: 0L) / 1000)
                        .put("aberturas", u.aberturas[nome] ?: 0),
                )
            }
        })
    }

    private fun isoAt(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun isoNow() = isoAt(System.currentTimeMillis())

    private fun deviceJson() = JSONObject().apply {
        put("nome", DeviceInfo.name(ctx))
        put("id", DeviceInfo.androidId(ctx))
        put("modelo", DeviceInfo.model())
        put("android", Build.VERSION.RELEASE.orEmpty())
        put("app", appVersion())
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    private fun network(): JSONObject {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val tipo = when {
            caps == null -> "nenhuma"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "celular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "outra"
        }
        return JSONObject().apply {
            put("tipo", tipo)
            put("ip", DeviceInfo.ipv4() ?: JSONObject.NULL)
            if (tipo == "wifi") {
                @Suppress("DEPRECATION")
                val info = runCatching {
                    ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
                }.getOrNull()
                val rssi = info?.rssi
                if (rssi != null && rssi > -127) {
                    put("sinalDbm", rssi)
                    put("sinalNivel", level(rssi))
                }
                info?.linkSpeed?.takeIf { it > 0 }?.let { put("velocidadeMbps", it) }
                // O nome da rede só vem com a permissão de localização; sem ela o Android devolve "<unknown ssid>".
                if (hasPermission()) {
                    info?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                        ?.let { put("ssid", it) }
                }
            } else if (Build.VERSION.SDK_INT >= 29) {
                // Para celular o valor depende do aparelho/versão; pode vir ausente.
                caps?.signalStrength?.takeIf { it != Int.MIN_VALUE }?.let { put("sinalDbm", it) }
            }
            // Conexão com o servidor da telemetria (medida no envio anterior): latência mediana e falhas de conexão.
            sonda?.let { s ->
                put(
                    "servidor",
                    JSONObject().put("latenciaMs", s.latenciaMs ?: JSONObject.NULL)
                        .put("perdaPct", s.falhas * 100 / s.total).put("tentativas", s.total)
                        .put("medidoEm", DeviceStats.iso(s.em)),
                )
            }
        }
    }

    /** 0 (sem sinal) a 4 (excelente), a partir do RSSI em dBm. */
    private fun level(dbm: Int) = when {
        dbm >= -55 -> 4
        dbm >= -66 -> 3
        dbm >= -77 -> 2
        dbm >= -88 -> 1
        else -> 0
    }

    private fun battery(): JSONObject {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().apply {
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            put("nivel", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            put("carregando", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            put(
                "fonte",
                when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "tomada"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "sem fio"
                    else -> JSONObject.NULL
                },
            )
            i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
                ?.let { put("temperaturaC", it / 10.0) }
            // Saúde, tensão e ciclos (a contagem de ciclos só existe no Android 14+ e nem todo aparelho informa).
            when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "boa"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "superaquecida"
                BatteryManager.BATTERY_HEALTH_DEAD -> "ruim"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "sobretensao"
                BatteryManager.BATTERY_HEALTH_COLD -> "fria"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "falha"
                else -> null
            }?.let { put("saude", it) }
            i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }?.let { put("tensaoMv", it) }
            if (Build.VERSION.SDK_INT >= 34) {
                i?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, 0)?.takeIf { it > 0 }?.let { put("ciclos", it) }
            }
        }
    }

    private fun location(): JSONObject {
        if (!hasPermission()) return JSONObject().put("status", "sem_permissao")
        val l = best ?: return JSONObject().put("status", "sem_fix")
        return JSONObject().apply {
            put("status", "ok")
            put("lat", l.latitude)
            put("lon", l.longitude)
            put("precisaoM", l.accuracy.toDouble())
            put("idadeSegundos", ((System.currentTimeMillis() - l.time) / 1000).coerceAtLeast(0))
            put("provedor", l.provider ?: JSONObject.NULL)
        }
    }

    private companion object {
        /** Intervalo mínimo entre o envio ao entrar no app e o anterior. */
        const val MIN_ENTRE_ENVIOS_MS = 10_000L
    }
}
