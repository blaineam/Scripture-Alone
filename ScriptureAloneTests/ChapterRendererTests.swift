import Foundation
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// The chapter the reader draws, built from the sealed ASV's real layout: what each verse is tagged
/// with (taps, selection, notes), how highlights, red letters, verse numbers and the footer come out.
@MainActor
struct ChapterRendererTests {
    let john3 = ChapterRef(.john, 3)

    func style(redLetters: Bool = true, verseNumbers: Bool = true, layout: ReadingLayout = .paragraphs) -> ReaderStyle {
        ReaderStyle(family: .newYork, size: 18, lineSpacing: 1.4, layout: layout, redLetters: redLetters,
                    verseNumbers: verseNumbers, headings: true, footnotes: true,
                    palette: ReaderTheme.light.palette(for: .light), paletteID: "light")
    }

    func render(_ style: ReaderStyle, highlights: [Int: String] = [:], notes: [Int: [String]] = [:],
                selection: Set<Int> = [], next: String? = "John 4",
                copyright: String = "Public domain. Details at www.example.org",
                compactHeader: Bool = false) throws -> NSAttributedString {
        let package = try #require(SealedTranslations.shared.package("ASV"))
        let input = ChapterRenderInput(chapter: john3, translation: "ASV", style: ReaderStyleKey(style),
                                       highlights: highlights, notes: notes, selection: selection,
                                       nextTitle: next, copyright: copyright, compactHeader: compactHeader)
        return ChapterRenderer.render(layout: try package.layout(for: john3), input: input, style: style).text
    }

    /// The characters tagged with a verse key, and their attributes.
    func verse(_ number: Int, in text: NSAttributedString) -> [(String, [NSAttributedString.Key: Any])] {
        var runs: [(String, [NSAttributedString.Key: Any])] = []
        text.enumerateAttributes(in: NSRange(location: 0, length: text.length)) { attrs, range, _ in
            if attrs[.verseKey] as? Int == VerseRef(.john, 3, number).key {
                runs.append(((text.string as NSString).substring(with: range), attrs))
            }
        }
        return runs
    }

    @Test func everyVerseIsTaggedAndTheChapterReadsInOrder() throws {
        let text = try render(style())
        #expect(text.string.contains("JOHN"))
        let sixteen = verse(16, in: text).map(\.0).joined()
        #expect(sixteen.contains("For God so loved the world"))
        for number in 1...36 {
            #expect(!verse(number, in: text).isEmpty, "John 3:\(number) has no tagged text")
        }
        let s = text.string as NSString
        #expect(s.range(of: "For God so loved").location < s.range(of: "He that believeth on the Son").location)
    }

    @Test func highlightsFillOnlyTheirVerse() throws {
        let text = try render(style(), highlights: [VerseRef(.john, 3, 16).key: HighlightColor.blue.rawValue])
        #expect(verse(16, in: text).allSatisfy { $0.1[.backgroundColor] != nil })
        #expect(verse(15, in: text).allSatisfy { $0.1[.backgroundColor] == nil })
        #expect(verse(17, in: text).allSatisfy { $0.1[.backgroundColor] == nil })
    }

    @Test func selectionIsMarkedAndANoteLeavesAMarkerCarryingItsID() throws {
        let text = try render(style(), notes: [VerseRef(.john, 3, 17).key: ["NOTE-1"]],
                              selection: [VerseRef(.john, 3, 18).key])
        #expect(verse(18, in: text).contains { $0.1[.underlineStyle] != nil })
        #expect(verse(19, in: text).allSatisfy { $0.1[.underlineStyle] == nil })
        var markers: [[String]] = []
        text.enumerateAttribute(.noteIDs, in: NSRange(location: 0, length: text.length)) { value, _, _ in
            if let ids = value as? [String] { markers.append(ids) }
        }
        #expect(markers == [["NOTE-1"]])
    }

