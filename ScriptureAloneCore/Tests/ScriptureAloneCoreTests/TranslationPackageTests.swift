import Foundation
import CryptoKit
import Testing
@testable import ScriptureAloneCore

/// The security properties of the `.sabible` format, stated as assertions.
///
/// `docs/encrypted-translations.md` — the paper shown to publishers — cites these by name. Each test
/// here is one sentence of that paper: a wrong key fails, a flipped header byte fails, an edited
/// policy fails, a transplanted chapter fails, an expired package refuses to open, a forbidden export
/// is refused by the app's own gate, and no API hands out a whole Bible in the clear.
///
/// Every verse quoted below is the American Standard Version (1901, public domain). The
/// "Demonstration Standard Version" identity is invented, and no copyrighted translation appears in
/// this repository.
@Suite struct TranslationPackageTests {

    // MARK: - Fixtures

    static let identity = PackagedTranslationIdentity(
        id: "DSV",
        name: "Demonstration Standard Version",
        abbreviation: "DSV",
        publisher: "Example Bible Publishers",
        copyright: "Copyright © 2026 Example Bible Publishers. All rights reserved.",
        license: "Licensed to Scripture Alone for demonstration")

    /// Three chapters, in the exact shape the SQLite stores keep: compact layout JSON, verse rows,
    /// and words-of-Christ ranges as scalar pairs.
    static func sourceChapters() -> [TranslationPackageWriter.SourceChapter] {
        [
            chapter(ChapterRef(.genesis, 1), verses: [
                (1, "In the beginning God created the heavens and the earth.", []),
                (2, "And the earth was waste and void; and darkness was upon the face of the deep.", []),
            ]),
            chapter(ChapterRef(.genesis, 2), verses: [
                (1, "And the heavens and the earth were finished, and all the host of them.", []),
            ]),
            chapter(ChapterRef(.john, 3), verses: [
                (16, "For God so loved the world, that he gave his only begotten Son, that whosoever believeth on him should not perish, but have eternal life.", [[0, 137]]),
                (17, "For God sent not the Son into the world to judge the world.", [[0, 58]]),
            ]),
        ]
    }

    static func chapter(_ ref: ChapterRef, verses: [(Int, String, [[Int]])]) -> TranslationPackageWriter.SourceChapter {
        let fragments = verses.map { verse, text, red -> String in
            let spans = red.isEmpty ? "" : ",\"s\":[[\(red[0][0]),\(red[0][1]),\"r\"]]"
            let escaped = text.replacingOccurrences(of: "\"", with: "\\\"")
            return "{\"v\":\(verse),\"t\":\"\(escaped)\",\"n\":1\(spans)}"
        }
        let layout = "{\"b\":[{\"k\":\"p\",\"f\":[\(fragments.joined(separator: ","))]}]}"
        return TranslationPackageWriter.SourceChapter(
            ref: ref,
            verses: verses.map(\.0).max() ?? 0,
            layoutJSON: layout,
            verseRows: verses.map { TranslationPackageWriter.SourceVerse(key: VerseRef(ref.book, ref.chapter, $0.0).key,
                                                                        text: $0.1,
                                                                        redScalarPairs: $0.2) })
    }

    struct Built {
        var url: URL
        var bytes: Data
        var contentKey: SymmetricKey
        var signingKey: Curve25519.Signing.PrivateKey
        var keyring: PublisherKeyring
    }

