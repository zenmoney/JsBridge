package app.zenmoney.jsbridge

internal fun String.toJson(): String =
    buildString {
        append('"')
        this@toJson.forEach {
            when (it) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\u2028', '\u2029' -> appendUnicodeEscape(it)
                else -> appendJsonCharacter(it)
            }
        }
        append('"')
    }

internal fun String.skipJsonWhitespace(startIndex: Int): Int {
    var index = startIndex
    while (index < length && this[index] in " \n\r\t") index++
    return index
}

internal fun String.expectJsonChar(
    startIndex: Int,
    char: Char,
): Int {
    val index = skipJsonWhitespace(startIndex)
    check(index < length && this[index] == char) { "Expected '$char' at $index" }
    return index + 1
}

internal fun String.peekJsonChar(
    startIndex: Int,
    char: Char,
): Boolean {
    val index = skipJsonWhitespace(startIndex)
    return index < length && this[index] == char
}

internal fun String.expectJsonEnd(startIndex: Int) {
    val index = skipJsonWhitespace(startIndex)
    check(index == length) { "Unexpected trailing data at $index" }
}

internal fun String.matchesJsonLiteral(
    startIndex: Int,
    literal: String,
): Boolean = startIndex + literal.length <= length && regionMatches(startIndex, literal, 0, literal.length)

internal fun String.skipJsonLiteral(
    startIndex: Int,
    literal: String,
): Int {
    check(matchesJsonLiteral(startIndex, literal)) { "Expected $literal at $startIndex" }
    return startIndex + literal.length
}

internal fun String.skipJsonNumber(startIndex: Int): Int {
    var index = startIndex
    if (index < length && this[index] == '-') index++
    check(index < length) { "Expected number at $index" }
    if (this[index] == '0') {
        index++
    } else {
        check(this[index] in '1'..'9') { "Expected number at $index" }
        do {
            index++
        } while (index < length && this[index] in '0'..'9')
    }
    if (index < length && this[index] == '.') {
        index++
        val fractionStart = index
        while (index < length && this[index] in '0'..'9') index++
        check(index > fractionStart) { "Expected fraction at $fractionStart" }
    }
    if (index < length && this[index] in "eE") {
        index++
        if (index < length && this[index] in "+-") index++
        val exponentStart = index
        while (index < length && this[index] in '0'..'9') index++
        check(index > exponentStart) { "Expected exponent at $exponentStart" }
    }
    return index
}

/**
 * Decodes a JSON string starting at [startIndex]. Calls [onEnd] with the index immediately after the closing quote
 * before returning the decoded value.
 */
internal inline fun String.decodeJsonString(
    startIndex: Int,
    onEnd: (endIndex: Int) -> Unit,
): String {
    var index = startIndex
    check(index < length && this[index] == '"') { "Expected '\"' at $index" }
    val contentStart = ++index
    while (true) {
        check(index < length) { "Unterminated string at $contentStart" }
        when (val char = this[index++]) {
            '"' -> {
                onEnd(index)
                return substring(contentStart, index - 1)
            }

            '\\' -> {
                break
            }

            else -> {
                check(char >= ' ') { "Unescaped control character at ${index - 1}" }
            }
        }
    }

    index = contentStart
    val result = CharArray(length - contentStart)
    var resultIndex = 0
    while (true) {
        check(index < length) { "Unterminated string at $contentStart" }
        when (val char = this[index++]) {
            '"' -> {
                onEnd(index)
                return result.concatToString(0, resultIndex)
            }

            '\\' -> {
                check(index < length) { "Unterminated escape sequence at $index" }
                when (val escaped = this[index++]) {
                    '"' -> {
                        result[resultIndex++] = '"'
                    }

                    '\\' -> {
                        result[resultIndex++] = '\\'
                    }

                    '/' -> {
                        result[resultIndex++] = '/'
                    }

                    'b' -> {
                        result[resultIndex++] = '\b'
                    }

                    'f' -> {
                        result[resultIndex++] = '\u000c'
                    }

                    'n' -> {
                        result[resultIndex++] = '\n'
                    }

                    'r' -> {
                        result[resultIndex++] = '\r'
                    }

                    't' -> {
                        result[resultIndex++] = '\t'
                    }

                    'u' -> {
                        check(index + 4 <= length) { "Invalid unicode escape at $index" }
                        var decoded = 0
                        repeat(4) {
                            decoded = decoded * 16 + jsonHexDigit(this[index++])
                        }
                        result[resultIndex++] = decoded.toChar()
                    }

                    else -> {
                        throw IllegalArgumentException("Invalid escape sequence at ${index - 1}")
                    }
                }
            }

            else -> {
                check(char >= ' ') { "Unescaped control character at ${index - 1}" }
                result[resultIndex++] = char
            }
        }
    }
}

internal fun String.skipJsonString(startIndex: Int): Int {
    var index = startIndex
    check(index < length && this[index] == '"') { "Expected '\"' at $index" }
    val contentStart = ++index
    while (true) {
        check(index < length) { "Unterminated string at $contentStart" }
        when (val char = this[index++]) {
            '"' -> {
                return index
            }

            '\\' -> {
                check(index < length) { "Unterminated escape sequence at $index" }
                when (this[index++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {}

                    'u' -> {
                        check(index + 4 <= length) { "Invalid unicode escape at $index" }
                        repeat(4) {
                            jsonHexDigit(this[index++])
                        }
                    }

                    else -> {
                        throw IllegalArgumentException("Invalid escape sequence at ${index - 1}")
                    }
                }
            }

            else -> {
                check(char >= ' ') { "Unescaped control character at ${index - 1}" }
            }
        }
    }
}

private fun StringBuilder.appendJsonCharacter(char: Char) {
    if (char < ' ') {
        appendUnicodeEscape(char)
    } else {
        append(char)
    }
}

private fun StringBuilder.appendUnicodeEscape(char: Char) {
    append("\\u")
    repeat(4) { shift ->
        append(HEX_DIGITS[(char.code shr (12 - shift * 4)) and 0xf])
    }
}

@PublishedApi
internal fun jsonHexDigit(char: Char): Int =
    when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> throw IllegalArgumentException("Invalid hex digit: $char")
    }

private const val HEX_DIGITS = "0123456789abcdef"
