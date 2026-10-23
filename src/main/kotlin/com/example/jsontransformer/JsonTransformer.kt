package com.example.jsontransformer

/**
 * Core JSON-to-table engine: it reads a JSON document in any of the supported
 * table layouts, normalises it into a column-oriented [Table], lets you
 * transform that table (transpose, de-duplicate, case conversion, find &
 * replace, sort, ...), and renders the table back out in any of the supported
 * layouts with configurable indentation, minification, an optional root object
 * name and optional "smart parsing" of cell contents.
 */

/** The four table layouts exposed by the tool, plus AUTO for input detection. */
enum class JsonTableFormat {
    AUTO,
    ARRAY_OF_OBJECTS,
    ARRAY_2D,
    COLUMN_ARRAY,
    KEYED_ARRAY
}

/** Indentation style for pretty-printed output. */
enum class IndentStyle {
    TWO_SPACES,
    FOUR_SPACES,
    EIGHT_SPACES,
    TABS
}

/** Text-case transformations offered by the table editor. */
enum class CaseMode { UPPER, LOWER, CAPITALIZE }

/** A normalised table: a list of named columns, each holding an equal-length list of cells. */
data class Column(val name: String, val cells: List<JsonValue>)

data class Table(val columns: List<Column>) {
    /** Cached: immutable table, rowCount never changes. Avoids O(cols) scans in hot loops. */
    val rowCount: Int by lazy { columns.maxOfOrNull { it.cells.size } ?: 0 }
    val isEmpty: Boolean get() = columns.isEmpty() || rowCount == 0
}

object JsonTransformer {

    // -------------------------------------------------------------- public API

    /**
     * Parse an input document into a [Table].
     * @param input raw JSON text
     * @param format explicit layout, or [JsonTableFormat.AUTO] to detect
     * @param smartParse when true, string cells whose content is valid JSON are
     *        parsed into real JSON values (the "解析单元格中的 JSON" option)
     */
    fun parse(input: String, format: JsonTableFormat = JsonTableFormat.AUTO, smartParse: Boolean = false): Table {
        val value = Json.parse(input)
        val fmt = if (format == JsonTableFormat.AUTO) detect(value) else format
        // A single top-level JSON object is a one-row table: wrap it so the
        // array-of-objects parser accepts it ({"a":1,"b":2} → [{"a":1,"b":2}]).
        val normalized = if (fmt == JsonTableFormat.ARRAY_OF_OBJECTS && value is JsonValue.Obj) {
            JsonValue.Arr(listOf(value))
        } else value
        return when (fmt) {
            JsonTableFormat.ARRAY_OF_OBJECTS -> parseArrayOfObjects(normalized, smartParse)
            JsonTableFormat.ARRAY_2D -> parseArray2D(value, smartParse)
            JsonTableFormat.COLUMN_ARRAY -> parseColumnArray(value, smartParse)
            JsonTableFormat.KEYED_ARRAY -> parseKeyedArray(value, smartParse)
            JsonTableFormat.AUTO -> parseArrayOfObjects(normalized, smartParse)
        }
    }

    /**
     * Render a [Table] back to JSON text.
     * @param format output layout (AUTO falls back to ARRAY_OF_OBJECTS)
     * @param indent indentation style
     * @param rootName when set and non-blank, wraps the produced value in an
     *        object under this key ("根对象名称")
     * @param minify emit compact, whitespace-free JSON ("压缩输出")
     */
    fun render(
        table: Table,
        format: JsonTableFormat,
        indent: IndentStyle = IndentStyle.TWO_SPACES,
        rootName: String? = null,
        minify: Boolean = false
    ): String {
        val fmt = if (format == JsonTableFormat.AUTO) JsonTableFormat.ARRAY_OF_OBJECTS else format
        val unit = when (indent) {
            IndentStyle.TWO_SPACES -> "  "
            IndentStyle.FOUR_SPACES -> "    "
            IndentStyle.EIGHT_SPACES -> "        "
            IndentStyle.TABS -> "\t"
        }
        val value = when (fmt) {
            JsonTableFormat.ARRAY_OF_OBJECTS -> renderArrayOfObjects(table)
            JsonTableFormat.ARRAY_2D -> renderArray2D(table)
            JsonTableFormat.COLUMN_ARRAY -> renderColumnArray(table)
            JsonTableFormat.KEYED_ARRAY -> renderKeyedArray(table)
            JsonTableFormat.AUTO -> renderArrayOfObjects(table)
        }
        return Json.render(wrapRoot(value, rootName), unit, minify)
    }