    @Test func redLettersFollowTheSetting() throws {
        let palette = ReaderTheme.light.palette(for: .light)
        func redCount(_ text: NSAttributedString) -> Int {
            var count = 0
            text.enumerateAttribute(.foregroundColor, in: NSRange(location: 0, length: text.length)) { value, range, _ in
                if let color = value as? PlatformColor, color == palette.red { count += range.length }
            }
            return count
        }
        let on = try render(style(redLetters: true))
        let off = try render(style(redLetters: false))
        // John 3 has seventeen verses of Jesus' words in the ASV.
        #expect(redCount(on) > 100, "no words of Christ drawn in red")
        #expect(redCount(off) == 0, "red letters drawn with the setting off")
        let jesus = verse(3, in: on).filter { ($0.1[.foregroundColor] as? PlatformColor) == palette.red }.map(\.0).joined()
        #expect(jesus.contains("Verily, verily"))
    }

    @Test func verseNumbersFollowTheSetting() throws {
        let numbered = try render(style(verseNumbers: true))
        let plain = try render(style(verseNumbers: false))
        #expect(numbered.length > plain.length)
        #expect(verse(16, in: numbered).first?.0.hasPrefix("16") == true)
        #expect(verse(16, in: plain).first?.0.hasPrefix("16") == false)
    }

