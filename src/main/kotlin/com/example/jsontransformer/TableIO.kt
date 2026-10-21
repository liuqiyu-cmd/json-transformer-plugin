package com.example.jsontransformer

/**
 * Multi-format table I/O with two outer blocks:
 *
 *  - "数据源"  : import a table from Excel(TSV) / CSV / Markdown / HTML /
 *                SQL INSERT / LaTeX / XML / MySQL-CLI output / MediaWiki,
 *                in addition to JSON arrays.
 *  - "表格生成器": export the working table to any of those text formats
 *                (plus an ASCII grid; "Magic" and "PDF" tabs render the same
 *                printable text grid since binaries cannot live in a text area).
 *
 * Everything is dependency-free pure Kotlin.
 */

/** Supported input ("数据源") formats. */
enum class SourceFormat { AUTO, JSON, EXCEL, CSV, MARKDOWN, HTML, SQL, LATEX, XML, MYSQL, MEDIAWIKI }

/** Supported output ("表格生成器") formats. */
enum class GeneratorFormat { JSON, EXCEL, MAGIC, SQL, LATEX, HTML, CSV, MARKDOWN, XML, PDF, ASCII, MEDIAWIKI }

object TableIO {

    // =====================================================================
    // INPUT
    // =====================================================================

    /** Parse any supported source text into a [Table]. */
    fun parseSource(input: String, format: SourceFormat, smartParse: Boolean = false): Table {
        val fmt = if (format == SourceFormat.AUTO) detect(input) else format
        return when (fmt) {
            SourceFormat.JSON -> JsonTransformer.parse(input, JsonTableFormat.AUTO, smartParse)
            SourceFormat.EXCEL -> parseDelimited(input, "\t", smartParse)
            SourceFormat.CSV -> parseDelimited(input, ",", smartParse, csvMode = true)
            SourceFormat.MARKDOWN -> parseMarkdown(input, smartParse)
            SourceFormat.HTML -> parseHtml(input, smartParse)
            SourceFormat.SQL -> parseSqlInserts(input, smartParse)
            SourceFormat.LATEX -> parseLatex(input, smartParse)
            SourceFormat.XML -> parseXml(input, smartParse)
            SourceFormat.MYSQL -> parseMysqlCli(input, smartParse)
            SourceFormat.MEDIAWIKI -> parseMediaWiki(input, smartParse)
            SourceFormat.AUTO -> throw IllegalArgumentException("unreachable")
        }
    }

    /** Split a single TSV clipboard line into cells (TSV has no quoting). */
    fun parseTsvLine(line: String): List<String> = line.split('\t')

    /** Heuristic detection of the source format, used by [SourceFormat.AUTO]. */    fun detect(input: String): SourceFormat {
        val t = input.trim()
        if (t.isEmpty()) return SourceFormat.JSON
        if (t.startsWith("{|")) return SourceFormat.MEDIAWIKI
        if (t.startsWith("{") || t.startsWith("[")) return SourceFormat.JSON
        if (t.contains("\\begin{tabular}")) return SourceFormat.LATEX
        if (Regex("(?is)<table[\\s>]").containsMatchIn(t)) return SourceFormat.HTML
        if (t.startsWith("<?xml") || Regex("^<\\w[\\w.-]*[\\s>]").containsMatchIn(t)) return SourceFormat.XML
        if (Regex("(?i)insert\\s+into").containsMatchIn(t)) return SourceFormat.SQL
        if (t.lines().any { it.trimStart().startsWith("+-") && it.trimEnd().endsWith("-+") }) return SourceFormat.MYSQL
        if (t.lines().any { it.trimStart().startsWith("|") }) return SourceFormat.MARKDOWN
        if (t.contains("\t")) return SourceFormat.EXCEL
        return SourceFormat.CSV
    }

    // ------------------------------------------------- delimited (CSV / TSV)

    private fun parseDelimited(input: String, sep: String, smart: Boolean, csvMode: Boolean = false): Table {
        val rows = splitDelimited(input, sep, csvMode)
        if (rows.isEmpty()) return Table(emptyList())
        val header = rows.first()
        val width = rows.maxOf { it.size }
        val columns = (0 until width).map { c ->
            val name = header.getOrNull(c)?.trim().takeUnless { it.isNullOrEmpty() } ?: "column${c + 1}"
            Column(name, rows.drop(1).map { typed(it.getOrNull(c) ?: "", smart) })
        }
        return Table(columns)
    }

