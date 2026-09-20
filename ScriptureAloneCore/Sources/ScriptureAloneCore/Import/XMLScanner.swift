// Importing is an iPhone, iPad and Mac feature: the watch has no file picker and no
// catalogue. It is also 32-bit (arm64_32), where the ZIP64 sentinel 0xFFFF_FFFF does not
// fit in an Int at all — so this code is not merely unused there, it cannot compile.
#if !os(watchOS)
import Foundation

/// One tag or run of text from an XHTML file.
struct XMLTag: Sendable {
    var name: String                     // lowercased, namespace prefix dropped ("html:p" -> "p")
    var attributes: [String: String]     // lowercased names; `epub:type` keeps its prefix
    var isSelfClosing: Bool

    func attribute(_ name: String) -> String? { attributes[name] }

    /// Whitespace-separated class tokens, lowercased.
    var classes: [String] {
        (attributes["class"] ?? "").split(whereSeparator: { $0 == " " || $0 == "\t" || $0 == "\n" }).map(String.init)
    }

    /// `epub:type` (OPS 3) or `type` (OPS 2 files sometimes carry it bare), lowercased.
    var epubType: String { attributes["epub:type"] ?? attributes["type"] ?? "" }
}

enum XMLEvent: Sendable {
    case start(XMLTag)
    case end(String)
    case text(String)
}

/// A deliberately lenient XML/XHTML scanner.
///
/// `XMLParser` is not usable here: publisher ePubs routinely reference HTML entities
/// (`&nbsp;`, `&mdash;`) without declaring them, and a strict parser aborts the whole document on
/// the first one. An importer that fails on a file it could 95% understand is worse than one that
/// reads what is there. So: no validation, no namespaces, unknown entities pass through as text.
enum XMLScanner {
    /// Elements that never have a closing tag in HTML-flavoured XHTML.
    static let voidElements: Set<String> = ["br", "img", "hr", "meta", "link", "input", "area", "base", "col", "source", "wbr"]

    static func scan(_ source: String) -> [XMLEvent] {
        var events: [XMLEvent] = []
        let characters = Array(source)
        var index = 0
        var text = ""

        func flushText() {
            if !text.isEmpty {
                events.append(.text(decodeEntities(text)))
                text = ""
            }
        }

        while index < characters.count {
            let character = characters[index]
            guard character == "<" else {
                text.append(character)
                index += 1
                continue
            }
            // Comments, CDATA, doctype, processing instructions.
            if matches(characters, index, "<!--") {
                index = skip(characters, from: index + 4, until: "-->") ?? characters.count
                continue
            }
            if matches(characters, index, "<![CDATA[") {
                let start = index + 9
                let end = find(characters, from: start, "]]>") ?? characters.count
                text.append(contentsOf: characters[start..<min(end, characters.count)])
                index = min(end + 3, characters.count)
                continue
            }
            if matches(characters, index, "<!") {
                index = skipDeclaration(characters, from: index)
                continue
            }
            if matches(characters, index, "<?") {
                index = skip(characters, from: index + 2, until: "?>") ?? characters.count
                continue
            }
            guard index + 1 < characters.count, characters[index + 1] == "/" || isNameStart(characters[index + 1]) else {
                // A bare "<" in prose.
                text.append(character)
                index += 1
                continue
            }
            flushText()

            if characters[index + 1] == "/" {
                var cursor = index + 2
                var name = ""
                while cursor < characters.count, characters[cursor] != ">" {
                    name.append(characters[cursor])
                    cursor += 1
                }
                events.append(.end(normalize(name)))
                index = min(cursor + 1, characters.count)
                continue
            }

            let (tag, next) = readTag(characters, from: index)
            index = next
            if let tag {
                events.append(.start(tag))
                if tag.isSelfClosing || voidElements.contains(tag.name) {
                    events.append(.end(tag.name))
                }
            }
        }
        flushText()
        return events
    }

    // MARK: - Tags

    private static func readTag(_ characters: [Character], from start: Int) -> (XMLTag?, Int) {
        var cursor = start + 1
        var name = ""
        while cursor < characters.count, !characters[cursor].isWhitespace,
              characters[cursor] != ">", characters[cursor] != "/" {
            name.append(characters[cursor])
            cursor += 1
        }
        var attributes: [String: String] = [:]
        var selfClosing = false
        while cursor < characters.count {
            while cursor < characters.count, characters[cursor].isWhitespace { cursor += 1 }
            guard cursor < characters.count else { break }
            if characters[cursor] == ">" { cursor += 1; break }
            if characters[cursor] == "/" {
                selfClosing = true
                cursor += 1
                continue
            }
            var key = ""
            while cursor < characters.count, !characters[cursor].isWhitespace,
                  characters[cursor] != "=", characters[cursor] != ">", characters[cursor] != "/" {
                key.append(characters[cursor])
                cursor += 1
            }
            var value = ""
            while cursor < characters.count, characters[cursor].isWhitespace { cursor += 1 }
            if cursor < characters.count, characters[cursor] == "=" {
                cursor += 1
                while cursor < characters.count, characters[cursor].isWhitespace { cursor += 1 }
                if cursor < characters.count, characters[cursor] == "\"" || characters[cursor] == "'" {
                    let quote = characters[cursor]
                    cursor += 1
                    while cursor < characters.count, characters[cursor] != quote {
                        value.append(characters[cursor])
                        cursor += 1
                    }
                    cursor = min(cursor + 1, characters.count)
                } else {
                    while cursor < characters.count, !characters[cursor].isWhitespace,
                          characters[cursor] != ">", characters[cursor] != "/" {
                        value.append(characters[cursor])
                        cursor += 1
                    }
                }
            }
            if !key.isEmpty {
                attributes[attributeName(key)] = decodeEntities(value)
            }
        }
        guard !name.isEmpty else { return (nil, max(cursor, start + 1)) }
        return (XMLTag(name: normalize(name), attributes: attributes, isSelfClosing: selfClosing), cursor)
    }

