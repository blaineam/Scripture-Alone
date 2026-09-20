import Testing
@testable import ScriptureAloneCore

/// The parser is the testable half: Crossway's plain-text shape is fixed and documented, and
/// getting it wrong would silently mis-number someone's Sunday passage.
struct ESVClientTests {
    let john3 = ChapterRef(.john, 3)

    @Test func splitsVerses() {
        let body = "[16] For God so loved the world, [17] For God did not send his Son"
        let verses = ESVClient.parse(body, in: john3)
        #expect(verses.count == 2)
        #expect(verses[0].ref.verse == 16)
        #expect(verses[0].text == "For God so loved the world,")
        #expect(verses[1].ref.verse == 17)
    }

    @Test func versesCarryTheChapterTheyWereAskedFor() {
        let verses = ESVClient.parse("[1] In the beginning was the Word", in: ChapterRef(.john, 1))
        #expect(verses.first?.ref == VerseRef(.john, 1, 1))
    }

    @Test func keepsMultiLineVersesTogether() {
        let body = """
        [1] The LORD is my shepherd; I shall not want.
            [2] He makes me lie down in green pastures.
        """
        let verses = ESVClient.parse(body, in: ChapterRef(.psalms, 23))
        #expect(verses.count == 2)
        #expect(verses[1].text == "He makes me lie down in green pastures.")
    }

    /// Text before the first marker belongs to the previous verse. We always request whole
    /// chapters, so it is dropped rather than misattributed to verse 1.
    @Test func dropsTextBeforeTheFirstVerseNumber() {
        let verses = ESVClient.parse("a trailing clause [2] Second verse", in: john3)
        #expect(verses.count == 1)
        #expect(verses[0].ref.verse == 2)
    }

    /// A bracket that isn't a verse number — Crossway uses them for supplied words — stays in
    /// the text rather than starting a phantom verse.
    @Test func leavesNonNumericBracketsInTheText() {
        let verses = ESVClient.parse("[1] Then he said [to them] plainly", in: john3)
        #expect(verses.count == 1)
        #expect(verses[0].text == "Then he said [to them] plainly")
    }

    @Test func emptyTextYieldsNoVerses() {
        #expect(ESVClient.parse("", in: john3).isEmpty)
        #expect(ESVClient.parse("   \n  ", in: john3).isEmpty)
    }

    @Test func skipsAVerseWithNoBody() {
        let verses = ESVClient.parse("[1] Real text [2]   [3] More text", in: john3)
        #expect(verses.map(\.ref.verse) == [1, 3])
    }

    /// Crossway's stated ceiling, and the reason the ESV can't be searched or read offline.
    @Test func cacheLimitMatchesCrosswaysTerms() {
        #expect(ESVClient.cacheVerseLimit == 500)
    }
}