    /** Convenience: parse, transform and render in one call. */
    fun convert(
        input: String,
        outputFormat: JsonTableFormat,
        inputFormat: JsonTableFormat = JsonTableFormat.AUTO,
        indent: IndentStyle = IndentStyle.TWO_SPACES,
        rootName: String? = null,
        minify: Boolean = false,
        smartParse: Boolean = false
    ): String {
        val table = parse(input, inputFormat, smartParse)
        return render(table, outputFormat, indent, rootName, minify)
    }

    // ----------------------------------------------------- table editor ops

    /** Swap rows and columns (the full grid, header row included) — "转置". */
    fun transpose(table: Table): Table {
        if (table.columns.isEmpty()) return table
        val grid = ArrayList<List<JsonValue>>()
        grid.add(table.columns.map { JsonValue.Str(it.name) as JsonValue })
        for (r in 0 until table.rowCount) {
            grid.add(table.columns.map { it.cells.getOrNull(r) ?: JsonValue.Null })
        }
        // grid has (rowCount + 1) rows (header + data) and columns.size columns.
        // The transposed grid therefore has columns.size rows; each original grid
        // row becomes a new column, whose header is that row's first cell.
        val gridRows = grid.size
        val gridCols = grid[0].size
        val newColumns = (0 until gridRows).map { c ->
            val cells = (1 until gridCols).map { j -> grid[c][j] }
            Column(headerName(grid[c][0]), cells)
        }
        return Table(newColumns)
    }

    /** Remove duplicate rows (rows that are identical across every column) — "去重". */
    fun deduplicate(table: Table): Table {
        if (table.columns.isEmpty()) return table
        val seen = HashSet<List<String>>()
        val keep = ArrayList<Int>()
        for (r in 0 until table.rowCount) {
            // Cheap row key that still distinguishes types (Str "1" ≠ Num 1)
            val key = table.columns.map { col ->
                val v = col.cells.getOrNull(r) ?: JsonValue.Null
                if (v is JsonValue.Str) "\"${v.value}\"" else displayCell(v)
            }
            if (seen.add(key)) keep.add(r)
        }
        return selectRows(table, keep)
    }

    /** Remove rows whose every cell is null or blank — "删除空行". */
    fun deleteBlankRows(table: Table): Table {
        if (table.columns.isEmpty()) return table
        val keep = (0 until table.rowCount).filter { r ->
            table.columns.any { col ->
                val v = col.cells.getOrNull(r)
                !(v == null || v is JsonValue.Null || (v is JsonValue.Str && v.value.isBlank()))
            }
        }
        return selectRows(table, keep)
    }

    /** Upper/lower/title-case every string cell — "转大写 / 转小写 / 首字母大写". */
    fun transformCase(table: Table, mode: CaseMode): Table {
        val cols = table.columns.map { col ->
            Column(col.name, col.cells.map { mapString(it) { s -> applyCase(s, mode) } })
        }
        return Table(cols)
    }

    /** Find & replace inside every string cell ("全部替换"). */
    fun replaceText(table: Table, find: String, replacement: String, regex: Boolean = false): Table {
        if (find.isEmpty()) return table
        val pattern = if (regex) runCatching { Regex(find) }.getOrNull() else null
        val cols = table.columns.map { col ->
            Column(col.name, col.cells.map { mapString(it) { s ->
                if (regex) (pattern?.replace(s, replacement) ?: s) else s.replace(find, replacement)
            } })
        }
        return Table(cols)
    }

    /** Sort rows by a column index — "排序". */
    fun sortRows(table: Table, columnIndex: Int, ascending: Boolean = true): Table {
        if (columnIndex !in table.columns.indices) return table
        val col = table.columns[columnIndex]
        val order = (0 until table.rowCount).sortedWith { a, b ->
            val cmp = compareJson(col.cells.getOrNull(a), col.cells.getOrNull(b))
            if (ascending) cmp else -cmp
        }
        return selectRows(table, order)
    }

