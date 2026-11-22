package br.com.hubweb

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- Senha

@Composable
fun PasswordDialog(title: String, verify: (String) -> String?, onOk: () -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Tocar fora não fecha: quem abriu pelo segredo (vários toques no título) costuma continuar tocando e,
        // sem isto, os toques que sobram caem fora da caixa e a fecham sem querer. Voltar e "Cancelar" fecham.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it; err = null },
                label = { Text(err ?: "Senha") },
                isError = err != null,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val r = verify(pw)
                if (r == null) onOk() else { err = r; pw = "" }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------- Launcher

@Composable
fun LauncherScreen(
    repo: ConfigRepo,
    tips: TipsRepo,
    verify: (String) -> String?,
    hasPassword: () -> Boolean,
    floatingOn: Boolean,
    onFloating: () -> Unit,
    onOpen: (AppItem) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    var askPassword by remember { mutableStateOf(false) }
    val ap = repo.aparencia
    val logo = repo.logo
    // Sem senha ainda (a lista não trouxe uma), as configurações abrem direto para o primeiro setup.
    val openSettings = { if (hasPassword()) askPassword = true else onSettings() }
    // Segredo para abrir as configurações com a engrenagem oculta: vários toques rápidos no título ou na logo.
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    // O hub mostra só os itens com hub = true; os apenas-flutuantes não viram cartão.
    val hubItems = remember(repo.items) { repo.items.filter { it.hub } }
    val rows = remember(hubItems) { buildRows(hubItems) }
    // Tamanho do nome nos cartões: recomeça quando muda a lista, o número de colunas ou a largura da tela.
    val larguraTela = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val ajusteNome = remember(hubItems, ap.colunas, larguraTela) { AjusteNome() }

    Box(Modifier.fillMaxSize()) {
    // Fundo centralizado; só reduz se for maior que a tela, nunca amplia.
    repo.background?.let {
        Image(it, null, Modifier.fillMaxSize(), alignment = Alignment.Center, contentScale = ContentScale.Inside)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).padding(vertical = 8.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (askPassword) return@clickable // a caixa de senha já está aberta: não conta mais toques
                    val now = System.currentTimeMillis()
                    taps = if (now - lastTap < 1500) taps + 1 else 1
                    lastTap = now
                    if (taps >= ap.toquesSecreto) { taps = 0; openSettings() }
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (logo != null) {
                    // A logo substitui o nome; a altura vem do JSON e a largura acompanha a proporção da imagem.
                    Image(
                        logo, ap.nome ?: "HubWeb",
                        Modifier.height(ap.logoAltura.dp).aspectRatio(logo.width.toFloat() / logo.height.toFloat()),
                        alignment = Alignment.CenterStart, contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(ap.nome ?: "HubWeb", style = MaterialTheme.typography.titleLarge)
                }
            }
            // Escolha dos botões flutuantes (calculadora e URLs da lista); aceso = há algum ligado.
            IconButton(onClick = onFloating) {
                Icon(
                    painterResource(R.drawable.ic_calculate),
                    "Botões flutuantes",
                    tint = if (floatingOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
            if (!ap.ocultarAtualizar) {
                if (repo.loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Atualizar lista") }
                }
            }
            if (!ap.ocultarConfiguracoes) {
                IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Configurações") }
            }
        }
        StatusLine(repo)
        if (hubItems.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (ap.ocultarConfiguracoes) "Nenhum sistema na lista."
                    else "Nenhum sistema na lista.\nAbra as configurações (engrenagem) e informe a URL do JSON.",
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                modifier = Modifier.weight(1f),
                columns = repo.aparencia.colunas.let { if (it > 0) GridCells.Fixed(it) else GridCells.Adaptive(104.dp) },
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { r ->
                    when (r) {
                        is String -> item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(r, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        is AppItem -> item { AppTile(r, ajusteNome) { onOpen(r) } }
                    }
                }
            }
        }
        TipBanner(tips) // caixa de dica logo acima do rodapé
        DeviceFooter(repo.rodape, repo.lastUpdate)
    }
    }

    if (askPassword) {
        PasswordDialog(
            title = "Configurações",
            verify = verify,
            onOk = { askPassword = false; onSettings() },
            onDismiss = { askPassword = false },
        )
    }
}

/** Itens sem grupo primeiro; depois cada grupo com seu cabeçalho. Dentro: ordem, depois nome. */
private fun buildRows(items: List<AppItem>): List<Any> {
    val out = ArrayList<Any>()
    var last: String? = null
    items.sortedWith(compareBy({ it.grupo ?: "" }, { it.ordem }, { it.nome.lowercase() })).forEach {
        if (it.grupo != null && it.grupo != last) out += it.grupo
        last = it.grupo
        out += it
    }
    return out
}

/** Descrição curta de uma regra de atualização, para as configurações. */
private fun regraTexto(r: RegraAtualizacao): String {
    if (!r.ativo) return "desativada"
    if (r.quando.isEmpty()) return "só pelo botão"
    val quando = r.quando.sorted().joinToString(" e ") { if (it == "hub") "ao voltar ao hub" else "ao abrir o app" }
    val s = r.intervaloSegundos
    val min = if (s < 120) "$s s" else "${s / 60} min"
    val espalhar = if (r.espalharSegundos > 0) ", espalhada em até ${r.espalharSegundos} s" else ""
    return "$quando, no mínimo a cada $min$espalhar"
}

@Composable
private fun StatusLine(repo: ConfigRepo) {
    val time = { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(repo.lastUpdate)) }
    val (text, isError) = when {
        repo.loading -> "Atualizando lista…" to false
        repo.error != null && repo.items.isNotEmpty() ->
            "Sem acesso à lista — usando cache de ${time()}" to true
        repo.error != null -> "Falha ao carregar a lista: ${repo.error}" to true
        repo.lastUpdate > 0 && !repo.aparencia.ocultarUltimaAtualizacao -> "Lista atualizada em ${time()}" to false
        else -> return
    }
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            .padding(horizontal = 16.dp),
    )
}

/**
 * Tamanho do nome nos cartões do hub, compartilhado por todos para ficarem iguais: se algum nome quebra em duas
 * linhas, a fonte de todos diminui (até [MINIMO]); se mesmo assim algum não couber, todos reservam duas linhas,
 * e os cartões ficam com a mesma altura.
 */
private class AjusteNome {
    var tamanho by mutableFloatStateOf(14f)
    var linhas by mutableIntStateOf(1)

    companion object {
        const val MINIMO = 9f
    }
}

@Composable
private fun AppTile(item: AppItem, ajuste: AjusteNome, onClick: () -> Unit) {
    val icon = remember(item.icone) { decodeIcon(item.icone) }
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Image(icon, null, Modifier.size(56.dp))
        } else {
            Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), Alignment.Center) {
                Text(item.nome.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.nome, maxLines = 2, minLines = ajuste.linhas, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = ajuste.tamanho.sp),
            onTextLayout = { r ->
                if (r.lineCount > 1) {
                    if (ajuste.tamanho > AjusteNome.MINIMO) ajuste.tamanho = maxOf(AjusteNome.MINIMO, ajuste.tamanho - 1f)
                    else if (ajuste.linhas < 2) ajuste.linhas = 2 // não coube nem no menor tamanho: todos ficam com 2 linhas
                }
            },
        )
    }
}

