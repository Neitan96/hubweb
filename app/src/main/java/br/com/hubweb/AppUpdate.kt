package br.com.hubweb

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Bloco "atualizacaoApp" da lista: a versão do HubWeb que os aparelhos devem ter e onde baixá-la.
 * Sem `versionCode` válido, `url` https ou `sha256` (64 hex), o bloco é ignorado.
 */
class AtualizacaoApp(
    val versionCode: Long,
    /** Texto da versão, só para mostrar (ex.: "1.0.3"). */
    val versao: String?,
    val url: String,
    val sha256: String,
    /** Token próprio do download; se ausente, usa o da lista quando for o mesmo servidor. */
    val token: String?,
    /** true = o aviso no hub não tem "Depois". */
    val obrigatoria: Boolean,
    val notas: String?,
)

/** Mensagem do último passo da atualização do app (baixando, aguardando confirmação, falha...). */
object UpdateStatus {
    var mensagem by mutableStateOf<String?>(null)
}

/**
 * Atualização do próprio app, sem a Play Store: baixa o APK da lista (https), confere o SHA-256, o pacote e o
 * `versionCode` e o entrega ao instalador do Android. Como device owner a instalação é silenciosa; nos demais
 * aparelhos o Android pede a confirmação do usuário (e a permissão "instalar apps desconhecidos" para o HubWeb).
 * O Android só aceita o APK se for assinado com a mesma chave do app instalado.
 */
class AppUpdater(ctx: Context, private val prefs: Prefs, private val repo: ConfigRepo) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())

    var baixando by mutableStateOf(false); private set
    var progresso by mutableIntStateOf(-1); private set

    private val info = app.packageManager.getPackageInfo(app.packageName, 0)
    val versaoInstalada: String = info.versionName ?: "?"
    val codigoInstalado: Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    val cfg: AtualizacaoApp? get() = repo.atualizacaoApp
    val disponivel: Boolean get() = cfg?.let { it.versionCode > codigoInstalado } == true
    val deviceOwner: Boolean
        get() = app.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(app.packageName)

    /** Resumo para as configurações. */
    fun resumo(): String {
        val c = cfg ?: return "Atualização do app: a lista não define \"atualizacaoApp\"."
        val alvo = c.versao?.let { "$it (código ${c.versionCode})" } ?: "código ${c.versionCode}"
        return if (c.versionCode > codigoInstalado) "Atualização do app: nova versão $alvo disponível."
        else "Atualização do app: em dia (a lista indica $alvo)."
    }

    /** Baixa, confere e instala a versão indicada pela lista. */
    fun instalar() {
        val c = cfg ?: return
        if (baixando || c.versionCode <= codigoInstalado) return
        if (!deviceOwner && Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            UpdateStatus.mensagem = "Libere \"Instalar apps desconhecidos\" para o HubWeb na tela que abriu e toque em Atualizar de novo."
            try {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
            return
        }
        baixando = true
        progresso = 0
        UpdateStatus.mensagem = "Baixando a atualização…"
        thread(name = "hubweb-update") {
            try {
                val arquivo = baixar(c)
                verificar(arquivo, c)
                main.post { UpdateStatus.mensagem = "Instalando…" }
                entregarAoInstalador(arquivo)
                main.post {
                    if (UpdateStatus.mensagem == "Instalando…") UpdateStatus.mensagem = "Aguardando o Android concluir a instalação…"
                }
            } catch (e: Exception) {
                main.post { UpdateStatus.mensagem = "Falha na atualização: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                main.post { baixando = false; progresso = -1 }
            }
        }
    }

    private fun baixar(c: AtualizacaoApp): File {
        if (!c.url.startsWith("https://", ignoreCase = true)) throw IllegalStateException("o download exige https")
        val dir = File(app.cacheDir, "update").apply { mkdirs() }
        val destino = File(dir, "HubWeb.apk")
        destino.delete()
        val token = c.token ?: prefs.token.takeIf { it.isNotBlank() && mesmoHost(c.url, prefs.configUrl) }
        val conn = URL(c.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = conn.contentLengthLong
            if (total > MAX_BYTES) throw IllegalStateException("APK muito grande")
            val sha = MessageDigest.getInstance("SHA-256")
            var lido = 0L
            var ultimo = -1
            conn.inputStream.use { ins ->
                destino.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        lido += n
                        if (lido > MAX_BYTES) throw IllegalStateException("APK muito grande")
                        sha.update(buf, 0, n)
                        out.write(buf, 0, n)
                        if (total > 0) {
                            val p = (lido * 100 / total).toInt()
                            if (p != ultimo) { ultimo = p; main.post { progresso = p } }
                        }
                    }
                }
            }
            val calculado = sha.digest().joinToString("") { "%02x".format(it) }
            if (!calculado.equals(c.sha256, ignoreCase = true)) {
                destino.delete()
                throw IllegalStateException("o SHA-256 do arquivo não confere com o da lista")
            }
        } finally {
            conn.disconnect()
        }
        return destino
    }

    /** O arquivo precisa ser o HubWeb (mesmo pacote) e ter o versionCode anunciado, maior que o instalado. */
    private fun verificar(arquivo: File, c: AtualizacaoApp) {
        val pi = app.packageManager.getPackageArchiveInfo(arquivo.path, 0)
            ?: throw IllegalStateException("o arquivo baixado não é um APK válido")
        if (pi.packageName != app.packageName) throw IllegalStateException("o APK é de outro app (${pi.packageName})")
        val codigo = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        if (codigo != c.versionCode) throw IllegalStateException("o versionCode do APK ($codigo) difere do da lista (${c.versionCode})")
        if (codigo <= codigoInstalado) throw IllegalStateException("o APK não é mais novo que a versão instalada")
    }

    private fun entregarAoInstalador(arquivo: File) {
        val instalador = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = instalador.createSession(params)
        instalador.openSession(id).use { sessao ->
            FileInputStream(arquivo).use { ins ->
                sessao.openWrite("HubWeb", 0, arquivo.length()).use { out ->
                    ins.copyTo(out)
                    sessao.fsync(out)
                }
            }
            val intent = Intent(app, UpdateReceiver::class.java).setAction(UpdateReceiver.ACAO)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            sessao.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
        }
    }

    private fun mesmoHost(a: String, b: String) =
        runCatching { URL(a).host.equals(URL(b).host, ignoreCase = true) }.getOrDefault(false)

    private companion object {
        const val MAX_BYTES = 300_000_000L
    }
}

