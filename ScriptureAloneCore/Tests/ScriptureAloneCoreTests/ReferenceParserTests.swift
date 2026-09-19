import Testing
@testable import ScriptureAloneCore

@Suite struct ReferenceParserTests {
    @Test(arguments: [
        ("jn 3 16", "John 3:16"),
        ("John 3:16", "John 3:16"),
        ("jn3:16", "John 3:16"),
        ("Jn. 3.16", "John 3:16"),
        ("rom 8:28-39", "Romans 8:28–39"),
        ("Rom 8:28–39", "Romans 8:28–39"),
        ("1co13", "1 Corinthians 13"),
        ("1 cor 13:4-7", "1 Corinthians 13:4–7"),
        ("I Cor 13", "1 Corinthians 13"),
        ("First John 1:9", "1 John 1:9"),
        ("ii tim 3:16", "2 Timothy 3:16"),
        ("ps 23", "Psalms 23"),
        ("psalm 119:105", "Psalms 119:105"),
        ("ps 23-24", "Psalms 23–24"),
        ("gen 1:1-2:3", "Genesis 1:1–2:3"),
        ("Jude 3", "Jude 3"),
        ("jude 3-5", "Jude 3–5"),
        ("jude", "Jude"),
        ("song of solomon 2:4", "Song of Solomon 2:4"),
        ("phil 4:13", "Philippians 4:13"),
        ("eph", "Ephesians 1"),
        ("rev 22:20", "Revelation 22:20"),
    ])
    func parses(input: String, expected: String) throws {
        let passage = try #require(ReferenceParser.parse(input))
        #expect(passage.display == expected)
    }

    @Test func partialTypingStillResolves() throws {
        #expect(ReferenceParser.parse("rom 8:28-")?.display == "Romans 8:28")
        #expect(ReferenceParser.parse("jo")?.book == .john)
        #expect(ReferenceParser.parse("ph")?.book == .philippians)
        #expect(ReferenceParser.parse("1c")?.book == .firstCorinthians)
    }

    @Test func rejectsNonsense() {
        #expect(ReferenceParser.parse("") == nil)
        #expect(ReferenceParser.parse("zzz 3") == nil)
        #expect(ReferenceParser.parse("3:16") == nil)
    }

    @Test func bookSuggestionsRankExactAbbreviationsFirst() {
        #expect(ReferenceParser.books(matching: "jud").first == .jude)
        #expect(ReferenceParser.books(matching: "judg").first == .judges)
        #expect(ReferenceParser.books(matching: "j").first == .john)
        #expect(ReferenceParser.books(matching: "job").first == .job)
    }

    @Test func parsesLists() {
        let list = ReferenceParser.parseList("Eph 2:1-10; Rom 3:23, 6:23, 25")
        #expect(list.map(\.display) == ["Ephesians 2:1–10", "Romans 3:23", "Romans 6:23", "Romans 6:25"])
        #expect(ReferenceParser.parseList("Ps 23, 24").map(\.display) == ["Psalms 23", "Psalms 24"])
    }

    @Test func wholeChapterResolvesAgainstVerseCount() {
        let passage = ReferenceParser.parse("ps 117")!
        let range = passage.range { _ in 2 }
        #expect(range.display == "Psalms 117:1–2")
    }
}

@Suite struct ReferenceDetectorTests {
    @Test func findsReferencesOnASermonSlide() {
        let slide = """
        The Shepherd Who Pursues
        Psalm 23:1-6 · John 10:11
        Pastor Mark — Week 3 of "I Am"
        See also 1 Pet. 2:25 and Ezekiel 34
        """
        let found = ReferenceDetector.detect(in: slide).map(\.passage.display)
        #expect(found == ["Psalms 23:1–6", "John 10:11", "1 Peter 2:25", "Ezekiel 34"])
    }

    @Test func ignoresOrdinaryWords() {
        let text = "Is 5 people enough? Am 3 years old. We read the book of John 3:16 together."
        #expect(ReferenceDetector.detect(in: text).map(\.passage.display) == ["John 3:16"])
    }
}

@Suite struct VerseRangeTests {
    @Test func collapsesSelectedVersesIntoRanges() {
        let keys = [43003016, 43003017, 43003018, 43003020, 43004001]
        let ranges = VerseRange.ranges(from: keys) { $0 == ChapterRef(.john, 3) ? 36 : 54 }
        #expect(ranges.map(\.display) == ["John 3:16–18", "John 3:20", "John 4:1"])
        let across = VerseRange.ranges(from: [43003036, 43004001]) { _ in 36 }
        #expect(across.map(\.display) == ["John 3:36–4:1"])
    }

    @Test func storageRoundTrips() {
        let range = VerseRange(VerseRef(.romans, 8, 1), VerseRef(.romans, 8, 17))
        #expect(VerseRange(storageString: range.storageString) == range)
    }

    @Test func chapterNavigationCrossesBooks() {
        #expect(ChapterRef(.malachi, 4).next == ChapterRef(.matthew, 1))
        #expect(ChapterRef(.matthew, 1).previous == ChapterRef(.malachi, 4))
        #expect(ChapterRef(.revelation, 22).next == nil)
    }
}
