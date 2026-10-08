import Foundation
import CryptoKit
import Testing
@testable import ScriptureAloneCore

/// A publisher's term that keeps a translation off watches and other wearables entirely
/// (`PackagePolicy.wearables`): read from the signed header, impossible to strip or flip, refused by
/// a watch, swept off one if it ever lands there, and never the source of a complication's text.
@Suite struct WearableLicenceTests {

    static let prohibited = PackagePolicy(wearables: PackagePolicy.Wearables.prohibited.rawValue)

    // MARK: Parsing

    @Test func theTermIsReadAsAllowedProhibitedOrAbsent() throws {
        func policy(_ json: String) throws -> PackagePolicy {
            try JSONDecoder().decode(PackagePolicy.self, from: Data(json.utf8))
        }
        // Absent: every package built before the term existed, and they were licensed for watches.
        #expect(try policy(#"{"allowCopy":true}"#).allowsWearables)
        #expect(try policy(#"{"wearables":null}"#).allowsWearables)
        #expect(try policy(#"{"wearables":"allowed"}"#).allowsWearables)
        #expect(try !policy(#"{"wearables":"prohibited"}"#).allowsWearables)
        // Anything but the two words fails closed, like an unreadable expiry.
        #expect(try !policy(#"{"wearables":"Allowed"}"#).allowsWearables)
        #expect(try !policy(#"{"wearables":"yes"}"#).allowsWearables)
        #expect(try !policy(#"{"wearables":""}"#).allowsWearables)
        // The wrong type is a damaged header, not a guess.
        #expect(throws: DecodingError.self) { try policy(#"{"wearables":true}"#) }
    }

    @Test func theTermBecomesTheRightsTheAppGatesOn() throws {
        #expect(try PackagePolicy().rights().allowWearables)
        #expect(try !Self.prohibited.rights().allowWearables)
        #expect(TranslationRights.publicDomain.allowWearables)
        #expect(TranslationRights.licensedDefault.allowWearables)
        // Round trip through the rights value.
        #expect(PackagePolicy(try Self.prohibited.rights()).wearables == "prohibited")
        #expect(PackagePolicy(try PackagePolicy().rights()).wearables == nil)
    }

