package com.example.jsontransformer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonTransformerTest {

    private val sample = """
        [
          { "name": "Laptop", "price": 999, "stock": 15 },
          { "name": "Mouse",  "price": 29,  "stock": 50 }
        ]
    """.trimIndent()

    @Test
    fun `auto-detect array of objects`() {
        val t = JsonTransformer.parse(sample)
        assertEquals(listOf("name", "price", "stock"), t.columns.map { it.name })
        assertEquals(2, t.rowCount)
    }

    @Test
    fun `array of objects round-trips`() {
        val out = JsonTransformer.convert(sample, JsonTableFormat.ARRAY_OF_OBJECTS, minify = true)
        assertEquals(
            """[{"name":"Laptop","price":999,"stock":15},{"name":"Mouse","price":29,"stock":50}]""",
            out
        )
    }

    @Test
    fun `array of objects to 2d array`() {
        val out = JsonTransformer.convert(sample, JsonTableFormat.ARRAY_2D, minify = true)
        assertEquals(
            """[["name","price","stock"],["Laptop",999,15],["Mouse",29,50]]""",
            out
        )
    }

    @Test
    fun `array of objects to column array`() {
        val out = JsonTransformer.convert(sample, JsonTableFormat.COLUMN_ARRAY, minify = true)
        assertEquals(
            """[["name","Laptop","Mouse"],["price",999,29],["stock",15,50]]""",
            out
        )
    }

    @Test
    fun `array of objects to keyed array with root name`() {
        val out = JsonTransformer.convert(
            sample, JsonTableFormat.KEYED_ARRAY, rootName = "data", minify = true
        )
        assertEquals(
            """{"data":{"name":["Laptop","Mouse"],"price":[999,29],"stock":[15,50]}}""",
            out
        )
    }

    @Test
    fun `2d array parses headers and pads short rows`() {
        val input = """[["a","b"],["x",1],["y"]]"""
        val t = JsonTransformer.parse(input, JsonTableFormat.ARRAY_2D)
        assertEquals(listOf("a", "b"), t.columns.map { it.name })
        assertEquals(2, t.rowCount)
        // second data row missing "b" -> null
        assertEquals(JsonValue.Null, t.columns[1].cells[1])
    }

    @Test
    fun `keyed array parses`() {
        val input = """{"name":["Laptop","Mouse"],"price":[999,29]}"""
        val t = JsonTransformer.parse(input, JsonTableFormat.KEYED_ARRAY)
        assertEquals(listOf("name", "price"), t.columns.map { it.name })
        assertEquals(2, t.columns[0].cells.size)
    }

    @Test
    fun `smart parse converts stringified json cells`() {
        val input = """[{"v":"{\"k\":1}"},{"v":"[1,2]"}]"""
        val out = JsonTransformer.convert(input, JsonTableFormat.KEYED_ARRAY, smartParse = true, minify = true)
        assertEquals("""{"v":[{"k":1},[1,2]]}""", out)
    }

    @Test
    fun `indent style changes pretty output`() {
        val out = JsonTransformer.convert(sample, JsonTableFormat.ARRAY_OF_OBJECTS, indent = IndentStyle.FOUR_SPACES)
        val nameLine = out.lines().first { it.trim().startsWith("\"name\"") }
        val lead = nameLine.takeWhile { it == ' ' }
        assertEquals("        ", lead, "expected 4-space indentation at depth 2 (8 spaces)")
    }

    @Test
    fun `missing keys become null`() {
        val input = """[{"a":1},{"b":2}]"""
        val t = JsonTransformer.parse(input)
        assertEquals(listOf("a", "b"), t.columns.map { it.name })
        assertEquals(JsonValue.Null, t.columns[0].cells[1])
        assertEquals(JsonValue.Null, t.columns[1].cells[0])
    }

    @Test
    fun `transpose rotates rows and columns`() {
        val out = JsonTransformer.render(
            JsonTransformer.transpose(JsonTransformer.parse(sample)),
            JsonTableFormat.ARRAY_2D, minify = true
        )
        assertEquals("""[["name","Laptop","Mouse"],["price",999,29],["stock",15,50]]""", out)
    }

    @Test
    fun `deduplicate removes identical rows`() {
        val t = JsonTransformer.parse("""[{"a":1},{"a":1},{"a":2}]""")
        assertEquals(2, JsonTransformer.deduplicate(t).rowCount)
    }

    @Test
    fun `deleteBlankRows removes all-empty rows`() {
        val t = JsonTransformer.parse("""[{"a":1},{"a":""}]""")
        assertEquals(1, JsonTransformer.deleteBlankRows(t).rowCount)
    }

    @Test
    fun `transformCase upper and capitalize`() {
        val t = JsonTransformer.parse("""[{"a":"hello world"}]""")
        assertEquals("HELLO WORLD", JsonTransformer.displayCell(JsonTransformer.transformCase(t, CaseMode.UPPER).columns[0].cells[0]))
        assertEquals("Hello World", JsonTransformer.displayCell(JsonTransformer.transformCase(t, CaseMode.CAPITALIZE).columns[0].cells[0]))
    }

    @Test
    fun `replaceText literal and regex`() {
        val t = JsonTransformer.parse("""[{"a":"foo-bar"}]""")
        assertEquals("foo_bar", JsonTransformer.displayCell(JsonTransformer.replaceText(t, "-", "_").columns[0].cells[0]))
        assertEquals("foo-bar", JsonTransformer.displayCell(JsonTransformer.replaceText(t, "\\d+", "N", regex = true).columns[0].cells[0]))
    }

    @Test
    fun `sortRows ascending and descending`() {
        val t = JsonTransformer.parse("""[{"n":3},{"n":1},{"n":2}]""")
        assertEquals(
            """[["n"],[1],[2],[3]]""",
            JsonTransformer.render(JsonTransformer.sortRows(t, 0, true), JsonTableFormat.ARRAY_2D, minify = true)
        )
        assertEquals(
            """[["n"],[3],[2],[1]]""",
            JsonTransformer.render(JsonTransformer.sortRows(t, 0, false), JsonTableFormat.ARRAY_2D, minify = true)
        )
    }

    @Test
    fun `setCell replaces a single cell`() {
        val t = JsonTransformer.parse("""[{"a":1}]""")
        val e = JsonTransformer.setCell(t, 0, 0, JsonTransformer.parseCell("99"))
        assertEquals(JsonValue.Num("99"), e.columns[0].cells[0])
    }

    @Test
    fun `parseCell infers value type`() {
        assertEquals(JsonValue.Num("123"), JsonTransformer.parseCell("123"))
        assertEquals(JsonValue.Bool(true), JsonTransformer.parseCell("true"))
        assertEquals(JsonValue.Str("hi"), JsonTransformer.parseCell("hi"))
    }

    @Test
    fun `root name wraps any output format`() {
        val out = JsonTransformer.convert(sample, JsonTableFormat.ARRAY_OF_OBJECTS, rootName = "data", minify = true)
        assertEquals(
            """{"data":[{"name":"Laptop","price":999,"stock":15},{"name":"Mouse","price":29,"stock":50}]}""",
            out
        )
    }

    @Test
    fun `columnStats avg divides by numeric cell count only`() {
        val t = JsonTransformer.parse("""[{"a":1},{"a":2},{"a":"x"}]""")
        val s = JsonTransformer.columnStats(t, 0)!!
        assertEquals(3, s.count)          // 3 non-empty cells
        assertEquals(3.0, s.sum!!, 1e-9)
        // AVG must be (1+2)/2 = 1.5, not 3/3 = 1.0
        assertEquals(1.5, s.avg!!, 1e-9)
    }

    @Test
    fun `deduplicate treats differently-typed but same-display rows distinctly`() {
        val t = JsonTransformer.parse("""[{"a":1},{"a":"1"},{"a":1}]""")
        // displayCell("1" as string) == displayCell(1 as number) -> both collapse
        assertEquals(2, JsonTransformer.deduplicate(t).rowCount)
    }

    @Test
    fun `sortRows mixes strings and numbers stably`() {
        val t = JsonTransformer.parse("""[{"a":"b"},{"a":1},{"a":null}]""")
        val sorted = JsonTransformer.sortRows(t, 0, true)
        assertEquals("1", JsonTransformer.displayCell(sorted.columns[0].cells[0]))
        assertEquals("b", JsonTransformer.displayCell(sorted.columns[0].cells[1]))
        assertTrue(sorted.columns[0].cells[2] is JsonValue.Null)   // nulls last
    }
}
