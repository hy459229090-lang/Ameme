package com.ameme.android.data.transport.channel

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface CanonicalJsonValue

internal data class CanonicalJsonObject(
    val values: Map<String, CanonicalJsonValue>,
) : CanonicalJsonValue

internal data class CanonicalJsonArray(
    val values: List<CanonicalJsonValue>,
) : CanonicalJsonValue

internal data class CanonicalJsonString(val value: String) : CanonicalJsonValue

internal data class CanonicalJsonInteger(val value: BigInteger) : CanonicalJsonValue

internal data class CanonicalJsonBoolean(val value: Boolean) : CanonicalJsonValue

internal data object CanonicalJsonNull : CanonicalJsonValue

internal class StrictJsonViolation : IllegalArgumentException("invalid_json")

/** Executable `ameme-canonical-json-v1` subset used by the paired channel. */
internal object StrictCanonicalJson {
    private val maxSafeInteger = BigInteger("9007199254740991")
    private val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    fun parseLine(line: ByteArray, maximum: Int): CanonicalJsonValue {
        if (line.isEmpty() || line.size > maximum) throw StrictJsonViolation()
        if (
            line.size >= 3 &&
            line[0] == 0xef.toByte() &&
            line[1] == 0xbb.toByte() &&
            line[2] == 0xbf.toByte()
        ) {
            throw StrictJsonViolation()
        }
        if (line.any { it == 0.toByte() }) throw StrictJsonViolation()
        val newlineIndexes = line.indices.filter { line[it] == '\n'.code.toByte() }
        if (newlineIndexes.size > 1 || (newlineIndexes.isNotEmpty() && newlineIndexes.single() != line.lastIndex)) {
            throw StrictJsonViolation()
        }
        val payload = if (newlineIndexes.isNotEmpty()) line.copyOf(line.size - 1) else line.copyOf()
        if (payload.isEmpty()) throw StrictJsonViolation()
        val text = try {
            synchronized(utf8Decoder) {
                utf8Decoder.reset()
                utf8Decoder.decode(ByteBuffer.wrap(payload)).toString()
            }
        } catch (_: Exception) {
            throw StrictJsonViolation()
        } finally {
            payload.fill(0)
        }
        return Parser(text).parse()
    }

    fun canonicalBytes(value: CanonicalJsonValue): ByteArray = buildString {
        appendCanonical(value)
    }.encodeToByteArray()

    fun obj(vararg values: Pair<String, CanonicalJsonValue>): CanonicalJsonObject =
        CanonicalJsonObject(linkedMapOf(*values))

    fun string(value: String): CanonicalJsonString {
        validateString(value)
        return CanonicalJsonString(value)
    }

    fun integer(value: Long): CanonicalJsonInteger =
        CanonicalJsonInteger(BigInteger.valueOf(value)).also { validateInteger(it.value) }

    fun array(values: Iterable<CanonicalJsonValue>): CanonicalJsonArray =
        CanonicalJsonArray(values.toList())

    fun digest(value: CanonicalJsonValue): String =
        "sha256_" + Crypto.sha256(canonicalBytes(value)).toHex()

