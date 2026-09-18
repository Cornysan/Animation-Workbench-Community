package com.playmation.motionlabsbackend.format

/**
 * Strikter JSON-Parser - Portierung von `AWStrictJson.cs` aus dem
 * Community-Modul der Animation Workbench. Beide Seiten müssen dieselben
 * Dateien mit denselben Codes ablehnen; die gemeinsamen Testdateien liegen
 * unter `src/test/resources/awclip`.
 *
 * Abgelehnt statt toleriert: doppelte Schlüssel, Kommentare, NaN/Infinity,
 * führende Nullen, Steuerzeichen in Strings, Tiefe über 16, Strings über
 * 65 536 Zeichen.
 */
object StrictJson {
    const val MAX_DEPTH = 16
    const val MAX_STRING_LENGTH = 1 shl 16

    sealed class Value {
        object Null : Value()
        data class Bool(val value: Boolean) : Value()
        data class Number(val value: Double) : Value()
        data class Str(val value: String) : Value()
        data class Arr(val items: List<Value>) : Value()

        /** Reihenfolge wie in der Datei. */
        data class Obj(val members: List<Pair<String, Value>>) : Value() {
            operator fun get(key: String): Value? = members.firstOrNull { it.first == key }?.second
        }
    }

    class JsonException(val code: String, message: String, val position: Int) :
        RuntimeException("$message (at $position)")

