import Testing
@testable import ScriptureAloneCore

/// Fixtures shaped like what Vision returns for photographed sermon slides: one line each,
/// boxes normalized with the origin at the top-left.
@Suite struct SlideParserTests {
    /// A typical title slide: church logo text, big title, passage, speaker, date.
    static let titleSlide: [SlideLine] = [
        SlideLine("Grace Community Church", x: 0.06, y: 0.05, width: 0.30, height: 0.035),
        SlideLine("THE SHEPHERD WHO", x: 0.18, y: 0.26, width: 0.64, height: 0.11),
        SlideLine("PURSUES", x: 0.34, y: 0.39, width: 0.32, height: 0.11),
        SlideLine("Psalm 23:1–6 · John 10:11", x: 0.30, y: 0.56, width: 0.40, height: 0.05),
        SlideLine("Pastor Mark Ellis — Week 3 of “I Am”", x: 0.28, y: 0.66, width: 0.44, height: 0.035),
        SlideLine("Sunday, September 14, 2026", x: 0.36, y: 0.88, width: 0.28, height: 0.03),
        SlideLine("gracecommunity.church", x: 0.70, y: 0.93, width: 0.24, height: 0.025),
    ]

    @Test func titleSlideBecomesANote() {
        let reading = SlideParser.read(Self.titleSlide)
        #expect(reading.title == "The Shepherd Who Pursues")
        #expect(reading.passages.map(\.display) == ["Psalms 23:1–6", "John 10:11"])
        #expect(reading.bodyLines == ["Pastor Mark Ellis — Week 3 of \"I Am\""])
    }

    /// A three-character Chinese title is a whole title, not noise too short to keep.
    @Test func shortChineseTitleIsKept() {
        let slide: [SlideLine] = [
            SlideLine("好牧人", x: 0.10, y: 0.20, width: 0.25, height: 0.10),
            SlideLine("他按着名叫自己的羊", x: 0.10, y: 0.45, width: 0.40, height: 0.04),
            SlideLine("好牧人为羊舍命", x: 0.10, y: 0.52, width: 0.34, height: 0.04),
            SlideLine("一群羊，一个牧人", x: 0.10, y: 0.59, width: 0.36, height: 0.04),
        ]
        let reading = SlideParser.read(slide)
        #expect(reading.title == "好牧人")
        #expect(reading.bodyLines.contains("好牧人为羊舍命"))
    }

