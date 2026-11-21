package br.com.hubweb

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.roundToInt

/** Estado de uma calculadora simples (4 operações), com BigDecimal para não gerar 0,1+0,2=0,30000000004. */
class CalcState {
    private var entry by mutableStateOf("0") // número em digitação ou resultado, com ponto decimal
    private var acc: BigDecimal? = null
    private var op: Char? = null
    private var fresh = true // o próximo dígito começa um número novo
    private var error by mutableStateOf(false)

    val shown: String get() = if (error) "Erro" else entry.replace('.', ',')

    fun digit(d: Char) {
        if (error) clear()
        when {
            fresh -> { entry = d.toString(); fresh = false }
            entry == "0" -> entry = d.toString()
            entry.length < 16 -> entry += d
        }
    }

    fun dot() {
        if (error) clear()
        if (fresh) { entry = "0."; fresh = false } else if ('.' !in entry) entry += "."
    }

    fun back() {
        if (error) { clear(); return }
        if (fresh) return
        entry = entry.dropLast(1).let { if (it.isEmpty() || it == "-") "0" else it }
    }

    fun negate() {
        if (error || entry == "0") return
        entry = if (entry.startsWith("-")) entry.drop(1) else "-$entry"
    }

    fun operator(o: Char) {
        if (error) return
        if (op != null && !fresh) compute() else if (op == null) acc = entry.toBigDecimalOrNull()
        if (error) return
        op = o
        fresh = true
    }

    fun equals() {
        if (error) return
        if (op != null && !fresh) compute()
        if (error) return
        op = null
        acc = null
        fresh = true
    }

    fun clear() {
        entry = "0"; acc = null; op = null; fresh = true; error = false
    }

    private fun compute() {
        val a = acc ?: return
        val b = entry.toBigDecimalOrNull() ?: return
        val mc = MathContext(12, RoundingMode.HALF_UP)
        val r = when (op) {
            '+' -> a.add(b)
            '-' -> a.subtract(b)
            '*' -> a.multiply(b)
            '/' -> if (b.signum() == 0) { fail(); return } else a.divide(b, mc)
            else -> return
        }.round(mc)
        acc = r
        entry = (if (r.signum() == 0) BigDecimal.ZERO else r.stripTrailingZeros()).toPlainString()
    }

    private fun fail() {
        error = true; acc = null; op = null; fresh = true
    }
}

@Composable
internal fun CalculatorDialog(calc: CalcState, onDismiss: () -> Unit, onHide: () -> Unit, canHide: Boolean = true) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    calc.shown,
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    fontSize = 34.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("C") { calc.clear() }
                    Key("←") { calc.back() }
                    Key("±") { calc.negate() }
                    Key("÷", op = true) { calc.operator('/') }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("7") { calc.digit('7') }; Key("8") { calc.digit('8') }; Key("9") { calc.digit('9') }
                    Key("×", op = true) { calc.operator('*') }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("4") { calc.digit('4') }; Key("5") { calc.digit('5') }; Key("6") { calc.digit('6') }
                    Key("-", op = true) { calc.operator('-') }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("1") { calc.digit('1') }; Key("2") { calc.digit('2') }; Key("3") { calc.digit('3') }
                    Key("+", op = true) { calc.operator('+') }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("0", weight = 2f) { calc.digit('0') }
                    Key(",") { calc.dot() }
                    Key("=", op = true) { calc.equals() }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (canHide) TextButton(onClick = onHide) { Text("Ocultar botão") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Fechar") }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Key(label: String, op: Boolean = false, weight: Float = 1f, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(weight).height(52.dp),
        contentPadding = PaddingValues(0.dp),
        colors = if (op) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
    ) { Text(label, fontSize = 20.sp) }
}
