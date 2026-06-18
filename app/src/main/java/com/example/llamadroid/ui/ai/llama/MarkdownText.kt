package com.example.llamadroid.ui.ai.llama

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R

private const val URL_ANNOTATION_TAG = "url"
private val HyperlinkBlue = Color(0xFF1A73E8)
private val PlainUrlPattern = Regex("""(?i)(https?://|www\.)[^\s<>\[\]{}"']+""")
private val RawKnowledgeChunkUrlPattern = Regex("""(?i)kb://chunk/\d+""")
private val ChunkReferencePattern = Regex("""(?i)\[?chunk_id\s*=\s*(\d+)]?""")
private val TrailingUrlPunctuation = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

/**
 * Lightweight Markdown renderer for chat messages.
 * Supports: headers, tables, links, bold, italic, inline code, fenced code blocks with copy, bullet/numbered lists.
 */
@Composable
fun MarkdownText(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Boolean = { false }
) {
    val blocks = parseIntoBlocks(text)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.CodeBlock -> CodeBlockView(block)
                is MdBlock.TableBlock -> MarkdownTableView(block, textColor, onLinkClick)
                is MdBlock.TextBlock -> MarkdownSpannedText(block.content, textColor, onLinkClick)
            }
        }
    }
}

// ─── Block Model ─────────────────────────────────────────────────────────────

private sealed class MdBlock {
    data class TextBlock(val content: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class TableBlock(val header: List<String>, val rows: List<List<String>>) : MdBlock()
}

// ─── Block Parser ────────────────────────────────────────────────────────────
// Splits raw markdown into alternating text blocks and fenced code blocks.

private fun parseIntoBlocks(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    var cursor = 0

    while (cursor < raw.length) {
        val fenceStart = raw.indexOf("```", startIndex = cursor)
        if (fenceStart < 0) {
            appendTextAndTableBlocks(blocks, raw.substring(cursor).trim())
            break
        }

        if (fenceStart > cursor) {
            appendTextAndTableBlocks(blocks, raw.substring(cursor, fenceStart).trim())
        }

        val afterFence = fenceStart + 3
        val headerEnd = raw.indexOf('\n', startIndex = afterFence)
        if (headerEnd < 0) {
            val language = raw.substring(afterFence).trim()
            blocks.add(MdBlock.CodeBlock(language, ""))
            break
        }

        val language = raw.substring(afterFence, headerEnd).trim()
        val codeStart = headerEnd + 1
        val closingFence = raw.indexOf("```", startIndex = codeStart)
        if (closingFence < 0) {
            blocks.add(MdBlock.CodeBlock(language, raw.substring(codeStart).trimEnd()))
            break
        }

        blocks.add(
            MdBlock.CodeBlock(
                language = language,
                code = raw.substring(codeStart, closingFence).trimEnd()
            )
        )
        cursor = closingFence + 3
    }

    if (blocks.isEmpty() && raw.isNotBlank()) {
        blocks.add(MdBlock.TextBlock(raw.trim()))
    }

    return blocks
}

private data class ParsedTable(
    val header: List<String>,
    val rows: List<List<String>>,
    val endLineExclusive: Int
)

private fun appendTextAndTableBlocks(blocks: MutableList<MdBlock>, rawText: String) {
    val text = rawText.trim()
    if (text.isEmpty()) return

    val lines = text.split('\n')
    val textBuffer = mutableListOf<String>()

    fun flushTextBuffer() {
        val content = textBuffer.joinToString("\n").trim()
        if (content.isNotEmpty()) {
            blocks.add(MdBlock.TextBlock(content))
        }
        textBuffer.clear()
    }

    var index = 0
    while (index < lines.size) {
        val table = parseTableAt(lines, index)
        if (table != null) {
            flushTextBuffer()
            blocks.add(MdBlock.TableBlock(table.header, table.rows))
            index = table.endLineExclusive
        } else {
            textBuffer.add(lines[index])
            index++
        }
    }

    flushTextBuffer()
}

private fun parseTableAt(lines: List<String>, startLine: Int): ParsedTable? {
    if (startLine + 1 >= lines.size) return null
    val header = parseTableRow(lines[startLine]) ?: return null
    if (header.size < 2) return null

    if (!isTableSeparator(lines[startLine + 1])) {
        val firstRow = parseTableRow(lines[startLine + 1]) ?: return null
        val rows = mutableListOf(firstRow)
        var index = startLine + 2
        while (index < lines.size) {
            val row = parseTableRow(lines[index]) ?: break
            if (isTableSeparator(lines[index])) break
            rows.add(row)
            index++
        }
        return ParsedTable(
            header = header,
            rows = rows,
            endLineExclusive = index
        )
    }

    val rows = mutableListOf<List<String>>()
    var index = startLine + 2
    while (index < lines.size) {
        val row = parseTableRow(lines[index]) ?: break
        if (isTableSeparator(lines[index])) break
        rows.add(row)
        index++
    }

    return ParsedTable(
        header = header,
        rows = rows,
        endLineExclusive = index
    )
}

private fun parseTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return null
    val content = trimmed
        .removePrefix("|")
        .removeSuffix("|")
    val cells = splitTableCells(content).map { it.trim() }
    return cells.takeIf { it.size >= 2 }
}

