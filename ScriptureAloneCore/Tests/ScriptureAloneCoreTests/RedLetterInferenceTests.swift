import Foundation
import Testing
@testable import ScriptureAloneCore

/// Carrying the words of Christ from a translation that marks them to one that doesn't.
/// Public-domain text only.
@Suite struct RedLetterInferenceTests {
    private func spans(_ target: String, _ reference: String, red: String) -> [String] {
        let start = reference.unicodeScalars.count - (reference.components(separatedBy: red).last!.unicodeScalars.count + red.unicodeScalars.count)
        let result = RedLetterInference.redSpans(in: target, reference: reference,
                                                 referenceRed: [ScalarSpan(start: start, length: red.unicodeScalars.count)])
        let scalars = Array(target.unicodeScalars)
        return result.map { String(String.UnicodeScalarView(scalars[$0.start..<($0.start + $0.length)])) }
    }

    @Test func spokenWordsFollowTheirAlignment() {
        let reference = "Jesus saith unto him, I am the way, and the truth, and the life: no man cometh unto the Father, but by me."
        let red = "I am the way, and the truth, and the life: no man cometh unto the Father, but by me."
        let target = "Jesus said to him, “I am the way, and the truth, and the life. No one comes to the Father except through me."
        #expect(spans(target, reference, red: red) == ["I am the way, and the truth, and the life. No one comes to the Father except through me."])
    }

    @Test func narrativeStaysBlack() {
        let reference = "Jesus saith unto him, I am the way."
        let target = "There was a man of the Pharisees, named Nicodemus."
        #expect(spans(target, reference, red: "I am the way.").isEmpty)
    }

    @Test func anotherLanguageIsLeftAlone() {
        let reference = "Jesus saith unto him, I am the way, and the truth, and the life."
        let target = "Jésus lui dit: Je suis le chemin, la vérité, et la vie."
        #expect(spans(target, reference, red: "I am the way, and the truth, and the life.").isEmpty)
    }

    @Test func aFileWithItsOwnRedLettersIsNotTouched() throws {
        var bible = try BibleTextExtractor().extract(documents: [(path: "jn.xhtml", xhtml: """
            <html><body><section title="John"><p><span class="chapter-num">14</span> Jesus saith unto him, <span class="wj">I am the way.</span></p></section></body></html>
            """)])
        let before = bible.verses[VerseRef(.john, 14, 1)]?.red
        let marked = bible.inferRedLetters { _ in ("Jesus saith, I am the way.", [ScalarSpan(start: 13, length: 13)]) }
        #expect(marked == 0)
        #expect(bible.verses[VerseRef(.john, 14, 1)]?.red == before)
        #expect(!bible.redLettersInferred)
    }

    @Test func versesAndLayoutBothGetTheWords() throws {
        var bible = try BibleTextExtractor().extract(documents: [(path: "jn.xhtml", xhtml: """
            <html><body><section title="John"><p><span class="chapter-num">14</span> Jesus saith unto him, I am the way.</p></section></body></html>
            """)])
        let marked = bible.inferRedLetters { _ in ("Jesus saith unto him, I am the way.", [ScalarSpan(start: 22, length: 13)]) }
        #expect(marked == 1)
        #expect(bible.redLettersInferred)
        #expect(bible.verses[VerseRef(.john, 14, 1)]?.red == [ScalarSpan(start: 22, length: 13)])
        let fragment = try #require(bible.blocks(for: ChapterRef(.john, 14)).flatMap(\.fragments).first)
        #expect(fragment.red == [ScalarSpan(start: 22, length: 13)])
    }

