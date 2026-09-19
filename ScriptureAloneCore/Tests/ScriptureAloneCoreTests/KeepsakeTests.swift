import Foundation
import Testing
@testable import ScriptureAloneCore

@Suite struct KeepsakeTests {
    /// The format stores milliseconds; these fractions are exact in binary, so they round-trip exactly.
    static func date(_ seconds: Double) -> Date { Date(timeIntervalSince1970: seconds) }

    static func sample() -> Keepsake {
        var manifest = KeepsakeManifest(ownerName: "Dad", dedication: "For Anna and Sam — read it slowly.",
                                        preferredTranslation: "ASV", generator: "Scripture Alone 1.0.0",
                                        createdAt: date(1_790_000_000.125))
        manifest.exportID = UUID()
        let john316 = VerseRef(.john, 3, 16)
        let romans8 = VerseRange(VerseRef(.romans, 8, 1), VerseRef(.romans, 8, 17))
        var keepsake = Keepsake(
            manifest: manifest,
            highlights: [
                KeepsakeHighlight(verse: john316.key, color: "yellow", createdAt: date(1_700_000_000.5)),
                KeepsakeHighlight(verse: VerseRef(.psalms, 23, 1).key, color: "blue", createdAt: date(1_710_000_000)),
            ],
            notes: [
                KeepsakeNote(title: "No condemnation", body: "Pastor Jim, Sunday.\nLine two — “quoted”.", anchors: [romans8],
                             createdAt: date(1_720_000_000.25), updatedAt: date(1_730_000_000)),
                KeepsakeNote(title: "", body: "", anchors: [VerseRange(john316)],
                             createdAt: date(1_740_000_000), updatedAt: date(1_740_000_000), origin: "camera"),
            ])
        keepsake.refreshSummary()
        return keepsake
    }

    @Test func roundTripPlain() throws {
        let keepsake = Self.sample()
        let data = try KeepsakeArchive.encode(keepsake)
        let decoded = try KeepsakeArchive.decode(data)
        #expect(decoded == keepsake)
        #expect(decoded.manifest.counts == .init(highlights: 2, notes: 2))
        #expect(decoded.manifest.dateRange?.start == Self.date(1_700_000_000.5))
        #expect(decoded.notes[0].anchors.first?.display == "Romans 8:1–17")
        #expect(decoded.manifest.displayTitle == "Dad’s Bible")
    }