/** Recebe o resultado do instalador: pede a confirmação do usuário (aparelho comum) ou mostra o erro. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmar = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                UpdateStatus.mensagem = "Confirme a instalação na tela do Android."
                try {
                    confirmar?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                } catch (e: Exception) {
                    UpdateStatus.mensagem = "Não foi possível abrir a confirmação: ${e.message}"
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateStatus.mensagem = "Atualizado."
            else -> UpdateStatus.mensagem =
                "Falha na instalação: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erro desconhecido"}"
        }
    }

    companion object {
        const val ACAO = "br.com.hubweb.UPDATE_RESULT"
    }
}

/** Depois que o app é atualizado ele é encerrado; tenta reabri-lo (o Android pode negar em segundo plano). */
class ReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
        } catch (_: Exception) {
        }
    }
}

/** Aviso no hub: há versão nova. Com `obrigatoria` não tem "Depois". */
@Composable
fun AppUpdateDialog(updater: AppUpdater, mostrar: Boolean, onDepois: () -> Unit) {
    val c = updater.cfg
    if (!mostrar || c == null || !updater.disponivel) return
    // Se a instalação falhar, libera o "Depois" para o aviso não prender o hub.
    val obrigatoria = c.obrigatoria && UpdateStatus.mensagem?.startsWith("Falha") != true
    AlertDialog(
        onDismissRequest = { if (!obrigatoria) onDepois() },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = !obrigatoria),
        title = { Text("Nova versão do HubWeb") },
        text = {
            val msg = UpdateStatus.mensagem
            val progresso = if (updater.baixando && updater.progresso >= 0) " ${updater.progresso}%" else ""
            Text(
                "Versão ${c.versao ?: c.versionCode.toString()} disponível (instalada: ${updater.versaoInstalada})." +
                    (c.notas?.let { "\n\n$it" } ?: "") +
                    (if (updater.deviceOwner) "" else "\n\nO Android vai pedir a sua confirmação para instalar.") +
                    (msg?.let { "\n\n$it$progresso" } ?: ""),
            )
        },
        confirmButton = {
            TextButton(onClick = { updater.instalar() }, enabled = !updater.baixando) { Text("Atualizar") }
        },
        dismissButton = {
            if (!obrigatoria) TextButton(onClick = onDepois) { Text("Depois") }
        },
    )
}
