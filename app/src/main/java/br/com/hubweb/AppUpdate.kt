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
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.random.Random

/** Uma versão nova encontrada no GitHub Releases. */
class NovaVersao(
    /** Texto da versão, sem o "v" da tag (ex.: "1.0.4"). */
    val versao: String,
    val url: String,
    val sha256: String,
    val notas: String?,
) {
    fun toJson(): String = JSONObject()
        .put("versao", versao).put("url", url).put("sha256", sha256).put("notas", notas ?: JSONObject.NULL).toString()

    companion object {
        fun fromJson(s: String): NovaVersao? = try {
            val o = JSONObject(s)
            NovaVersao(
                versao = o.getString("versao"),
                url = o.getString("url"),
                sha256 = o.getString("sha256"),
                notas = if (o.isNull("notas")) null else o.optString("notas").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Mensagem do último passo da atualização do app (verificando, baixando, aguardando confirmação, falha...). */
object UpdateStatus {
    var mensagem by mutableStateOf<String?>(null)
}

/**
 * Atualização do próprio app, sem a Play Store. O app consulta o último Release do repositório do projeto no GitHub
 * ([REPO]), compara a versão com a instalada e, se houver uma nova, baixa o APK (https), confere o SHA-256, o pacote
 * e o `versionCode` e o entrega ao instalador do Android. Como device owner a instalação é silenciosa; nos demais
 * aparelhos o Android pede a confirmação do usuário (e a permissão "instalar apps desconhecidos" para o HubWeb).
 * O Android só aceita o APK se for assinado com a mesma chave do app instalado.
 *
 * A lista só liga/desliga e regula a verificação (`atualizacao.app`); o endereço e a versão não vêm dela.
 * Em versão publicada na Play Store, retire este arquivo, a permissão REQUEST_INSTALL_PACKAGES e os receivers do manifesto.
 */
class AppUpdater(ctx: Context, private val prefs: Prefs, private val repo: ConfigRepo) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())

    var baixando by mutableStateOf(false); private set
    var verificando by mutableStateOf(false); private set
    var progresso by mutableIntStateOf(-1); private set
    private var agendado = false

    private val info = app.packageManager.getPackageInfo(app.packageName, 0)
    val versaoInstalada: String = info.versionName ?: "?"
    val codigoInstalado: Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    /** Versão nova conhecida (da última verificação); vale só enquanto for maior que a instalada. */
    var nova by mutableStateOf(NovaVersao.fromJson(prefs.appNovaJson)?.takeIf { maior(it.versao, versaoInstalada) }); private set

    val regra: RegraAtualizacao get() = repo.atualizacao.app
    val ativo: Boolean get() = regra.ativo
    val disponivel: Boolean get() = ativo && nova?.let { maior(it.versao, versaoInstalada) } == true
    val deviceOwner: Boolean
        get() = app.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(app.packageName)

    /** Resumo para as configurações. */
    fun resumo(): String = when {
        !ativo -> "Atualização do app: desativada pela lista (atualizacao.app.ativo)."
        disponivel -> "Atualização do app: nova versão ${nova?.versao} disponível."
        verificando -> "Atualização do app: verificando…"
        prefs.appVerificadoEm > 0 -> "Atualização do app: em dia (verificado em " +
            java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(prefs.appVerificadoEm)) + ")."
        else -> "Atualização do app: ainda não verificada."
    }

    /**
     * Consulta o GitHub. `force` é o botão das configurações: ignora o intervalo e os gatilhos. Sem `force`, só consulta
     * se a regra `atualizacao.app` permitir o gatilho (`"abrir"` ou `"hub"`) e o intervalo mínimo (guardado no aparelho)
     * já tiver passado.
     */
    fun verificar(force: Boolean = false, gatilho: String = "abrir") {
        val r = regra
        if (!r.ativo || verificando || baixando || agendado) {
            if (!r.ativo) nova = null
            return
        }
        val agora = System.currentTimeMillis()
        val ultima = prefs.appTentativaEm
        fun recente(ms: Long) = ultima != 0L && (agora - ultima) in 0 until ms
        if (force) { if (recente(5_000)) return }
        else if (gatilho !in r.quando || recente(r.intervaloSegundos * 1000L)) return
        prefs.appTentativaEm = agora
        val atraso = if (force || r.espalharSegundos <= 0) 0L else Random.nextLong(r.espalharSegundos * 1000L + 1)
        agendado = true
        thread(name = "hubweb-update-check") {
            if (atraso > 0) try { Thread.sleep(atraso) } catch (_: InterruptedException) {}
            main.post { agendado = false; verificando = true }
            try {
                val achada = consultarGitHub()
                main.post {
                    prefs.appVerificadoEm = System.currentTimeMillis()
                    prefs.appNovaJson = achada?.toJson() ?: ""
                    nova = achada?.takeIf { maior(it.versao, versaoInstalada) }
                    if (force) UpdateStatus.mensagem = if (nova != null) null else "O app já está na versão mais nova."
                    verificando = false
                }
            } catch (e: Exception) {
                main.post {
                    if (force) UpdateStatus.mensagem = "Não foi possível verificar: ${e.message ?: e.javaClass.simpleName}"
                    verificando = false
                }
            }
        }
    }

    /** Lê o último Release (não rascunho, não pré-lançamento) e acha o APK e o SHA-256. null = sem release utilizável. */
    private fun consultarGitHub(): NovaVersao? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        val texto = try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "HubWeb-Android")
            when (val code = conn.responseCode) {
                404 -> return null // ainda não há release
                403, 429 -> throw IllegalStateException("limite de consultas do GitHub atingido; tente mais tarde")
                !in 200..299 -> throw IllegalStateException("HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val rel = JSONObject(texto)
        val versao = rel.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        if (versao.isEmpty()) return null
        val corpo = rel.optString("body")
        val assets = rel.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val nome = a.optString("name")
            val url = a.optString("browser_download_url")
            if (!nome.endsWith(".apk", ignoreCase = true) || !url.startsWith("https://github.com/$REPO/releases/download/")) continue
            val sha = Regex("^sha256:([0-9a-fA-F]{64})$").find(a.optString("digest"))?.groupValues?.get(1)
                ?: Regex("(?i)SHA-256[^0-9a-f]{0,12}([0-9a-f]{64})").find(corpo)?.groupValues?.get(1)
                ?: continue
            return NovaVersao(versao, url, sha.lowercase(), limparNotas(corpo))
        }
        return null
    }

    /** Tira da descrição do Release as linhas técnicas (SHA, link) e limita o tamanho. */
    private fun limparNotas(corpo: String): String? = corpo.lines()
        .filter { l -> l.isNotBlank() && !l.contains("SHA-256", true) && !l.contains("Link de download", true) && !l.contains("Full Changelog", true) }
        .joinToString("\n").trim().take(500).takeIf { it.isNotEmpty() }

    /** Baixa, confere e instala a versão nova. */
    fun instalar() {
        val c = nova ?: return
        if (baixando || !disponivel) return
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
                verificarArquivo(arquivo)
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

    private fun baixar(c: NovaVersao): File {
        val dir = File(app.cacheDir, "update").apply { mkdirs() }
        val destino = File(dir, "HubWeb.apk")
        destino.delete()
        val conn = URL(c.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "HubWeb-Android")
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
                throw IllegalStateException("o SHA-256 do arquivo não confere com o do Release")
            }
        } finally {
            conn.disconnect()
        }
        return destino
    }

    /** O arquivo precisa ser o HubWeb (mesmo pacote) e ter um versionCode maior que o instalado. */
    private fun verificarArquivo(arquivo: File) {
        val pi = app.packageManager.getPackageArchiveInfo(arquivo.path, 0)
            ?: throw IllegalStateException("o arquivo baixado não é um APK válido")
        if (pi.packageName != app.packageName) throw IllegalStateException("o APK é de outro app (${pi.packageName})")
        val codigo = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
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

    companion object {
        /** Repositório do GitHub (dono/nome) de onde saem as atualizações. Troque ao fazer um fork. */
        const val REPO = "Neitan96/hubweb"
        private const val MAX_BYTES = 300_000_000L

        private fun partes(v: String) =
            v.trim().removePrefix("v").removePrefix("V").split('.', '-', '+').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

        /** `a` é uma versão mais nova que `b` (compara cada número, ex.: 1.0.10 > 1.0.9). */
        fun maior(a: String, b: String): Boolean {
            val x = partes(a)
            val y = partes(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val p = x.getOrElse(i) { 0 }
                val q = y.getOrElse(i) { 0 }
                if (p != q) return p > q
            }
            return false
        }
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

/** Aviso no hub: há versão nova. */
@Composable
fun AppUpdateDialog(updater: AppUpdater, mostrar: Boolean, onDepois: () -> Unit) {
    val c = updater.nova
    if (!mostrar || c == null || !updater.disponivel) return
    AlertDialog(
        onDismissRequest = onDepois,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Nova versão do HubWeb") },
        text = {
            val msg = UpdateStatus.mensagem
            val progresso = if (updater.baixando && updater.progresso >= 0) " ${updater.progresso}%" else ""
            Text(
                "Versão ${c.versao} disponível (instalada: ${updater.versaoInstalada})." +
                    (c.notas?.let { "\n\n$it" } ?: "") +
                    (if (updater.deviceOwner) "" else "\n\nO Android vai pedir a sua confirmação para instalar.") +
                    (msg?.let { "\n\n$it$progresso" } ?: ""),
            )
        },
        confirmButton = {
            TextButton(onClick = { updater.instalar() }, enabled = !updater.baixando) { Text("Atualizar") }
        },
        dismissButton = {
            TextButton(onClick = onDepois) { Text("Depois") }
        },
    )
}
