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
    /// the bottom bar's 78 points of safe area inside it. The reserve for the floating bars left each
    /// column five lines with a band of empty page beneath; now the text fills the page from bar to
    /// bar, and still no line is cut at a column's foot.
    @Test func aPhoneHeldSidewaysFillsTheColumnsFromBarToBar() throws {
        let width: CGFloat = 832, height: CGFloat = 362, safeBottom: CGFloat = 78
        #expect(ReaderColumns.count(width: width, height: height, fontSize: 18) == 2)
        let columnWidth = (width - ReaderColumns.margin * 2 - ReaderColumns.gutter) / 2
        let tall = ReaderColumns.columnHeight(height: height, safeTop: 0, safeBottom: safeBottom, compactHeight: true)
        let short = ReaderColumns.columnHeight(height: height, safeTop: 0, safeBottom: safeBottom, compactHeight: false)
        #expect(tall >= 260, "the column stops well short of the bottom bar: \(tall)")
        #expect(tall + 8 + safeBottom + 6 <= height + 0.5, "the column runs under the bottom bar")
        let before = spread(try render(style()), width: columnWidth, height: short)
        let after = spread(try render(style(), compactHeader: true), width: columnWidth, height: tall)
        #expect(!before.overflow && !after.overflow, "a line runs past a column's foot")
        #expect(after.lines.reduce(0, +) >= before.lines.reduce(0, +) + 7,
                "a sideways spread holds \(after.lines) lines, against \(before.lines) before")
        // A tablet or Mac window (regular height) keeps the room for the floating bars.
        #expect(ReaderColumns.verticalInsets(safeTop: 0, safeBottom: 20, compactHeight: false) == (top: 20, bottom: 130))
    }

    /// A highlight wrapping over three lines — starting mid-line, ending mid-line — is one shape:
    /// only its outer corners are rounded, and where one line meets the next the corners stay square.
    @Test func aWrappedHighlightRoundsOnlyItsOuterCorners() {
        let bands = HighlightShape.bands([
            CGRect(x: 50, y: 0, width: 250, height: 28),
            CGRect(x: 0, y: 28, width: 300, height: 28),
            CGRect(x: 0, y: 56, width: 120, height: 28),
        ])
        typealias C = HighlightShape.Corners
        #expect(bands.map(\.corners) == [
            C(topLeft: true, topRight: true, bottomRight: false, bottomLeft: false),
            C(topLeft: true, topRight: false, bottomRight: true, bottomLeft: false),
            C(topLeft: false, topRight: false, bottomRight: true, bottomLeft: true),
        ])
        #expect(bands.allSatisfy { $0.radius >= 4 && $0.radius <= 6 })
        // A verse number's raised run and the words after it on one line are one band, all rounded.
        let line = HighlightShape.bands([CGRect(x: 0, y: 0, width: 12, height: 26), CGRect(x: 12, y: 1, width: 200, height: 27)])
        #expect(line.count == 1)
        #expect(line.first?.corners == .all)
        #expect(line.first?.rect == CGRect(x: 0, y: 0, width: 212, height: 28))
        // Two lines of poetry, 3 points apart, join; a line a paragraph's width away does not.
        let poetry = HighlightShape.bands([CGRect(x: 40, y: 0, width: 300, height: 28),
                                           CGRect(x: 60, y: 31, width: 200, height: 28),
                                           CGRect(x: 60, y: 120, width: 200, height: 28)])
        #expect(poetry[0].rect.maxY == 31)
        #expect(poetry[0].corners == .all)
        #expect(poetry[1].corners == C(topLeft: false, topRight: false, bottomRight: true, bottomLeft: true))
        #expect(poetry[2].corners == .all)
    }

    @Test func noticeLinksBecomeHTTPS() {
        let found = NoticeLinks.find(in: "See www.Lockman.org and http://example.com/x — not mailto:a@b.c")
        #expect(found.map(\.url.absoluteString) == ["https://www.Lockman.org", "https://example.com/x"])
    }
}