    fun parse(text: String): Value {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd) throw parser.error("json-syntax", "Unexpected content after the document")
        return value
    }

    private class Parser(private val text: String) {
        private var pos = 0
        private val sb = StringBuilder()

        val atEnd get() = pos >= text.length

        fun error(code: String, message: String) = JsonException(code, message, pos)

        fun skipWhitespace() {
            while (pos < text.length) {
                val c = text[pos]
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break
                pos++
            }
        }

        fun parseValue(depth: Int): Value {
            if (depth > MAX_DEPTH) throw error("json-depth", "Nesting deeper than $MAX_DEPTH")
            if (atEnd) throw error("json-syntax", "Unexpected end of document")

            return when (val c = text[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> Value.Str(parseString())
                't' -> { expect("true"); Value.Bool(true) }
                'f' -> { expect("false"); Value.Bool(false) }
                'n' -> { expect("null"); Value.Null }
                else ->
                    if (c == '-' || c in '0'..'9') Value.Number(parseNumber())
                    else throw error("json-syntax", "Unexpected character '$c'")
            }
        }

        private fun parseObject(depth: Int): Value {
            pos++
            val members = ArrayList<Pair<String, Value>>()
            val seen = HashSet<String>()

            skipWhitespace()
            if (!atEnd && text[pos] == '}') {
                pos++
                return Value.Obj(members)
            }

            while (true) {
                skipWhitespace()
                if (atEnd || text[pos] != '"') throw error("json-syntax", "Expected a member name")

                val key = parseString()
                if (!seen.add(key)) throw error("json-duplicate-key", "Duplicate member '$key'")

                skipWhitespace()
                if (atEnd || text[pos] != ':') throw error("json-syntax", "Expected ':'")
                pos++

                skipWhitespace()
                members.add(key to parseValue(depth + 1))

                skipWhitespace()
                if (atEnd) throw error("json-syntax", "Unterminated object")

                val c = text[pos++]
                if (c == '}') break
                if (c != ',') throw error("json-syntax", "Expected ',' or '}'")
            }

            return Value.Obj(members)
        }

        private fun parseArray(depth: Int): Value {
            pos++
            val items = ArrayList<Value>()

            skipWhitespace()
            if (!atEnd && text[pos] == ']') {
                pos++
                return Value.Arr(items)
            }

            while (true) {
                skipWhitespace()
                items.add(parseValue(depth + 1))

                skipWhitespace()
                if (atEnd) throw error("json-syntax", "Unterminated array")

                val c = text[pos++]
                if (c == ']') break
                if (c != ',') throw error("json-syntax", "Expected ',' or ']'")
            }

            return Value.Arr(items)
        }

        private fun parseString(): String {
            pos++
            sb.setLength(0)

            while (true) {
                if (atEnd) throw error("json-syntax", "Unterminated string")

                val c = text[pos++]
                if (c == '"') return sb.toString()
                if (c.code < 0x20) throw error("json-syntax", "Control character in string")

                if (c == '\\') {
                    if (atEnd) throw error("json-syntax", "Unterminated escape")
                    when (val e = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) throw error("json-syntax", "Truncated unicode escape")
                            var code = 0
                            repeat(4) {
                                val d = Character.digit(text[pos++], 16)
                                if (d < 0) throw error("json-syntax", "Invalid unicode escape")
                                code = code * 16 + d
                            }
                            sb.append(code.toChar())
                        }
                        else -> throw error("json-syntax", "Invalid escape '\\$e'")
                    }
                } else {
                    sb.append(c)
                }

                if (sb.length > MAX_STRING_LENGTH) throw error("json-string-length", "String longer than $MAX_STRING_LENGTH")
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            if (text[pos] == '-') pos++
            if (atEnd) throw error("json-syntax", "Truncated number")

            when {
                text[pos] == '0' -> {
                    pos++
                    if (!atEnd && text[pos].isAsciiDigit()) throw error("json-syntax", "Leading zero in number")
                }
                text[pos].isAsciiDigit() -> while (!atEnd && text[pos].isAsciiDigit()) pos++
                else -> throw error("json-syntax", "Invalid number")
            }

            if (!atEnd && text[pos] == '.') {
                pos++
                if (atEnd || !text[pos].isAsciiDigit()) throw error("json-syntax", "Digit expected after '.'")
                while (!atEnd && text[pos].isAsciiDigit()) pos++
            }

            if (!atEnd && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (!atEnd && (text[pos] == '+' || text[pos] == '-')) pos++
                if (atEnd || !text[pos].isAsciiDigit()) throw error("json-syntax", "Digit expected in exponent")
                while (!atEnd && text[pos].isAsciiDigit()) pos++
            }

            if (pos - start > 64) throw error("json-syntax", "Number literal too long")

            val value = text.substring(start, pos).toDouble()
            if (value.isInfinite() || value.isNaN()) throw error("json-number-range", "Number out of range")
            return value
        }

        private fun expect(literal: String) {
            if (!text.regionMatches(pos, literal, 0, literal.length)) throw error("json-syntax", "Expected '$literal'")
            pos += literal.length
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }

    // ── Schreiben ─────────────────────────────────────────────────────────

    /** Kompaktes JSON eines geprüften Baums - für die separat abgelegte Vorschau. */
    fun write(value: Value): String = buildString { writeValue(this, value) }

    private fun writeValue(sb: StringBuilder, value: Value) {
        when (value) {
            is Value.Null -> sb.append("null")
            is Value.Bool -> sb.append(value.value)
            is Value.Number -> {
                val d = value.value
                if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Value.Str -> writeString(sb, value.value)
            is Value.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { i, item -> if (i > 0) sb.append(','); writeValue(sb, item) }
                sb.append(']')
            }
            is Value.Obj -> {
                sb.append('{')
                value.members.forEachIndexed { i, (key, item) ->
                    if (i > 0) sb.append(',')
                    writeString(sb, key); sb.append(':'); writeValue(sb, item)
                }
                sb.append('}')
            }
        }
    }

    fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Float als kürzeste Darstellung, die beim Einlesen als float exakt zurückkommt. */
    fun writeFloat(sb: StringBuilder, value: Float) {
        require(value.isFinite()) { "Non-finite number cannot be written as JSON" }
        if (value == 0f) {
            sb.append('0')
            return
        }
        sb.append(value.toString())
    }
}