    /** State-machine splitter handling CSV quoting ("" escapes, embedded newlines). */
    private fun splitDelimited(input: String, sep: String, csvMode: Boolean): List<List<String>> {
        val rows = ArrayList<MutableList<String>>(input.lines().size)
        val row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = input.length
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() { endCell(); rows.add(row.toMutableList()); row.clear() }
        while (i < n) {
            val c = input[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && input[i + 1] == '"') { cell.append('"'); i++ } else inQuotes = false
                } else cell.append(c)
            } else when {
                csvMode && c == '"' -> inQuotes = true
                c.toString() == sep -> endCell()
                c == '\r' -> { /* swallow; handled with \n */ }
                c == '\n' -> endRow()
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        // drop fully-empty trailing rows
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    // ------------------------------------------------------------- Markdown

    private fun parseMarkdown(input: String, smart: Boolean): Table {
        val rowLines = input.lines()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .filter { !isMarkdownSeparator(it) }
        if (rowLines.isEmpty()) throw IllegalArgumentException("未找到 Markdown 表格行 / No Markdown table rows found")
        val rows = rowLines.map { splitMarkdownRow(it) }
        val width = rows.maxOf { it.size }
        val columns = (0 until width).map { c ->
            val name = rows.first().getOrNull(c)?.trim().takeUnless { it.isNullOrEmpty() } ?: "column${c + 1}"
            Column(name, rows.drop(1).map { typed(it.getOrNull(c) ?: "", smart) })
        }
        return Table(columns)
    }

    private fun isMarkdownSeparator(line: String): Boolean =
        line.replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty()

    private fun splitMarkdownRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        // protect escaped pipes before splitting on the real ones
        val guarded = s.replace("\\|", "\u0000")
        return guarded.split("|").map { it.trim().replace("\u0000", "|") }
    }

    // ----------------------------------------------------------------- HTML

    private fun parseHtml(input: String, smart: Boolean): Table {
        val tableMatch = Regex("(?is)<table[^>]*>(.*)</table>").find(input)
            ?: throw IllegalArgumentException("未找到 <table> 元素 / No <table> element found")
        val inner = tableMatch.groupValues[1]
        // drop thead/tbody wrappers so <tr> extraction is uniform
        val cleaned = inner.replace(Regex("(?is)</?(thead|tbody|tfoot)[^>]*>"), "")
        val trs = Regex("(?is)<tr[^>]*>(.*?)</tr>").findAll(cleaned).toList()
        if (trs.isEmpty()) throw IllegalArgumentException("未找到 <tr> 行 / No <tr> rows found")
        val rows = trs.map { m ->
            Regex("(?is)<t([hd])[^>]*>(.*?)</t\\1>").findAll(m.groupValues[1]).map {
                decodeEntities(stripTags(it.groupValues[2])).trim()
            }.toList()
        }
        val width = rows.maxOf { it.size }
        val columns = (0 until width).map { c ->
            val name = rows.first().getOrNull(c)?.trim().takeUnless { it.isNullOrEmpty() } ?: "column${c + 1}"
            Column(name, rows.drop(1).map { typed(it.getOrNull(c) ?: "", smart) })
        }
        return Table(columns)
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "")

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ")

    // ------------------------------------------------------------ SQL INSERT

    private fun parseSqlInserts(input: String, smart: Boolean): Table {
        val stmtRegex = Regex(
            "(?is)insert\\s+into\\s+[`\"\\[]?[\\w.\\-]+[`\"\\]]?\\s*\\(([^)]*)\\)\\s*values(.*)"
        )
        val m = stmtRegex.find(input)
            ?: throw IllegalArgumentException("未找到 INSERT INTO ... VALUES 语句 / No INSERT INTO statement found")
        val columns = m.groupValues[1].split(",").map { c ->
            c.trim().trim('`', '"', '[', ']').ifEmpty { "column" }
        }
        val tuples = parseSqlTuples(m.groupValues[2])
        if (tuples.isEmpty()) throw IllegalArgumentException("未找到 VALUES 元组 / No VALUES tuples found")
        val dataColumns = columns.mapIndexed { c, name ->
            Column(name, tuples.map { t -> sqlTyped(t.getOrNull(c) ?: "NULL", smart) })
        }
        return Table(dataColumns)
    }