    /// Writes a package into a fresh temporary directory and returns it with its keys. The keys are
    /// generated here and thrown away with the process: no key of any kind lives in this repository.
    @discardableResult
    static func build(policy: PackagePolicy = .publisherStandard,
                      identity: PackagedTranslationIdentity = Self.identity,
                      chapters: [TranslationPackageWriter.SourceChapter] = sourceChapters(),
                      contentKey: SymmetricKey = SymmetricKey(size: .bits256),
                      signingKey: Curve25519.Signing.PrivateKey = Curve25519.Signing.PrivateKey(),
                      packageID: String = UUID().uuidString,
                      name: String = "demonstration") throws -> Built {
        let bytes = try TranslationPackageWriter.data(identity: identity, policy: policy, chapters: chapters,
                                                     contentKey: contentKey, signingKey: signingKey,
                                                     packageID: packageID)
        let directory = URL.temporaryDirectory.appending(path: "sabible-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appending(path: "\(name).sabible")
        try bytes.write(to: url)
        return Built(url: url, bytes: bytes, contentKey: contentKey, signingKey: signingKey,
                     keyring: try PublisherKeyring(rawPublicKeys: [signingKey.publicKey.rawRepresentation]))
    }

    /// The three regions of a package file, located the way any other implementation would locate
    /// them — which is also how the tamper tests reach in and change one.
    struct Regions {
        var header: Range<Int>
        var signature: Range<Int>
        var body: Range<Int>
    }

    static func regions(_ bytes: Data) -> Regions {
        let magic = TranslationPackageFormat.magic.count
        let headerLength = Int(TranslationPackage.uint32(bytes, at: magic + 2))
        let headerStart = magic + 6
        let signatureStart = headerStart + headerLength + 2
        let signatureLength = Int(TranslationPackage.uint16(bytes, at: headerStart + headerLength))
        let bodyStart = signatureStart + signatureLength
        return Regions(header: headerStart ..< (headerStart + headerLength),
                       signature: signatureStart ..< (signatureStart + signatureLength),
                       body: bodyStart ..< bytes.count)
    }

    static func assemble(header: Data, signature: Data, body: Data) -> Data {
        var file = Data()
        file.append(TranslationPackageFormat.magic)
        file.append(bigEndian: TranslationPackageFormat.version)
        file.append(bigEndian: UInt32(header.count))
        file.append(header)
        file.append(bigEndian: UInt16(signature.count))
        file.append(signature)
        file.append(body)
        return file
    }

    static func write(_ bytes: Data, beside url: URL, named name: String) throws -> URL {
        let target = url.deletingLastPathComponent().appending(path: name)
        try bytes.write(to: target)
        return target
    }

    // MARK: - Round trip

    @Test func aPackageOpensAndReadsAChapter() throws {
        let built = try Self.build()
        let package = try TranslationPackage.open(url: built.url, keyring: built.keyring, contentKey: built.contentKey)

        #expect(package.info.abbreviation == "DSV")
        #expect(package.publisher == "Example Bible Publishers")
        #expect(package.chapters == [ChapterRef(.genesis, 1), ChapterRef(.genesis, 2), ChapterRef(.john, 3)])
        #expect(package.verseCount(ChapterRef(.john, 3)) == 17)
        #expect(package.contains(ChapterRef(.john, 3)))
        #expect(!package.contains(ChapterRef(.john, 4)))

        let john3 = try package.chapter(ChapterRef(.john, 3))
        let fragment = try #require(john3.layout.blocks.first?.fragments.first)
        #expect(fragment.verse == 16)
        #expect(fragment.text.hasPrefix("For God so loved the world"))
        #expect(fragment.spans.first?.style == .wordsOfChrist)

        let verse = try #require(john3.verses.first)
        #expect(verse.ref == VerseRef(.john, 3, 16))
        #expect((verse.text as NSString).substring(with: try #require(verse.red.first)).hasPrefix("For God so loved"))

        // The selection path: one verse out of the range the reader asked for.
        let selection = try package.verses(in: VerseRange(VerseRef(.john, 3, 17)))
        #expect(selection.map(\.ref) == [VerseRef(.john, 3, 17)])
    }

    @Test func theHeaderIsReadableWithoutAnyKey() throws {
        let built = try Self.build()
        // A publisher's engineer, or anyone else, can read what a package claims and who signed it
        // with nothing but a JSON parser. That is the point of a plaintext header.
        let header = try JSONDecoder().decode(TranslationPackageHeader.self,
                                             from: built.bytes.subdata(in: Self.regions(built.bytes).header))
        #expect(header.translation.publisher == "Example Bible Publishers")
        #expect(header.policy.allowExternalHandoff == false)
        #expect(header.crypto.cipher == "AES-256-GCM")
        #expect(header.crypto.signature == "Ed25519")
        #expect(header.chapters.count == 3)
        // …and nothing of the text. The first chapter's bytes are sealed.
        let body = built.bytes.subdata(in: Self.regions(built.bytes).body)
        #expect(!String(decoding: body, as: UTF8.self).contains("In the beginning"))
    }

    // MARK: - A wrong key fails

    @Test func aWrongContentKeyIsRefused() throws {
        let built = try Self.build()
        #expect(throws: TranslationPackageError.wrongContentKey) {
            try TranslationPackage.open(url: built.url, keyring: built.keyring, contentKey: SymmetricKey(size: .bits256))
        }
    }

