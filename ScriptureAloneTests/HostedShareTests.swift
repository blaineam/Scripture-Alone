import SwiftUI
import SwiftData
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The verse-image designer under every ground, shape and option, and the card it renders — drawn for
/// real from the shipped ASV (public domain), never a licensed text.
@MainActor
@Suite(.serialized)
struct HostedShareTests {
    let container: ModelContainer
    let model: ReaderModel

    init() throws {
        container = try ViewHost.container()
        model = ViewHost.readerModel()
    }

    static let keys = [ShareSettingsKey.template, ShareSettingsKey.ink, ShareSettingsKey.shadow, ShareSettingsKey.aspect,
                       ShareSettingsKey.family, ShareSettingsKey.alignment, ShareSettingsKey.redLetters,
                       ShareSettingsKey.verseNumbers, ShareSettingsKey.wordmark, ShareSettingsKey.customizing]

    /// Runs `body` with the designer's remembered settings replaced, then puts back what was there.
    func withShareSettings(_ style: ShareStyle, customizing: Bool, _ body: () async -> Void) async {
        let defaults = UserDefaults.standard
        let saved = Self.keys.map { ($0, defaults.object(forKey: $0)) }
        style.save(to: defaults)
        defaults.set(customizing, forKey: ShareSettingsKey.customizing)
        await body()
        for (key, value) in saved {
            if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
        }
    }

    func source(_ ranges: [VerseRange]) throws -> ShareSource {
        let source = try #require(model.source)
        return try #require(ShareSource(source: source, ranges: ranges))
    }

    func designer(_ source: ShareSource, size: CGSize = CGSize(width: 900, height: 900)) async -> HostedView {
        await ViewHost.render(ViewHost.inReader(ShareDesigner(source: source), model: model, container: container), size: size)
    }

    @Test func theDesignerDrawsOnEveryGroundWithCustomizeOpen() async throws {
        let share = try source([VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 17))])
        for background in ShareBackground.allCases {
            var style = ShareStyle()
            style.apply(background)
            await withShareSettings(style, customizing: true) {
                #expect(await designer(share).isDrawn, "\(background)")
            }
        }
    }

    @Test func theDesignerDrawsEveryShapeShadowAndTypeface() async throws {
        let share = try source([VerseRange(VerseRef(.psalms, 23, 1), VerseRef(.psalms, 23, 6))])
        for aspect in ShareAspect.allCases {
            for shadow in ShareShadow.allCases {
                var style = ShareStyle()
                style.aspect = aspect
                style.shadow = shadow
                style.ink = shadow == .strong ? 0xFFD700 : nil
                style.alignment = aspect == .wide ? .leading : .center
                style.redLetters = aspect != .story
                style.verseNumbers = shadow != .soft
                style.wordmark = aspect != .wide
                await withShareSettings(style, customizing: true) {
                    #expect(await designer(share).isDrawn, "\(aspect) \(shadow)")
                }
            }
        }
        for family in FontFamily.allCases {
            var style = ShareStyle()
            style.family = family
            await withShareSettings(style, customizing: false) {
                #expect(await designer(share, size: CGSize(width: 390, height: 844)).isDrawn, "\(family)")
            }
        }
    }

    @Test func aLongPassageIsTrimmedToFitTheCard() throws {
        let share = try source([VerseRange(VerseRef(.psalms, 119, 1), VerseRef(.psalms, 119, 40))])
        let style = ShareStyle()
        let fit = ShareCardFitter.fit(verses: share.verses, info: share.info, style: style, verseCount: share.source.verseCount)
        #expect(fit.trimmed && fit.shownVerses < fit.totalVerses, "forty verses do not fit one card")
    }

    @Test func everyGroundRendersToAnImageOfItsShape() throws {
        let share = try source([VerseRange(VerseRef(.john, 3, 16))])
        for aspect in ShareAspect.allCases {
            for background in ShareBackground.allCases {
                var style = ShareStyle()
                style.apply(background)
                style.aspect = aspect
                let fit = ShareCardFitter.fit(verses: share.verses, info: share.info, style: style, verseCount: share.source.verseCount)
                let output = try #require(ShareRenderer.render(fit.content, style: style))
                #expect(output.cgImage.width > 0 && output.cgImage.height > 0)
                let ratio = Double(output.cgImage.width) / Double(output.cgImage.height)
                #expect(abs(ratio - aspect.size.width / aspect.size.height) < 0.02, "\(background) \(aspect)")
                #expect(!output.image.png.isEmpty)
            }
        }
    }

    @Test func swatchesAndTilesDrawEveryGround() async {
        for background in ShareBackground.allCases {
            let hosted = await ViewHost.render(BackdropSwatch(background: background, side: 48), size: CGSize(width: 64, height: 64))
            #expect(hosted.isLaidOut, "\(background)")
        }
    }
}
