package com.blainemiller.scripturealone.data.importer

/**
 * One tag from an XHTML file, ported from `XMLTag` in `Import/XMLScanner.swift`.
 *
 * [name] is lowercased with its namespace prefix dropped ("html:p" → "p"). Attribute names are
 * lowercased; `epub:`, `opf:` and `dc:` keep their prefix. Attribute *values* are kept as written —
 * including `class` and `epub:type`, which the Swift doc comments call lowercased but the Swift code
 * does not lowercase, so neither does this.
 */
data class XMLTag(val name: String, val attributes: Map<String, String>, val isSelfClosing: Boolean) {
    fun attribute(name: String): String? = attributes[name]

    /** Class tokens, split on space, tab and newline characters (not CR: a "\r\n" is one Swift character). */
    val classes: List<String>
        get() = SwiftText.split(attributes["class"] ?: "") { chars, i ->
            chars.isChar(i, ' ') || chars.isChar(i, '\t') || chars.isChar(i, '\n')
        }

    /** `epub:type` (OPS 3) or `type` (OPS 2 files sometimes carry it bare). */
    val epubType: String get() = attributes["epub:type"] ?: attributes["type"] ?: ""
}

sealed class XMLEvent {
    data class Start(val tag: XMLTag) : XMLEvent()
    data class End(val name: String) : XMLEvent()
    data class Text(val text: String) : XMLEvent()
}

/**
 * A deliberately lenient XML/XHTML scanner, ported from `Import/XMLScanner.swift`.
 *
 * A strict parser is not usable here: publisher ePubs routinely reference HTML entities (`&nbsp;`,
 * `&mdash;`) without declaring them, and a strict parser aborts the whole document on the first one.
 * An importer that fails on a file it could 95% understand is worse than one that reads what is
 * there. So: no validation, no namespaces, unknown entities pass through as text.
 *
 * It walks Swift characters (grapheme clusters, see [SwiftCharacters]), not UTF-16 units, so a
 * combining mark or a CR LF pair is treated exactly as the iOS engine treats it.
 */
object XMLScanner {
    /** Elements that never have a closing tag in HTML-flavoured XHTML. */
    val voidElements: Set<String> = setOf("br", "img", "hr", "meta", "link", "input", "area", "base", "col", "source", "wbr")

