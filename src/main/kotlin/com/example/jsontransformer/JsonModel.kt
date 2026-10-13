package com.example.jsontransformer

/**
 * A minimal, dependency-free JSON value model plus a tolerant parser and a
 * configurable renderer.
 *
 * The model is intentionally independent of any IntelliJ / third-party APIs so
 * the whole conversion engine can be unit-tested against the Kotlin stdlib alone.
 */

sealed interface JsonValue {

    /** JSON object, key order preserved (insertion order). */
    data class Obj(val members: LinkedHashMap<String, JsonValue>) : JsonValue

    /** JSON array. */
    data class Arr(val elements: List<JsonValue>) : JsonValue

    /** JSON string (decoded value, without surrounding quotes). */
    data class Str(val value: String) : JsonValue

    /** JSON number. The original textual representation is preserved verbatim. */
    data class Num(val raw: String) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

class JsonParseException(message: String) : RuntimeException(message)

object Json {

    /** Parse a complete JSON document. Throws [JsonParseException] on errors. */
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        parser.skipWs()
        val value = parser.parseValue()
        parser.skipWs()
        if (!parser.isEnd()) {
            throw JsonParseException("Unexpected trailing characters at index ${parser.index}")
        }
        return value
    }

    /**
     * Render a JSON value.
     * @param indentUnit the string used for one indentation level when pretty-printing.
     * @param minify when true, emit compact JSON with no whitespace.
     */
    fun render(value: JsonValue, indentUnit: String = "  ", minify: Boolean = false): String {
        val sb = StringBuilder()
        if (minify) renderCompact(value, sb) else renderPretty(value, sb, "", indentUnit)
        return sb.toString()
    }

    // ------------------------------------------------------------------ pretty

    private fun renderPretty(v: JsonValue, sb: StringBuilder, indent: String, unit: String) {
        when (v) {
            is JsonValue.Obj -> {
                if (v.members.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                var first = true
                for ((k, value) in v.members) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(indent).append(unit).append(quote(k)).append(": ")
                    renderPretty(value, sb, indent + unit, unit)
                }
                sb.append("\n").append(indent).append("}")
            }
            is JsonValue.Arr -> {
                if (v.elements.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                var first = true
                for (e in v.elements) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(indent).append(unit)
                    renderPretty(e, sb, indent + unit, unit)
                }
                sb.append("\n").append(indent).append("]")
            }
            is JsonValue.Str -> sb.append(quote(v.value))
            is JsonValue.Num -> sb.append(v.raw)
            is JsonValue.Bool -> sb.append(if (v.value) "true" else "false")
            is JsonValue.Null -> sb.append("null")
        }
    }

    // ----------------------------------------------------------------- compact

    private fun renderCompact(v: JsonValue, sb: StringBuilder) {
        when (v) {
            is JsonValue.Obj -> {
                sb.append("{")
                var first = true
                for ((k, value) in v.members) {
                    if (!first) sb.append(",")
                    first = false
                    sb.append(quote(k)).append(":")
                    renderCompact(value, sb)
                }
                sb.append("}")
            }
            is JsonValue.Arr -> {
                sb.append("[")
                var first = true
                for (e in v.elements) {
                    if (!first) sb.append(",")
                    first = false
                    renderCompact(e, sb)
                }
                sb.append("]")
            }
            is JsonValue.Str -> sb.append(quote(v.value))
            is JsonValue.Num -> sb.append(v.raw)
            is JsonValue.Bool -> sb.append(if (v.value) "true" else "false")
            is JsonValue.Null -> sb.append("null")
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    // ------------------------------------------------------------------ parser

    private class Parser(private val text: String) {
        var index = 0

        fun isEnd() = index >= text.length

        fun skipWs() {
            while (index < text.length) {
                val c = text[index]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') index++ else break
            }
        }

        fun parseValue(): JsonValue {
            skipWs()
            if (isEnd()) throw JsonParseException("Unexpected end of input")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> parseNumber()
                else -> throw JsonParseException("Unexpected character '$c' at index $index")
            }
        }

        fun parseObject(): JsonValue.Obj {
            expect('{')
            val members = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') {
                index++
                return JsonValue.Obj(members)
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonParseException("Expected string key at index $index")
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                members[key] = value
                skipWs()
                when (val c = peek()) {
                    ',' -> {
                        index++
                        continue
                    }
                    '}' -> {
                        index++
                        break
                    }
                    else -> throw JsonParseException("Expected ',' or '}' at index $index, got '$c'")
                }
            }
            return JsonValue.Obj(members)
        }

        fun parseArray(): JsonValue.Arr {
            expect('[')
            val elements = mutableListOf<JsonValue>()
            skipWs()
            if (peek() == ']') {
                index++
                return JsonValue.Arr(elements)
            }
            while (true) {
                elements.add(parseValue())
                skipWs()
                when (val c = peek()) {
                    ',' -> {
                        index++
                        continue
                    }
                    ']' -> {
                        index++
                        break
                    }
                    else -> throw JsonParseException("Expected ',' or ']' at index $index, got '$c'")
                }
            }
            return JsonValue.Arr(elements)
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (index < text.length) {
                val c = text[index++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (isEnd()) throw JsonParseException("Unterminated escape sequence")
                        val e = text[index++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw JsonParseException("Invalid unicode escape")
                                val hex = text.substring(index, index + 4)
                                index += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw JsonParseException("Invalid escape '\\$e'")
                        }
                    }
                    c.code < 0x20 -> throw JsonParseException("Control character in string at index ${index - 1}")
                    else -> sb.append(c)
                }
            }
            throw JsonParseException("Unterminated string")
        }

        fun parseLiteral(lit: String, value: JsonValue): JsonValue {
            if (text.startsWith(lit, index)) {
                index += lit.length
                return value
            }
            throw JsonParseException("Expected '$lit' at index $index")
        }

        fun parseNumber(): JsonValue.Num {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            if (index < text.length && text[index] == '.') {
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            if (raw.isEmpty() || raw == "-") throw JsonParseException("Invalid number at index $start")
            return JsonValue.Num(raw)
        }

        fun peek(): Char = if (isEnd()) '\u0000' else text[index]

        fun expect(c: Char) {
            if (isEnd() || text[index] != c) throw JsonParseException("Expected '$c' at index $index")
            index++
        }
    }
}

/** Try to interpret [s] as embedded JSON; returns null when it is not valid JSON. */
fun tryParseEmbedded(s: String): JsonValue? {
    val t = s.trim()
    if (t.isEmpty()) return null
    return try {
        Json.parse(t)
    } catch (_: JsonParseException) {
        null
    }
}