    /** Delete the given data rows — "删除选中行". */
    fun deleteRows(table: Table, rows: Collection<Int>): Table {
        if (table.columns.isEmpty() || rows.isEmpty()) return table
        val toDrop = rows.filter { it in 0 until table.rowCount }.toSet()
        if (toDrop.isEmpty()) return table
        val keep = (0 until table.rowCount).filter { it !in toDrop }
        return selectRows(table, keep)
    }

    /** Delete the given columns (with their header names) — "删除选中列". */
    fun deleteColumns(table: Table, columns: Collection<Int>): Table {
        if (table.columns.isEmpty() || columns.isEmpty()) return table
        val toDrop = columns.toSet()
        val cols = table.columns.filterIndexed { i, _ -> i !in toDrop }
        return Table(cols)
    }

    /** Insert [count] blank rows at [atIndex] (0-based data-row position). */
    fun insertRows(table: Table, atIndex: Int, count: Int = 1): Table {
        if (count <= 0 || table.columns.isEmpty()) return table
        val at = atIndex.coerceIn(0, table.rowCount)
        val cols = table.columns.map { col ->
            val cells = col.cells.toMutableList()
            repeat(count) { cells.add(at, JsonValue.Null) }
            col.copy(cells = cells)
        }
        return Table(cols)
    }

    /** Insert [count] blank columns before [atIndex] (0-based column position). */
    fun insertColumns(table: Table, atIndex: Int, count: Int = 1): Table {
        if (count <= 0) return table
        val at = atIndex.coerceIn(0, table.columns.size)
        val cols = table.columns.toMutableList()
        repeat(count) { k ->
            cols.add(at + k, Column("column${table.columns.size + k + 1}", (0 until table.rowCount).map { JsonValue.Null }))
        }
        return Table(cols)
    }

    /** Return a copy of [table] with one cell replaced ("单元格编辑"). */
    fun setCell(table: Table, row: Int, column: Int, value: JsonValue): Table {
        if (column !in table.columns.indices) return table
        val cols = table.columns.toMutableList()
        val col = cols[column]
        val cells = col.cells.toMutableList()
        while (cells.size <= row) cells.add(JsonValue.Null)
        cells[row] = value
        cols[column] = col.copy(cells = cells)
        return Table(cols)
    }

    // ------------------------------------------------------------- statistics

    /** Column statistics shown in the editor's footer (COUNT/SUM/MIN/MAX/AVG). */
    data class ColumnStats(val count: Int, val sum: Double?, val min: Double?, val max: Double?, val avg: Double?) {
        fun render(): String {
            fun f(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
            val sb = StringBuilder("COUNT: $count")
            sum?.let { sb.append("  SUM: ").append(f(it)) }
            min?.let { sb.append("  MIN: ").append(f(it)) }
            max?.let { sb.append("  MAX: ").append(f(it)) }
            avg?.let { sb.append("  AVG: ").append(f(it)) }
            return sb.toString()
        }
    }

    /** Compute statistics over one column of the table. */
    fun columnStats(table: Table, columnIndex: Int): ColumnStats? {
        val col = table.columns.getOrNull(columnIndex) ?: return null
        var count = 0
        var numericCount = 0
        var sum = 0.0; var min = 0.0; var max = 0.0; var hasNum = false
        for (cell in col.cells) {
            val text = displayCell(cell).trim()
            if (text.isEmpty()) continue
            count++
            val num = text.toDoubleOrNull() ?: continue
            numericCount++
            sum += num
            if (!hasNum) { min = num; max = num; hasNum = true }
            else { if (num < min) min = num; if (num > max) max = num }
        }
        return ColumnStats(
            count = count,
            sum = if (hasNum) sum else null,
            min = if (hasNum) min else null,
            max = if (hasNum) max else null,
            // AVG must divide by the number of numeric cells, not all cells
            avg = if (hasNum) sum / numericCount else null
        )
    }

    // --------------------------------------------------- cell value helpers

    /** The plain-text form of a cell, used by the grid editor. */
    fun displayCell(v: JsonValue): String = when (v) {
        is JsonValue.Str -> v.value
        is JsonValue.Num -> v.raw
        is JsonValue.Bool -> v.value.toString()
        is JsonValue.Null -> ""
        else -> Json.render(v, minify = true)
    }