    @Test func theFooterCarriesNextAndALinkedNotice() throws {
        let text = try render(style())
        let s = text.string as NSString
        let next = s.range(of: "John 4  →")
        #expect(next.location != NSNotFound)
        #expect(text.attribute(.readerAction, at: next.location, effectiveRange: nil) as? String == "next")
        let link = s.range(of: "www.example.org")
        #expect(link.location != NSNotFound)
        #expect(text.attribute(.readerAction, at: link.location, effectiveRange: nil) as? String
                == ChapterRenderer.linkActionPrefix + "https://www.example.org")
        // The last chapter of the Bible has no "next".
        #expect((try render(style(), next: nil).string as NSString).range(of: "→").location == NSNotFound)
    }

    @Test func verseByVerseStartsEachVerseOnItsOwnLine() throws {
        let text = try render(style(layout: .verses))
        let lines = text.string.components(separatedBy: .newlines)
        for number in [1, 16, 36] {
            #expect(lines.contains { $0.hasPrefix("\(number)\u{202F}") }, "verse \(number) does not begin a line")
        }
    }

    @Test func theCompactHeaderPutsBookAndChapterOnOneLine() throws {
        let compact = try render(style(), compactHeader: true).string.components(separatedBy: "\n")
        #expect(compact.first == "JOHN  3")
        let usual = try render(style()).string.components(separatedBy: "\n")
        #expect(Array(usual.prefix(2)) == ["JOHN", "3"])
    }

    /// The lines each column of a spread holds, laid out as `ColumnChapterView` lays it out, and
    /// whether any line runs past its column's foot.
    func spread(_ text: NSAttributedString, width: CGFloat, height: CGFloat) -> (lines: [Int], overflow: Bool) {
        let storage = NSTextStorage(attributedString: text)
        let layoutManager = NSLayoutManager()
        storage.addLayoutManager(layoutManager)
        var lines: [Int] = []
        var overflow = false
        for _ in 0..<2 {
            let container = NSTextContainer(size: CGSize(width: width, height: height))
            container.lineFragmentPadding = 0
            layoutManager.addTextContainer(container)
            var count = 0
            layoutManager.enumerateLineFragments(forGlyphRange: layoutManager.glyphRange(for: container)) { rect, _, _, _, _ in
                count += 1
                if rect.maxY > height + 0.5 { overflow = true }
            }
            lines.append(count)
        }
        return (lines, overflow)
    }

    /// An iPhone Pro Max held sideways: the column view is 832 × 362 points under a top bar, with
    /// the bottom bar's 78 points of safe area inside it. The old 110-point reserve for the floating
    /// bars left each column five lines with a band of empty page beneath; now the text fills the
    /// page from bar to bar, and still no line is cut at a column's foot.
    @Test func aPhoneHeldSidewaysFillsTheColumnsFromBarToBar() throws {
        let width: CGFloat = 832, height: CGFloat = 362, safeBottom: CGFloat = 78
        #expect(columns(width: width, height: height, safeBottom: safeBottom, size: 18) == 2)
        let columnWidth = ReaderColumns.columnWidth(width: width, columns: 2)
        let tall = ReaderColumns.columnHeight(height: height, safeTop: 0, safeBottom: safeBottom, compactHeight: true)
        let reserved = max(120, height - 20 - safeBottom - 110)
        #expect(tall >= 260, "the column stops well short of the bottom bar: \(tall)")
        #expect(tall + 8 + safeBottom + 6 <= height + 0.5, "the column runs under the bottom bar")
        let before = spread(try render(style()), width: columnWidth, height: reserved)
        let after = spread(try render(style(), compactHeader: true), width: columnWidth, height: tall)
        #expect(!before.overflow && !after.overflow, "a line runs past a column's foot")
        #expect(after.lines.reduce(0, +) >= before.lines.reduce(0, +) + 7,
                "a sideways spread holds \(after.lines) lines, against \(before.lines) before")
    }

    /// No layout keeps room for the selection, Now Playing or highlighter bars: they float over the
    /// page and are gone in a moment. A tablet or Mac window's columns run to just above its bottom bar.
    @Test func columnsKeepNoRoomForTheFloatingBars() {
        #expect(ReaderColumns.verticalInsets(safeTop: 0, safeBottom: 20, compactHeight: false) == (top: 20, bottom: 40))
        #expect(ReaderColumns.verticalInsets(safeTop: 0, safeBottom: 78, compactHeight: true) == (top: 8, bottom: 84))
    }

    // MARK: Columns or one scrolling column

    /// The column decision for a page `width` × `height` (the reader's pane, as SwiftUI measures it)
    /// whose bottom bar covers `safeBottom`, at `size` points of `family`.
    func columns(width: CGFloat, height: CGFloat, safeTop: CGFloat = 0, safeBottom: CGFloat, compact: Bool = true,
                 size: CGFloat, family: FontFamily = .newYork, lineSpacing: CGFloat = 1.35, language: String = "en") -> Int {
        let metrics = ReaderColumns.metrics(font: family.font(size: size), lineSpacing: lineSpacing, language: language)
        return ReaderColumns.count(width: width, height: height, safeTop: safeTop, safeBottom: safeBottom,
                                   compactHeight: compact, metrics: metrics)
    }

    /// An iPhone Pro Max held sideways: an 832 × 362 pane with the bottom bar's 78 points inside it.
    /// The old rule wanted 18 ems a column, which two columns there only hold up to 19.8 points: the
    /// default is 19, so one step up the size slider sent the reader back to one scrolling column.
    @Test func aProMaxSidewaysKeepsColumnsAtLargerSizes() {
        for size: CGFloat in [17, 19, 20, 22, 24] {
            #expect(columns(width: 832, height: 362, safeBottom: 78, size: size) == 2, "\(size) pt")
        }
        for family in FontFamily.allCases {
            #expect(columns(width: 832, height: 362, safeBottom: 78, size: 19, family: family) == 2, "\(family)")
        }
    }

    /// On iPhone the reader's size is the slider's times the system text size (`dynamicTypeScale`).
    /// The old rule took a Pro Max's columns away at any system text size above the default: at
    /// 19 points × xLarge's 1.12 two 18-em columns no longer fitted 832 points. All three sizes above
    /// the default keep them now.
    @Test func largerSystemTextSizesKeepColumnsOnAProMax() {
        for (name, scale) in [("xLarge", 19.0 / 17), ("xxLarge", 21.0 / 17), ("xxxLarge", 23.0 / 17)] {
            #expect(columns(width: 832, height: 362, safeTop: 78, safeBottom: 0, size: 19 * scale) == 2, "\(name)")
        }
        #expect(columns(width: 750, height: 324, safeTop: 78, safeBottom: 0, size: 19 * 19.0 / 17) == 2, "iPhone Pro, xLarge")
    }

    /// The old rule also wanted a pane at least 360 points tall, which a Pro Max cleared by two
    /// points: an iPhone Pro (and a Pro Max with Display Zoom) held sideways is a 750 × 324 pane, and a
    /// larger system text size makes the bars taller. Both still hold a readable column.
    @Test func shorterPhonesAndTallerBarsStillGetColumns() {
        #expect(columns(width: 750, height: 324, safeBottom: 78, size: 19) == 2)
        #expect(columns(width: 750, height: 324, safeBottom: 78, size: 21) == 2)
        // Accessibility text sizes: a top bar 30 points taller and a bottom bar 12 points taller.
        #expect(columns(width: 832, height: 332, safeBottom: 90, size: 19) == 2)
        #expect(columns(width: 750, height: 294, safeBottom: 90, size: 19) == 2)
    }

    @Test func columnsTooNarrowOrTooShortScrollInstead() {
        // Too narrow: a very large size sideways, or any phone upright.
        #expect(columns(width: 832, height: 362, safeBottom: 78, size: 32) == 1)
        #expect(columns(width: 750, height: 324, safeBottom: 78, size: 26) == 1)
        #expect(columns(width: 440, height: 800, safeTop: 0, safeBottom: 90, compact: false, size: 19) == 1)
        // Too short: a pane with room for only a few lines a column.
        #expect(columns(width: 832, height: 180, safeBottom: 78, size: 19) == 1)
        // Very wide line spacing at a large size leaves too few lines on a short page.
        #expect(columns(width: 832, height: 300, safeBottom: 78, size: 24, lineSpacing: 2) == 1)
    }

    /// Every comfortable column the decision allows really is one: at least the minimum measure
    /// across, and at least the minimum lines down, at the column size `ColumnChapterView` lays out.
    @Test func aColumnTheDecisionAllowsHoldsTheMeasure() {
        for size in stride(from: CGFloat(12), through: 40, by: 1) {
            let metrics = ReaderColumns.metrics(font: FontFamily.newYork.font(size: size), lineSpacing: 1.35, language: "en")
            guard ReaderColumns.count(width: 832, height: 362, safeTop: 0, safeBottom: 78, compactHeight: true,
                                      metrics: metrics) == 2 else { continue }
            #expect(ReaderColumns.columnWidth(width: 832, columns: 2) / metrics.characterWidth >= ReaderColumns.minimumCharacters)
            #expect(ReaderColumns.columnHeight(height: 362, safeTop: 0, safeBottom: 78, compactHeight: true)
                    / metrics.lineHeight >= ReaderColumns.minimumLines)
        }
    }

    /// iPad and Mac windows: two columns when wide, one in a narrow window or Split View.
    @Test func tabletAndDesktopWindows() {
        #expect(columns(width: 1180, height: 760, safeTop: 70, safeBottom: 70, compact: false, size: 19) == 2)
        #expect(columns(width: 820, height: 1100, safeTop: 70, safeBottom: 70, compact: false, size: 19) == 2)
        #expect(columns(width: 500, height: 1100, safeTop: 70, safeBottom: 70, compact: false, size: 19) == 1)
    }

    /// Chinese, Japanese and Korean characters are each about an em wide, so their measure is counted
    /// in characters of their own.
    @Test func ideographicTextIsMeasuredInItsOwnCharacters() {
        let latin = ReaderColumns.metrics(font: FontFamily.newYork.font(size: 19), lineSpacing: 1.35, language: "en")
        let chinese = ReaderColumns.metrics(font: FontFamily.newYork.font(size: 19), lineSpacing: 1.35, language: "zh-Hans")
        #expect(chinese.characterWidth > latin.characterWidth * 1.6)
        #expect(chinese.minimumCharacters == ReaderColumns.minimumIdeographs)
        #expect(columns(width: 832, height: 362, safeBottom: 78, size: 19, language: "zh-Hans") == 2)
        #expect(columns(width: 832, height: 362, safeBottom: 78, size: 26, language: "ja") == 1)
    }

    @Test func noticeLinksBecomeHTTPS() {
        let found = NoticeLinks.find(in: "See www.Lockman.org and http://example.com/x — not mailto:a@b.c")
        #expect(found.map(\.url.absoluteString) == ["https://www.Lockman.org", "https://example.com/x"])
    }
}
