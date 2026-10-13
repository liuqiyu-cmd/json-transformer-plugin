package com.example.jsontransformer

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Dependency-free .xlsx writer. An xlsx file is just a ZIP of XML parts,
 * so the JDK's zip + string APIs are enough — no Apache POI needed.
 *
 * Cells are written as inline strings (numbers/booleans typed), which
 * Excel, WPS and Numbers all read without requiring a sharedStrings part.
 */
object XlsxWriter {

    /** Build a single-sheet .xlsx from a [Table]. */
    fun write(table: Table, sheetName: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out.buffered()).use { zip ->
            put(zip, "[Content_Types].xml", contentTypesXml())
            put(zip, "_rels/.rels", rootRelsXml())
            put(zip, "xl/workbook.xml", workbookXml(sheetName))
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml())
            put(zip, "xl/worksheets/sheet1.xml", sheetXml(table))
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------- zip parts

    private fun put(zip: ZipOutputStream, name: String, xml: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(xml.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

    private fun rootRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbookXml(sheetName: String?): String {
        val name = sanitizeSheetName(sheetName)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="$name" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""
    }

    private fun workbookRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""

    // ---------------------------------------------------------------- sheet

    private fun sheetXml(table: Table): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        // header row
        sb.append("""<row r="1">""")
        table.columns.forEachIndexed { c, col ->
            sb.append(inlineStrCell(colName(c, 1), col.name))
        }
        sb.append("</row>")
        // data rows
        for (r in 0 until table.rowCount) {
            sb.append("<row r=\"").append(r + 2).append("\">")
            table.columns.forEachIndexed { c, col ->
                sb.append(cell(colName(c, r + 2), col.cells.getOrNull(r) ?: JsonValue.Null))
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun cell(ref: String, v: JsonValue): String = when (v) {
        is JsonValue.Num -> """<c r="$ref"><v>""" + xmlEscape(v.raw) + "</v></c>"
        is JsonValue.Bool -> """<c r="$ref" t="b"><v>""" + (if (v.value) "1" else "0") + "</v></c>"
        else -> inlineStrCell(ref, JsonTransformer.displayCell(v))
    }

    private fun inlineStrCell(ref: String, text: String): String =
        """<c r="$ref" t="inlineStr"><is><t>""" + xmlEscape(text) + "</t></is></c>"

    // -------------------------------------------------------------- helpers

    /** 0 -> "A", 25 -> "Z", 26 -> "AA", ... */
    private fun colName(index: Int, row: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString() + row
    }

    private fun sanitizeSheetName(raw: String?): String {
        val base = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: "Sheet1"
        val cleaned = base.replace(Regex("[\\[\\]\\*\\?/:\\\\]"), "_")
        return cleaned.take(31)
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
