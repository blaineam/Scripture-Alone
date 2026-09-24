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
}