    fun scan(source: String): List<XMLEvent> {
        val events = ArrayList<XMLEvent>()
        val characters = SwiftCharacters(source)
        val count = characters.count
        var index = 0
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                events.add(XMLEvent.Text(decodeEntities(text.toString())))
                text.setLength(0)
            }
        }

        while (index < count) {
            if (!characters.isChar(index, '<')) {
                characters.appendTo(text, index)
                index++
                continue
            }
            // Comments, CDATA, doctype, processing instructions.
            if (matches(characters, index, "<!--")) {
                index = skip(characters, index + 4, "-->") ?: count
                continue
            }
            if (matches(characters, index, "<![CDATA[")) {
                val start = index + 9
                val end = find(characters, start, "]]>") ?: count
                if (start < minOf(end, count)) text.append(characters.substring(start, minOf(end, count)))
                index = minOf(end + 3, count)
                continue
            }
            if (matches(characters, index, "<!")) {
                index = skipDeclaration(characters, index)
                continue
            }
            if (matches(characters, index, "<?")) {
                index = skip(characters, index + 2, "?>") ?: count
                continue
            }
            if (!(index + 1 < count && (characters.isChar(index + 1, '/') || isNameStart(characters, index + 1)))) {
                // A bare "<" in prose.
                characters.appendTo(text, index)
                index++
                continue
            }
            flushText()

            if (characters.isChar(index + 1, '/')) {
                var cursor = index + 2
                val name = StringBuilder()
                while (cursor < count && !characters.isChar(cursor, '>')) {
                    characters.appendTo(name, cursor)
                    cursor++
                }
                events.add(XMLEvent.End(normalize(name.toString())))
                index = minOf(cursor + 1, count)
                continue
            }

            val (tag, next) = readTag(characters, index)
            index = next
            if (tag != null) {
                events.add(XMLEvent.Start(tag))
                if (tag.isSelfClosing || tag.name in voidElements) events.add(XMLEvent.End(tag.name))
            }
        }
        flushText()
        return events
    }

    // MARK: - Tags

    private fun readTag(characters: SwiftCharacters, start: Int): Pair<XMLTag?, Int> {
        val count = characters.count
        var cursor = start + 1
        val name = StringBuilder()
        while (cursor < count && !characters.isWhitespace(cursor) &&
            !characters.isChar(cursor, '>') && !characters.isChar(cursor, '/')
        ) {
            characters.appendTo(name, cursor)
            cursor++
        }
        val attributes = LinkedHashMap<String, String>()
        var selfClosing = false
        while (cursor < count) {
            while (cursor < count && characters.isWhitespace(cursor)) cursor++
            if (cursor >= count) break
            if (characters.isChar(cursor, '>')) { cursor++; break }
            if (characters.isChar(cursor, '/')) {
                selfClosing = true
                cursor++
                continue
            }
            val key = StringBuilder()
            while (cursor < count && !characters.isWhitespace(cursor) && !characters.isChar(cursor, '=') &&
                !characters.isChar(cursor, '>') && !characters.isChar(cursor, '/')
            ) {
                characters.appendTo(key, cursor)
                cursor++
            }
            val value = StringBuilder()
            while (cursor < count && characters.isWhitespace(cursor)) cursor++
            if (cursor < count && characters.isChar(cursor, '=')) {
                cursor++
                while (cursor < count && characters.isWhitespace(cursor)) cursor++
                if (cursor < count && (characters.isChar(cursor, '"') || characters.isChar(cursor, '\''))) {
                    val quote = if (characters.isChar(cursor, '"')) '"' else '\''
                    cursor++
                    while (cursor < count && !characters.isChar(cursor, quote)) {
                        characters.appendTo(value, cursor)
                        cursor++
                    }
                    cursor = minOf(cursor + 1, count)
                } else {
                    while (cursor < count && !characters.isWhitespace(cursor) &&
                        !characters.isChar(cursor, '>') && !characters.isChar(cursor, '/')
                    ) {
                        characters.appendTo(value, cursor)
                        cursor++
                    }
                }
            }
            if (key.isNotEmpty()) attributes[attributeName(key.toString())] = decodeEntities(value.toString())
        }
        if (name.isEmpty()) return null to maxOf(cursor, start + 1)
        return XMLTag(normalize(name.toString()), attributes, selfClosing) to cursor
    }

    /**
     * Element names lose their namespace prefix; `epub:type` keeps it because it is the only prefixed
     * attribute whose meaning we use.
     */
    private fun normalize(raw: String): String {
        val trimmed = SwiftText.trimWhitespaceAndNewlines(raw).lowercase()
        return afterLastColon(trimmed)
    }

    private fun attributeName(raw: String): String {
        val lowered = raw.lowercase()
        if (lowered.startsWith("epub:") || lowered.startsWith("opf:") || lowered.startsWith("dc:")) return lowered
        return afterLastColon(lowered)
    }

    /** `s[s.index(after: s.lastIndex(of: ":"))...]`, by character. */
    private fun afterLastColon(s: String): String {
        if (!s.contains(':')) return s
        val characters = SwiftCharacters(s)
        for (i in characters.count - 1 downTo 0) {
            if (characters.isChar(i, ':')) return s.substring(characters.end(i))
        }
        return s
    }

    // MARK: - Scanning helpers

    private fun matches(characters: SwiftCharacters, index: Int, token: String): Boolean {
        if (index + token.length > characters.count) return false
        for (offset in token.indices) if (!characters.isChar(index + offset, token[offset])) return false
        return true
    }

    private fun find(characters: SwiftCharacters, start: Int, token: String): Int? {
        var index = start
        while (index < characters.count) {
            if (matches(characters, index, token)) return index
            index++
        }
        return null
    }

    private fun skip(characters: SwiftCharacters, start: Int, until: String): Int? =
        find(characters, start, until)?.let { it + until.length }

    /** `<!DOCTYPE …>`, including an internal subset in square brackets. */
    private fun skipDeclaration(characters: SwiftCharacters, start: Int): Int {
        var index = start + 2
        var depth = 0
        while (index < characters.count) {
            if (characters.isChar(index, '[')) depth++
            if (characters.isChar(index, ']')) depth = maxOf(0, depth - 1)
            if (characters.isChar(index, '>') && depth == 0) return index + 1
            index++
        }
        return characters.count
    }

    private fun isNameStart(characters: SwiftCharacters, index: Int): Boolean =
        characters.isLetter(index) || characters.isChar(index, '_')

    // MARK: - Entities

    private val namedEntities: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "middot" to "·", "bull" to "•",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "bdquo" to "„",
        "dagger" to "†", "Dagger" to "‡", "sect" to "§", "para" to "¶", "copy" to "©", "reg" to "®",
        "deg" to "°", "prime" to "′", "Prime" to "″", "eacute" to "é", "egrave" to "è", "shy" to "­",
    )

    fun decodeEntities(raw: String): String {
        if (!raw.contains('&')) return raw
        val characters = SwiftCharacters(raw)
        val output = StringBuilder(raw.length)
        val pending = StringBuilder()
        var pendingCount = 0
        var inEntity = false
        for (i in 0 until characters.count) {
            if (inEntity) {
                if (characters.isChar(i, ';')) {
                    output.append(expand(pending.toString()))
                    pending.setLength(0); pendingCount = 0
                    inEntity = false
                } else if (characters.isChar(i, '&')) {
                    output.append('&').append(pending)
                    pending.setLength(0); pendingCount = 0
                } else if (pendingCount > 12) {
                    output.append('&').append(pending)
                    characters.appendTo(output, i)
                    pending.setLength(0); pendingCount = 0
                    inEntity = false
                } else {
                    characters.appendTo(pending, i)
                    pendingCount++
                }
                continue
            }
            if (characters.isChar(i, '&')) {
                inEntity = true
                pending.setLength(0); pendingCount = 0
            } else {
                characters.appendTo(output, i)
            }
        }
        if (inEntity) output.append('&').append(pending)
        return output.toString()
    }

    private fun expand(body: String): String {
        namedEntities[body]?.let { return it }
        if (body.startsWith('#')) {
            // `dropFirst()` drops a character; "#" followed by a combining mark is one character.
            val characters = SwiftCharacters(body)
            if (characters.isChar(0, '#')) {
                val digits = body.substring(characters.end(0))
                val value = if (digits.startsWith('x') || digits.startsWith('X')) {
                    val rest = SwiftCharacters(digits)
                    if (rest.isChar(0, 'x') || rest.isChar(0, 'X')) SwiftText.uint32(digits.substring(rest.end(0)), 16) else null
                } else {
                    SwiftText.uint32(digits, 10)
                }
                if (value != null && value <= 0x10FFFF && value !in 0xD800L..0xDFFFL) {
                    return String(Character.toChars(value.toInt()))
                }
            }
        }
        // Unknown entity: keep it visible rather than silently eating text.
        return "&$body;"
    }
}