    @Test func archiveIsAnOrdinaryZipWithReadableJSON() throws {
        let data = try KeepsakeArchive.encode(Self.sample())
        let files = try ZipArchive.read(data)
        #expect(Set(files.keys) == ["manifest.json", "highlights.json", "notes.json", "README.txt"])
        let manifest = try #require(String(data: files["manifest.json"]!, encoding: .utf8))
        #expect(manifest.contains("\"formatVersion\" : 1"))
        #expect(manifest.contains("\"format\" : \"com.blainemiller.scripturealone.legacy\""))
        let highlights = try #require(String(data: files["highlights.json"]!, encoding: .utf8))
        #expect(highlights.contains("\"reference\" : \"John 3:16\""))

        // The system unzip agrees, so the file is readable without this app.
        #if os(macOS)
        let dir = FileManager.default.temporaryDirectory.appending(path: "keepsake-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appending(path: "k.zip")
        try data.write(to: file)
        let unzip = Process()
        unzip.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
        unzip.arguments = ["-tq", file.path]
        unzip.standardOutput = FileHandle.nullDevice
        try unzip.run()
        unzip.waitUntilExit()
        #expect(unzip.terminationStatus == 0)
        #endif
    }

    @Test func versionFieldsAreEnforced() throws {
        let keepsake = Self.sample()
        let files = try ZipArchive.read(try KeepsakeArchive.encode(keepsake))
        var json = try #require(JSONSerialization.jsonObject(with: files["manifest.json"]!) as? [String: Any])
        #expect(json["formatVersion"] as? Int == KeepsakeManifest.currentVersion)
        #expect(json["minimumReaderVersion"] as? Int == 1)

        json["minimumReaderVersion"] = KeepsakeManifest.supportedReaderVersion + 1
        json["formatVersion"] = 7
        let rewritten = try rezip(files, replacing: "manifest.json", with: JSONSerialization.data(withJSONObject: json))
        #expect(throws: KeepsakeError.newerVersion(7)) { try KeepsakeArchive.decode(rewritten) }
    }

    @Test func unknownFieldsAreIgnored() throws {
        let keepsake = Self.sample()
        let files = try ZipArchive.read(try KeepsakeArchive.encode(keepsake))

        var manifest = try #require(JSONSerialization.jsonObject(with: files["manifest.json"]!) as? [String: Any])
        manifest["formatVersion"] = 3            // a newer writer…
        manifest["minimumReaderVersion"] = 1     // …that says old readers are fine
        manifest["photos"] = ["cover.jpg"]
        manifest["counts"] = ["highlights": 2, "notes": 2, "bookmarks": 9]

        var highlights = try #require(JSONSerialization.jsonObject(with: files["highlights.json"]!) as? [String: Any])
        var rows = try #require(highlights["highlights"] as? [[String: Any]])
        rows[0]["inkStyle"] = "fountain pen"
        highlights["highlights"] = rows
        highlights["schemaNote"] = "future"

        var notes = try #require(JSONSerialization.jsonObject(with: files["notes.json"]!) as? [String: Any])
        var noteRows = try #require(notes["notes"] as? [[String: Any]])
        noteRows[0]["audio"] = ["file": "sermon.m4a"]
        notes["notes"] = noteRows

        var rewritten = files
        rewritten["manifest.json"] = try JSONSerialization.data(withJSONObject: manifest)
        rewritten["highlights.json"] = try JSONSerialization.data(withJSONObject: highlights)
        rewritten["notes.json"] = try JSONSerialization.data(withJSONObject: notes)
        rewritten["future/extra.bin"] = Data([1, 2, 3])
        let data = ZipArchive.write(rewritten.map { ZipArchive.Entry(name: $0.key, data: $0.value) })

        let decoded = try KeepsakeArchive.decode(data)
        #expect(decoded.manifest.formatVersion == 3)
        #expect(decoded.highlights == keepsake.highlights)
        #expect(decoded.notes == keepsake.notes)
        #expect(decoded.manifest.ownerName == "Dad")
    }

    @Test func missingOptionalFieldsDecode() throws {
        let manifest = #"{"format":"com.blainemiller.scripturealone.legacy"}"#
        let notes = #"{"notes":[{"body":"Just a thought","passages":[{"start":43003016}]}]}"#
        let data = ZipArchive.write([
            .init(name: "manifest.json", data: Data(manifest.utf8)),
            .init(name: "notes.json", data: Data(notes.utf8)),
        ])
        let decoded = try KeepsakeArchive.decode(data)
        #expect(decoded.highlights.isEmpty)
        #expect(decoded.notes.first?.anchors == [VerseRange(VerseRef(.john, 3, 16))])
        #expect(decoded.manifest.displayTitle == "A Legacy Bible")
    }

    @Test func encryptedRoundTrip() throws {
        let keepsake = Self.sample()
        let data = try KeepsakeArchive.encode(keepsake, passphrase: "Mañana, grace", hint: "Our first dog", iterations: 2_000)

        let outer = try KeepsakeArchive.peek(data)
        #expect(outer.isEncrypted)
        #expect(outer.passphraseHint == "Our first dog")
        #expect(outer.ownerName == nil)
        #expect(outer.encryption?.kdf == "PBKDF2-HMAC-SHA256")
        #expect(outer.encryption?.iterations == 2_000)

        let files = try ZipArchive.read(data)
        #expect(files["notes.json"] == nil)
        let sealed = try #require(files["payload.sealed"])
        #expect(sealed.range(of: Data("condemnation".utf8)) == nil)

        // Decomposed "ñ" still opens a keepsake sealed with the precomposed one.
        let decoded = try KeepsakeArchive.decode(data, passphrase: "Man\u{0303}ana, grace")
        #expect(decoded == keepsake)
        #expect(decoded.manifest.encryption == nil)
    }

    @Test func defaultIterationsRoundTrip() throws {
        let keepsake = Self.sample()
        let data = try KeepsakeArchive.encode(keepsake, passphrase: "psalm 23")
        #expect(try KeepsakeArchive.peek(data).encryption?.iterations == KeepsakeCrypto.defaultIterations)
        #expect(try KeepsakeArchive.decode(data, passphrase: "psalm 23") == keepsake)
    }

    @Test func wrongPassphraseFailsCleanly() throws {
        let data = try KeepsakeArchive.encode(Self.sample(), passphrase: "correct horse", iterations: 1_000)
        #expect(throws: KeepsakeError.wrongPassphrase) { try KeepsakeArchive.decode(data, passphrase: "Correct horse") }
        #expect(throws: KeepsakeError.passphraseRequired) { try KeepsakeArchive.decode(data) }
        #expect(throws: KeepsakeError.passphraseRequired) { try KeepsakeArchive.decode(data, passphrase: "") }
    }

    @Test func tamperedEncryptionSettingsFail() throws {
        let data = try KeepsakeArchive.encode(Self.sample(), passphrase: "correct horse", iterations: 1_000)
        let files = try ZipArchive.read(data)
        var json = try #require(JSONSerialization.jsonObject(with: files["manifest.json"]!) as? [String: Any])
        json["passphraseHint"] = "It's 'password'"
        let rewritten = try rezip(files, replacing: "manifest.json", with: JSONSerialization.data(withJSONObject: json))
        // The manifest is authenticated, so even the right passphrase refuses an altered one.
        #expect(throws: KeepsakeError.wrongPassphrase) { try KeepsakeArchive.decode(rewritten, passphrase: "correct horse") }
    }

    @Test func rejectsOtherFiles() throws {
        #expect(throws: KeepsakeError.notAKeepsake) { try KeepsakeArchive.decode(Data("hello".utf8)) }
        let other = ZipArchive.write([.init(name: "manifest.json", data: Data(#"{"format":"com.example.other"}"#.utf8))])
        #expect(throws: KeepsakeError.notAKeepsake) { try KeepsakeArchive.decode(other) }
        var truncated = try KeepsakeArchive.encode(Self.sample())
        truncated.removeSubrange(40..<200)
        #expect(throws: (any Error).self) { try KeepsakeArchive.decode(truncated) }
    }

    @Test func zipReadsDeflatedEntries() throws {
        // `zip -X` output of a single file "a.txt" containing "hello hello hello hello\n", deflated.
        let text = Data("hello hello hello hello hello hello hello hello\n".utf8)
        let deflated = try (text as NSData).compressed(using: .zlib) as Data
        var archive = Data()
        func le16(_ v: Int) { archive.append(contentsOf: [UInt8(v & 0xFF), UInt8(v >> 8 & 0xFF)]) }
        func le32(_ v: UInt32) { archive.append(contentsOf: (0..<4).map { UInt8(v >> (8 * $0) & 0xFF) }) }
        let crc = CRC32.checksum(text)
        le32(0x0403_4B50); le16(20); le16(0); le16(8); le16(0); le16(0)
        le32(crc); le32(UInt32(deflated.count)); le32(UInt32(text.count)); le16(5); le16(0)
        archive.append(Data("a.txt".utf8)); archive.append(deflated)
        let central = archive.count
        le32(0x0201_4B50); le16(20); le16(20); le16(0); le16(8); le16(0); le16(0)
        le32(crc); le32(UInt32(deflated.count)); le32(UInt32(text.count)); le16(5); le16(0); le16(0); le16(0); le16(0); le32(0); le32(0)
        archive.append(Data("a.txt".utf8))
        let size = archive.count - central
        le32(0x0605_4B50); le16(0); le16(0); le16(1); le16(1); le32(UInt32(size)); le32(UInt32(central)); le16(0)
        #expect(try ZipArchive.read(archive)["a.txt"] == text)
    }

    @Test func markdownAndTextExport() throws {
        let notes = Self.sample().notes
        let verseText: NotesTextExport.VerseText = { $0.display == "John 3:16" ? "For God so loved the world" : nil }
        let options = NotesTextExport.Options(title: "Dad’s Notes", translation: "ASV")
        let markdown = NotesTextExport.markdown(notes, options: options, verseText: verseText)
        #expect(markdown.hasPrefix("# Dad’s Notes"))
        #expect(markdown.contains("## No condemnation"))
        #expect(markdown.contains("**Romans 8:1–17**"))
        #expect(markdown.contains("> For God so loved the world\n> — John 3:16 (ASV)"))

        let files = NotesTextExport.markdownFiles(notes + [notes[0]], options: options, verseText: verseText)
        #expect(files.count == 3)
        #expect(Set(files.map(\.name)).count == 3)
        #expect(files.allSatisfy { !$0.name.contains("/") && $0.name.hasSuffix(".md") })

        let plain = NotesTextExport.plainText(notes, options: .init(title: "Notes", translation: nil), verseText: verseText)
        #expect(plain.contains("No condemnation\nRomans 8:1–17"))
        #expect(!plain.contains("For God so loved"))
    }

    private func rezip(_ files: [String: Data], replacing name: String, with data: Data) throws -> Data {
        var files = files
        files[name] = data
        return ZipArchive.write(files.sorted { $0.key < $1.key }.map { ZipArchive.Entry(name: $0.key, data: $0.value) })
    }
}
