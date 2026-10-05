import Foundation
import Testing
@testable import Scripture_Alone

/// Which Bible a fresh install fetches, which are offered, and that every pack the app asks App Store
/// Connect for is one the repository actually describes (`Tools/asset-packs`). A pack id or file name
/// out of step with its manifest is a download that fails for every reader, and only in the field.
struct AssetPackTests {
    @Test(arguments: [
        (["de-DE", "en-US"], AssetPack?.some(.lut1912)),
        (["fr-CA"], .lsg),
        (["es-419"], .rvr1909),
        (["pt-BR"], .blivre),
        (["it"], .riv1927),
        (["ko-KR"], .krv),
        (["ja-JP"], .bungo),
        (["zh-Hans-CN"], .cuvs),
        (["zh-CN"], .cuvs),
        // A Traditional-script reader is not handed the simplified 和合本 unasked.
        (["zh-Hant-TW"], nil),
        (["zh-TW"], nil),
        // English first means English, whatever comes after.
        (["en-GB", "de-DE"], nil),
        // A language with no Bible of its own is passed over for the next.
        (["nl-NL", "fr-FR"], .lsg),
        (["nl-NL"], nil),
        ([], nil),
    ])
    func firstLaunchBibleFollowsThePreferredLanguages(languages: [String], expected: AssetPack?) {
        #expect(AssetPack.bible(forPreferredLanguages: languages) == expected)
    }

    @Test func englishTranslationsAreAlwaysOfferedLocaleOnesOnlyWhenWanted() {
        let german = AssetPack.offeredTranslations(forPreferredLanguages: ["de-DE"])
        #expect(german.contains(.bsb) && german.contains(.kjv))
        #expect(german.contains(.lut1912))
        #expect(!german.contains(.cuvs) && !german.contains(.krv))

        // Any of the reader's languages counts, not only the first.
        let both = AssetPack.offeredTranslations(forPreferredLanguages: ["en-US", "ko-KR", "es-MX"])
        #expect(both.contains(.krv) && both.contains(.rvr1909) && !both.contains(.lsg))
    }

    @Test func aBibleOnTheDeviceStaysOfferedAfterALanguageChange() {
        let offered = AssetPack.offeredTranslations(forPreferredLanguages: ["en-US"]) { $0 == .krv }
        #expect(offered.contains(.krv))
        #expect(!offered.contains(.lsg))
        // In the Translations screen's order.
        #expect(offered == AssetPack.translations.filter { offered.contains($0) })
    }

    @Test func translationIDsMapBothWays() {
        for pack in AssetPack.allCases {
            if let id = pack.translationID {
                #expect(AssetPack(translationID: id) == pack)
            }
        }
        #expect(AssetPack(translationID: "NASB2020") == nil, "the default ships in the app, never as a pack")
        #expect(AssetPack(translationID: "ESV") == nil)
        #expect(AssetPack.commentary.translationID == nil && AssetPack.interlinear.translationID == nil)
    }

    @Test func sealedPacksAreTheSealedTranslations() {
        #expect(Set(AssetPack.allCases.filter(\.isSealed)) == [.asv, .nasb1995])
        for pack in AssetPack.allCases where pack.isSealed {
            #expect(SealedTranslations.identifiers.contains(pack.translationID ?? ""))
        }
    }

    /// The App Store Connect manifests in `Tools/asset-packs` (copied into this test bundle).
    struct Manifest: Decodable {
        struct Selector: Decodable { let fileSource: String; let fileDestination: String }
        let assetPackID: String
        let fileSelectors: [Selector]
    }

    static func manifests() throws -> [String: Manifest] {
        let bundle = Bundle(for: BundleToken.self)
        let urls = try #require(bundle.urls(forResourcesWithExtension: "json", subdirectory: "asset-packs"),
                                "Tools/asset-packs is not in the test bundle")
        var out: [String: Manifest] = [:]
        for url in urls {
            let manifest = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: url))
            #expect(url.deletingPathExtension().lastPathComponent == manifest.assetPackID,
                    "\(url.lastPathComponent) declares \(manifest.assetPackID)")
            out[manifest.assetPackID] = manifest
        }
        return out
    }

    @Test func everyPackTheAppRequestsHasAManifestCarryingItsFile() throws {
        let manifests = try Self.manifests()
        for pack in AssetPack.allCases {
            let manifest = try #require(manifests[pack.id], "no manifest for asset pack \(pack.id) (\(pack))")
            #expect(manifest.fileSelectors.contains { $0.fileDestination == pack.file },
                    "\(pack.id) does not put \(pack.file) at its root")
        }
    }

    /// The archived identifiers can never carry content again (see `AssetPack.id`).
    @Test func studyPacksNeverUseTheArchivedIdentifiers() {
        #expect(AssetPack.commentary.id == "study-commentary")
        #expect(AssetPack.interlinear.id == "study-interlinear")
        #expect(!AssetPack.allCases.map(\.id).contains("commentary"))
        #expect(!AssetPack.allCases.map(\.id).contains("interlinear"))
    }

    @Test func titlesSizesAndExplanationsAreFilledIn() {
        for pack in AssetPack.allCases {
            #expect(!pack.title.isEmpty)
            #expect(pack.megabytes > 0 && pack.megabytes < 100)
            #expect(pack.explanation.contains("\(pack.megabytes)"))
        }
        #expect(!AssetPack.nasb1995.title.contains("NASB ") || AssetPack.nasb1995.title.contains("NASB 1995"),
                "the agreement allows the name only with its year")
    }

    // MARK: Failure messages

    @Test func offlineFailuresSayAConnectionIsNeeded() {
        let error = NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet)
        let message = AssetLibrary.message(for: error, pack: .kjv)
        #expect(message.contains(AssetPack.kjv.title))
        #expect(message == String(localized: "\(AssetPack.kjv.title) needs a connection to download."))
    }

    @Test func otherFailuresCarryTheUnderlyingReason() {
        struct Boom: LocalizedError { var errorDescription: String? { "disk full" } }
        let message = AssetLibrary.message(for: Boom(), pack: .commentary)
        #expect(message.contains("disk full"))
        #expect(message.contains(AssetPack.commentary.title))
    }
}

final class BundleToken {}