    /// Element names lose their namespace prefix; `epub:type` keeps it because it is the only
    /// prefixed attribute whose meaning we use.
    private static func normalize(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard let colon = trimmed.lastIndex(of: ":") else { return trimmed }
        return String(trimmed[trimmed.index(after: colon)...])
    }

    private static func attributeName(_ raw: String) -> String {
        let lowered = raw.lowercased()
        if lowered.hasPrefix("epub:") || lowered.hasPrefix("opf:") || lowered.hasPrefix("dc:") { return lowered }
        guard let colon = lowered.lastIndex(of: ":") else { return lowered }
        return String(lowered[lowered.index(after: colon)...])
    }

    // MARK: - Scanning helpers

    private static func matches(_ characters: [Character], _ index: Int, _ token: String) -> Bool {
        let tokenCharacters = Array(token)
        guard index + tokenCharacters.count <= characters.count else { return false }
        for offset in 0..<tokenCharacters.count where characters[index + offset] != tokenCharacters[offset] {
            return false
        }
        return true
    }

    private static func find(_ characters: [Character], from start: Int, _ token: String) -> Int? {
        var index = start
        while index < characters.count {
            if matches(characters, index, token) { return index }
            index += 1
        }
        return nil
    }

    private static func skip(_ characters: [Character], from start: Int, until token: String) -> Int? {
        find(characters, from: start, token).map { $0 + token.count }
    }

    /// `<!DOCTYPE …>`, including an internal subset in square brackets.
    private static func skipDeclaration(_ characters: [Character], from start: Int) -> Int {
        var index = start + 2
        var depth = 0
        while index < characters.count {
            let character = characters[index]
            if character == "[" { depth += 1 }
            if character == "]" { depth = max(0, depth - 1) }
            if character == ">", depth == 0 { return index + 1 }
            index += 1
        }
        return characters.count
    }

    private static func isNameStart(_ character: Character) -> Bool {
        character.isLetter || character == "_"
    }

    // MARK: - Entities

    private static let namedEntities: [String: String] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'",
        "nbsp": "\u{00A0}", "ensp": "\u{2002}", "emsp": "\u{2003}", "thinsp": "\u{2009}",
        "ndash": "–", "mdash": "—", "hellip": "…", "middot": "·", "bull": "•",
        "lsquo": "‘", "rsquo": "’", "ldquo": "“", "rdquo": "”", "sbquo": "‚", "bdquo": "„",
        "dagger": "†", "Dagger": "‡", "sect": "§", "para": "¶", "copy": "©", "reg": "®",
        "deg": "°", "prime": "′", "Prime": "″", "eacute": "é", "egrave": "è", "shy": "\u{00AD}",
    ]

    static func decodeEntities(_ raw: String) -> String {
        guard raw.contains("&") else { return raw }
        var output = ""
        output.reserveCapacity(raw.count)
        var pending = ""
        var inEntity = false
        for character in raw {
            if inEntity {
                if character == ";" {
                    output += expand(pending)
                    pending = ""
                    inEntity = false
                } else if character == "&" {
                    output += "&" + pending
                    pending = ""
                } else if pending.count > 12 {
                    output += "&" + pending + String(character)
                    pending = ""
                    inEntity = false
                } else {
                    pending.append(character)
                }
                continue
            }
            if character == "&" {
                inEntity = true
                pending = ""
            } else {
                output.append(character)
            }
        }
        if inEntity { output += "&" + pending }
        return output
    }

    private static func expand(_ body: String) -> String {
        if let named = namedEntities[body] { return named }
        if body.hasPrefix("#") {
            let digits = body.dropFirst()
            let value: UInt32?
            if digits.hasPrefix("x") || digits.hasPrefix("X") {
                value = UInt32(digits.dropFirst(), radix: 16)
            } else {
                value = UInt32(digits)
            }
            if let value, let scalar = Unicode.Scalar(value) { return String(Character(scalar)) }
        }
        // Unknown entity: keep it visible rather than silently eating text.
        return "&" + body + ";"
    }
}
#endif
