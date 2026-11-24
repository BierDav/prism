// The little Markdown the assistant writes, parsed for the transcript:
// paragraphs, headings, bullet and numbered lists, fenced code, and inside
// lines **bold**, *italic*, `code` and [links](https://…). Anything else
// stays as it was written. The model's text is untrusted: this only styles
// it, and links show their target (assistant/Transcript UI).

package at.quickme.prism.assistant.core

object Markdown {
    sealed interface Block {
        data class Paragraph(val spans: List<Span>) : Block
        data class Heading(val level: Int, val spans: List<Span>) : Block
        /** A list item; [marker] is "•" or "3.". */
        data class Item(val marker: String, val spans: List<Span>, val depth: Int = 0) : Block
        data class Code(val text: String) : Block
    }

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
    )

    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")

    fun parse(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks.add(Block.Paragraph(inline(paragraph.joinToString("\n"))))
                paragraph.clear()
            }
        }
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code.add(lines[i++])
                    blocks.add(Block.Code(code.joinToString("\n")))
                }
                trimmed.isEmpty() -> flush()
                heading.matches(trimmed) -> {
                    flush()
                    val m = heading.matchEntire(trimmed)!!
                    blocks.add(Block.Heading(m.groupValues[1].length, inline(m.groupValues[2])))
                }
                bullet.matches(line) -> {
                    flush()
                    val m = bullet.matchEntire(line)!!
                    blocks.add(Block.Item("•", inline(m.groupValues[2]), depth(m.groupValues[1])))
                }
                numbered.matches(line) -> {
                    flush()
                    val m = numbered.matchEntire(line)!!
                    blocks.add(Block.Item(m.groupValues[2] + ".", inline(m.groupValues[3]), depth(m.groupValues[1])))
                }
                else -> paragraph.add(trimmed)
            }
            i++
        }
        flush()
        return blocks
    }

    private fun depth(indent: String) = (indent.replace("\t", "  ").length / 2).coerceAtMost(3)

    /** A line's styled runs. Unclosed markers stay as text. */
    fun inline(text: String): List<Span> {
        val out = ArrayList<Span>()
        val plain = StringBuilder()
        fun emit(span: Span) {
            if (plain.isNotEmpty()) {
                out.add(Span(plain.toString()))
                plain.clear()
            }
            out.add(span)
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_[]()#" -> {
                    plain.append(text[i + 1]); i += 2
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) { emit(Span(text.substring(i + 1, end), code = true)); i = end + 1 } else { plain.append(c); i++ }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i + 2) {
                        inline(text.substring(i + 2, end)).forEach { emit(it.copy(bold = true)) }
                        i = end + 2
                    } else { plain.append(marker); i += 2 }
                }
                (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() &&
                    // An underscore inside a word (snake_case) isn't emphasis.
                    !(c == '_' && i > 0 && text[i - 1].isLetterOrDigit()) -> {
                    val end = findClosing(text, c, i + 1)
                    if (end > i + 1) {
                        inline(text.substring(i + 1, end)).forEach { emit(it.copy(italic = true)) }
                        i = end + 1
                    } else { plain.append(c); i++ }
                }
                c == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close > i) text.indexOf(')', close + 2) else -1
                    val target = if (end > close) text.substring(close + 2, end).trim() else ""
                    if (close > i && end > close && (target.startsWith("https://") || target.startsWith("http://"))) {
                        emit(Span(text.substring(i + 1, close), link = target))
                        i = end + 1
                    } else { plain.append(c); i++ }
                }
                else -> { plain.append(c); i++ }
            }
        }
        if (plain.isNotEmpty()) out.add(Span(plain.toString()))
        return out
    }

    private fun findClosing(text: String, marker: Char, from: Int): Int {
        var j = from
        while (j < text.length) {
            if (text[j] == marker && !text[j - 1].isWhitespace() &&
                (marker != '_' || j + 1 >= text.length || !text[j + 1].isLetterOrDigit())
            ) return j
            j++
        }
        return -1
    }

    /** The text without its markup, for the model's history and copying. */
    fun plain(text: String): String = parse(text).joinToString("\n") { b ->
        when (b) {
            is Block.Paragraph -> b.spans.joinToString("") { it.text }
            is Block.Heading -> b.spans.joinToString("") { it.text }
            is Block.Item -> "  ".repeat(b.depth) + b.marker + " " + b.spans.joinToString("") { it.text }
            is Block.Code -> b.text
        }
    }
}
