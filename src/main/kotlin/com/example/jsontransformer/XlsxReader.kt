package com.example.jsontransformer

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Dependency-free .xlsx reader. Like [XlsxWriter], this exploits the fact that
 * an xlsx file is a ZIP of XML parts: the JDK zip APIs are enough — no Apache
 * POI needed. The sheet XML is machine-generated and regular, so a small
 * hand-rolled tag scanner is used instead of javax.xml DOM (which also keeps
 * the compile surface minimal).
 *
 * Supports: shared strings (t="s"), inline strings (t="inlineStr"),
 * typed numbers and booleans, formula strings (t="str"), and header-row
 * detection.
 */
object XlsxReader {

    /** Parse the first worksheet of an .xlsx byte stream into a [Table]. */
    fun read(input: InputStream, smart: Boolean = false): Table {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) parts[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (parts.isEmpty()) throw IllegalArgumentException("不是有效的 xlsx 文件 / Not a valid xlsx file")

        val shared = parts["xl/sharedStrings.xml"]?.let { parseSharedStrings(it.decodeToString()) } ?: emptyList()

        // Pick the first sheet: lowest sheetN.xml number wins.
        val sheetName = parts.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: 0 }
            .firstOrNull()
            ?: throw IllegalArgumentException("xlsx 中没有工作表 / No worksheet found")
        val sheetXml = parts[sheetName]?.decodeToString() ?: throw IllegalArgumentException("工作表缺失 / Worksheet missing")