    /** Scan SQL VALUES text for parenthesised tuples, honouring '...' strings. */
    private fun parseSqlTuples(s: String): List<List<String>> {
        val tuples = ArrayList<List<String>>()
        var i = 0
        val n = s.length
        while (i < n) {
            if (s[i] == '(') {
                val cells = ArrayList<String>()
                val sb = StringBuilder()
                var depth = 1
                var inStr = false
                i++
                loop@ while (i < n) {
                    val c = s[i]
                    if (inStr) {
                        when {
                            // escaped '' inside a string -> keep both quotes, skip the pair
                            c == '\'' && i + 1 < n && s[i + 1] == '\'' -> { sb.append("''"); i += 2; continue@loop }
                            c == '\'' -> inStr = false
                        }
                        sb.append(c)
                        i++
                        continue@loop
                    }
                    when (c) {
                        '\'' -> { inStr = true; sb.append(c); i++ }
                        '(' -> { depth++; sb.append(c); i++ }
                        ')' -> { depth--; i++; if (depth == 0) break@loop else sb.append(c) }
                        ',' -> if (depth == 1) { cells.add(sb.toString().trim()); sb.setLength(0); i++ } else { sb.append(c); i++ }
                        else -> { sb.append(c); i++ }
                    }
                }
                cells.add(sb.toString().trim())
                tuples.add(cells)
            }
            i++
        }
        return tuples
    }

    private fun sqlTyped(raw: String, smart: Boolean): JsonValue {
        val t = raw.trim()
        if (t.equals("NULL", ignoreCase = true)) return JsonValue.Null
        if (t.length >= 2 && t.startsWith("'") && t.endsWith("'")) {
            val unquoted = t.substring(1, t.length - 1).replace("''", "'")
            return if (smart) trySmart(unquoted) else JsonValue.Str(unquoted)
        }
        return typed(t, smart)
    }

    private fun trySmart(s: String): JsonValue {
        val t = s.trim()
        if (t.startsWith("{") || t.startsWith("[")) {
            return runCatching { Json.parse(t) }.getOrElse { JsonValue.Str(s) }
        }
        return JsonTransformer.parseCell(t)
    }

    // ---------------------------------------------------------------- LaTeX

    private fun parseLatex(input: String, smart: Boolean): Table {
        val body = Regex("(?s)\\\\begin\\{tabular}\\s*\\{[^}]*}(.*)\\\\end\\{tabular}").find(input)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("未找到 tabular 环境 / No tabular environment found")
        // strip \hline lines first, then split rows on the two-backslash separator
        val cleaned = body.replace("\\hline", "")
        val rows = cleaned.split("\\\\")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { r -> r.split("&").map { c -> decodeLatex(c.trim()) } }
        if (rows.isEmpty()) throw IllegalArgumentException("tabular 内容为空 / Empty tabular body")
        val width = rows.maxOf { it.size }
        val columns = (0 until width).map { c ->
            val name = rows.first().getOrNull(c)?.trim().takeUnless { it.isNullOrEmpty() } ?: "column${c + 1}"
            Column(name, rows.drop(1).map { typed(it.getOrNull(c) ?: "", smart) })
        }
        return Table(columns)
    }

    private fun decodeLatex(s: String): String = s
        .replace("\\&", "&").replace("\\%", "%").replace("\\_", "_")
        .replace("\\#", "#").replace("\\$", "$")

    // ------------------------------------------------------------------ XML