    @Test func aQuotationTurnsRedWholeAndTheNarrationAroundItStaysBlack() {
        // A parable told across verses, worded unlike the reference in places ("Calling",
        // "come"), and a verse the reference renders too freely to line up at all.
        var bible = ExtractedBible()
        let chapter = ChapterRef(.luke, 19)
        let texts = [
            12: "He said therefore, \u{201C}A nobleman went into a far country to receive for himself a kingdom and then return.",
            13: "Calling ten of his servants, he gave them ten minas, and said to them, \u{2018}Engage in business until I come.\u{2019}",
            14: "But his citizens hated him and sent a delegation after him, saying, \u{2018}We do not want this man to reign over us.\u{2019}\u{201D}",
            15: "When he returned, the people said, \u{201C}Who is this man?\u{201D}",
        ]
        bible.append(ExtractedBlock(kind: .paragraph, fragments: texts.keys.sorted().map {
            ExtractedFragment(verse: $0, numbered: true, text: texts[$0]!)
        }), to: chapter)
        for (verse, text) in texts { bible.appendVerseText(text, red: [], to: VerseRef(.luke, 19, verse)) }
        let reference: [Int: (String, Int?)] = [
            // (text, where the red begins; nil = none)
            12: ("So He said, \u{201C}A man of noble birth went to a distant country to lay claim to his kingship and then return.", 12),
            13: ("Beforehand, he called ten of his servants and gave them ten minas. \u{2018}Conduct business with this until I return,\u{2019} he said.", 0),
            14: ("Something entirely different that lines up with nothing here at all whatsoever.", 0),
            15: ("And the crowd asked who this could possibly be.", nil),
        ]
        bible.inferRedLetters { ref in
            guard let (text, start) = reference[ref.verse] else { return nil }
            guard let start else { return (text, []) }
            return (text, [ScalarSpan(start: start, length: text.unicodeScalars.count - start)])
        }
        func red(_ verse: Int) -> String {
            let text = texts[verse]!
            let scalars = Array(text.unicodeScalars)
            return (bible.verses[VerseRef(.luke, 19, verse)]?.red ?? []).map {
                String(String.UnicodeScalarView(scalars[$0.start..<($0.start + $0.length)]))
            }.joined(separator: "|")
        }
        #expect(red(12) == "\u{201C}A nobleman went into a far country to receive for himself a kingdom and then return.")
        #expect(red(13) == texts[13]!)   // "Calling" and "come" too: they're inside the quotation
        #expect(red(14) == texts[14]!)   // no alignment, but the whole quotation is His
        #expect(red(15) == "")           // someone else's question stays black
        let fragments = bible.blocks(for: chapter).flatMap(\.fragments)
        #expect(fragments.first { $0.verse == 13 }?.red == [ScalarSpan(start: 0, length: texts[13]!.unicodeScalars.count)])
    }

    @Test func aBookWithoutQuotationMarksIsMatchedWordByWord() {
        var bible = ExtractedBible()
        let text = "Jesus saith unto him, I am the way, the truth, and the life."
        bible.append(ExtractedBlock(kind: .paragraph, fragments: [ExtractedFragment(verse: 6, numbered: true, text: text)]),
                     to: ChapterRef(.john, 14))
        bible.appendVerseText(text, red: [], to: VerseRef(.john, 14, 6))
        let source = "Jesus answered, \u{201C}I am the way and the truth and the life.\u{201D}"
        bible.inferRedLetters { _ in (source, [ScalarSpan(start: 16, length: source.unicodeScalars.count - 16)]) }
        let red = bible.verses[VerseRef(.john, 14, 6)]?.red ?? []
        #expect(red.count == 1)
        #expect(red.first?.start == 22)
    }

    @Test func aBookInAnotherLanguageIsLeftAlone() {
        // Quotation marks alone must never colour a text whose words can't be checked.
        var bible = ExtractedBible()
        let text = "Jesus sprach zu ihm: \u{201C}Ich bin der Weg und die Wahrheit und das Leben.\u{201D}"
        bible.append(ExtractedBlock(kind: .paragraph, fragments: [ExtractedFragment(verse: 6, numbered: true, text: text)]),
                     to: ChapterRef(.john, 14))
        bible.appendVerseText(text, red: [], to: VerseRef(.john, 14, 6))
        let source = "Jesus answered, \u{201C}I am the way and the truth and the life.\u{201D}"
        let marked = bible.inferRedLetters { _ in (source, [ScalarSpan(start: 16, length: source.unicodeScalars.count - 16)]) }
        #expect(marked == 0)
        #expect(bible.verses[VerseRef(.john, 14, 6)]?.red.isEmpty == true)
    }
}
