package br.com.hubweb

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/**
 * Botão redondo e arrastável sobre qualquer tela. A posição é lembrada por `key`;
 * `slot` só define o lugar inicial (um acima do outro) enquanto o usuário não o move.
 */
@Composable
fun FloatingButton(
    prefs: Prefs,
    key: String,
    slot: Int,
    onClick: () -> Unit,
    /** A lista travou este botão: fica em `posicao` (ou no canto inferior direito) e não é arrastado. */
    travado: Boolean = false,
    posicao: PosicaoFab? = null,
    /** O usuário travou os botões: ninguém é arrastado, e cada um fica onde está. */
    userLocked: Boolean = false,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val sizeDp = 52.dp
        val sizePx = with(density) { sizeDp.toPx() }
        val gap = with(density) { 8.dp.toPx() }
        val maxX = maxOf(with(density) { maxWidth.toPx() } - sizePx, 0f)
        val maxY = maxOf(with(density) { maxHeight.toPx() } - sizePx, 0f)
        var pos by remember(key) {
            val saved = prefs.fabPos(key)
            mutableStateOf(
                if (saved != null) Offset(saved.first, saved.second)
                else Offset(maxX - with(density) { 16.dp.toPx() }, maxY * 0.75f - slot * (sizePx + gap)),
            )
        }

        // Travado pela lista: a posição vem do JSON e a de arrastar não vale. Travado pelo usuário: fica onde está.
        val fixa = if (travado) {
            posicaoFixa(posicao, slot, maxX, maxY, passo = sizePx + gap, margem = with(density) { 16.dp.toPx() })
        } else null
        val parado = travado || userLocked

        Box(
            Modifier
                .offset {
                    val p = fixa ?: pos
                    IntOffset(p.x.coerceIn(0f, maxX).roundToInt(), p.y.coerceIn(0f, maxY).roundToInt())
                }
                .size(sizeDp)
                .shadow(6.dp, CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .clip(CircleShape)
                .then(
                    if (parado) Modifier else Modifier.pointerInput(key, maxX, maxY) {
                        detectDragGestures(
                            onDragEnd = { prefs.setFabPos(key, pos.x, pos.y) },
                        ) { change, drag ->
                            change.consume()
                            pos = Offset((pos.x + drag.x).coerceIn(0f, maxX), (pos.y + drag.y).coerceIn(0f, maxY))
                        }
                    },
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/**
 * Posição de um botão travado pela lista. Com `posicao` explícita não empilha (quem configura escolhe lugares
 * diferentes); sem ela, os travados ficam no canto inferior direito, um acima do outro.
 */
private fun posicaoFixa(p: PosicaoFab?, slot: Int, maxX: Float, maxY: Float, passo: Float, margem: Float): Offset {
    val s = if (p != null) 0 else slot
    if (p?.x != null && p.y != null) return Offset(p.x * maxX, p.y * maxY)
    val canto = p?.canto ?: "inferior-direita"
    val x = if (canto.endsWith("esquerda")) margem else maxX - margem
    val y = when {
        canto.startsWith("superior") -> margem + s * passo
        canto.startsWith("meio") -> maxY / 2f + s * passo
        else -> maxY - margem - s * passo
    }
    return Offset(x, y)
}

/** Calculadora: botão que abre o popup (arrastável, a menos que a lista ou o usuário o travem). */
@Composable
fun CalculatorFab(
    prefs: Prefs,
    onHide: () -> Unit,
    canHide: Boolean = true,
    travada: Boolean = false,
    posicao: PosicaoFab? = null,
    userLocked: Boolean = false,
) {
    val calc = remember { CalcState() }
    var open by remember { mutableStateOf(false) }
    FloatingButton(
        prefs, "calc", slot = 0, onClick = { open = true },
        travado = travada, posicao = posicao, userLocked = userLocked,
    ) {
        Icon(painterResource(R.drawable.ic_calculate), "Calculadora", tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    if (open) {
        CalculatorDialog(calc, onDismiss = { open = false }, onHide = { open = false; onHide() }, canHide = canHide)
    }
}

/** Botão de uma URL da lista (`flutuantes`): mostra o ícone do item, ou a inicial do nome. */
@Composable
fun ItemFab(prefs: Prefs, item: AppItem, slot: Int, userLocked: Boolean = false, onClick: () -> Unit) {
    val icon = remember(item.icone) { decodeIcon(item.icone) }
    FloatingButton(
        prefs, "app:${item.id}", slot, onClick,
        travado = item.flutuanteTravado, posicao = item.flutuantePosicao, userLocked = userLocked,
    ) {
        if (icon != null) {
            Image(icon, item.nome, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(item.nome.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Popup com o WebView da URL. Voltar navega para trás na página e só fecha quando não há histórico. */
@Composable
fun PopupBrowser(
    entry: Entry,
    tips: TipsRepo,
    onClose: () -> Unit,
    onScan: (() -> Unit)? = null,
    /** Não nulo quando o item identifica o usuário por crachá: abre a troca de usuário. */
    onUser: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val reload = {
        if (entry.error != null || entry.webView.url == null) entry.webView.loadUrl(entry.item.startUrl(ctx))
        else entry.webView.reload()
    }
    Dialog(
        onDismissRequest = { if (entry.webView.canGoBack()) entry.webView.goBack() else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(entry.item.nome, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    onUser?.let { user ->
                        IconButton(onClick = user) { Icon(Icons.Default.Person, "Trocar usuário") }
                    }
                    onScan?.let { scan ->
                        IconButton(onClick = scan) { Icon(painterResource(R.drawable.ic_qr_scan), "Ler código com a câmera") }
                    }
                    if (entry.item.barraAtualizar) {
                        IconButton(onClick = reload) { Icon(Icons.Default.Refresh, "Atualizar página") }
                    }
                    if (entry.item.barraInicio) {
                        IconButton(onClick = { entry.webView.loadUrl(entry.item.startUrl(ctx)) }) {
                            Icon(Icons.Default.Home, "Página inicial")
                        }
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fechar") }
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
                            Button(onClick = { reload() }) { Text("Tentar novamente") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Identificação por crachá: o usuário digita a matrícula ou lê o crachá (câmera). `error` mostra o formato inválido.
 * `required` = sem identificação não abre o sistema (o botão vira "Voltar").
 */
@Composable
fun CrachaDialog(
    sistema: String,
    required: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!required) onCancel() },
        title = { Text("Identificação") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quem vai usar $sistema? Digite a matrícula ou leia o crachá.")
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(error ?: "Matrícula") },
                    isError = error != null,
                    singleLine = true,
                )
                androidx.compose.material3.OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_qr_scan), null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Ler crachá com a câmera")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(text.trim()) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text(if (required) "Voltar" else "Pular") } },
    )
}

/** Escolha do usuário: liga/desliga a calculadora e os botões da lista que não são obrigatórios. */
@Composable
fun FloatingButtonsDialog(
    calcEnabled: Boolean,
    /** A lista habilita a calculadora; false a tira da lista de escolha. */
    calcAvailable: Boolean,
    /** A lista torna a calculadora obrigatória: sempre ligada, sem opção de desligar. */
    calcForced: Boolean,
    onCalc: (Boolean) -> Unit,
    items: List<AppItem>,
    enabledIds: Set<String>,
    onToggle: (AppItem, Boolean) -> Unit,
    /** Chave do usuário: trava a posição de todos os botões (não dá para arrastar). */
    locked: Boolean,
    onLock: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Botões flutuantes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow(
                    label = "Travar a posição dos botões",
                    note = "Impede arrastar: cada botão fica onde está",
                    checked = locked,
                    enabled = true,
                    onChange = onLock,
                )
                if (calcAvailable) {
                    SwitchRow(
                        label = "Calculadora",
                        note = if (calcForced) "Obrigatório (definido pela lista)" else null,
                        checked = calcForced || calcEnabled,
                        enabled = !calcForced,
                        onChange = onCalc,
                    )
                }
                items.forEach { item ->
                    SwitchRow(
                        label = item.nome,
                        note = if (item.obrigatorio) "Obrigatório (definido pela lista)" else null,
                        checked = item.obrigatorio || item.id in enabledIds,
                        enabled = !item.obrigatorio,
                    ) { on -> onToggle(item, on) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun SwitchRow(label: String, note: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