    /// A policy written without the term encodes exactly as before: nothing new in old headers.
    @Test func anUnsetTermIsNotWritten() throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let plain = String(decoding: try encoder.encode(PackagePolicy()), as: UTF8.self)
        #expect(!plain.contains("wearables"))
        let restricted = String(decoding: try encoder.encode(Self.prohibited), as: UTF8.self)
        #expect(restricted.contains(#""wearables":"prohibited""#))
    }

    /// Rights encoded before the field existed (a keepsake, the imported library) still decode, as
    /// allowed — what they always meant.
    @Test func rightsEncodedBeforeTheTermStillDecode() throws {
        let encoder = JSONEncoder()
        var json = try JSONSerialization.jsonObject(with: try encoder.encode(TranslationRights.licensedDefault)) as! [String: Any]
        json["allowWearables"] = nil
        let decoded = try JSONDecoder().decode(TranslationRights.self, from: try JSONSerialization.data(withJSONObject: json))
        #expect(decoded == .licensedDefault)
        #expect(decoded.allowWearables)
    }

    // MARK: Integrity

    /// The term is inside the signed header, so flipping it — to the same length, so nothing else in
    /// the file moves — or removing it leaves a package nothing will open.
    @Test func aFlippedOrStrippedTermFailsTheSignature() throws {
        let built = try TranslationPackageTests.build(policy: Self.prohibited)
        let regions = TranslationPackageTests.regions(built.bytes)
        let text = String(decoding: built.bytes.subdata(in: regions.header), as: UTF8.self)
        let term = try #require(text.range(of: #""wearables":"prohibited""#))
        let offset = regions.header.lowerBound + text.utf8.distance(from: text.utf8.startIndex,
                                                                    to: term.lowerBound.samePosition(in: text.utf8)!)
        // `"allowed"` padded with JSON whitespace to the length of `"prohibited"`: still valid JSON.
        var flipped = built.bytes
        for (index, character) in Array(#""wearables":"allowed"   "#).enumerated() {
            flipped[offset + index] = character.asciiValue!
        }
        #expect(flipped.count == built.bytes.count)
        let flippedURL = try TranslationPackageTests.write(flipped, beside: built.url, named: "flipped-wearables.sabible")
        // The unverified read believes the edit — and the signed open, on any device, does not.
        #expect(WearableLicence.allowsWearables(packageAt: flippedURL))
        for device in [PackageDevice.handheld, .wearable] {
            #expect(throws: TranslationPackageError.signatureInvalid) {
                try TranslationPackage.open(url: flippedURL, keyring: built.keyring, contentKey: built.contentKey,
                                            device: device)
            }
        }

        // Removed outright, header re-encoded, old signature kept.
        var header = try JSONDecoder().decode(TranslationPackageHeader.self, from: built.bytes.subdata(in: regions.header))
        header.policy.wearables = nil
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let stripped = TranslationPackageTests.assemble(header: try encoder.encode(header),
                                                        signature: built.bytes.subdata(in: regions.signature),
                                                        body: built.bytes.subdata(in: regions.body))
        let strippedURL = try TranslationPackageTests.write(stripped, beside: built.url, named: "stripped-wearables.sabible")
        #expect(throws: TranslationPackageError.signatureInvalid) {
            try TranslationPackage.open(url: strippedURL, keyring: built.keyring, contentKey: built.contentKey,
                                        device: .wearable)
        }
    }

    // MARK: Enforcement

    /// A watch refuses the package after verifying it; a phone reads it as ever.
    @Test func aWatchRefusesAProhibitedPackageAndAPhoneDoesNot() throws {
        let built = try TranslationPackageTests.build(policy: Self.prohibited)
        #expect(throws: TranslationPackageError.notForWearables) {
            try TranslationPackage.open(url: built.url, keyring: built.keyring, contentKey: built.contentKey,
                                        device: .wearable)
        }
        let phone = try TranslationPackage.open(url: built.url, keyring: built.keyring, contentKey: built.contentKey,
                                                device: .handheld)
        #expect(!phone.rights.allowWearables)
        #expect(try !phone.verses(in: VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 16))).isEmpty)
        #expect(TranslationPackageError.notForWearables.errorDescription?.isEmpty == false)
    }

    @Test func aWatchOpensAllowedAndLegacyPackages() throws {
        for policy in [PackagePolicy(), PackagePolicy(wearables: "allowed")] {
            let built = try TranslationPackageTests.build(policy: policy)
            let watch = try TranslationPackage.open(url: built.url, keyring: built.keyring, contentKey: built.contentKey,
                                                    device: .wearable)
            #expect(watch.rights.allowWearables)
        }
    }

    /// What the phone asks before sending a package to the watch.
    @Test func thePhoneSendsOnlyPackagesTheirTermsLetGo() throws {
        let allowed = try TranslationPackageTests.build(policy: PackagePolicy())
        let prohibited = try TranslationPackageTests.build(policy: Self.prohibited)
        #expect(WearableLicence.allowsWearables(packageAt: allowed.url))
        #expect(!WearableLicence.allowsWearables(packageAt: prohibited.url))
        // A file that isn't a readable package is never sent: the watch couldn't open it anyway.
        let junk = try TranslationPackageTests.write(Data("not a package".utf8), beside: allowed.url, named: "junk.sabible")
        #expect(!WearableLicence.allowsWearables(packageAt: junk))
        #expect(!WearableLicence.allowsWearables(packageAt: allowed.url.appending(path: "missing")))
    }

    /// The watch's sweep: a prohibited package is deleted however it arrived; everything else stays.
    @Test func aWatchDeletesAProhibitedPackageItFinds() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "wearable-sweep-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let prohibited = try TranslationPackageTests.build(policy: Self.prohibited)
        let allowed = try TranslationPackageTests.build(policy: PackagePolicy())
        try FileManager.default.copyItem(at: prohibited.url, to: directory.appending(path: "NASB1995.sabible"))
        try FileManager.default.copyItem(at: allowed.url, to: directory.appending(path: "ASV.sabible"))
        try Data("edition".utf8).write(to: directory.appending(path: "BSB-Watch.sqlite"))
        try Data("{}".utf8).write(to: directory.appending(path: "received.json"))

        let removed = WearableLicence.removeProhibitedPackages(in: directory)
        #expect(removed.map(\.lastPathComponent) == ["NASB1995.sabible"])
        let left = try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted()
        #expect(left == ["ASV.sabible", "BSB-Watch.sqlite", "received.json"])
    }

    /// The complication's snapshot never carries a prohibited translation's text: with no source the
    /// watch may read — or one whose terms keep it off wearables — it falls back to the public list's
    /// translation with nothing of its own.
    @Test func theVerseOfDaySnapshotFallsBackFromAProhibitedTranslation() throws {
        let built = try TranslationPackageTests.build(policy: Self.prohibited)
        let prohibited = try TranslationPackage.open(url: built.url, keyring: built.keyring, contentKey: built.contentKey,
                                                     device: .handheld)
        let text = ["43003016-43003016": VerseSnapshot.DailyText(text: "words", red: [])]
        var asked = false
        let refused = VerseSnapshot.forWearable(source: prohibited, translation: prohibited.info.id) { _ in
            asked = true
            return text
        }
        #expect(!asked)
        #expect(refused.translation == DailyVerseCatalog.fallbackTranslation)
        #expect(refused.daily == nil)
        #expect(refused.abbreviation == nil)

        let none = VerseSnapshot.forWearable(source: nil, translation: "NASB2020") { _ in text }
        #expect(none.translation == DailyVerseCatalog.fallbackTranslation)
        #expect(none.daily == nil)

        let open = try TranslationPackageTests.build(policy: PackagePolicy())
        let allowed = try TranslationPackage.open(url: open.url, keyring: open.keyring, contentKey: open.contentKey,
                                                  device: .wearable)
        let shown = VerseSnapshot.forWearable(source: allowed, translation: allowed.info.id) { _ in text }
        #expect(shown.translation == allowed.info.id)
        #expect(shown.daily == text)
    }
}
