package br.com.hubweb

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Documentação e exemplo de JSON dentro do app (Configurações). Os textos vêm dos assets `README.md` e
 * `exemplo-apps.json`, copiados do projeto no build, então a documentação do app é sempre a mesma do repositório.
 */
@Composable
fun DocsDialog(initialTab: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(initialTab) }
    var copied by remember { mutableStateOf(false) }
    val readme = remember { readAsset(ctx, "README.md") }
    val example = remember { readAsset(ctx, "exemplo-apps.json") }
    val exemploDicas = remember { readAsset(ctx, "exemplo-dicas.json") }
    val blocks = remember(readme) { parseMarkdown(readme) }
    val clipboard = LocalClipboardManager.current
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // As abas rolam na horizontal, para caberem em telas estreitas.
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("Documentação", "Exemplo de JSON", "Exemplo de dicas").forEachIndexed { i, nome ->
                            if (tab == i) Button(onClick = { tab = i }) { Text(nome) }
                            else OutlinedButton(onClick = { tab = i }) { Text(nome) }
                        }
                    }
                    TextButton(onClick = onClose) { Text("Fechar") }
                }
                HorizontalDivider()
                if (tab == 0) {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(blocks) { MdView(it) }
                    }
                } else {
                    val jsonAtual = if (tab == 1) example else exemploDicas
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(jsonAtual)); copied = true }) {
                                Text("Copiar JSON")
                            }
                            if (copied) Text("  Copiado!", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll).horizontalScroll(hScroll)) {
                            SelectionContainer {
                                Text(jsonAtual, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun readAsset(ctx: Context, name: String): String =
    try {
        ctx.assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "Não foi possível carregar $name."
    }

// ---------------------------------------------------------------- mini renderizador de Markdown

private sealed interface Md
private class MdHeading(val level: Int, val text: String) : Md
private class MdPara(val text: String) : Md
private class MdBullet(val text: String, val indent: Int) : Md
private class MdCode(val text: String) : Md
private class MdRow(val text: String) : Md

/** Só o que o README usa: títulos, parágrafos, listas, blocos de código e tabelas (cada linha vira um item). */
private fun parseMarkdown(src: String): List<Md> {
    val out = ArrayList<Md>()
    val code = StringBuilder()
    var inCode = false
    val sep = Regex("^:?-{2,}:?$")
    val numbered = Regex("^\\s*\\d+\\.\\s+")
    for (raw in src.lines()) {
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out += MdCode(code.toString().trimEnd())
                code.clear()
            }
            inCode = !inCode
            continue
        }
        if (inCode) {
            code.appendLine(line)
            continue
        }
        val t = line.trimStart()
        when {
            line.isBlank() -> {}
            line.startsWith("#") -> {
                val n = line.takeWhile { it == '#' }.length
                out += MdHeading(n, line.drop(n).trim())
            }
            line.startsWith("|") -> {
                val cells = line.trim().trim('|').split("|").map { it.trim() }
                if (!cells.all { it.isEmpty() || sep.matches(it) }) out += MdRow(cells.filter { it.isNotEmpty() }.joinToString("  —  "))
            }
            t.startsWith("- ") -> out += MdBullet(t.drop(2), (line.length - t.length) / 2)
            numbered.containsMatchIn(line) -> out += MdBullet(t, (line.length - t.length) / 2)
            else -> out += MdPara(line.trim())
        }
    }
    return out
}

/** `**negrito**` e `código` dentro de uma linha. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        if (text.startsWith("**", i)) {
            val e = text.indexOf("**", i + 2)
            if (e > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, e)) }
                i = e + 2
                continue
            }
        }
        if (text[i] == '`') {
            val e = text.indexOf('`', i + 1)
            if (e > 0) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14000000))) {
                    append(text.substring(i + 1, e))
                }
                i = e + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}

@Composable
private fun MdView(b: Md) {
    when (b) {
        is MdHeading -> Text(
            inline(b.text),
            Modifier.padding(top = if (b.level <= 2) 20.dp else 12.dp, bottom = 4.dp),
            style = when (b.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.Bold,
        )
        is MdPara -> Text(inline(b.text), Modifier.padding(vertical = 3.dp), style = MaterialTheme.typography.bodyMedium)
        is MdBullet -> Text(
            inline("• " + b.text),
            Modifier.padding(start = (12 + b.indent * 12).dp, top = 2.dp, bottom = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        is MdRow -> Text(
            inline(b.text),
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        is MdCode -> Box(
            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .background(Color(0x12000000), RoundedCornerShape(6.dp))
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            SelectionContainer { Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
