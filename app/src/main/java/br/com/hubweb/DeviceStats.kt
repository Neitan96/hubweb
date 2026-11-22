package br.com.hubweb

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Dados do aparelho que entram na telemetria (memória, armazenamento, segurança). Cada bloco é independente: se um falha, vai null. */
object DeviceStats {
    private const val MB = 1024L * 1024L

    fun iso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    /** Memória, armazenamento, tempo ligado, reinícios, modo avião e tela. */
    fun hardware(ctx: Context): JSONObject = JSONObject().apply {
        put("memoria", runCatching {
            val mi = ActivityManager.MemoryInfo()
            ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            JSONObject().put("totalMb", mi.totalMem / MB).put("livreMb", mi.availMem / MB).put("baixa", mi.lowMemory)
        }.getOrNull() ?: JSONObject.NULL)
        put("armazenamento", runCatching {
            val sf = StatFs(Environment.getDataDirectory().path)
            JSONObject().put("totalMb", sf.totalBytes / MB).put("livreMb", sf.availableBytes / MB)
        }.getOrNull() ?: JSONObject.NULL)
        // Tempo desde que o aparelho ligou (o relógio conta também com a tela apagada) e quantas vezes já reiniciou.
        put("ligadoHaSegundos", SystemClock.elapsedRealtime() / 1000)
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrNull()
            ?.takeIf { it >= 0 }?.let { put("reinicios", it) }
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }.getOrNull()
            ?.let { put("modoAviao", it) }
        runCatching { ctx.getSystemService(PowerManager::class.java).isInteractive }.getOrNull()
            ?.let { put("telaLigada", it) }
    }

    /** Gestão e segurança: device owner, kiosk, bloqueio de tela, patch de segurança do Android e root. */
    fun seguranca(ctx: Context, kiosk: Boolean): JSONObject = JSONObject().apply {
        runCatching { ctx.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(ctx.packageName) }.getOrNull()
            ?.let { put("deviceOwner", it) }
        put("modoKiosk", kiosk)
        runCatching { ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure }.getOrNull()
            ?.let { put("bloqueioDeTela", it) }
        Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() }?.let { put("patchSeguranca", it) }
        put("root", root)
    }

    /** Indício de root: build de teste ou binário `su` conhecido. É heurístico (quem quer esconder o root esconde). */
    private val root: Boolean by lazy {
        Build.TAGS?.contains("test-keys") == true ||
            listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk")
                .any { runCatching { File(it).exists() }.getOrDefault(false) }
    }
}
