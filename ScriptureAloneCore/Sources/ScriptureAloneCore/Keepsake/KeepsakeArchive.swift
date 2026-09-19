import Foundation

/// Reads and writes `.scripturelegacy` files.
///
/// A keepsake is a ZIP archive:
///
///     manifest.json     who, when, which translation, format version
///     highlights.json   { "highlights": [ { "verse", "color", "createdAt" } ] }
///     notes.json        { "notes": [ { "id", "title", "body", "passages", "createdAt", "updatedAt" } ] }
///     README.txt        what this file is, in plain words
///
/// A protected keepsake holds the same three JSON files in an inner ZIP sealed with
/// AES-256-GCM (`payload.sealed`); its outer `manifest.json` carries only the format fields,
/// the encryption parameters and an optional hint. See `KeepsakeCrypto`.
public enum KeepsakeArchive {
    public static let fileExtension = "scripturelegacy"
    public static let typeIdentifier = KeepsakeManifest.formatIdentifier
    public static let payloadName = "payload.sealed"

    // MARK: Encoding

    public static func encode(_ keepsake: Keepsake, passphrase: String? = nil, hint: String? = nil,
                              iterations: Int = KeepsakeCrypto.defaultIterations) throws -> Data {
        var manifest = keepsake.manifest
        manifest.encryption = nil
        manifest.passphraseHint = nil
        manifest.formatVersion = KeepsakeManifest.currentVersion
        manifest.format = KeepsakeManifest.formatIdentifier
        let date = manifest.createdAt

        let inner = [
            ZipArchive.Entry(name: "manifest.json", data: try json(manifest), modified: date),
            ZipArchive.Entry(name: "highlights.json", data: try json(HighlightsFile(highlights: keepsake.highlights)), modified: date),
            ZipArchive.Entry(name: "notes.json", data: try json(NotesFile(notes: keepsake.notes)), modified: date),
        ]

        guard let passphrase, !passphrase.isEmpty else {
            return ZipArchive.write(inner + [ZipArchive.Entry(name: "README.txt", data: Data(readme(protected: false).utf8), modified: date)])
        }

        let salt = KeepsakeCrypto.randomSalt()
        var outer = KeepsakeManifest(bibleID: manifest.bibleID, generator: manifest.generator, createdAt: manifest.createdAt)
        outer.exportID = manifest.exportID
        outer.minimumReaderVersion = manifest.minimumReaderVersion
        outer.encryption = .init(algorithm: KeepsakeCrypto.algorithm, kdf: KeepsakeCrypto.kdf, iterations: iterations,
                                 salt: salt.base64EncodedString(), payload: payloadName)
        let trimmedHint = hint?.trimmingCharacters(in: .whitespacesAndNewlines)
        outer.passphraseHint = (trimmedHint?.isEmpty ?? true) ? nil : trimmedHint
        let outerData = try json(outer)

        let key = try KeepsakeCrypto.deriveKey(passphrase: passphrase, salt: salt, iterations: iterations)
        let sealed = try KeepsakeCrypto.seal(ZipArchive.write(inner), key: key, associatedData: outerData)
        return ZipArchive.write([
            ZipArchive.Entry(name: "manifest.json", data: outerData, modified: date),
            ZipArchive.Entry(name: payloadName, data: sealed, modified: date),
            ZipArchive.Entry(name: "README.txt", data: Data(readme(protected: true).utf8), modified: date),
        ])
    }

    // MARK: Decoding

    /// The outer manifest, without a passphrase: enough to show a hint or ask for one.
    public static func peek(_ data: Data) throws -> KeepsakeManifest {
        try manifest(in: try entries(data)).manifest
    }

    public static func decode(_ data: Data, passphrase: String? = nil) throws -> Keepsake {
        let files = try entries(data)
        let (outer, outerData) = try manifest(in: files)

        guard let encryption = outer.encryption else { return try contents(files, manifest: outer) }

        guard encryption.algorithm == KeepsakeCrypto.algorithm, encryption.kdf == KeepsakeCrypto.kdf else {
            throw KeepsakeError.newerVersion(outer.formatVersion)
        }
        guard let passphrase, !passphrase.isEmpty else { throw KeepsakeError.passphraseRequired }
        guard let salt = Data(base64Encoded: encryption.salt), let sealed = files[encryption.payload] else {
            throw KeepsakeError.damaged("missing encrypted payload")
        }
        let key = try KeepsakeCrypto.deriveKey(passphrase: passphrase, salt: salt, iterations: encryption.iterations)
        let innerData = try KeepsakeCrypto.open(sealed, key: key, associatedData: outerData)
        let innerFiles = try entries(innerData)
        let (inner, _) = try manifest(in: innerFiles)
        return try contents(innerFiles, manifest: inner)
    }