    /** Interpret raw editor text as the most specific JSON value it can represent. */
    fun parseCell(raw: String): JsonValue {
        val t = raw.trim()
        if (t.isEmpty()) return JsonValue.Str(raw)
        return when {
            t == "null" -> JsonValue.Null
            t == "true" -> JsonValue.Bool(true)
            t == "false" -> JsonValue.Bool(false)
            NUMBER.matches(t) -> JsonValue.Num(t)
            t.startsWith("{") || t.startsWith("[") -> tryParseEmbedded(t) ?: JsonValue.Str(raw)
            else -> JsonValue.Str(raw)
        }
    }

    private val NUMBER = Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")

    // ------------------------------------------------------------ input parsing

    private fun detect(v: JsonValue): JsonTableFormat = when (v) {
        is JsonValue.Arr -> {
            if (v.elements.isNotEmpty() && v.elements.all { it is JsonValue.Obj }) JsonTableFormat.ARRAY_OF_OBJECTS
            else if (v.elements.isNotEmpty() && v.elements.all { it is JsonValue.Arr }) JsonTableFormat.ARRAY_2D
            else if (v.elements.isNotEmpty() && v.elements.first() is JsonValue.Arr) JsonTableFormat.ARRAY_2D
            else JsonTableFormat.ARRAY_OF_OBJECTS
        }
        is JsonValue.Obj -> {
            if (v.members.isNotEmpty() && v.members.values.all { it is JsonValue.Arr }) JsonTableFormat.KEYED_ARRAY
            else JsonTableFormat.ARRAY_OF_OBJECTS
        }
        else -> JsonTableFormat.ARRAY_OF_OBJECTS
    }

    private fun asArr(v: JsonValue): JsonValue.Arr? = v as? JsonValue.Arr

    private fun headerName(jv: JsonValue): String = when (jv) {
        is JsonValue.Str -> jv.value
        else -> Json.render(jv, minify = true)
    }

    /** Resolve a raw cell, applying smart-parse to strings when enabled. */
    private fun cell(v: JsonValue?, smart: Boolean): JsonValue {
        if (v == null) return JsonValue.Null
        if (smart && v is JsonValue.Str) return tryParseEmbedded(v.value) ?: v
        return v
    }

    private fun parseArrayOfObjects(v: JsonValue, smart: Boolean): Table {
        val arr = asArr(v) ?: throw IllegalArgumentException("Array-of-Objects input must be a JSON array")
        val objs = arr.elements.map {
            it as? JsonValue.Obj ?: throw IllegalArgumentException("Array-of-Objects requires every element to be an object")
        }
        val keys = LinkedHashSet<String>()
        objs.forEach { keys.addAll(it.members.keys) }
        val columns = keys.map { key ->
            Column(key, objs.map { o -> cell(o.members[key], smart) })
        }
        return Table(columns)
    }

    private fun parseArray2D(v: JsonValue, smart: Boolean): Table {
        val arr = asArr(v) ?: throw IllegalArgumentException("2D-Array input must be a JSON array")
        if (arr.elements.isEmpty()) return Table(emptyList())
        val rows = arr.elements.map {
            asArr(it) ?: throw IllegalArgumentException("2D-Array requires every row to be an array")
        }
        val headerNames = rows.first().elements.map { headerName(it) }
        val maxCols = maxOf(headerNames.size, rows.maxOf { it.elements.size })
        val columns = (0 until maxCols).map { c ->
            val name = if (c < headerNames.size) headerNames[c] else "column${c + 1}"
            val cells = rows.drop(1).map { r -> cell(r.elements.getOrNull(c), smart) }
            Column(name, cells)
        }
        return Table(columns)
    }

    private fun parseColumnArray(v: JsonValue, smart: Boolean): Table {
        val arr = asArr(v) ?: throw IllegalArgumentException("Column-Array input must be a JSON array")
        val columns = arr.elements.map { col ->
            val carr = asArr(col) ?: throw IllegalArgumentException("Column-Array requires every column to be an array")
            val name = if (carr.elements.isNotEmpty()) headerName(carr.elements[0]) else ""
            val cells = carr.elements.drop(1).map { cell(it, smart) }
            Column(name, cells)
        }
        return Table(columns)
    }

