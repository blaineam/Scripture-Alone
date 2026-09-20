import Foundation
import CryptoKit
import Testing
@testable import ScriptureAloneCore

/// A publisher's terms, and the app obeying them through the same gate it uses for its own texts.
@Suite struct PackagePolicyTests {

    static func package(_ policy: PackagePolicy) throws -> TranslationPackage {
        let built = try TranslationPackageTests.build(policy: policy)
        return try TranslationPackage.open(url: built.url, keyring: built.keyring, contentKey: built.contentKey)
    }

    /// The gate the app already had. `MiSpeaksClient.availability(for:)` asks
    /// `translation.mayHandOffToOtherApps`; `NotesExportSheet` asks for the attribution notice before
    /// writing verses; the share sheet asks whether the text may leave. None of them know whether the
    /// translation came out of a package.
    struct Gates: Equatable {
        var copy: Bool
        var share: Bool
        var image: Bool
        var export: Bool
        var handoff: Bool
        var offline: Bool
    }

    static func appWouldAllow(_ info: TranslationInfo) -> Gates {
        Gates(copy: info.mayCopy, share: info.mayShareText, image: info.mayRenderVerseImage,
              export: info.mayExportNotesWithVerses, handoff: info.mayHandOffToOtherApps,
              offline: info.mayStoreOffline)
    }

    @Test func aPolicyThatForbidsExportIsRefusedByTheAppsOwnGate() throws {
        let strict = PackagePolicy(allowCopy: true, allowShare: false, allowVerseImages: false,
                                   allowNotesExport: false, allowExternalHandoff: false,
                                   allowOfflineStorage: true, maxQuotationVerses: 25)
        let package = try Self.package(strict)
        let allowed = Self.appWouldAllow(package.info)
        #expect(allowed.copy)
        #expect(!allowed.share)
        #expect(!allowed.image)
        #expect(!allowed.export)
        #expect(!allowed.handoff)
        #expect(allowed.offline)
        #expect(package.info.mayQuote(verseCount: 25))
        #expect(!package.info.mayQuote(verseCount: 26))
        // The notice travels with any quotation that is allowed.
        #expect(package.info.attributionNotice == TranslationPackageTests.identity.copyright)
    }

    /// The same six questions, asked of the bundled public-domain ASV by the same code, answered yes.
    /// One judgement, two sources of terms — which is what "do not invent a second permission system"
    /// has to mean in practice.
    @Test func packagedAndPublicDomainTextsAreJudgedByOneCodePath() throws {
        let asv = try BibleStore(url: BibleStoreTests.biblesDirectory.appending(path: "ASV.sqlite"))
        let free = Self.appWouldAllow(asv.info)
        #expect(free == Gates(copy: true, share: true, image: true, export: true, handoff: true, offline: true))
        #expect(asv.info.mayQuote(verseCount: 31_102))
        #expect(asv.info.grantedRights == nil)
        #expect(asv.info.rights == .publicDomain)

        let packaged = try Self.package(.readingOnly)
        #expect(Self.appWouldAllow(packaged.info) == Gates(copy: false, share: false, image: false,
                                                          export: false, handoff: false, offline: true))
        #expect(packaged.info.grantedRights != nil)
    }

    /// A policy can grant more than the app's own default for a copyrighted text, not only less. A
    /// publisher who is happy for the text to be spoken by another app says so, and the hand-off that
    /// is otherwise refused opens.
    @Test func aPolicyMayGrantMoreThanTheDefaultForACopyrightedText() throws {
        let unpackaged = TranslationInfo(id: "X", name: "X", abbreviation: "X",
                                         copyright: "Copyright © 2026 Example Bible Publishers",
                                         license: "All rights reserved")
        #expect(!unpackaged.mayHandOffToOtherApps)
        #expect(!unpackaged.mayQuote(verseCount: 2_000))

        let generous = try Self.package(PackagePolicy(allowExternalHandoff: true, maxQuotationVerses: 2_000))
        #expect(generous.info.mayHandOffToOtherApps)
        #expect(generous.info.mayQuote(verseCount: 2_000))
        #expect(!generous.info.mayQuote(verseCount: 2_001))
    }

    /// A field this build does not find in the policy must read as "no". A policy written by an older
    /// tool, or truncated, must not grant a permission by omission.
    @Test func aMissingFieldInAPolicyGrantsNothing() throws {
        let empty = try JSONDecoder().decode(PackagePolicy.self, from: Data("{}".utf8))
        #expect(empty == PackagePolicy(allowCopy: false, allowShare: false, allowVerseImages: false,
                                       allowNotesExport: false, allowExternalHandoff: false,
                                       allowOfflineStorage: false, maxQuotationVerses: 0))
        let rights = try empty.rights()
        #expect(!rights.allowCopy)
        #expect(!rights.mayQuote(verseCount: 1))

        let partial = try JSONDecoder().decode(PackagePolicy.self, from: Data(#"{"allowCopy":true}"#.utf8))
        #expect(partial.allowCopy)
        #expect(!partial.allowShare)
        #expect(partial.maxQuotationVerses == 0)
    }

    @Test func aPolicyAndItsRightsAreTheSameFactsTwice() throws {
        let policy = PackagePolicy(allowCopy: true, allowShare: false, allowVerseImages: true,
                                   allowNotesExport: false, allowExternalHandoff: true,
                                   allowOfflineStorage: false, maxQuotationVerses: 40,
                                   expires: "2030-06-01T00:00:00Z")
        let rights = try policy.rights()
        #expect(rights.maxQuotationVerses == 40)
        #expect(rights.expires == PackagePolicy.date(fromISO8601: "2030-06-01T00:00:00Z"))
        let round = try PackagePolicy(rights).rights()
        #expect(round == rights)
    }

    @Test func bareDatesAndTimestampsAreBothUnderstood() {
        let bare = PackagePolicy.date(fromISO8601: "2030-06-01")
        let stamped = PackagePolicy.date(fromISO8601: "2030-06-01T00:00:00Z")
        #expect(bare != nil)
        #expect(bare == stamped)
        #expect(PackagePolicy.date(fromISO8601: "June 2030") == nil)
    }

    /// An imported file whose licence nobody stated is not a licensed grant and not a free text. It
    /// gets the copyrighted default, which is the conservative answer.
    @Test func anUnknownLicenceIsTreatedAsLicensed() {
        let unknown = TranslationInfo(id: "IMPORT-1", name: "Imported", abbreviation: "IMP",
                                      copyright: "© Somebody",
                                      license: ImportedTranslationIdentity.unknownLicense)
        #expect(!unknown.isPublicDomain)
        #expect(unknown.rights == .licensedDefault)
        #expect(!unknown.mayHandOffToOtherApps)
        #expect(unknown.mayQuote(verseCount: 500))
        #expect(!unknown.mayQuote(verseCount: 501))
    }
}