    // MARK: Pieces

    private struct HighlightsFile: Codable {
        var highlights: [KeepsakeHighlight]
    }

    private struct NotesFile: Codable {
        var notes: [KeepsakeNote]
    }

    private static func entries(_ data: Data) throws -> [String: Data] {
        do {
            return try ZipArchive.read(data)
        } catch ZipArchive.ZipError.notAZip {
            throw KeepsakeError.notAKeepsake
        } catch {
            throw KeepsakeError.damaged("\(error)")
        }
    }

    private static func manifest(in files: [String: Data]) throws -> (manifest: KeepsakeManifest, data: Data) {
        guard let data = files["manifest.json"] else { throw KeepsakeError.notAKeepsake }
        let manifest: KeepsakeManifest
        do {
            manifest = try decoder.decode(KeepsakeManifest.self, from: data)
        } catch {
            throw KeepsakeError.damaged("manifest")
        }
        guard manifest.format == KeepsakeManifest.formatIdentifier else { throw KeepsakeError.notAKeepsake }
        guard manifest.minimumReaderVersion <= KeepsakeManifest.supportedReaderVersion else {
            throw KeepsakeError.newerVersion(manifest.formatVersion)
        }
        return (manifest, data)
    }

    private static func contents(_ files: [String: Data], manifest: KeepsakeManifest) throws -> Keepsake {
        var highlights: [KeepsakeHighlight] = []
        var notes: [KeepsakeNote] = []
        do {
            if let data = files["highlights.json"] {
                highlights = try decoder.decode(HighlightsFile.self, from: data).highlights
            }
            if let data = files["notes.json"] {
                notes = try decoder.decode(NotesFile.self, from: data).notes
            }
        } catch {
            throw KeepsakeError.damaged("contents")
        }
        // Drop anything that doesn't point at a real verse.
        highlights = highlights.filter { VerseRef(key: $0.verse) != nil }
        var keepsake = Keepsake(manifest: manifest, highlights: highlights, notes: notes)
        keepsake.manifest.encryption = nil
        keepsake.manifest.passphraseHint = nil
        return keepsake
    }

    // MARK: JSON

    static func json(_ value: some Encodable) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var c = encoder.singleValueContainer()
            // Nearest millisecond (the formatter alone truncates).
            let rounded = Date(timeIntervalSinceReferenceDate: (date.timeIntervalSinceReferenceDate * 1000).rounded() / 1000)
            try c.encode(rounded.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true)))
        }
        return try encoder.encode(value)
    }

    static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let c = try decoder.singleValueContainer()
            let string = try c.decode(String.self)
            if let date = try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(string) { return date }
            if let date = try? Date(string, strategy: .iso8601) { return date }
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "Unrecognized date \(string)")
        }
        return decoder
    }

    static func readme(protected: Bool) -> String {
        var text = """
        LEGACY BIBLE KEEPSAKE
        =====================

        This file holds the highlights and notes someone made while reading the Bible in
        Scripture Alone, a free app for iPhone, iPad and Mac. Open it in Scripture Alone to
        read their Bible the way they marked it: their highlights in the text, their notes
        beside the verses. It is a read-only keepsake; nothing in it can be changed.

        The file is an ordinary ZIP archive. Rename it to end in .zip to look inside.

        """
        if protected {
            text += """

            This keepsake is protected with a passphrase. manifest.json describes how:
            PBKDF2-HMAC-SHA256 (with the salt and iteration count given) turns the passphrase
            into a 256-bit key, and payload.sealed is AES-256-GCM (12-byte nonce, then the
            ciphertext, then the 16-byte tag) with the exact bytes of manifest.json as
            associated data. Opened, it is a ZIP holding the files described below.

            """
        }
        text += """

        manifest.json    Whose Bible this is, a dedication, dates, the translation they read.
        highlights.json  Each highlighted verse. Verse numbers are book × 1,000,000 +
                         chapter × 1,000 + verse, with books numbered Genesis = 1 to
                         Revelation = 66 (so John 3:16 is 43003016).
        notes.json       Each note: title, text, the passages it is attached to, and dates.

        More about the format: https://wemiller.com/apps/scripture-alone/
        """
        return text
    }
}