    @Test func pointSlideKeepsItsLinesInReadingOrder() {
        // A later slide: a heading, three points with a passage, a slide number.
        let slide: [SlideLine] = [
            SlideLine("The Shepherd Who Pursues", x: 0.05, y: 0.04, width: 0.35, height: 0.03),
            SlideLine("Three Marks of a Good Shepherd", x: 0.10, y: 0.16, width: 0.80, height: 0.08),
            SlideLine("1. He knows his sheep by name", x: 0.12, y: 0.34, width: 0.60, height: 0.045),
            SlideLine("2. He lays down his life (Jn 10:15)", x: 0.12, y: 0.44, width: 0.66, height: 0.045),
            SlideLine("3. He goes after the one who wanders", x: 0.12, y: 0.54, width: 0.70, height: 0.045),
            SlideLine("vv. 4-7", x: 0.80, y: 0.54, width: 0.10, height: 0.045),
            SlideLine("7", x: 0.94, y: 0.94, width: 0.02, height: 0.03),
        ]
        let reading = SlideParser.read(slide)
        #expect(reading.title == "Three Marks of a Good Shepherd")
        #expect(reading.passages.map(\.display) == ["John 10:15"])
        #expect(reading.bodyLines == [
            "The Shepherd Who Pursues",
            "1. He knows his sheep by name",
            "2. He lays down his life",
            "3. He goes after the one who wanders",
        ])
    }

    @Test func referenceSplitAcrossLinesIsFound() {
        let slide: [SlideLine] = [
            SlideLine("Love Is Patient", x: 0.2, y: 0.2, width: 0.6, height: 0.12),
            SlideLine("1 Corinthians", x: 0.35, y: 0.45, width: 0.3, height: 0.05),
            SlideLine("13:4–7", x: 0.42, y: 0.51, width: 0.16, height: 0.05),
        ]
        let reading = SlideParser.read(slide)
        #expect(reading.title == "Love Is Patient")
        #expect(reading.passages.map(\.display) == ["1 Corinthians 13:4–7"])
        #expect(reading.bodyLines.isEmpty)
    }

    @Test(arguments: [
        ("Jn 3:16", "John 3:16"),
        ("John 3 : 16", "John 3:16"),
        ("1 Cor 13:4–7", "1 Corinthians 13:4–7"),
        ("1 Cor. 13:4—7", "1 Corinthians 13:4–7"),
        ("Romans 8, vv. 1-17", "Romans 8:1–17"),
        ("Romans 8 verses 28–39", "Romans 8:28–39"),
        ("Text: Isaiah 40:28‑31", "Isaiah 40:28–31"),
        ("Luke 15;4", "Luke 15:4"),
        ("II Timothy 3:16-17", "2 Timothy 3:16–17"),
        ("(n 10:3)", "John 10:3"),
        ("In 3:16", "John 3:16"),
    ])
    func ocrQuirks(input: String, expected: String) {
        let reading = SlideParser.read([
            SlideLine("A Title", x: 0.1, y: 0.1, width: 0.8, height: 0.1),
            SlideLine(input, x: 0.1, y: 0.4, width: 0.5, height: 0.05),
        ])
        #expect(reading.passages.map(\.display) == [expected])
        #expect(reading.bodyLines.isEmpty, "\(input) left \(reading.bodyLines)")
    }

    @Test func titleSkipsBoilerplateEvenWhenItIsLarger() {
        let slide: [SlideLine] = [
            SlideLine("WELCOME", x: 0.2, y: 0.08, width: 0.6, height: 0.16),
            SlideLine("First Baptist Church of Springfield", x: 0.2, y: 0.28, width: 0.6, height: 0.09),
            SlideLine("Fear Not", x: 0.3, y: 0.45, width: 0.4, height: 0.08),
            SlideLine("Isaiah 41:10", x: 0.35, y: 0.58, width: 0.3, height: 0.04),
            SlideLine("CCLI License #1234567", x: 0.05, y: 0.95, width: 0.3, height: 0.02),
            SlideLine("9/14/26 · 10:30 AM", x: 0.7, y: 0.95, width: 0.25, height: 0.02),
        ]
        let reading = SlideParser.read(slide)
        #expect(reading.title == "Fear Not")
        #expect(reading.passages.map(\.display) == ["Isaiah 41:10"])
        #expect(reading.bodyLines.isEmpty)
    }

    @Test(arguments: ["unday, September 14, 2026", "Sunday, September 14, 2026", "Easter Sunday · April 5",
                      "9/14/26 · 10:30 AM", "14 September 2026", "Sept. 14"])
    func datesAreBoilerplate(line: String) {
        let reading = SlideParser.read([
            SlideLine("Fear Not", x: 0.1, y: 0.2, width: 0.8, height: 0.1),
            SlideLine(line, x: 0.1, y: 0.9, width: 0.4, height: 0.03),
        ])
        #expect(reading.bodyLines.isEmpty, "\(line) was kept")
    }

    @Test func sentencesMentioningADateStay() {
        let reading = SlideParser.read([
            SlideLine("Fear Not", x: 0.1, y: 0.2, width: 0.8, height: 0.1),
            SlideLine("Baptism class meets Sunday, October 5", x: 0.1, y: 0.6, width: 0.6, height: 0.04),
        ])
        #expect(reading.bodyLines == ["Baptism class meets Sunday, October 5"])
    }

    @Test func titleAndPassageOnOneLine() {
        let reading = SlideParser.read([
            SlideLine("“Fear Not” — Isaiah 41:10", x: 0.1, y: 0.3, width: 0.8, height: 0.1),
            SlideLine("God is with you in the storm", x: 0.2, y: 0.5, width: 0.6, height: 0.05),
        ])
        #expect(reading.title == "Fear Not")
        #expect(reading.passages.map(\.display) == ["Isaiah 41:10"])
        #expect(reading.bodyLines == ["God is with you in the storm"])
    }

    @Test func titlesLabeledOrAChurchWordInside() {
        let reading = SlideParser.read([
            SlideLine("Sermon: The Church That Prays", x: 0.1, y: 0.3, width: 0.8, height: 0.1),
            SlideLine("Acts 12:1-17", x: 0.3, y: 0.5, width: 0.4, height: 0.05),
        ])
        #expect(reading.title == "The Church That Prays")
    }

    @Test func dedupesPassagesAndRepeatedLines() {
        let reading = SlideParser.read([
            SlideLine("Living Hope", x: 0.1, y: 0.1, width: 0.8, height: 0.1),
            SlideLine("1 Peter 1:3-9", x: 0.1, y: 0.3, width: 0.4, height: 0.05),
            SlideLine("Born again to a living hope", x: 0.1, y: 0.4, width: 0.6, height: 0.05),
            SlideLine("born again to a living hope", x: 0.1, y: 0.5, width: 0.6, height: 0.05),
            SlideLine("1 Pet. 1:3–9", x: 0.1, y: 0.6, width: 0.4, height: 0.05),
            SlideLine("LIVING HOPE", x: 0.1, y: 0.9, width: 0.3, height: 0.03),
        ])
        #expect(reading.passages.map(\.display) == ["1 Peter 1:3–9"])
        #expect(reading.bodyLines == ["Born again to a living hope"])
    }

    @Test func lowConfidenceAndTinyFragmentsAreDropped() {
        let reading = SlideParser.read([
            SlideLine("Rest for the Weary", x: 0.1, y: 0.2, width: 0.8, height: 0.1),
            SlideLine("Matthew 11:28-30", x: 0.3, y: 0.4, width: 0.4, height: 0.05),
            SlideLine("~ ~", x: 0.5, y: 0.6, width: 0.05, height: 0.03),
            SlideLine("Ok", x: 0.5, y: 0.7, width: 0.05, height: 0.03),
            SlideLine("zqxv wmmrr", x: 0.5, y: 0.8, width: 0.2, height: 0.03, confidence: 0.1),
            SlideLine("12 / 40", x: 0.9, y: 0.95, width: 0.05, height: 0.02),
        ])
        #expect(reading.title == "Rest for the Weary")
        #expect(reading.bodyLines.isEmpty)
    }

    @Test func plainTextFallback() {
        let reading = SlideParser.read(text: "Welcome\nThe Good Shepherd\nJohn 10:1-18\nWe are known")
        #expect(reading.title == "The Good Shepherd")
        #expect(reading.passages.map(\.display) == ["John 10:1–18"])
        #expect(reading.bodyLines == ["We are known"])
    }

    @Test func rangesClampToRealVerseCounts() {
        let counts: [ChapterRef: Int] = [ChapterRef(.psalms, 23): 6, ChapterRef(.john, 3): 36,
                                         ChapterRef(.genesis, 1): 31, ChapterRef(.genesis, 2): 25]
        let passages = [
            Passage(book: .psalms, startChapter: 23, startVerse: 1, endChapter: 23, endVerse: 66),
            Passage(book: .psalms, startChapter: 23),
            Passage(book: .john, startChapter: 3, startVerse: 16),
            Passage(book: .john, startChapter: 3, startVerse: 16),
            Passage(book: .genesis, startChapter: 1, startVerse: 1, endChapter: 2, endVerse: 3),
        ]
        let ranges = SlideParser.ranges(for: passages) { counts[$0] ?? 0 }
        #expect(ranges.map(\.display) == ["Psalms 23:1–6", "John 3:16", "Genesis 1:1–2:3"])
    }

    @Test func appendingSkipsWhatTheNoteAlreadySays() {
        let body = SlideParser.body(for: ["Pastor Mark Ellis"])
        #expect(body == "• Pastor Mark Ellis")
        let next = SlideParser.append(heading: "Three Marks of a Good Shepherd",
                                      lines: ["Pastor Mark Ellis", "He knows his sheep", "He knows his sheep"], to: body)
        #expect(next == "• Pastor Mark Ellis\n\nThree Marks of a Good Shepherd\n• He knows his sheep")
        #expect(SlideParser.append(heading: nil, lines: ["He knows his sheep"], to: next) == next)
        #expect(SlideParser.append(heading: nil, lines: ["First line"], to: "") == "• First line")
        #expect(SlideParser.append(heading: nil, lines: ["THE SHEPHERD WHO PURSUES"], to: body,
                                   title: "The Shepherd Who Pursues") == body)
        #expect(SlideParser.noteAlreadyHas("The Shepherd Who Pursues", title: "The Shepherd Who Pursues", body: ""))
        #expect(SlideParser.noteAlreadyHas("pastor mark ellis", title: "", body: body))
        #expect(!SlideParser.noteAlreadyHas("He knows his sheep", title: "", body: body))
    }

    @Test func ordinaryInIsNotJohn() {
        let reading = SlideParser.read(text: "Grace Abounds\nIn 3 ways God meets us")
        #expect(reading.passages.isEmpty)
        #expect(reading.bodyLines == ["In 3 ways God meets us"])
    }

    @Test func mergingDropsCoveredRanges() {
        let verse = VerseRange(VerseRef(.john, 10, 11))
        let span = VerseRange(VerseRef(.john, 10, 11), VerseRef(.john, 10, 15))
        let psalm = VerseRange(VerseRef(.psalms, 23, 1), VerseRef(.psalms, 23, 6))
        #expect(SlideParser.merging([psalm, verse], [span, psalm]) == [psalm, span])
    }

    @Test func readingOrderGroupsRows() {
        let ordered = SlideParser.readingOrder([
            SlideLine("right", x: 0.6, y: 0.301, width: 0.3, height: 0.05),
            SlideLine("below", x: 0.1, y: 0.5, width: 0.3, height: 0.05),
            SlideLine("left", x: 0.1, y: 0.3, width: 0.3, height: 0.05),
        ])
        #expect(ordered.map(\.text) == ["left", "right", "below"])
    }
}