/** Aceita base64 puro ou data URI; reduz a no máx. ~256px para não gastar memória. */
internal fun decodeIcon(b64: String?): ImageBitmap? {
    if (b64.isNullOrBlank()) return null
    return try {
        val bytes = Base64.decode(b64.substringAfter("base64,").trim(), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

// ---------------------------------------------------------------- Navegador

@Composable
fun BrowserScreen(
    entry: Entry,
    tips: TipsRepo,
    onLauncher: () -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onHome: () -> Unit,
    /** Não nulo quando o item tem `leitorCamera`: abre o leitor de códigos. */
    onScan: (() -> Unit)? = null,
    /** Não nulo quando o item identifica o usuário por crachá: abre a troca de usuário. */
    onUser: (() -> Unit)? = null,
) {
    // A barra já abre visível ou oculta conforme o item ("barra.visivel"); o usuário alterna pelo botão.
    var barVisible by remember(entry.item.id) { mutableStateOf(entry.item.barraVisivel) }
    Column(Modifier.fillMaxSize()) {
        if (barVisible) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onLauncher) { Icon(Icons.Default.Menu, "Launcher") }
                if (entry.item.barraVoltar) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") }
                if (entry.item.barraAtualizar) IconButton(onClick = onReload) { Icon(Icons.Default.Refresh, "Atualizar página") }
                if (entry.item.barraInicio) IconButton(onClick = onHome) { Icon(Icons.Default.Home, "Página inicial") }
                onScan?.let { scan ->
                    IconButton(onClick = scan) { Icon(painterResource(R.drawable.ic_qr_scan), "Ler código com a câmera") }
                }
                onUser?.let { user ->
                    IconButton(onClick = user) { Icon(Icons.Default.Person, "Trocar usuário") }
                }
                Text(entry.item.nome, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { barVisible = false }) { Icon(Icons.Default.KeyboardArrowUp, "Esconder barra") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { FrameLayout(it) },
                update = { frame ->
                    val wv = entry.webView
                    if (frame.childCount != 1 || frame.getChildAt(0) !== wv) {
                        frame.removeAllViews()
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        frame.addView(wv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
            )
            // Dica enquanto o sistema carrega (abaixo do aviso de erro, que sempre vence).
            TipOverlay(tips, entry)
            entry.error?.let { msg ->
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Não foi possível abrir ${entry.item.nome}", style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(msg, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onReload) { Text("Tentar novamente") }
                    TextButton(onClick = onLauncher) { Text("Voltar ao launcher") }
                }
            }
            if (!barVisible) {
                IconButton(
                    onClick = { barVisible = true },
                    modifier = Modifier.align(Alignment.TopEnd).size(32.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), CircleShape),
                ) { Icon(Icons.Default.Menu, "Mostrar barra", Modifier.size(18.dp)) }
            }
        }
    }
}

// ---------------------------------------------------------------- Configurações

@Composable
fun SettingsScreen(
    prefs: Prefs,
    repo: ConfigRepo,
    telemetry: Telemetry,
    tracker: UserTracker,
    tips: TipsRepo,
    updater: AppUpdater,
    onSaved: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    onExitApp: () -> Unit,
) {
    var url by remember { mutableStateOf(prefs.configUrl) }
    var token by remember { mutableStateOf(prefs.token) }
    val ctx = LocalContext.current
    var isOwner by remember { mutableStateOf(ctx.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(ctx.packageName)) }
    var confirmRemoveOwner by remember { mutableStateOf(false) }
    var docsTab by remember { mutableStateOf<Int?>(null) } // 0 = documentação, 1 = exemplo de JSON
    var verTelemetria by remember { mutableStateOf(false) }
    val kioskForced = repo.modoKiosk != null // a lista define o modo kiosk
    var kiosk by remember { mutableStateOf(prefs.kioskLocal) }

    val urlOk = url.isBlank() || url.startsWith("https://", true) || url.startsWith("http://", true)
    val valid = urlOk

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Configurações", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (prefs.hasPassword) "A senha de administração vem da lista (campo senha ou senhaSha256 do JSON)."
            else "A lista ainda não trouxe senha: estas configurações ficam abertas até ela chegar (campo senha ou senhaSha256 do JSON).",
            style = MaterialTheme.typography.bodySmall,
        )

        if (prefs.urlGerenciada || prefs.tokenGerenciado) {
            Text(
                "Definido pelo MDM (configuração gerenciada): " +
                    listOfNotNull("URL".takeIf { prefs.urlGerenciada }, "token".takeIf { prefs.tokenGerenciado }).joinToString(" e ") +
                    ". Para mudar, altere a configuração do app no MDM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedTextField(
            url, { url = it }, Modifier.fillMaxWidth(), enabled = !prefs.urlGerenciada,
            label = { Text(if (urlOk) "URL do JSON com a lista" else "Use http:// ou https://") },
            isError = !urlOk, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            token, { token = it }, Modifier.fillMaxWidth(), enabled = !prefs.tokenGerenciado,
            label = { Text("Token (opcional, enviado como Bearer só em HTTPS)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        val fmt = { ms: Long -> SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(ms)) }
        Text(
            "Última atualização da lista (configurações): " +
                (if (repo.lastUpdate > 0) "${fmt(repo.lastUpdate)} · ${repo.items.size} sistema(s)" else "nunca (ainda sem cache)") +
                (if (repo.loading) " · atualizando…" else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Última atualização das dicas: " +
                (if (repo.dicas == null) "desligadas (a lista não define \"dicas\")"
                else if (tips.lastUpdate > 0) "${fmt(tips.lastUpdate)} · ${tips.dicas.size} dica(s)" else "nunca") +
                (if (tips.loading) " · atualizando…" else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onRefresh, enabled = !repo.loading && !tips.loading) {
            Text("Atualizar agora (lista e dicas)")
        }
        repo.error?.let { Text("Último erro: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val tel = repo.telemetria
        Text(
            if (tel == null) "Telemetria: desligada (a lista não define \"telemetria\")."
            else "Telemetria: POST a cada ${tel.frequenciaSegundos} s para " +
                "${tel.url.substringAfter("://").substringBefore('/')} · ${telemetry.status ?: "aguardando o primeiro envio"}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (tel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { telemetry.enviarAgora() }) { Text("Enviar telemetria agora") }
                OutlinedButton(onClick = { verTelemetria = true }, enabled = telemetry.ultimoEnvio != null) { Text("Ver a última enviada") }
            }
        }
        Text(tips.resumo(), style = MaterialTheme.typography.bodySmall)
        Text("Versão instalada: ${updater.versaoInstalada} (código ${updater.codigoInstalado})", style = MaterialTheme.typography.bodySmall)
        Text(updater.resumo(), style = MaterialTheme.typography.bodySmall)
        UpdateStatus.mensagem?.let {
            Text(it + if (updater.baixando && updater.progresso >= 0) " ${updater.progresso}%" else "", style = MaterialTheme.typography.bodySmall)
        }
        if (updater.disponivel) {
            OutlinedButton(onClick = { updater.instalar() }, enabled = !updater.baixando) { Text("Atualizar o app agora") }
        } else if (updater.ativo) {
            OutlinedButton(onClick = { updater.verificar(force = true) }, enabled = !updater.verificando && !updater.baixando) {
                Text("Verificar atualização do app")
            }
        }
        Text(
            "Atualização automática. Lista: ${regraTexto(repo.atualizacao.lista)}. Dicas: ${regraTexto(repo.atualizacao.dicas)}. App: ${regraTexto(repo.atualizacao.app)}.",
            style = MaterialTheme.typography.bodySmall,
        )
        // Quem está logado em cada sistema (diagnóstico da detecção de usuário).
        Text(
            if (tracker.states.isEmpty()) "Usuários identificados: nenhum."
            else "Usuários identificados:\n" + tracker.states.values.sortedBy { it.sistema }
                .joinToString("\n") { "• ${it.sistema}: ${it.login} (${it.fonte})" },
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        // Estes dois valores vêm da lista (timeoutInatividadeMinutos e sistemasAbertos); aqui só se mostram.
        Text(
            "Timeout de inatividade: " + (if (repo.timeoutMin > 0) "${repo.timeoutMin} min" else "desligado") +
                " (lista: timeoutInatividadeMinutos; padrão 30).",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Sistemas mantidos abertos: ${repo.sistemasAbertos} (RAM do aparelho: ${repo.ramMb} MB; lista: sistemasAbertos; padrão 2).",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Modo kiosk")
                Text(
                    "Esconde as barras do sistema e bloqueia o Voltar. Só trava o aparelho de verdade (sem saída pelo sistema e sem aviso do Android) se o HubWeb for device owner.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (kioskForced) "Definido pela lista (modoKiosk): ${if (repo.modoKiosk == true) "ligado" else "desligado"}. Para usar esta opção, remova o campo da lista."
                    else "A última lista recebida não define \"modoKiosk\" (campo exato, na raiz do JSON); vale a escolha acima.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Switch(
                checked = if (kioskForced) repo.modoKiosk == true else kiosk,
                onCheckedChange = { kiosk = it },
                enabled = !kioskForced,
            )
        }

        // Instruções só para o administrador (esta tela exige a senha); o Android não exibe aviso de saída ao usuário.
        Text("Como sair do modo kiosk", style = MaterialTheme.typography.titleSmall)
        Text(
            "• Com a senha: na tela inicial toque na engrenagem, informe a senha e desligue \"Modo kiosk\" (Salvar), " +
                "ou use \"Encerrar o HubWeb\" abaixo.\n" +
                "• Se a lista define \"modoKiosk\", a chave fica travada: altere ou remova o campo na lista e atualize o app.\n" +
                "• Sem device owner o HubWeb não fixa a tela (por isso o Android não mostra aviso): só esconde as barras e " +
                "bloqueia o Voltar, e o gesto de Início ainda sai do app.\n" +
                "• Com device owner o aparelho fica travado e a única saída é por aqui. Para ativar, num aparelho sem contas: " +
                "adb shell dpm set-device-owner br.com.hubweb/.AdminReceiver",
            style = MaterialTheme.typography.bodySmall,
        )
        if (isOwner) {
            OutlinedButton(onClick = { confirmRemoveOwner = true }, Modifier.fillMaxWidth()) {
                Text("Remover device owner (libera a desinstalação)")
            }
        }

        HorizontalDivider()
        Button(
            onClick = {
                if (!prefs.urlGerenciada) prefs.configUrl = url // o valor do MDM não vira cópia local
                if (!prefs.tokenGerenciado) prefs.token = token
                prefs.kioskLocal = kiosk
                onSaved()
            },
            enabled = valid, modifier = Modifier.fillMaxWidth(),
        ) { Text("Salvar e atualizar lista") }
        OutlinedButton(onClick = { docsTab = 0 }, Modifier.fillMaxWidth()) { Text("Documentação do JSON e do app") }
        OutlinedButton(onClick = { docsTab = 1 }, Modifier.fillMaxWidth()) { Text("Exemplo de JSON") }
        OutlinedButton(onClick = onClose, Modifier.fillMaxWidth()) { Text("Cancelar") }
        TextButton(onClick = onExitApp, Modifier.fillMaxWidth()) { Text("Encerrar o HubWeb") }
    }

    // Em um diálogo, para as alterações ainda não salvas nesta tela não se perderem ao consultar a documentação.
    docsTab?.let { DocsDialog(initialTab = it, onClose = { docsTab = null }) }

    if (verTelemetria) {
        AlertDialog(
            onDismissRequest = { verTelemetria = false },
            title = { Text("Última telemetria enviada") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(telemetry.status ?: "", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        telemetry.ultimoEnvio ?: "Nada enviado ainda.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { verTelemetria = false }) { Text("Fechar") } },
        )
    }

    if (confirmRemoveOwner) {
        AlertDialog(
            onDismissRequest = { confirmRemoveOwner = false },
            title = { Text("Remover device owner?") },
            text = {
                Text(
                    "O aparelho deixa de ser travado pelo HubWeb e o app poderá ser desinstalado. " +
                        "Para travar de novo será preciso rodar o comando do adb (aparelho sem contas).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    releaseDeviceOwner(ctx)
                    isOwner = false
                    confirmRemoveOwner = false
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveOwner = false }) { Text("Cancelar") } },
        )
    }
}

/** Sai do lock task e abre mão do device owner (único jeito de desinstalar o app sem restaurar de fábrica). */
@Suppress("DEPRECATION")
private fun releaseDeviceOwner(ctx: Context) {
    try { (ctx as? Activity)?.stopLockTask() } catch (_: Exception) {}
    ctx.getSystemService(DevicePolicyManager::class.java).clearDeviceOwnerApp(ctx.packageName)
}
