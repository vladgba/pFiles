package x.vladgba.files

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Pure parsing for the table viewer: delimited text (CSV/TSV) and JSON arrays become columns and rows. */
object TableOps {
    class Table(val columns: List<String>, val rows: List<List<String>>, val truncated: Boolean = false)

    const val MAX_ROWS = 50_000

    /** Picks the delimiter that splits the first lines most consistently: comma, semicolon, tab or pipe. */
    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(10).toList()
        if (sample.isEmpty()) return ','
        var best = ','
        var bestScore = 0
        for (d in charArrayOf(',', ';', '\t', '|')) {
            val counts = sample.map { line -> countOutsideQuotes(line, d) }
            val min = counts.minOrNull() ?: 0
            if (min == 0) continue
            // Same count on every line beats a higher but uneven count.
            val score = min * 10 + if (counts.all { it == counts[0] }) 5 else 0
            if (score > bestScore) { bestScore = score; best = d }
        }
        return best
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var n = 0
        var quoted = false
        for (c in line) {
            if (c == '"') quoted = !quoted else if (c == d && !quoted) n++
        }
        return n
    }

    /** RFC 4180 style: quoted fields, doubled quotes, line breaks inside quotes. The first record is the header. */
    fun parseDelimited(text: String, delimiter: Char = detectDelimiter(text), maxRows: Int = MAX_ROWS): Table {
        val records = ArrayList<List<String>>()
        val field = StringBuilder()
        var record = ArrayList<String>()
        var quoted = false
        var fieldStarted = false
        var truncated = false
        var i = if (text.startsWith('﻿')) 1 else 0
        fun endRecord() {
            record.add(field.toString())
            field.setLength(0)
            if (record.size > 1 || record[0].isNotEmpty()) records.add(record)
            record = ArrayList()
            fieldStarted = false
        }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when {
                c == '"' && !fieldStarted -> { quoted = true; fieldStarted = true }
                c == delimiter -> { record.add(field.toString()); field.setLength(0); fieldStarted = false }
                c == '\r' -> {}
                c == '\n' -> {
                    endRecord()
                    if (records.size > maxRows) { truncated = true; break }
                }
                else -> { field.append(c); fieldStarted = true }
            }
            i++
        }
        if (!truncated && (field.isNotEmpty() || record.isNotEmpty())) endRecord()
        if (records.isEmpty()) return Table(emptyList(), emptyList())
        val width = records.maxOf { it.size }
        val header = records[0]
        val columns = List(width) { c -> header.getOrNull(c)?.takeIf { it.isNotBlank() } ?: "#${c + 1}" }
        val rows = records.subList(1, records.size).map { r -> if (r.size == width) r else List(width) { r.getOrElse(it) { "" } } }
        return Table(columns, rows, truncated)
    }

    /**
     * JSON to a table: an array of objects gives one column per key (in first-seen order); an array of arrays
     * or scalars gives positional columns; an object holding exactly one array is unwrapped; any other object
     * becomes key/value rows.
     */
    fun parseJson(text: String, maxRows: Int = MAX_ROWS): Table {
        val root = JSONTokener(text.trimStart('﻿')).nextValue()
        val array: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                val arrays = root.keys().asSequence().map { root.get(it) }.filterIsInstance<JSONArray>().toList()
                if (arrays.size == 1 && arrays[0].length() > 0) arrays[0]
                else return Table(listOf("key", "value"), root.keys().asSequence().map { listOf(it, cell(root.get(it))) }.toList())
            }
            else -> return Table(listOf("value"), listOf(listOf(cell(root))))
        }
        val n = minOf(array.length(), maxRows)
        val truncated = array.length() > maxRows
        val columns = LinkedHashSet<String>()
        var objects = false
        var width = 1
        for (i in 0 until n) {
            when (val v = array.get(i)) {
                is JSONObject -> { objects = true; v.keys().forEach { columns.add(it) } }
                is JSONArray -> width = maxOf(width, v.length())
            }
        }
        if (objects) {
            val cols = columns.toList()
            val rows = (0 until n).map { i ->
                val v = array.get(i)
                if (v is JSONObject) cols.map { k -> if (v.has(k)) cell(v.get(k)) else "" }
                else cols.mapIndexed { c, _ -> if (c == 0) cell(v) else "" }
            }
            return Table(cols, rows, truncated)
        }
        val cols = if (width == 1) listOf("value") else List(width) { "#${it + 1}" }
        val rows = (0 until n).map { i ->
            val v = array.get(i)
            if (v is JSONArray) List(width) { c -> if (c < v.length()) cell(v.get(c)) else "" }
            else List(width) { c -> if (c == 0) cell(v) else "" }
        }
        return Table(cols, rows, truncated)
    }

    private fun cell(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is String -> v
        else -> v.toString()
    }

    fun isDelimited(ext: String) = ext == "csv" || ext == "tsv" || ext == "tab" || ext == "psv"
    fun isJson(ext: String) = ext == "json" || ext == "geojson" || ext == "ndjson" || ext == "jsonl"

    /** One JSON value per line (ndjson / jsonl) becomes an array. */
    fun parseJsonLines(text: String, maxRows: Int = MAX_ROWS): Table {
        val sb = StringBuilder("[")
        var n = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if (n > 0) sb.append(',')
            sb.append(line)
            n++
        }
        sb.append(']')
        return parseJson(sb.toString(), maxRows)
    }
}
