package x.vladgba.files

import java.nio.charset.Charset

/** Small read-only RTF text decoder. It never loads linked objects or executes fields. */
object RtfOps {
    private data class State(val hidden: Boolean, val unicodeFallback: Int)
    private val ignored = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
        "header", "footer", "headerl", "headerr", "footerl", "footerr", "xmlopen", "datastore")
    private val ansi = Charset.forName("windows-1252")

    fun preview(bytes: ByteArray): String {
        val source = String(bytes, Charsets.ISO_8859_1)
        require(source.startsWith("{\\rtf")) { "Not an RTF document" }
        val out = StringBuilder()
        val states = ArrayDeque<State>()
        var hidden = false
        var fallback = 1
        var codepage = ansi
        var skip = 0
        var i = 0
        while (i < source.length) {
            when (val c = source[i++]) {
                '{' -> states.addLast(State(hidden, fallback))
                '}' -> {
                    if (states.isNotEmpty()) {
                        val state = states.removeLast()
                        hidden = state.hidden
                        fallback = state.unicodeFallback
                    }
                }
                '\\' -> {
                    if (i >= source.length) break
                    val next = source[i]
                    if (next.isLetter()) {
                        val start = i
                        while (i < source.length && source[i].isLetter()) i++
                        val word = source.substring(start, i)
                        val numStart = i
                        if (i < source.length && source[i] == '-') i++
                        while (i < source.length && source[i].isDigit()) i++
                        val number = source.substring(numStart, i).toIntOrNull()
                        if (i < source.length && source[i] == ' ') i++
                        when {
                            word in ignored -> hidden = true
                            word == "ansicpg" && number != null -> codepage = try {
                                Charset.forName(if (number == 65001) "UTF-8" else "windows-$number")
                            } catch (_: Exception) { ansi }
                            word == "uc" -> fallback = (number ?: 1).coerceIn(0, 32)
                            word == "u" -> {
                                if (!hidden && number != null) out.append((number and 0xffff).toChar())
                                skip = fallback
                            }
                            word == "bin" -> i = (i + (number ?: 0).coerceAtLeast(0)).coerceAtMost(source.length)
                            hidden -> Unit
                            word == "par" || word == "line" -> out.append('\n')
                            word == "tab" -> out.append('\t')
                            word == "cell" -> out.append('\t')
                            word == "row" -> out.append('\n')
                            word == "emdash" -> out.append('—')
                            word == "endash" -> out.append('–')
                            word == "bullet" -> out.append('•')
                            word == "lquote" -> out.append('‘')
                            word == "rquote" -> out.append('’')
                            word == "ldblquote" -> out.append('“')
                            word == "rdblquote" -> out.append('”')
                        }
                    } else {
                        i++
                        val value = when (next) {
                            '\'' -> {
                                if (i + 1 >= source.length) null else {
                                    val code = source.substring(i, i + 2).toIntOrNull(16)
                                    i += 2
                                    code?.let { String(byteArrayOf(it.toByte()), codepage)[0] }
                                }
                            }
                            '\\', '{', '}' -> next
                            '~' -> '\u00a0'
                            '_' -> '‑'
                            '-' -> '\u00ad'
                            '*' -> { hidden = true; null }
                            else -> null
                        }
                        if (value != null && !hidden) {
                            if (skip > 0) skip-- else out.append(value)
                        }
                    }
                }
                '\r', '\n' -> Unit
                else -> if (!hidden) {
                    if (skip > 0) skip-- else out.append(c)
                }
            }
        }
        return out.toString().trim()
    }

    /** Builds a selectable reading view. ASCII and RTF cell tables become scrollable HTML tables. */
    fun renderPage(text: String, originalLayout: Boolean, night: Boolean, fontSp: Float): String {
        val fg = if (night) "#e5e7eb" else "#202124"
        val bg = if (night) "#202124" else "#ffffff"
        val border = if (night) "#5f6368" else "#c7cbd1"
        val stripe = if (night) "#292d30" else "#f5f6f7"
        val content = if (originalLayout) {
            "<pre>${escape(text)}</pre>"
        } else {
            val html = StringBuilder()
            val rows = ArrayList<List<String>>()

            fun flushRows() {
                if (rows.isEmpty()) return
                val maxCols = rows.maxOfOrNull { it.size } ?: 1
                html.append("<div class=\"table-scroll\"><table>")
                for (row in rows) {
                    html.append("<tr>")
                    val cols = row.size
                    for ((idx, cell) in row.withIndex()) {
                        val trimmedCell = cell.trim()
                        val colspan = if (cols < maxCols && idx == cols - 1) maxCols - cols + 1 else 1
                        val spanAttr = if (colspan > 1) " colspan=\"$colspan\"" else ""
                        html.append("<td$spanAttr>").append(escape(trimmedCell)).append("</td>")
                    }
                    html.append("</tr>")
                }
                html.append("</table></div>")
                rows.clear()
            }

            fun isSeparator(trimmed: String): Boolean {
                if (trimmed.isEmpty()) return false
                val borderChars = "|-+_=: \u2014\u2013\u2500\u2501\u2502\u250c\u2510\u2514\u2518\u253c"
                if (trimmed.all { it in borderChars } && trimmed.any { it in "-_=\u2014\u2013\u2500\u2501" }) return true
                if (trimmed.startsWith('|') && trimmed.endsWith('|') && ("---" in trimmed || "——" in trimmed)) {
                    val bcCount = trimmed.count { it in borderChars }
                    if (bcCount.toDouble() / trimmed.length > 0.6) return true
                }
                return false
            }

            for (line in text.lines()) {
                val trimmed = line.trim()
                val separator = isSeparator(trimmed)
                val pipe = trimmed.startsWith('|') && trimmed.count { it == '|' } >= 2
                val tab = line.contains('\t')
                when {
                    separator -> Unit
                    pipe -> {
                        val cells = trimmed.trim('|').split('|')
                        rows += cells
                    }
                    tab -> rows += line.split('\t').dropLastWhile { it.isEmpty() }
                    else -> {
                        flushRows()
                        if (trimmed.isEmpty()) html.append("<div class=\"gap\"></div>")
                        else html.append("<p>").append(escape(trimmed)).append("</p>")
                    }
                }
            }
            flushRows()
            html.toString()
        }
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<style>
body{margin:0;padding:16px;background:$bg;color:$fg;font:${fontSp.coerceIn(10f,32f)}px/1.5 ui-monospace,"Cascadia Code","Roboto Mono","Courier New",monospace;overflow-wrap:normal}
p{margin:.25em 0;white-space:pre}
.gap{height:.8em}
.table-scroll{max-width:100%;overflow-x:auto;-webkit-overflow-scrolling:touch;margin:.8em 0;border:1px solid $border;border-radius:4px}
table{border-collapse:collapse;width:max-content;min-width:100%;table-layout:auto}
td{border:1px solid $border;padding:.4em .6em;vertical-align:top;white-space:pre;font-family:inherit;font-size:inherit;line-height:1.4}
tr:nth-child(even){background:$stripe}
pre{margin:0;font:inherit;font-family:inherit;white-space:pre;overflow-x:auto;tab-size:4}
</style></head><body>$content</body></html>"""
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) append(when (c) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> c.toString()
        })
    }
}
