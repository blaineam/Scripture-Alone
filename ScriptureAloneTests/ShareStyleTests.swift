import CoreGraphics
import Foundation
import Testing
@testable import Scripture_Alone

/// The verse-image designer's style model: the grounds and their ready-made styles, the contrast
/// rules that keep text readable on them, the remembered design, and the drawn backdrops.
/// `ShareStyleTest.kt` holds Android to the same numbers.
struct ShareStyleTests {
    // MARK: Contrast

    @Test func contrastRatiosFollowWCAG() {
        #expect(abs(ShareContrast.ratio(0x000000, 0xFFFFFF) - 21) < 0.01)
        #expect(abs(ShareContrast.ratio(0x777777, 0x777777) - 1) < 0.0001)
        // WCAG's own example pair: #767676 on white is just over 4.5:1.
        #expect(ShareContrast.ratio(0x767676, 0xFFFFFF) > 4.5)
        #expect(ShareContrast.ratio(0x777777, 0xFFFFFF) < 4.5)
    }

    /// Every ready-made style reads as it is: the curated text reaches 4.5:1 on its ground's average
    /// as drawn, every patch of a textured ground stays at 3:1 or better with the style's shadow,
    /// and nothing has to be nudged.
    @Test(arguments: ShareBackground.allCases)
    func everyStyleIsLegibleWithoutAdjustment(_ background: ShareBackground) {
        var style = ShareStyle()
        style.apply(background)
        let stats = ShareBackdrop.stats(background)
        let colors = style.colors(on: stats)
        let (mean, worst) = ShareContrast.check(colors.ink, stats)
        #expect(mean >= ShareContrast.target, "\(background): text \(mean):1 on the average ground")
        #expect(worst >= ShareContrast.minimum || background.presetShadow != .none,
                "\(background): text \(worst):1 on the worst patch without a shadow")
        #expect(!colors.adjusted, "\(background): the ready-made style needed adjusting")
        #expect(colors.ink == background.ink)
        #expect(ShareContrast.ratio(colors.accent, stats.mean) >= ShareContrast.minimum, "\(background): accent")
        #expect(ShareContrast.ratio(colors.red, stats.mean) >= ShareContrast.minimum, "\(background): red letters")
    }

    @Test func aPickedColorTooFaintForTheGroundIsNudgedUntilItReads() {
        var style = ShareStyle()
        style.apply(.parchment)
        style.ink = 0xE8D9B8 // nearly the paper itself
        let stats = ShareBackdrop.stats(.parchment)
        let colors = style.colors(on: stats)
        #expect(colors.adjusted)
        #expect(colors.ink != 0xE8D9B8)
        #expect(ShareContrast.ratio(colors.ink, stats.mean) >= ShareContrast.target)
        // Toward black on a light ground: darker than it was.
        #expect(ShareContrast.luminance(colors.ink) < ShareContrast.luminance(0xE8D9B8))
    }

    @Test func aPickedColorThatReadsIsKeptExactly() {
        var style = ShareStyle()
        style.apply(.night)
        style.ink = 0xE9C77F // gold on navy
        let colors = style.colors(on: ShareBackdrop.stats(.night))
        #expect(colors.ink == 0xE9C77F)
        #expect(!colors.adjusted)
    }

    @Test func aBusyGroundGetsAShadowWhenThereIsNone() {
        // White on a ground with a very light patch: readable on average, not everywhere.
        let stats = ShareBackdropStats(mean: 0x202020, lightest: 0xD0D0D0, darkest: 0x101010)
        let colors = ShareContrast.resolve(background: .ink, ink: 0xFFFFFF, shadow: .none, stats: stats)
        #expect(colors.ink == 0xFFFFFF)
        #expect(colors.shadow == .soft)
        #expect(colors.adjusted)
        #expect(colors.shadowColor == 0x000000, "light text takes a dark shadow")
        // A shadow already chosen is left alone.
        #expect(ShareContrast.resolve(background: .ink, ink: 0xFFFFFF, shadow: .strong, stats: stats).shadow == .strong)
    }

    @Test func theContrastPickerChoosesLightOnDarkAndDarkOnLight() {
        let candidates: [UInt32] = [0x111111, 0xFFFFFF, 0x1F2F4A, 0xF5EBD7]
        let night = ShareContrast.bestInk(on: ShareBackdrop.stats(.night), from: candidates)
        #expect(ShareContrast.luminance(night) > 0.7)
        let minimal = ShareContrast.bestInk(on: ShareBackdrop.stats(.minimal), from: candidates)
        #expect(minimal == 0x111111)
        // The palette offered for a ground suits it.
        for background in ShareBackground.allCases {
            for swatch in background.palette {
                #expect(ShareContrast.ratio(swatch.hex, ShareBackdrop.stats(background).mean) >= ShareContrast.minimum,
                        "\(swatch.name) on \(background)")
            }
        }
    }

    // MARK: Automatic color

    @Test func theTextColorFollowsTheGroundUntilThePersonPicksOne() {
        var style = ShareStyle()
        style.apply(.parchment)
        style.background = .night
        #expect(style.colors(on: ShareBackdrop.stats(.night)).ink == ShareBackground.night.ink)
        style.ink = 0xE9C77F
        style.background = .ink
        #expect(style.colors(on: ShareBackdrop.stats(.ink)).ink == 0xE9C77F, "a picked color survives a new ground")
        // A ready-made style hands the choice back to the ground.
        style.apply(.watercolor)
        #expect(style.ink == nil)
        #expect(style.shadow == .soft)
        #expect(style.family == .palatino)
    }

    // MARK: Remembering

    @Test func theDesignIsRememberedBetweenLaunches() throws {
        let name = "ShareStyleTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }

        // A fresh install starts on Parchment, square, centred.
        #expect(ShareStyle(defaults: defaults) == ShareStyle())

        var style = ShareStyle()
        style.apply(.bokeh)
        style.ink = 0xCFE0F7
        style.aspect = .story
        style.redLetters = false
        style.save(to: defaults)
        #expect(ShareStyle(defaults: defaults) == style)

        // Back to automatic.
        style.ink = nil
        style.save(to: defaults)
        #expect(ShareStyle(defaults: defaults).ink == nil)
    }

    @Test func aTemplateRememberedBeforeTheseStylesStillLoads() throws {
        let name = "ShareStyleTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set("night", forKey: ShareSettingsKey.template)
        defaults.set("georgia", forKey: ShareSettingsKey.family)
        let style = ShareStyle(defaults: defaults)
        #expect(style.background == .night)
        #expect(style.family == .georgia)
        #expect(style.ink == nil)
        #expect(style.shadow == .none)
    }

    // MARK: Links

    @Test func everyGroundTravelsInALinkAsATemplateTheWebKnows() {
        for template in ShareTemplate.allCases {
            #expect(template.background.linkTemplate == template)
        }
        for background in ShareBackground.allCases {
            #expect(ShareTemplate.allCases.contains(background.linkTemplate))
        }
    }

    // MARK: Drawing

    /// The export draws the ground at 2× with fine texture per output pixel: a 2160-pixel card whose
    /// neighbouring pixels still differ (crisp grain), and patterns that aren't flat.
    @Test(arguments: [ShareBackground.grain, .linen, .canvas, .watercolor, .contour, .bokeh])
    func texturesAreCrispAtExportSize(_ background: ShareBackground) throws {
        let image = try #require(ShareBackdrop.image(background, size: ShareAspect.square.size,
                                                     pixelsPerPoint: ShareRenderer.scale))
        #expect(image.width == 2160 && image.height == 2160)
        let (detail, spread) = try texture(of: image)
        #expect(detail > 0.6, "\(background): neighbouring pixels differ by only \(detail) levels on average")
        #expect(spread > 4, "\(background): the ground is nearly flat (spread \(spread))")
    }

    @Test func cleanGroundsStayClean() throws {
        let image = try #require(ShareBackdrop.image(.minimal, size: ShareAspect.wide.size, pixelsPerPoint: 1))
        #expect(image.width == 1080 && image.height == 608)
        let (detail, spread) = try texture(of: image)
        #expect(detail == 0 && spread == 0, "Minimal should be pure white")
    }

    @Test func theSameGroundDrawsTheSameEveryTime() throws {
        let a = try #require(ShareBackdrop.image(.watercolor, size: CGSize(width: 1080, height: 1081), pixelsPerPoint: 0.25))
        let b = try #require(ShareBackdrop.image(.watercolor, size: CGSize(width: 1080, height: 1081), pixelsPerPoint: 0.25))
        #expect(a.dataProvider?.data as Data? == b.dataProvider?.data as Data?)
    }

    /// The hash and noise Android must reproduce.
    @Test func noiseMatchesTheAndroidPort() {
        // Pinned in ShareStyleTest.kt too.
        #expect(ShareBackdrop.hash(1, 2, 3) == 0x02BC_EC5F)
        #expect(ShareBackdrop.hash(12, -7, 99) == 0x20F8_9503)
        #expect(abs(ShareBackdrop.fbm(3.7, 11.2, octaves: 4, seed: 42) - 0.4618003) < 1e-5)
        #expect(ShareBackdrop.seed(for: .watercolor) == -2_023_758_861)
    }

    /// Mean absolute difference between horizontal neighbours (in 0–255 levels) over a central strip,
    /// and the luminance range of 16-pixel block averages across the image.
    private func texture(of image: CGImage) throws -> (detail: Double, spread: Double) {
        let width = image.width, height = image.height
        let context = try #require(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                             space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                             bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        let data = try #require(context.data?.assumingMemoryBound(to: UInt8.self))
        func lum(_ x: Int, _ y: Int) -> Double {
            let i = (y * width + x) * 4
            return 0.2126 * Double(data[i]) + 0.7152 * Double(data[i + 1]) + 0.0722 * Double(data[i + 2])
        }
        var total = 0.0, count = 0
        for y in stride(from: height / 2 - 40, to: height / 2 + 40, by: 1) {
            for x in 1..<width {
                total += abs(lum(x, y) - lum(x - 1, y))
                count += 1
            }
        }
        var low = 255.0, high = 0.0
        let block = 16
        for by in stride(from: 0, to: height - block, by: block * 4) {
            for bx in stride(from: 0, to: width - block, by: block * 4) {
                var sum = 0.0
                for y in by..<(by + block) { for x in bx..<(bx + block) { sum += lum(x, y) } }
                let mean = sum / Double(block * block)
                low = min(low, mean); high = max(high, mean)
            }
        }
        return (total / Double(count), high - low)
    }
}