    private fun parseKeyedArray(v: JsonValue, smart: Boolean): Table {
        val obj = v as? JsonValue.Obj ?: throw IllegalArgumentException("Keyed-Array input must be a JSON object")
        val columns = obj.members.map { (k, arr) ->
            val a = asArr(arr) ?: throw IllegalArgumentException("Keyed-Array requires every value to be an array of cells")
            Column(k, a.elements.map { cell(it, smart) })
        }
        return Table(columns)
    }

    // ----------------------------------------------------------- output render

    private fun renderArrayOfObjects(t: Table): JsonValue {
        val rows = (0 until t.rowCount).map { r ->
            val members = LinkedHashMap<String, JsonValue>()
            t.columns.forEach { col -> members[col.name] = col.cells.getOrNull(r) ?: JsonValue.Null }
            JsonValue.Obj(members)
        }
        return JsonValue.Arr(rows)
    }

    private fun renderArray2D(t: Table): JsonValue {
        val header = JsonValue.Arr(t.columns.map { JsonValue.Str(it.name) })
        val rows = (0 until t.rowCount).map { r ->
            JsonValue.Arr(t.columns.map { col -> col.cells.getOrNull(r) ?: JsonValue.Null })
        }
        return JsonValue.Arr(listOf(header) + rows)
    }

    private fun renderColumnArray(t: Table): JsonValue {
        val cols = t.columns.map { col ->
            JsonValue.Arr(listOf(JsonValue.Str(col.name)) + col.cells)
        }
        return JsonValue.Arr(cols)
    }

    private fun renderKeyedArray(t: Table): JsonValue {
        val members = LinkedHashMap<String, JsonValue>()
        t.columns.forEach { col -> members[col.name] = JsonValue.Arr(col.cells) }
        return JsonValue.Obj(members)
    }

    private fun wrapRoot(value: JsonValue, rootName: String?): JsonValue {
        if (rootName.isNullOrBlank()) return value
        val members = LinkedHashMap<String, JsonValue>()
        members[rootName] = value
        return JsonValue.Obj(members)
    }

    // ------------------------------------------------------------- utilities

    private fun selectRows(table: Table, rows: List<Int>): Table {
        val cols = table.columns.map { col ->
            Column(col.name, rows.map { r -> col.cells.getOrNull(r) ?: JsonValue.Null })
        }
        return Table(cols)
    }

    private inline fun mapString(v: JsonValue, f: (String) -> String): JsonValue =
        if (v is JsonValue.Str) JsonValue.Str(f(v.value)) else v

    private fun applyCase(s: String, mode: CaseMode): String = when (mode) {
        CaseMode.UPPER -> s.uppercase()
        CaseMode.LOWER -> s.lowercase()
        CaseMode.CAPITALIZE -> s.split(" ").joinToString(" ") { w ->
            if (w.isEmpty()) w else w[0].uppercase() + w.substring(1)
        }
    }

    private fun compareJson(a: JsonValue?, b: JsonValue?): Int {
        val aNull = a == null || a is JsonValue.Null
        val bNull = b == null || b is JsonValue.Null
        if (aNull && bNull) return 0
        if (aNull) return 1          // nulls sort last
        if (bNull) return -1
        // type ordering: numbers < strings < booleans < structures
        val ra = typeRank(a)
        val rb = typeRank(b)
        if (ra != rb) return ra - rb
        if (a is JsonValue.Num && b is JsonValue.Num) {
            val da = a.raw.toDoubleOrNull()
            val db = b.raw.toDoubleOrNull()
            if (da != null && db != null) return da.compareTo(db)
        }
        // fast path: compare string values directly instead of re-rendering JSON
        if (a is JsonValue.Str && b is JsonValue.Str) return a.value.compareTo(b.value)
        return Json.render(a, minify = true).compareTo(Json.render(b, minify = true))
    }

    private fun typeRank(v: JsonValue): Int = when (v) {
        is JsonValue.Num -> 0
        is JsonValue.Str -> 1
        is JsonValue.Bool -> 2
        else -> 3
    }
}