        return parseSheet(sheetXml, shared, smart)
    }

    // ------------------------------------------------------------ shared strings

    private fun parseSharedStrings(xml: String): List<String> {
        val result = ArrayList<String>()
        var pos = 0
        while (true) {
            val start = xml.indexOf("<si>", pos).let { if (it < 0) xml.indexOf("<si ", pos) else it }
            if (start < 0) break
            val end = xml.indexOf("</si>", start)
            if (end < 0) break
            result.add(concatT(xml.substring(start + 4, end)))
            pos = end + 5
        }
        return result
    }

    /** Concatenate the text of all <t>…</t> elements inside [chunk]. */
    private fun concatT(chunk: String): String {
        val sb = StringBuilder()
        var pos = 0
        while (true) {
            val open = findTag(chunk, "t", pos) ?: break
            val close = chunk.indexOf("</t>", open.end)
            if (close < 0) break
            sb.append(decodeEntities(chunk.substring(open.end, close)))
            pos = close + 4
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------- sheet

    private fun parseSheet(xml: String, shared: List<String>, smart: Boolean): Table {
        val grid = ArrayList<MutableMap<Int, String>>()   // row -> colIdx -> text
        var maxCol = 0
        var pos = 0
        while (true) {
            val rowStart = findTag(xml, "row", pos) ?: break
            val rowEnd = xml.indexOf("</row>", rowStart.end)
            if (rowEnd < 0) break
            val rowXml = xml.substring(rowStart.end, rowEnd)
            val map = HashMap<Int, String>()
            var cpos = 0
            while (true) {
                val cell = nextCell(rowXml, cpos) ?: break
                cpos = cell.nextPos
                val ref = cell.attr("r") ?: continue
                val colIdx = ref.takeWhile { it.isLetter() }.uppercase().colIndex()
                val value = cellValue(cell, shared) ?: continue
                map[colIdx] = value
                if (colIdx + 1 > maxCol) maxCol = colIdx + 1
            }
            grid.add(map)
            pos = rowEnd + 6
        }
        if (maxCol == 0 || grid.isEmpty()) throw IllegalArgumentException("工作表为空 / Empty worksheet")

        val width = maxCol
        val columns = (0 until width).map { c ->
            val header = grid.firstOrNull()?.get(c)?.trim().takeUnless { it.isNullOrEmpty() } ?: "column${c + 1}"
            val cells = grid.drop(1).map { row ->
                val text = row[c]?.trim().takeUnless { it.isNullOrEmpty() } ?: return@map JsonValue.Null
                if (smart) JsonTransformer.parseCell(text) else typed(text)
            }
            Column(header, cells)
        }
        return Table(columns)
    }

    // ------------------------------------------------------------- cell model

    private class CellInfo(val xml: String, val bodyStart: Int, val bodyEnd: Int, val attrs: Map<String, String>, val selfClosing: Boolean, val nextPos: Int) {
        fun attr(name: String): String? = attrs[name]
        fun body(): String = if (selfClosing) "" else xml.substring(bodyStart, bodyEnd)
    }

    /** Find the next <c …>…</c> or <c …/> element starting at or after [from]. */
    private fun nextCell(xml: String, from: Int): CellInfo? {
        var pos = from
        while (true) {
            val open = findTag(xml, "c", pos) ?: return null
            // only accept cell tags: previous char in "<c" must be boundary checked by findTag
            val selfClosing = xml[open.end - 1] == '/'
            if (selfClosing) {
                return CellInfo(xml, open.end, open.end, open.attrs, true, open.end)
            }
            val close = xml.indexOf("</c>", open.end)
            if (close < 0) return null
            return CellInfo(xml, open.end, close, open.attrs, false, close + 4)
        }
    }

    /** Extract the value of a cell honouring its `t` attribute. */
    private fun cellValue(cell: CellInfo, shared: List<String>): String? {
        val t = cell.attr("t") ?: "n"
        val body = cell.body()
        val v = tagBody(body, "v")?.let { decodeEntities(it) }
        return when (t) {
            "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }
            "inlineStr" -> concatT(body).ifEmpty { null }
            "b" -> when (v?.trim()) { "1" -> "true"; "0" -> "false"; else -> v }
            "str" -> v                       // formula result string
            else -> v                        // numeric
        }
    }

    // ----------------------------------------------------------------- helpers

    /** Locate the next open tag [name] with its attributes; returns span + attrs. */
    private class TagSpan(val start: Int, val end: Int, val attrs: Map<String, String>)

    private fun findTag(xml: String, name: String, from: Int): TagSpan? {
        val candidates = listOf("<$name>", "<$name ")
        var best = -1
        for (cand in candidates) {
            val i = xml.indexOf(cand, from)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        if (best < 0) return null
        val close = xml.indexOf('>', best)
        if (close < 0) return null
        // parse attributes out of the tag interior
        val interior = xml.substring(best + name.length + 1, close).trimEnd('/')
        val attrs = HashMap<String, String>()
        var i = 0
        while (i < interior.length) {
            val eq = interior.indexOf('=', i)
            if (eq < 0) break
            val key = interior.substring(i, eq).trim().takeWhile { it.isLetterOrDigit() || it == ':' }
            var j = eq + 1
            while (j < interior.length && interior[j].isWhitespace()) j++
            if (j >= interior.length || interior[j] != '"') break
            val quoteEnd = interior.indexOf('"', j + 1)
            if (quoteEnd < 0) break
            attrs[key] = interior.substring(j + 1, quoteEnd)
            i = quoteEnd + 1
            while (i < interior.length && interior[i].isWhitespace()) i++
        }
        return TagSpan(best, close + 1, attrs)
    }

    /** Return the decoded text inside the first <name>…</name> of [xml]. */
    private fun tagBody(xml: String, name: String): String? {
        val open = findTag(xml, name, 0) ?: return null
        val close = xml.indexOf("</$name>", open.end)
        if (close < 0) return null
        return xml.substring(open.end, close)
    }

    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        var out = s
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        // numeric entities &#123; and &#x1F;
        out = Regex("&#(\\d+);").replace(out) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
        out = Regex("&#x([0-9A-Fa-f]+);").replace(out) { m -> m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value }
        return out.replace("&amp;", "&")   // must be last
    }

    private fun typed(text: String): JsonValue {
        val t = text.trim()
        return when {
            t.isEmpty() -> JsonValue.Null
            t.equals("true", true) -> JsonValue.Bool(true)
            t.equals("false", true) -> JsonValue.Bool(false)
            t.toDoubleOrNull() != null -> JsonValue.Num(t)
            else -> JsonValue.Str(text)
        }
    }

    private fun String.colIndex(): Int {
        var n = 0
        for (ch in this) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }
}