    private fun StringBuilder.appendCanonical(value: CanonicalJsonValue) {
        when (value) {
            is CanonicalJsonObject -> {
                append('{')
                value.values.entries
                    .sortedWith { left, right -> compareUnicode(left.key, right.key) }
                    .forEachIndexed { index, entry ->
                        if (index > 0) append(',')
                        appendQuoted(entry.key)
                        append(':')
                        appendCanonical(entry.value)
                    }
                append('}')
            }
            is CanonicalJsonArray -> {
                append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendCanonical(item)
                }
                append(']')
            }
            is CanonicalJsonString -> appendQuoted(value.value)
            is CanonicalJsonInteger -> {
                validateInteger(value.value)
                append(value.value.toString())
            }
            is CanonicalJsonBoolean -> append(if (value.value) "true" else "false")
            CanonicalJsonNull -> append("null")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        validateString(value)
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000c' -> append("\\f")
                '\r' -> append("\\r")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun validateString(value: String) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\u0000' || Character.isLowSurrogate(character)) {
                throw StrictJsonViolation()
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    throw StrictJsonViolation()
                }
                index += 2
            } else {
                index += 1
            }
        }
    }

    private fun validateInteger(value: BigInteger) {
        if (value.abs() > maxSafeInteger) throw StrictJsonViolation()
    }

    private fun compareUnicode(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftPoint = left.codePointAt(leftIndex)
            val rightPoint = right.codePointAt(rightIndex)
            if (leftPoint != rightPoint) return leftPoint.compareTo(rightPoint)
            leftIndex += Character.charCount(leftPoint)
            rightIndex += Character.charCount(rightPoint)
        }
        return (left.length - leftIndex).compareTo(right.length - rightIndex)
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): CanonicalJsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (index != text.length) throw StrictJsonViolation()
            return value
        }

        private fun parseValue(): CanonicalJsonValue {
            if (index >= text.length) throw StrictJsonViolation()
            return when (text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> CanonicalJsonString(parseString())
                't' -> parseLiteral("true", CanonicalJsonBoolean(true))
                'f' -> parseLiteral("false", CanonicalJsonBoolean(false))
                'n' -> parseLiteral("null", CanonicalJsonNull)
                '-', in '0'..'9' -> parseInteger()
                else -> throw StrictJsonViolation()
            }
        }

        private fun parseObject(): CanonicalJsonObject {
            index += 1
            skipWhitespace()
            val values = linkedMapOf<String, CanonicalJsonValue>()
            if (consume('}')) return CanonicalJsonObject(values)
            while (true) {
                if (!consume('"')) throw StrictJsonViolation()
                index -= 1
                val key = parseString()
                if (values.containsKey(key)) throw StrictJsonViolation()
                skipWhitespace()
                if (!consume(':')) throw StrictJsonViolation()
                skipWhitespace()
                values[key] = parseValue()
                skipWhitespace()
                if (consume('}')) return CanonicalJsonObject(values)
                if (!consume(',')) throw StrictJsonViolation()
                skipWhitespace()
            }
        }

        private fun parseArray(): CanonicalJsonArray {
            index += 1
            skipWhitespace()
            val values = mutableListOf<CanonicalJsonValue>()
            if (consume(']')) return CanonicalJsonArray(values)
            while (true) {
                values += parseValue()
                skipWhitespace()
                if (consume(']')) return CanonicalJsonArray(values)
                if (!consume(',')) throw StrictJsonViolation()
                skipWhitespace()
            }
        }

        private fun parseString(): String {
            if (!consume('"')) throw StrictJsonViolation()
            val result = StringBuilder()
            while (index < text.length) {
                val character = text[index++]
                when {
                    character == '"' -> return result.toString().also(::validateString)
                    character == '\\' -> result.append(parseEscape())
                    character.code < 0x20 -> throw StrictJsonViolation()
                    Character.isHighSurrogate(character) -> {
                        if (index >= text.length || !Character.isLowSurrogate(text[index])) {
                            throw StrictJsonViolation()
                        }
                        result.append(character)
                        result.append(text[index++])
                    }
                    Character.isLowSurrogate(character) -> throw StrictJsonViolation()
                    else -> result.append(character)
                }
            }
            throw StrictJsonViolation()
        }

        private fun parseEscape(): String {
            if (index >= text.length) throw StrictJsonViolation()
            return when (val escape = text[index++]) {
                '"', '\\', '/' -> escape.toString()
                'b' -> "\b"
                'f' -> "\u000c"
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                'u' -> parseUnicodeEscape()
                else -> throw StrictJsonViolation()
            }
        }

        private fun parseUnicodeEscape(): String {
            val first = parseHexCodeUnit()
            if (first == 0 || Character.isLowSurrogate(first.toChar())) {
                throw StrictJsonViolation()
            }
            if (!Character.isHighSurrogate(first.toChar())) return first.toChar().toString()
            if (index + 2 > text.length || text[index] != '\\' || text[index + 1] != 'u') {
                throw StrictJsonViolation()
            }
            index += 2
            val second = parseHexCodeUnit()
            if (!Character.isLowSurrogate(second.toChar())) throw StrictJsonViolation()
            return "${first.toChar()}${second.toChar()}"
        }

        private fun parseHexCodeUnit(): Int {
            if (index + 4 > text.length) throw StrictJsonViolation()
            var value = 0
            repeat(4) {
                val digit = text[index++].digitToIntOrNull(16) ?: throw StrictJsonViolation()
                value = (value shl 4) or digit
            }
            return value
        }

        private fun parseInteger(): CanonicalJsonInteger {
            val start = index
            consume('-')
            if (index >= text.length) throw StrictJsonViolation()
            if (text[index] == '0') {
                index += 1
                if (index < text.length && text[index].isDigit()) throw StrictJsonViolation()
            } else {
                if (text[index] !in '1'..'9') throw StrictJsonViolation()
                while (index < text.length && text[index].isDigit()) index += 1
            }
            if (index < text.length && text[index] in charArrayOf('.', 'e', 'E')) {
                throw StrictJsonViolation()
            }
            val value = try {
                BigInteger(text.substring(start, index))
            } catch (_: NumberFormatException) {
                throw StrictJsonViolation()
            }
            validateInteger(value)
            return CanonicalJsonInteger(value)
        }

        private fun <T : CanonicalJsonValue> parseLiteral(
            literal: String,
            value: T,
        ): T {
            if (!text.startsWith(literal, index)) throw StrictJsonViolation()
            index += literal.length
            return value
        }

        private fun consume(character: Char): Boolean {
            if (index >= text.length || text[index] != character) return false
            index += 1
            return true
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in charArrayOf(' ', '\t', '\r', '\n')) {
                index += 1
            }
        }
    }
}

internal object Crypto {
    fun sha256(value: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(value)
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
