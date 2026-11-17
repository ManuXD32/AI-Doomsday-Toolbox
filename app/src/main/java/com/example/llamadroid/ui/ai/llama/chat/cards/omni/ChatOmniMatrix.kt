package com.example.llamadroid.ui.ai.llama.chat.cards.omni

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.omni.OmniBlock
import com.example.llamadroid.chat.cards.omni.OmniExpr
import com.example.llamadroid.chat.cards.omni.OmniMatrix

private const val MAX_DIM = 4
private const val SEP = '|'

private val OPS = listOf(
    R.string.chat_card_omni_matrix_op_add, R.string.chat_card_omni_matrix_op_sub, R.string.chat_card_omni_matrix_op_mul,
    R.string.chat_card_omni_matrix_op_det, R.string.chat_card_omni_matrix_op_inv, R.string.chat_card_omni_matrix_op_transpose,
)

/** Cells live in a fixed 4x4 grid (index r*4+c) joined by [SEP], so resizing keeps the typed values. */
private fun decodeCells(s: String): List<String> = s.split(SEP).let { l -> List(MAX_DIM * MAX_DIM) { l.getOrElse(it) { "0" } } }

private fun parseMatrix(cells: List<String>, rows: Int, cols: Int): List<List<Double>>? {
    val m = List(rows) { r ->
        List(cols) { c -> cells[r * MAX_DIM + c].trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null }
    }
    return m
}

/** Matrix calculator: A and B (up to 4x4) with size steppers, an operation row and the result. Local state, not persisted. */
@Composable
internal fun OmniMatrixView(b: OmniBlock.Matrix) {
    var aCells by rememberSaveable { mutableStateOf(List(MAX_DIM * MAX_DIM) { "0" }.joinToString(SEP.toString())) }
    var bCells by rememberSaveable { mutableStateOf(List(MAX_DIM * MAX_DIM) { "0" }.joinToString(SEP.toString())) }
    var aRows by rememberSaveable { mutableIntStateOf(b.rows.coerceIn(1, MAX_DIM)) }
    var aCols by rememberSaveable { mutableIntStateOf(b.cols.coerceIn(1, MAX_DIM)) }
    var bRows by rememberSaveable { mutableIntStateOf(b.rows.coerceIn(1, MAX_DIM)) }
    var bCols by rememberSaveable { mutableIntStateOf(b.cols.coerceIn(1, MAX_DIM)) }
    var op by rememberSaveable { mutableIntStateOf(0) }
    val a = decodeCells(aCells)
    val bb = decodeCells(bCells)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        b.label?.takeIf { it.isNotBlank() }?.let { OmniLabel(it) }
        MatrixEditor("A", a, aRows, aCols,
            onRows = { aRows = it }, onCols = { aCols = it },
            onCell = { i, t -> aCells = a.toMutableList().also { l -> l[i] = t }.joinToString(SEP.toString()) })
        MatrixEditor("B", bb, bRows, bCols,
            onRows = { bRows = it }, onCols = { bCols = it },
            onCell = { i, t -> bCells = bb.toMutableList().also { l -> l[i] = t }.joinToString(SEP.toString()) })
        for (rowOps in listOf(0..2, 3..5)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in rowOps) {
                    FilterChip(
                        selected = op == i,
                        onClick = { op = i },
                        label = { Text(stringResource(OPS[i]), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        MatrixResult(op, parseMatrix(a, aRows, aCols), parseMatrix(bb, bRows, bCols))
    }
}

@Composable
private fun MatrixEditor(
    name: String, cells: List<String>, rows: Int, cols: Int,
    onRows: (Int) -> Unit, onCols: (Int) -> Unit, onCell: (Int, String) -> Unit,
) {
    val desc = stringResource(R.string.chat_card_omni_matrix_desc, name, rows.toString(), cols.toString())
    val rowsLabel = stringResource(R.string.chat_card_omni_matrix_rows)
    val colsLabel = stringResource(R.string.chat_card_omni_matrix_cols)
    val dec = stringResource(R.string.chat_card_omni_decrease)
    val inc = stringResource(R.string.chat_card_omni_increase)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics { contentDescription = desc }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.widthIn(min = 20.dp))
            OmniStepButton("−", "$dec: $rowsLabel", rows > 1) { onRows(rows - 1) }
            Text(rows.toString(), style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.widthIn(min = 14.dp), textAlign = TextAlign.Center)
            OmniStepButton("+", "$inc: $rowsLabel", rows < MAX_DIM) { onRows(rows + 1) }
            Text("×", style = MaterialTheme.typography.titleSmall)
            OmniStepButton("−", "$dec: $colsLabel", cols > 1) { onCols(cols - 1) }
            Text(cols.toString(), style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.widthIn(min = 14.dp), textAlign = TextAlign.Center)
            OmniStepButton("+", "$inc: $colsLabel", cols < MAX_DIM) { onCols(cols + 1) }
        }
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until cols) {
                    val i = r * MAX_DIM + c
                    MatrixCell(cells[i], Modifier.weight(1f).widthIn(max = 72.dp)) { onCell(i, it) }
                }
            }
        }
    }
}

/** Compact numeric cell (a bordered BasicTextField, 44 dp high) so four of them fit a 360 dp card. */
@Composable
private fun MatrixCell(value: String, modifier: Modifier, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' || ch == '-' }.take(10)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        decorationBox = { inner ->
            Box(
                Modifier.heightIn(min = 44.dp).fillMaxWidth()
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), MaterialTheme.shapes.small)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) { inner() }
        },
        modifier = modifier
    )
}

@Composable
private fun MatrixResult(op: Int, a: List<List<Double>>?, b: List<List<Double>>?) {
    val errorColor = MaterialTheme.colorScheme.error
    val result = if (a == null || (op <= 2 && b == null)) null else when (op) {
        0 -> OmniMatrix.add(a, b!!)
        1 -> OmniMatrix.sub(a, b!!)
        2 -> OmniMatrix.mul(a, b!!)
        3 -> OmniMatrix.det(a)
        4 -> OmniMatrix.inverse(a)
        else -> OmniMatrix.transpose(a)
    }
    when (result) {
        null -> Text(stringResource(R.string.chat_card_omni_matrix_invalid), style = MaterialTheme.typography.bodySmall, color = errorColor)
        is OmniMatrix.Result.Error -> Text(
            stringResource(
                when (result.reason) {
                    "singular" -> R.string.chat_card_omni_matrix_singular
                    "not_square" -> R.string.chat_card_omni_matrix_not_square
                    else -> R.string.chat_card_omni_matrix_dims
                }
            ),
            style = MaterialTheme.typography.bodySmall, color = errorColor
        )
        is OmniMatrix.Result.Ok -> {
            result.scalar?.let {
                Text(
                    stringResource(R.string.chat_card_omni_matrix_scalar, stringResource(OPS[op]), OmniExpr.format(it, 4)),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            result.matrix?.let { m ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    m.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { v ->
                                Text(
                                    OmniExpr.format(v, 4),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).widthIn(max = 72.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