    /// The key identifier in the header is a courtesy, so the app can say "wrong key" instead of
    /// "damaged". AES-GCM is the enforcement. With the courtesy check disabled, a wrong key still
    /// gets nothing.
    @Test func aWrongContentKeyIsRefusedByTheCipherNotOnlyByTheKeyIdentifier() throws {
        let built = try Self.build()
        let package = try TranslationPackage(url: built.url, keyring: built.keyring,
                                             contentKey: SymmetricKey(size: .bits256), now: Date(), checkKeyID: false)
        #expect(throws: TranslationPackageError.chapterTampered(ChapterRef(.john, 3))) {
            try package.chapter(ChapterRef(.john, 3))
        }
    }

    // MARK: - A flipped byte fails

    @Test func aSingleFlippedByteInTheHeaderFailsTheSignature() throws {
        let built = try Self.build()
        let regions = Self.regions(built.bytes)
        for offset in [regions.header.lowerBound, regions.header.lowerBound + (regions.header.count / 2), regions.header.upperBound - 1] {
            var tampered = built.bytes
            tampered[offset] ^= 0x01
            let url = try Self.write(tampered, beside: built.url, named: "flipped-\(offset).sabible")
            // Either the JSON no longer parses or the signature no longer matches. Both refuse; the
            // signature is the one that catches an edit that stays valid JSON.
            #expect(throws: (any Error).self) {
                try TranslationPackage.open(url: url, keyring: built.keyring, contentKey: built.contentKey)
            }
        }
        // A byte inside a value, so the JSON is still valid and only the signature can tell.
        var renamed = built.bytes
        let text = String(decoding: built.bytes.subdata(in: regions.header), as: UTF8.self)
        let index = try #require(text.range(of: "Demonstration Standard Version"))
        let offset = regions.header.lowerBound + text.utf8.distance(from: text.utf8.startIndex, to: index.lowerBound.samePosition(in: text.utf8)!)
        renamed[offset] = UInt8(ascii: "d")
        let url = try Self.write(renamed, beside: built.url, named: "renamed.sabible")
        #expect(throws: TranslationPackageError.signatureInvalid) {
            try TranslationPackage.open(url: url, keyring: built.keyring, contentKey: built.contentKey)
        }
    }

    // MARK: - An edited policy fails

    /// The realistic attack: loosen the terms and keep everything else. Done two ways — an edit that
    /// does not change the header's length at all, and a full re-encode of the header — because a
    /// signature that only caught length changes would be worthless.
    @Test func anEditedPolicyFailsTheSignature() throws {
        let built = try Self.build(policy: PackagePolicy(maxQuotationVerses: 500))
        let regions = Self.regions(built.bytes)
        let text = String(decoding: built.bytes.subdata(in: regions.header), as: UTF8.self)

        // 1. Same length, different terms: 500 verses becomes 999.
        let cap = try #require(text.range(of: "\"maxQuotationVerses\":500"))
        var sameLength = built.bytes
        let capOffset = regions.header.lowerBound + text.utf8.distance(from: text.utf8.startIndex,
                                                                      to: cap.lowerBound.samePosition(in: text.utf8)!)
        for (index, character) in Array("\"maxQuotationVerses\":999").enumerated() {
            sameLength[capOffset + index] = character.asciiValue!
        }
        #expect(sameLength.count == built.bytes.count)
        let sameLengthURL = try Self.write(sameLength, beside: built.url, named: "loosened-cap.sabible")
        #expect(throws: TranslationPackageError.signatureInvalid) {
            try TranslationPackage.open(url: sameLengthURL, keyring: built.keyring, contentKey: built.contentKey)
        }

        // 2. A rewritten header: hand-off turned on, header re-encoded and re-laid-out, old signature
        //    kept. The body is untouched and the index still describes it correctly.
        var header = try JSONDecoder().decode(TranslationPackageHeader.self,
                                             from: built.bytes.subdata(in: regions.header))
        header.policy.allowExternalHandoff = true
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let rewritten = Self.assemble(header: try encoder.encode(header),
                                      signature: built.bytes.subdata(in: regions.signature),
                                      body: built.bytes.subdata(in: regions.body))
        let rewrittenURL = try Self.write(rewritten, beside: built.url, named: "rewritten-policy.sabible")
        #expect(throws: TranslationPackageError.signatureInvalid) {
            try TranslationPackage.open(url: rewrittenURL, keyring: built.keyring, contentKey: built.contentKey)
        }
    }

    /// And if the attacker signs the edited policy with a key of their own: the app pins publisher
    /// keys, so it is refused before the terms are even read.
    @Test func anEditedPolicyResignedWithAnotherKeyIsRefusedByPinning() throws {
        let built = try Self.build()
        let regions = Self.regions(built.bytes)
        var header = try JSONDecoder().decode(TranslationPackageHeader.self,
                                             from: built.bytes.subdata(in: regions.header))
        header.policy.allowExternalHandoff = true
        let attacker = Curve25519.Signing.PrivateKey()
        header.crypto.publisherKeyID = TranslationPackageKeys.publisherKeyID(attacker.publicKey.rawRepresentation)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let headerBytes = try encoder.encode(header)
        let forged = Self.assemble(header: headerBytes,
                                   signature: try attacker.signature(for: headerBytes),
                                   body: built.bytes.subdata(in: regions.body))
        let url = try Self.write(forged, beside: built.url, named: "forged.sabible")
        #expect(throws: TranslationPackageError.unknownPublisherKey(header.crypto.publisherKeyID)) {
            try TranslationPackage.open(url: url, keyring: built.keyring, contentKey: built.contentKey)
        }

        // Suppose the attacker's key were pinned anyway — a device they fully control. The chapters
        // are bound to a hash of the header, so the text still does not open under edited terms.
        let attackersKeyring = try PublisherKeyring(rawPublicKeys: [attacker.publicKey.rawRepresentation])
        let package = try TranslationPackage.open(url: url, keyring: attackersKeyring, contentKey: built.contentKey)
        #expect(package.info.mayHandOffToOtherApps)  // the forged terms parsed…
        #expect(throws: TranslationPackageError.chapterTampered(ChapterRef(.john, 3))) {
            try package.chapter(ChapterRef(.john, 3))  // …and bought nothing.
        }
    }

    // MARK: - A transplanted chapter fails

    /// Two packages of the same translation, sealed with the same content key, built minutes apart:
    /// the second build's John 3 dropped into the first. The blobs are the same length and the index
    /// still fits, so only the associated data can catch it — and it does, because each chapter is
    /// bound to its own package's identity.
    @Test func aChapterTransplantedFromAnotherPackageFailsItsSeal() throws {
        let key = SymmetricKey(size: .bits256)
        let signing = Curve25519.Signing.PrivateKey()
        let first = try Self.build(contentKey: key, signingKey: signing, name: "first")
        let second = try Self.build(contentKey: key, signingKey: signing, name: "second")

        let firstHeader = try JSONDecoder().decode(TranslationPackageHeader.self,
                                                   from: first.bytes.subdata(in: Self.regions(first.bytes).header))
        let secondHeader = try JSONDecoder().decode(TranslationPackageHeader.self,
                                                    from: second.bytes.subdata(in: Self.regions(second.bytes).header))
        #expect(firstHeader.packageID != secondHeader.packageID)
        let entry = try #require(firstHeader.chapters.first { $0.ref == ChapterRef(.john, 3) })
        #expect(secondHeader.chapters.contains { $0.ref == ChapterRef(.john, 3) && $0.length == entry.length })

        let firstBody = Self.regions(first.bytes).body
        let secondBody = Self.regions(second.bytes).body
        var transplanted = first.bytes
        let target = (firstBody.lowerBound + entry.offset) ..< (firstBody.lowerBound + entry.offset + entry.length)
        let source = (secondBody.lowerBound + entry.offset) ..< (secondBody.lowerBound + entry.offset + entry.length)
        transplanted.replaceSubrange(target, with: second.bytes.subdata(in: source))
        #expect(transplanted.count == first.bytes.count)

        let url = try Self.write(transplanted, beside: first.url, named: "transplanted.sabible")
        let package = try TranslationPackage.open(url: url, keyring: first.keyring, contentKey: key)
        #expect(throws: TranslationPackageError.chapterTampered(ChapterRef(.john, 3))) {
            try package.chapter(ChapterRef(.john, 3))
        }
        // The chapters that were not moved are untouched, and the donor package still reads its own.
        #expect(try package.chapter(ChapterRef(.genesis, 1)).verses.count == 2)
        let donor = try TranslationPackage.open(url: second.url, keyring: second.keyring, contentKey: key)
        #expect(try donor.chapter(ChapterRef(.john, 3)).verses.count == 2)
    }

    /// The chapter the publisher re-issued under tightened terms, replaced with the chapter from the
    /// build before it: same package identity, same translation, same chapter, same length. Only the
    /// header differs — and the header's hash is in every chapter's associated data, so the old
    /// chapter will not open under the new terms.
    @Test func aChapterReplayedAcrossAPolicyChangeFailsItsSeal() throws {
        let key = SymmetricKey(size: .bits256)
        let signing = Curve25519.Signing.PrivateKey()
        let packageID = UUID().uuidString
        let generous = try Self.build(policy: PackagePolicy(maxQuotationVerses: 500), contentKey: key,
                                      signingKey: signing, packageID: packageID, name: "generous")
        let tightened = try Self.build(policy: PackagePolicy(maxQuotationVerses: 25), contentKey: key,
                                       signingKey: signing, packageID: packageID, name: "tightened")

        let header = try JSONDecoder().decode(TranslationPackageHeader.self,
                                              from: tightened.bytes.subdata(in: Self.regions(tightened.bytes).header))
        let entry = try #require(header.chapters.first { $0.ref == ChapterRef(.john, 3) })
        let oldBody = Self.regions(generous.bytes).body
        let newBody = Self.regions(tightened.bytes).body
        var replayed = tightened.bytes
        replayed.replaceSubrange((newBody.lowerBound + entry.offset) ..< (newBody.lowerBound + entry.offset + entry.length),
                                 with: generous.bytes.subdata(in: (oldBody.lowerBound + entry.offset) ..< (oldBody.lowerBound + entry.offset + entry.length)))
        #expect(replayed.count == tightened.bytes.count)

        let url = try Self.write(replayed, beside: tightened.url, named: "replayed.sabible")
        let package = try TranslationPackage.open(url: url, keyring: tightened.keyring, contentKey: key)
        #expect(package.rights.maxQuotationVerses == 25)
        #expect(throws: TranslationPackageError.chapterTampered(ChapterRef(.john, 3))) {
            try package.chapter(ChapterRef(.john, 3))
        }
    }

    /// Each of the four things a chapter is bound to, tested one at a time: move it to another
    /// package, another translation, another chapter, or another header, and it does not open. This
    /// is the binding itself, stated without the file format around it.
    @Test func everyFieldBoundIntoAChapterIsNecessary() throws {
        let key = SymmetricKey(size: .bits256)
        let plaintext = Data("In the beginning God created the heavens and the earth.".utf8)
        let digest = Data(SHA256.hash(data: Data("a header".utf8)))
        let otherDigest = Data(SHA256.hash(data: Data("a different header".utf8)))
        let sealed = try AES.GCM.seal(plaintext, using: key, authenticating: TranslationPackage.associatedData(
            packageID: "package-1", translationID: "DSV", chapter: ChapterRef(.genesis, 1), headerDigest: digest))
        let box = try #require(sealed.combined)

        let wrong: [(String, Data)] = [
            ("another package", TranslationPackage.associatedData(packageID: "package-2", translationID: "DSV",
                                                                  chapter: ChapterRef(.genesis, 1), headerDigest: digest)),
            ("another translation", TranslationPackage.associatedData(packageID: "package-1", translationID: "OTHER",
                                                                      chapter: ChapterRef(.genesis, 1), headerDigest: digest)),
            ("another chapter", TranslationPackage.associatedData(packageID: "package-1", translationID: "DSV",
                                                                  chapter: ChapterRef(.genesis, 2), headerDigest: digest)),
            ("another header", TranslationPackage.associatedData(packageID: "package-1", translationID: "DSV",
                                                                chapter: ChapterRef(.genesis, 1), headerDigest: otherDigest)),
        ]
        for (what, associated) in wrong {
            #expect(throws: (any Error).self, "a chapter opened under \(what)") {
                try AES.GCM.open(try AES.GCM.SealedBox(combined: box), using: key, authenticating: associated)
            }
        }
        // …and with what it was actually sealed under, it opens.
        let opened = try AES.GCM.open(try AES.GCM.SealedBox(combined: box), using: key,
                                      authenticating: TranslationPackage.associatedData(
                                        packageID: "package-1", translationID: "DSV",
                                        chapter: ChapterRef(.genesis, 1), headerDigest: digest))
        #expect(opened == plaintext)
    }

    // MARK: - Expiry

    @Test func aPackagePastItsExpiryRefusesToOpen() throws {
        let built = try Self.build(policy: PackagePolicy(expires: "2026-01-01T00:00:00Z"))
        let expiry = try #require(PackagePolicy.date(fromISO8601: "2026-01-01T00:00:00Z"))

        #expect(throws: TranslationPackageError.expired(expiry)) {
            try TranslationPackage.open(url: built.url, keyring: built.keyring, contentKey: built.contentKey,
                                        now: expiry)
        }
        #expect(throws: TranslationPackageError.expired(expiry)) {
            try TranslationPackage.open(url: built.url, keyring: built.keyring, contentKey: built.contentKey,
                                        now: expiry.addingTimeInterval(1))
        }

        // The day before, it opens — and its rights carry the date, so a translation already open
        // when the licence lapses stops permitting anything.
        let package = try TranslationPackage.open(url: built.url, keyring: built.keyring,
                                                  contentKey: built.contentKey,
                                                  now: expiry.addingTimeInterval(-86_400))
        #expect(package.rights.expires == expiry)
        #expect(package.rights.hasExpired(asOf: expiry))
        #expect(!package.rights.mayQuote(verseCount: 1, asOf: expiry))
        #expect(!package.rights.permits(\.allowCopy, asOf: expiry))
        #expect(package.rights.permits(\.allowCopy, asOf: expiry.addingTimeInterval(-1)))
    }

    /// A date the app cannot parse must stop the package. Silently treating it as "no expiry" would
    /// be a licence that never ends because of a typo.
    @Test func anUnreadableExpiryRefusesToOpen() throws {
        let built = try Self.build(policy: PackagePolicy(expires: "one year from Michaelmas"))
        #expect(throws: TranslationPackageError.self) {
            try TranslationPackage.open(url: built.url, keyring: built.keyring, contentKey: built.contentKey)
        }
    }

    // MARK: - No whole-Bible plaintext

    /// The convenience that returns a selection's verses is bounded: it decrypts the chapters a range
    /// touches, one at a time, and refuses a range wide enough to be an extraction. There is no other
    /// API that returns text.
    @Test func noApiHandsOutMoreThanASelection() throws {
        let psalms = (1...30).map { chapter in
            Self.chapter(ChapterRef(.psalms, chapter), verses: [(1, "Blessed is the man.", [])])
        }
        let built = try Self.build(chapters: psalms)
        let package = try TranslationPackage.open(url: built.url, keyring: built.keyring, contentKey: built.contentKey)

        // A selection: two chapters, decrypted one after the other.
        #expect(try package.verses(in: VerseRange(VerseRef(.psalms, 1, 1), VerseRef(.psalms, 2, 1))).count == 2)
        // An extraction: refused before anything is decrypted.
        let everything = VerseRange(VerseRef(.genesis, 1, 1), VerseRef(.revelation, 22, 21))
        #expect(throws: TranslationPackageError.rangeTooLarge(chapters: 30)) {
            try package.verses(in: everything)
        }
    }

    /// Opening a package never touches the text: every check at open is over the header. A package
    /// whose later chapters are corrupt still opens, and still reads the chapters that are intact —
    /// which is only possible if each chapter is read and decrypted from its own byte range on
    /// demand, rather than the body being decrypted as one piece.
    @Test func chaptersAreDecryptedOneAtATimeFromTheirOwnByteRanges() throws {
        let built = try Self.build()
        let regions = Self.regions(built.bytes)
        let header = try JSONDecoder().decode(TranslationPackageHeader.self, from: built.bytes.subdata(in: regions.header))
        let john = try #require(header.chapters.first { $0.ref == ChapterRef(.john, 3) })

        var corrupted = built.bytes
        corrupted[regions.body.lowerBound + john.offset + 20] ^= 0xFF
        let url = try Self.write(corrupted, beside: built.url, named: "corrupt-chapter.sabible")

        let package = try TranslationPackage.open(url: url, keyring: built.keyring, contentKey: built.contentKey)
        #expect(try package.chapter(ChapterRef(.genesis, 1)).verses.count == 2)
        #expect(try package.chapter(ChapterRef(.genesis, 2)).verses.count == 1)
        #expect(throws: TranslationPackageError.chapterTampered(ChapterRef(.john, 3))) {
            try package.chapter(ChapterRef(.john, 3))
        }
    }

    /// The shape of the decrypting API, asserted against the source itself, because this is the claim
    /// a publisher cannot check by running the app: there is exactly one place in the reader that
    /// opens a sealed box, it takes one chapter, and nothing returns a collection of chapters.
    @Test func thereIsExactlyOneDecryptingEntryPoint() throws {
        let source = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "Sources/ScriptureAloneCore/Package/TranslationPackage.swift")
        let text = try String(contentsOf: source, encoding: .utf8)

        let opens = text.components(separatedBy: "AES.GCM.open(").count - 1
        #expect(opens == 1, "the reader should open exactly one sealed box in one place, found \(opens)")

        // That one call sits inside the private per-chapter function, and that function takes a single
        // chapter's index entry.
        let signature = "private func plaintext(forChapterAt entry: PackagedChapterEntry, ref: ChapterRef) throws -> Data"
        #expect(text.contains(signature))
        let body = try #require(text.range(of: signature)).upperBound ..< text.endIndex
        #expect(text[body].contains("AES.GCM.open("))

        // No API returns more than one chapter's worth of anything, and the only method that returns
        // verses is bounded by the chapter-span limit.
        #expect(!text.contains("[PackagedChapter]"))
        #expect(!text.contains("-> [ChapterLayout]"))
        let versesInRange = try #require(text.range(of: "public func verses(in range: VerseRange) throws -> [VerseText]"))
        let versesBody = versesInRange.upperBound ..< text.endIndex
        #expect(text[versesBody].prefix(600).contains("TranslationPackageFormat.chapterSpanLimit"))
    }

    // MARK: - Malformed files

    @Test func somethingThatIsNotAPackageIsRefusedAsSuch() throws {
        let built = try Self.build()
        let notAPackage = try Self.write(Data("This is a text file, not a Bible.".utf8), beside: built.url, named: "plain.txt")
        #expect(throws: TranslationPackageError.notAPackage) {
            try TranslationPackage.open(url: notAPackage, keyring: built.keyring, contentKey: built.contentKey)
        }

        var futureVersion = built.bytes
        futureVersion[TranslationPackageFormat.magic.count + 1] = 9
        let future = try Self.write(futureVersion, beside: built.url, named: "v9.sabible")
        #expect(throws: TranslationPackageError.unsupportedFormat(9)) {
            try TranslationPackage.open(url: future, keyring: built.keyring, contentKey: built.contentKey)
        }

        let truncated = try Self.write(built.bytes.prefix(built.bytes.count - 40), beside: built.url, named: "short.sabible")
        #expect(throws: TranslationPackageError.truncated) {
            try TranslationPackage.open(url: truncated, keyring: built.keyring, contentKey: built.contentKey)
        }

        let empty = try Self.write(Data(), beside: built.url, named: "empty.sabible")
        #expect(throws: TranslationPackageError.truncated) {
            try TranslationPackage.open(url: empty, keyring: built.keyring, contentKey: built.contentKey)
        }
    }

    @Test func aPackageSignedByAnUnknownKeyIsRefused() throws {
        let built = try Self.build()
        #expect(throws: TranslationPackageError.self) {
            try TranslationPackage.open(url: built.url, keyring: PublisherKeyring(), contentKey: built.contentKey)
        }
    }
}