    private fun parseXml(input: String, smart: Boolean): Table {
        val cleaned = input
            .replace(Regex("(?s)<!--.*?-->"), "")
            .replace(Regex("(?s)<\\?xml.*?\\?>"), "")
            .replace(Regex("(?s)<!DOCTYPE.*?>"), "")
            .trim()
        val rootTag = Regex("<([\\w.-]+)[\\s>]|^<([\\w.-]+)/>").find(cleaned)
            ?.groupValues?.let { g -> g[1].ifEmpty { g[2] } }
            ?: throw IllegalArgumentException("未找到 XML 根元素 / No XML root element found")
        val rootInner = elementInner(cleaned, rootTag)
            ?: throw IllegalArgumentException("XML 根元素未闭合 / Unclosed root element")
        val rowTag = firstChildTag(rootInner) ?: rootTag
        val rowInnerList = childElements(rootInner, rowTag).mapNotNull { elementInner(it, rowTag) }
        if (rowInnerList.isEmpty()) throw IllegalArgumentException("未找到 XML 数据行 / No XML rows found")
        val colNames = LinkedHashSet<String>()
        val rowCells = rowInnerList.map { inner ->
            val cells = LinkedHashMap<String, String>()
            for (childTag in childTags(inner)) {
                childElements(inner, childTag).forEach { el ->
                    val text = decodeEntities(stripTags(el)).trim()
                    cells[childTag] = if (cells.containsKey(childTag)) cells[childTag] + ", " + text else text
                    colNames.add(childTag)
                }
            }
            cells
        }
        val columns = colNames.map { name ->
            Column(name, rowCells.map { typed(it[name] ?: "", smart) })
        }
        return Table(columns)
    }

    private fun elementInner(s: String, tag: String): String? {
        val open = Regex("<$tag(\\s[^>]*)?>").find(s) ?: return null
        val start = open.range.last + 1
        if (s.getOrNull(open.range.first + 1) == '/' ) return ""  // self-closed "<tag/>" shape
        if (open.value.endsWith("/>")) return ""
        val close = "</$tag>"
        val end = s.indexOf(close, start)
        if (end < 0) return null
        return s.substring(start, end)
    }