private fun splitTableCells(line: String): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    for (char in line) {
        when {
            escaped -> {
                if (char != '|') current.append('\\')
                current.append(char)
                escaped = false
            }
            char == '\\' -> escaped = true
            char == '|' -> {
                cells.add(current.toString())
                current.clear()
            }
            else -> current.append(char)
        }
    }
    if (escaped) current.append('\\')
    cells.add(current.toString())
    return cells
}

private fun isTableSeparator(line: String): Boolean {
    val cells = parseTableRow(line) ?: return false
    return cells.size >= 2 && cells.all { it.matches(Regex(""":?-+:?""")) }
}

// ─── Code Block Composable ───────────────────────────────────────────────────

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest

    Surface(
        color = codeBg,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header: language label + copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.language.ifEmpty { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(block.code))
                        Toast.makeText(context, context.getString(R.string.llama_code_copied), Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.llama_copy_code),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.llama_copy_code),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                }
            }

            // Code content — horizontally scrollable
            SelectionContainer {
                Text(
                    text = block.code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                        .fillMaxWidth(),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ─── Table Composable ────────────────────────────────────────────────────────

@Composable
private fun MarkdownTableView(
    block: MdBlock.TableBlock,
    textColor: Color,
    onLinkClick: (String) -> Boolean
) {
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val inlineCodeColor = MaterialTheme.colorScheme.primary
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = inlineCodeBg,
        color = inlineCodeColor,
        fontSize = 14.sp
    )
    val columnWidths = remember(block) { tableColumnWidths(block) }
    val totalWidth = columnWidths.fold(0.dp) { total, width -> total + width }

    DisableSelection {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Column(modifier = Modifier.width(totalWidth)) {
                MarkdownTableRow(
                    cells = block.header,
                    columnWidths = columnWidths,
                    textColor = textColor,
                    codeStyle = codeStyle,
                    onLinkClick = onLinkClick,
                    background = MaterialTheme.colorScheme.surfaceContainerHigh,
                    header = true
                )
                block.rows.forEachIndexed { index, row ->
                    MarkdownTableRow(
                        cells = row,
                        columnWidths = columnWidths,
                        textColor = textColor,
                        codeStyle = codeStyle,
                        onLinkClick = onLinkClick,
                        background = if (index % 2 == 0) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
                        },
                        header = false
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    columnWidths: List<Dp>,
    textColor: Color,
    codeStyle: SpanStyle,
    onLinkClick: (String) -> Boolean,
    background: Color,
    header: Boolean
) {
    Row {
        columnWidths.forEachIndexed { index, width ->
            Box(
                modifier = Modifier
                    .width(width)
                    .heightIn(min = 40.dp)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .background(background)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                MarkdownInlineText(
                    text = cells.getOrNull(index).orEmpty(),
                    textColor = textColor,
                    codeStyle = codeStyle,
                    onLinkClick = onLinkClick,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                )
            }
        }
    }
}

private fun tableColumnWidths(block: MdBlock.TableBlock): List<Dp> {
    val columnCount = tableColumnCount(block).coerceAtLeast(1)
    return List(columnCount) { column ->
        val maxLength = sequenceOf(block.header)
            .plus(block.rows.asSequence())
            .map { it.getOrNull(column).orEmpty().length }
            .maxOrNull()
            ?: 0
        (maxLength.coerceIn(6, 32) * 7 + 56).coerceIn(104, 280).dp
    }
}

private fun tableColumnCount(block: MdBlock.TableBlock): Int =
    sequenceOf(block.header)
        .plus(block.rows.asSequence())
        .maxOfOrNull { it.size }
        ?: 0

private data class ParsedHeading(val level: Int, val text: String)

private val HeadingPattern = Regex("""^(#{1,6})\s*(\S.*)$""")
private val SetextHeadingPattern = Regex("""^(=+|-+)\s*$""")

private fun headingForLine(line: String): ParsedHeading? {
    val match = HeadingPattern.matchEntire(line.trimStart()) ?: return null
    val text = match.groupValues[2].trim()
    if (text.isBlank()) return null
    return ParsedHeading(level = match.groupValues[1].length, text = text)
}

private fun setextHeadingLevelForLine(line: String): Int? {
    val marker = SetextHeadingPattern.matchEntire(line.trim())?.groupValues?.getOrNull(1) ?: return null
    return when (marker.firstOrNull()) {
        '=' -> 1
        '-' -> 2
        else -> null
    }
}

@Composable
private fun headingTextStyle(level: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when (level) {
        1 -> typography.titleLarge
        2 -> typography.titleMedium
        3 -> typography.titleSmall
        else -> typography.bodyLarge
    }.copy(fontWeight = FontWeight.Bold)
}

private fun headingTopPadding(level: Int): Dp =
    when (level) {
        1 -> 8.dp
        2 -> 6.dp
        3 -> 4.dp
        else -> 3.dp
    }

// ─── Inline Markdown Renderer ────────────────────────────────────────────────
// Renders a text block with headers, bold, italic, inline code, and lists.

@Composable
private fun MarkdownSpannedText(
    content: String,
    textColor: Color,
    onLinkClick: (String) -> Boolean
) {
    val lines = content.split("\n")
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val inlineCodeColor = MaterialTheme.colorScheme.primary
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = inlineCodeBg,
        color = inlineCodeColor,
        fontSize = 14.sp
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val trimmed = line.trimStart()
            val heading = headingForLine(trimmed)
            val setextHeadingLevel = if (trimmed.isNotBlank() && lineIndex + 1 < lines.size) {
                setextHeadingLevelForLine(lines[lineIndex + 1])
            } else {
                null
            }
            when {
                setextHeadingLevel != null -> {
                    MarkdownInlineText(
                        text = trimmed.trimEnd(),
                        textColor = textColor,
                        codeStyle = codeStyle,
                        onLinkClick = onLinkClick,
                        style = headingTextStyle(setextHeadingLevel),
                        modifier = Modifier.padding(top = headingTopPadding(setextHeadingLevel))
                    )
                    lineIndex += 2
                    continue
                }
                // Headers
                heading != null -> {
                    MarkdownInlineText(
                        text = heading.text,
                        textColor = textColor,
                        codeStyle = codeStyle,
                        onLinkClick = onLinkClick,
                        style = headingTextStyle(heading.level),
                        modifier = Modifier.padding(top = headingTopPadding(heading.level))
                    )
                }
                // Task lists
                trimmed.matches(Regex("""^[-*]\s+\[[ xX]]\s+.*""")) -> {
                    val checked = trimmed.contains("[x]", ignoreCase = true)
                    val itemText = trimmed.replace(Regex("""^[-*]\s+\[[ xX]]\s+"""), "")
                    Row(
                        modifier = Modifier.padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            modifier = Modifier.size(28.dp)
                        )
                        MarkdownInlineText(
                            text = itemText,
                            textColor = textColor,
                            codeStyle = codeStyle,
                            onLinkClick = onLinkClick,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Bullet lists
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Text("• ", color = textColor, fontSize = 16.sp)
                        MarkdownInlineText(
                            text = trimmed.drop(2),
                            textColor = textColor,
                            codeStyle = codeStyle,
                            onLinkClick = onLinkClick,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Numbered lists
                trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                    val number = trimmed.substringBefore(".")
                    val rest = trimmed.substringAfter(". ")
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Text("$number. ", color = textColor, fontSize = 16.sp)
                        MarkdownInlineText(
                            text = rest,
                            textColor = textColor,
                            codeStyle = codeStyle,
                            onLinkClick = onLinkClick,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Empty lines as spacers
                trimmed.isEmpty() -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                // Regular paragraph
                else -> {
                    MarkdownInlineText(
                        text = trimmed,
                        textColor = textColor,
                        codeStyle = codeStyle,
                        onLinkClick = onLinkClick,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
                    )
                }
            }
            lineIndex++
        }
    }
}

@Composable
private fun MarkdownInlineText(
    text: String,
    textColor: Color,
    codeStyle: SpanStyle,
    onLinkClick: (String) -> Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default
) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(text, textColor, codeStyle) {
        buildInlineAnnotated(text, textColor, codeStyle)
    }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = textColor),
        onClick = { offset ->
            annotated
                .getStringAnnotations(URL_ANNOTATION_TAG, offset, offset)
                .firstOrNull()
                ?.let { annotation ->
                    if (!onLinkClick(annotation.item)) {
                        runCatching { uriHandler.openUri(annotation.item) }
                    }
                }
        }
    )
}

// ─── Inline Span Builder ─────────────────────────────────────────────────────
// Handles **bold**, *italic*, and `inline code` within a single line.

private fun buildInlineAnnotated(
    text: String,
    defaultColor: Color,
    codeStyle: SpanStyle
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text[i] == '[' && (i == 0 || text[i - 1] != '!') -> {
                    val markdownLink = parseMarkdownLinkAt(text, i)
                    if (markdownLink != null) {
                        appendHyperlink(
                            label = markdownLink.label.ifBlank { markdownLink.url },
                            url = markdownLink.url
                        )
                        i = markdownLink.endExclusive
                    } else {
                        val chunkReference = chunkReferenceAt(text, i)
                        if (chunkReference != null) {
                            appendHyperlink(chunkReference.displayText, chunkReference.url)
                            i = chunkReference.endExclusive
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                }
                plainUrlAt(text, i) != null -> {
                    val plainUrl = plainUrlAt(text, i)!!
                    appendHyperlink(plainUrl.displayText, plainUrl.url)
                    if (plainUrl.trailingText.isNotEmpty()) {
                        append(plainUrl.trailingText)
                    }
                    i = plainUrl.endExclusive
                }
                rawKnowledgeChunkUrlAt(text, i) != null -> {
                    val chunkUrl = rawKnowledgeChunkUrlAt(text, i)!!
                    appendHyperlink(chunkUrl.displayText, chunkUrl.url)
                    if (chunkUrl.trailingText.isNotEmpty()) {
                        append(chunkUrl.trailingText)
                    }
                    i = chunkUrl.endExclusive
                }
                chunkReferenceAt(text, i) != null -> {
                    val chunkReference = chunkReferenceAt(text, i)!!
                    appendHyperlink(chunkReference.displayText, chunkReference.url)
                    i = chunkReference.endExclusive
                }
                // Inline code: `...`
                text[i] == '`' && i + 1 < text.length -> {
                    val endTick = text.indexOf('`', i + 1)
                    if (endTick != -1) {
                        withStyle(codeStyle) {
                            append(" ")
                            append(text.substring(i + 1, endTick))
                            append(" ")
                        }
                        i = endTick + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Bold: **...**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = defaultColor)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Italic: *...*  (single asterisk, not double)
                text[i] == '*' && (i == 0 || text[i - 1] != '*') && i + 1 < text.length && text[i + 1] != '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1 && (end + 1 >= text.length || text[end + 1] != '*')) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Regular character
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

private data class ParsedMarkdownLink(
    val label: String,
    val url: String,
    val endExclusive: Int
)

private data class ParsedPlainUrl(
    val displayText: String,
    val trailingText: String,
    val url: String,
    val endExclusive: Int
)

private fun parseMarkdownLinkAt(text: String, start: Int): ParsedMarkdownLink? {
    if (start !in text.indices || text[start] != '[') return null
    val labelEnd = findMarkdownClosingBracket(text, start + 1) ?: return null
    var cursor = labelEnd + 1
    while (cursor < text.length && text[cursor].isWhitespace()) cursor++
    if (cursor >= text.length || text[cursor] != '(') return null
    val urlStart = cursor + 1
    val urlEnd = findMarkdownClosingParen(text, urlStart) ?: return null
    val rawUrl = text.substring(urlStart, urlEnd).trim()
    val normalizedUrl = normalizeMarkdownUrl(rawUrl) ?: return null
    return ParsedMarkdownLink(
        label = unescapeMarkdownLabel(text.substring(start + 1, labelEnd)),
        url = normalizedUrl,
        endExclusive = urlEnd + 1
    )
}

private fun findMarkdownClosingBracket(text: String, start: Int): Int? {
    var escaped = false
    for (index in start until text.length) {
        val char = text[index]
        when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            char == ']' -> return index
        }
    }
    return null
}

private fun findMarkdownClosingParen(text: String, start: Int): Int? {
    var escaped = false
    var depth = 0
    for (index in start until text.length) {
        val char = text[index]
        when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            char == '(' -> depth++
            char == ')' -> {
                if (depth == 0) return index
                depth--
            }
        }
    }
    return null
}

private fun unescapeMarkdownLabel(label: String): String {
    val result = StringBuilder()
    var escaped = false
    for (char in label) {
        if (escaped) {
            result.append(char)
            escaped = false
        } else if (char == '\\') {
            escaped = true
        } else {
            result.append(char)
        }
    }
    if (escaped) result.append('\\')
    return result.toString()
}

private fun plainUrlAt(text: String, start: Int): ParsedPlainUrl? {
    val match = PlainUrlPattern.find(text, start)?.takeIf { it.range.first == start } ?: return null
    val raw = match.value
    val display = raw.trimEnd { it in TrailingUrlPunctuation }
    if (display.isBlank()) return null
    val normalizedUrl = normalizeBrowserUrl(display) ?: return null
    return ParsedPlainUrl(
        displayText = display,
        trailingText = raw.drop(display.length),
        url = normalizedUrl,
        endExclusive = match.range.last + 1
    )
}

private fun rawKnowledgeChunkUrlAt(text: String, start: Int): ParsedPlainUrl? {
    val match = RawKnowledgeChunkUrlPattern.find(text, start)?.takeIf { it.range.first == start } ?: return null
    val raw = match.value
    val display = raw.trimEnd { it in TrailingUrlPunctuation }
    if (display.isBlank()) return null
    return ParsedPlainUrl(
        displayText = display,
        trailingText = raw.drop(display.length),
        url = display,
        endExclusive = match.range.last + 1
    )
}

private fun chunkReferenceAt(text: String, start: Int): ParsedPlainUrl? {
    val match = ChunkReferencePattern.find(text, start)?.takeIf { it.range.first == start } ?: return null
    val chunkId = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return null
    return ParsedPlainUrl(
        displayText = match.value,
        trailingText = "",
        url = "kb://chunk/$chunkId",
        endExclusive = match.range.last + 1
    )
}

internal fun knowledgeChunkUriForReferenceAt(text: String, start: Int = 0): String? =
    chunkReferenceAt(text, start)?.url

internal fun knowledgeChunkUriForRawUrlAt(text: String, start: Int = 0): String? =
    rawKnowledgeChunkUrlAt(text, start)?.url

internal fun markdownLinkUriForLinkAt(text: String, start: Int = 0): String? =
    parseMarkdownLinkAt(text, start)?.url

internal fun markdownLinkLabelForLinkAt(text: String, start: Int = 0): String? =
    parseMarkdownLinkAt(text, start)?.label

internal fun markdownBlockKindsForText(text: String): List<String> =
    parseIntoBlocks(text).map { block ->
        when (block) {
            is MdBlock.CodeBlock -> "code"
            is MdBlock.TableBlock -> "table"
            is MdBlock.TextBlock -> "text"
        }
    }

internal fun markdownTableShapeForText(text: String): Pair<Int, Int>? {
    val table = parseIntoBlocks(text).filterIsInstance<MdBlock.TableBlock>().firstOrNull() ?: return null
    return tableColumnCount(table) to table.rows.size
}

internal fun markdownCodeBlockForText(text: String): Pair<String, String>? =
    parseIntoBlocks(text).filterIsInstance<MdBlock.CodeBlock>().firstOrNull()?.let { it.language to it.code }

internal fun markdownHeadingLevelForLine(line: String): Int? =
    headingForLine(line)?.level

internal fun markdownSetextHeadingLevelForLine(line: String): Int? =
    setextHeadingLevelForLine(line)

internal fun normalizeMarkdownUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    val withoutTitle = if (trimmed.startsWith("<")) {
        trimmed.substringBefore('>').removePrefix("<")
    } else {
        trimmed.substringBefore(' ')
    }.trim()
    val normalizedCandidate = withoutTitle
        .removeSurrounding("<", ">")
        .trim()
    return when {
        normalizedCandidate.startsWith("kb://chunk/", ignoreCase = true) -> normalizedCandidate
        else -> normalizeBrowserUrl(normalizedCandidate)
    }
}

private fun normalizeBrowserUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("www.", ignoreCase = true) -> "https://$trimmed"
        else -> null
    }
}

private fun AnnotatedString.Builder.appendHyperlink(label: String, url: String) {
    val start = length
    append(label)
    addStringAnnotation(URL_ANNOTATION_TAG, url, start, length)
    addStyle(
        SpanStyle(
            color = HyperlinkBlue,
            textDecoration = TextDecoration.Underline
        ),
        start,
        length
    )
}