    /** Immediate child elements (as raw strings) of [inner] with tag [tag]. */
    private fun childElements(inner: String, tag: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            val open = Regex("<$tag(\\s[^>]*)?/?>").find(inner, i) ?: break
            val start = open.range.last + 1
            val el = if (open.value.endsWith("/>")) {
                open.value
            } else {
                val end = inner.indexOf("</$tag>", start)
                if (end < 0) break
                inner.substring(open.range.first, end + tag.length + 3)
            }
            out.add(el)
            i = open.range.last + 1
        }
        return out
    }

    private fun firstChildTag(inner: String): String? =
        Regex("<([\\w.-]+)[\\s/>]").find(inner)?.groupValues?.get(1)

    private fun childTags(inner: String): List<String> {
        val tags = LinkedHashSet<String>()
        Regex("<([\\w.-]+)[\\s/>]").findAll(inner).forEach { tags.add(it.groupValues[1]) }
        return tags.toList()
    }

    // -------------------------------------------------------- MySQL CLI grid

    /**
     * Parses the bordered output of the mysql command-line client:
     * +----+-------+
     * | id | name  |
     * +----+-------+
     * | 1  | Alice |
     * +----+-------+
     * Also accepts tab-separated output of `mysql -e`.
     */
    private fun parseMysqlCli(input: String, smart: Boolean): Table {
        val dataLines = input.lines().map { it.trimEnd() }.filter { l ->
            val t = l.trim()
            t.startsWith("|") && !(t.startsWith("+-") || t.startsWith("+="))
        }
        if (dataLines.isNotEmpty()) {
            val rows = dataLines.map { l ->
                var s = l.trim()
                if (s.startsWith("|")) s = s.substring(1)
                if (s.endsWith("|")) s = s.substring(0, s.length - 1)
                s.split("|").map { it.trim() }
            }
            return toTable(rows, smart)
        }
        return parseDelimited(input, "\t", smart)
    }

    // ----------------------------------------------------------- MediaWiki

    private fun parseMediaWiki(input: String, smart: Boolean): Table {
        val body = Regex("(?s)\\{\\|(.*)\\|}").find(input)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("未找到 wikitable ({| ... |}) / No wikitable found")
        val headerCells = ArrayList<String>()
        val dataRows = ArrayList<List<String>>()
        for (rawLine in body.lines()) {
            val line = rawLine.trim()
            when {
                line.isEmpty() || line == "|-" || line.startsWith("|-") || line.startsWith("|+") -> {}
                line.startsWith("!") -> {
                    val cells = splitWikiRow(line.substring(1), header = true)
                    headerCells.addAll(cells)
                }
                line.startsWith("|") -> dataRows.add(splitWikiRow(line.substring(1), header = false))
            }
        }
        if (headerCells.isEmpty() && dataRows.isEmpty()) {
            throw IllegalArgumentException("wikitable 内容为空 / Empty wikitable")
        }
        if (headerCells.isEmpty()) {
            // no explicit header: fall back to generic column names
            val width = dataRows.maxOf { it.size }
            headerCells.addAll((0 until width).map { "column${it + 1}" })
        }
        // drop rows whose every cell is blank (e.g. the trailing "|" before |})
        val keptRows = dataRows.filter { row -> row.any { it.isNotBlank() } }
        val columns = headerCells.mapIndexed { c, name ->
            Column(name.trim().ifEmpty { "column${c + 1}" },
                keptRows.map { typed(it.getOrNull(c) ?: "", smart) })
        }
        return Table(columns)
    }

    private fun splitWikiRow(rest: String, header: Boolean): List<String> {
        // headers separate cells with !!, data rows with ||
        val sep = if (header) "!!" else "||"
        return rest.split(sep).map { cell ->
            var c = cell.trim()
            // strip cell attributes like style="..." | (value after the last '|')
            if (c.contains("|")) c = c.substringAfterLast('|').trim()
            c
        }
    }

    // ------------------------------------------------------------- helpers

    /** Convert a plain-text cell into the most specific JSON value. */
    private fun typed(raw: String, smart: Boolean): JsonValue {
        val t = raw.trim()
        if (t.isEmpty()) return JsonValue.Str(raw)
        if (t.equals("NULL", ignoreCase = true)) return JsonValue.Null
        return if (smart) trySmart(t) else JsonTransformer.parseCell(t)
    }

    private fun toTable(rows: List<List<String>>, smart: Boolean): Table {
        if (rows.isEmpty()) return Table(emptyList())
        val width = rows.maxOf { it.size }
        val columns = (0 until width).map { c ->
            val name = rows.first().getOrNull(c)?.trim().takeUnless { it.isNullOrEmpty() } ?: "column${c + 1}"
            Column(name, rows.drop(1).map { typed(it.getOrNull(c) ?: "", smart) })
        }
        return Table(columns)
    }

    // =====================================================================
    // OUTPUT (表格生成器)
    // =====================================================================

    /**
     * Render a [Table] in any generator format.
     * @param rootName doubles as the SQL table name and the XML root element
     *                 name; falls back to sensible defaults when blank.
     */
    fun generate(
        table: Table,
        format: GeneratorFormat,
        indent: IndentStyle = IndentStyle.TWO_SPACES,
        rootName: String? = null,
        minify: Boolean = false
    ): String {
        if (table.columns.isEmpty()) return ""
        return when (format) {
            GeneratorFormat.JSON -> JsonTransformer.render(
                table, JsonTableFormat.ARRAY_OF_OBJECTS, indent, rootName, minify
            )
            GeneratorFormat.EXCEL -> toDelimited(table, "\t")
            GeneratorFormat.MAGIC -> asciiGrid(table)   // text grid placeholder
            GeneratorFormat.PDF -> asciiGrid(table)     // printable text grid
            GeneratorFormat.ASCII -> asciiGrid(table)
            GeneratorFormat.SQL -> toSql(table, rootName)
            GeneratorFormat.LATEX -> toLatex(table)
            GeneratorFormat.HTML -> toHtml(table, indent)
            GeneratorFormat.CSV -> toDelimited(table, ",", csvMode = true)
            GeneratorFormat.MARKDOWN -> toMarkdown(table)
            GeneratorFormat.XML -> toXml(table, rootName, indent)
            GeneratorFormat.MEDIAWIKI -> toMediaWiki(table)
        }
    }

    // ------------------------------------------------------------- CSV / TSV

    private fun toDelimited(t: Table, sep: String, csvMode: Boolean = false): String {
        fun q(s: String): String =
            if (csvMode && (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r'))) {
                "\"" + s.replace("\"", "\"\"") + "\""
            } else s

        val sb = StringBuilder()
        sb.append(t.columns.joinToString(sep) { q(it.name) }).append('\n')
        for (r in 0 until t.rowCount) {
            sb.append(t.columns.joinToString(sep) { col -> q(JsonTransformer.displayCell(col.cells.getOrNull(r) ?: JsonValue.Null)) })
            sb.append('\n')
        }
        return sb.toString()
    }

    // -------------------------------------------------------------- Markdown

    private fun toMarkdown(t: Table): String {
        fun esc(s: String) = s.replace("|", "\\|").replace("\n", "<br>")
        val sb = StringBuilder()
        sb.append("| ").append(t.columns.joinToString(" | ") { esc(it.name) }).append(" |\n")
        sb.append("| ")
        sb.append(t.columns.joinToString(" | ") { "---" }).append(" |\n")
        for (r in 0 until t.rowCount) {
            sb.append("| ").append(t.columns.joinToString(" | ") { col ->
                esc(JsonTransformer.displayCell(col.cells.getOrNull(r) ?: JsonValue.Null))
            }).append(" |\n")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ HTML

    private fun toHtml(t: Table, indent: IndentStyle): String {
        val unit = if (indent == IndentStyle.TABS) "\t" else if (indent == IndentStyle.FOUR_SPACES) "    " else if (indent == IndentStyle.EIGHT_SPACES) "        " else "  "
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
        val sb = StringBuilder("<table>\n")
        sb.append(unit).append("<thead>\n").append(unit).append(unit).append("<tr>\n")
        t.columns.forEach { sb.append(unit).append(unit).append(unit).append("<th>").append(esc(it.name)).append("</th>\n") }
        sb.append(unit).append(unit).append("</tr>\n").append(unit).append("</thead>\n")
        sb.append(unit).append("<tbody>\n")
        for (r in 0 until t.rowCount) {
            sb.append(unit).append(unit).append("<tr>\n")
            t.columns.forEach { col ->
                sb.append(unit).append(unit).append(unit).append("<td>")
                    .append(esc(JsonTransformer.displayCell(col.cells.getOrNull(r) ?: JsonValue.Null)))
                    .append("</td>\n")
            }
            sb.append(unit).append(unit).append("</tr>\n")
        }
        sb.append(unit).append("</tbody>\n</table>")
        return sb.toString()
    }

    // ------------------------------------------------------------------ SQL

    private fun toSql(t: Table, rootName: String?): String {
        val tableName = rootName?.takeIf { it.isNotBlank() } ?: "my_table"
        fun sql(v: JsonValue): String = when (v) {
            is JsonValue.Null -> "NULL"
            is JsonValue.Num -> v.raw
            is JsonValue.Bool -> if (v.value) "1" else "0"
            is JsonValue.Str -> "'" + v.value.replace("'", "''") + "'"
            else -> "'" + Json.render(v, minify = true).replace("'", "''") + "'"
        }
        val cols = t.columns.joinToString(", ") { "`${it.name}`" }
        val sb = StringBuilder()
        // CREATE TABLE with inferred column types (INT/DOUBLE/BOOLEAN/VARCHAR/TEXT)
        sb.append("CREATE TABLE IF NOT EXISTS `").append(tableName).append("` (\n")
        sb.append(t.columns.joinToString(",\n") { col ->
            "  `" + col.name.replace("`", "``") + "` " + sqlType(col)
        })
        sb.append("\n);\n\n")
        // INSERT statements
        sb.append("INSERT INTO ").append(tableName).append(" (").append(cols).append(") VALUES\n")
        for (r in 0 until t.rowCount) {
            sb.append("  (")
            sb.append(t.columns.joinToString(", ") { col -> sql(col.cells.getOrNull(r) ?: JsonValue.Null) })
            sb.append(')')
            if (r != t.rowCount - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append(';')
        return sb.toString()
    }

    /** Infer a SQL column type from the cell values. */
    private fun sqlType(col: Column): String {
        val cells = col.cells.filter { it !is JsonValue.Null }
        if (cells.isEmpty()) return "VARCHAR(255)"
        return when {
            cells.all { it is JsonValue.Bool } -> "BOOLEAN"
            cells.all { it is JsonValue.Num && !it.raw.contains('.') && !it.raw.contains('e', ignoreCase = true) } -> "INT"
            cells.all { it is JsonValue.Num } -> "DOUBLE"
            else -> {
                val maxLen = cells.maxOf { JsonTransformer.displayCell(it).length }
                if (maxLen > 255) "TEXT" else "VARCHAR(255)"
            }
        }
    }

    // ---------------------------------------------------------------- LaTeX

    private fun toLatex(t: Table): String {
        fun esc(s: String) = s.replace("\\", "\\textbackslash{}")
            .replace("([#$%&_{}])".toRegex(), "\\\\$1")
            .replace("~", "\\textasciitilde{}").replace("^", "\\textasciicircum{}")
        val sb = StringBuilder()
        sb.append("\\begin{tabular}{|")
        sb.append("l|".repeat(t.columns.size))
        sb.append("}\n\\hline\n")
        sb.append(t.columns.joinToString(" & ") { esc(it.name) }).append(" \\\\\n\\hline\n")
        for (r in 0 until t.rowCount) {
            sb.append(t.columns.joinToString(" & ") { col ->
                esc(JsonTransformer.displayCell(col.cells.getOrNull(r) ?: JsonValue.Null))
            }).append(" \\\\\n\\hline\n")
        }
        sb.append("\\end{tabular}")
        return sb.toString()
    }

    // ------------------------------------------------------------------ XML

    private fun toXml(t: Table, rootName: String?, indent: IndentStyle): String {
        val unit = if (indent == IndentStyle.TABS) "\t" else if (indent == IndentStyle.FOUR_SPACES) "    " else if (indent == IndentStyle.EIGHT_SPACES) "        " else "  "
        val root = tagName(rootName) ?: "table"
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<").append(root).append(">\n")
        for (r in 0 until t.rowCount) {
            sb.append(unit).append("<row>\n")
            t.columns.forEach { col ->
                val text = esc(JsonTransformer.displayCell(col.cells.getOrNull(r) ?: JsonValue.Null))
                if (text.isEmpty()) {
                    sb.append(unit).append(unit).append("<").append(tagName(col.name) ?: "field").append("/>\n")
                } else {
                    sb.append(unit).append(unit).append("<").append(tagName(col.name) ?: "field").append(">")
                        .append(text)
                        .append("</").append(tagName(col.name) ?: "field").append(">\n")
                }
            }
            sb.append(unit).append("</row>\n")
        }
        sb.append("</").append(root).append(">")
        return sb.toString()
    }

    /** Sanitise a column/root name into a legal XML tag name. */
    private fun tagName(raw: String?): String? {
        val cleaned = raw?.replace(Regex("[^A-Za-z0-9_.\\-]"), "_")?.trim('_') ?: return null
        if (cleaned.isEmpty()) return null
        return if (cleaned[0].isDigit()) "n$cleaned" else cleaned
    }

    // ------------------------------------------------------------ MediaWiki

    private fun toMediaWiki(t: Table): String {
        val sb = StringBuilder("{| class=\"wikitable\"\n|-\n")
        sb.append("! ").append(t.columns.joinToString(" !! ") { it.name.replace("|", "\\|") }).append("\n")
        for (r in 0 until t.rowCount) {
            sb.append("|-\n")
            sb.append("| ").append(t.columns.joinToString(" || ") { col ->
                JsonTransformer.displayCell(col.cells.getOrNull(r) ?: JsonValue.Null).replace("|", "\\|").replace("\n", "<br>")
            }).append('\n')
        }
        sb.append("|}")
        return sb.toString()
    }

    // ---------------------------------------------------------------- ASCII

    /** Plain-text grid with MySQL-style borders; also used for Magic/PDF tabs. */
    private fun asciiGrid(t: Table): String {
        val rows = ArrayList<List<String>>()
        rows.add(t.columns.map { it.name })
        for (r in 0 until t.rowCount) rows.add(t.columns.map { c -> JsonTransformer.displayCell(c.cells.getOrNull(r) ?: JsonValue.Null) })
        val widths = IntArray(t.columns.size) { c -> rows.maxOf { it.getOrNull(c)?.length ?: 0 } }
        val sep = "+" + widths.joinToString("+") { "-".repeat(it + 2) } + "+"
        val line = { cells: List<String> ->
            "|" + cells.mapIndexed { c, s -> " " + (s ?: "").padEnd(widths[c]) + " " }.joinToString("|") + "|"
        }
        val sb = StringBuilder()
        sb.append(sep).append('\n')
        sb.append(line(rows[0])).append('\n')
        sb.append(sep).append('\n')
        for (i in 1 until rows.size) sb.append(line(rows[i])).append('\n')
        sb.append(sep)
        return sb.toString()
    }
}
