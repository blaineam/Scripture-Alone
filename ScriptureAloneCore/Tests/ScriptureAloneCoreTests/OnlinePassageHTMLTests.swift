import Foundation
import Testing
@testable import ScriptureAloneCore

/// Reading a chapter from a publisher's API with its structure intact.
///
/// **The committed fixtures carry no licensed text.** They reproduce each service's *markup* —
/// which is the thing under test — around wording that is either public domain or invented. A
/// chapter of the ESV or the CSB pasted into a public AGPL repository would be redistribution, and
/// no test is worth that.
///
/// The real captured responses are checked too, by the suite at the bottom, from a directory
/// outside the repository. Those are what the parser was written against; these are what keeps it
/// honest afterwards.
@Suite struct OnlinePassageHTMLTests {

    // MARK: Crossway's ESV markup

    /// Red letters. This is the whole reason the app asks for HTML rather than text — the plain
    /// text endpoint marks the words of Christ not at all.
    @Test func esvWordsOfChristBecomeRedRanges() throws {
        let html = """
        <p class="starts-chapter"><b class="chapter-num">3:1&nbsp;</b>A ruler came by night. \
        <b class="verse-num woc">2&nbsp;</b><span class="woc">Truly, I say to you.</span> \
        <b class="verse-num">3&nbsp;</b>And he answered him.</p>
        """
        let passage = ESVPassageHTML.parse(html, in: ChapterRef(.john, 3))
        #expect(passage.verses.count == 3)
        #expect(passage.verses[0].red.isEmpty)
        #expect(passage.verses[1].text == "Truly, I say to you.")
        #expect(passage.verses[1].red.count == 1)

        // The range must actually cover the quoted words in that verse's own text.
        let verse = passage.verses[1]
        let range = try #require(Range(verse.red[0], in: verse.text))
        #expect(String(verse.text[range]) == "Truly, I say to you.")
        #expect(passage.verses[2].red.isEmpty)
    }

    @Test func esvPoetryBecomesLinesAndAPsalmTitle() {
        let html = """
        <h4 class="psalm-title">A Psalm of David.</h4>
        <p class="block-indent"><span class="begin-line-group"></span>
        <span class="line"><b class="chapter-num">23:1&nbsp;</b>&nbsp;&nbsp;The first line.</span><br />\
        <span class="indent line"><b class="verse-num inline">2&nbsp;</b>&nbsp;&nbsp;&nbsp;&nbsp;An indented line.</span><br />\
        <span class="line">&nbsp;&nbsp;A second line of verse two.</span>
        <span class="end-line-group"></span></p>
        """
        let passage = ESVPassageHTML.parse(html, in: ChapterRef(.psalms, 23))
        let kinds = passage.blocks.map(\.kind)
        #expect(kinds.contains(.title))
        #expect(kinds.contains(.poetry1))
        #expect(kinds.contains(.poetry2))
        // The superscription is a title, not verse text — it must not become part of verse 1.
        #expect(passage.verses.first?.text == "The first line.")
        // Verse 2 spans two poetry lines and reads as one verse.
        #expect(passage.verses.last?.text == "An indented line. A second line of verse two.")
    }

    /// Runs of non-breaking spaces are indentation, and the reader does its own indenting.
    @Test func esvIndentationDoesNotSurviveAsText() {
        let html = #"<p><b class="verse-num">1&nbsp;</b>&nbsp;&nbsp;&nbsp;Spaced&nbsp;&nbsp;out.</p>"#
        let passage = ESVPassageHTML.parse(html, in: ChapterRef(.john, 1))
        #expect(passage.verses.first?.text == "Spaced out.")
    }

    /// A words-of-Christ span ending in whitespace at a verse boundary — closed by its own tag, or
    /// still open when the next verse number arrives — must survive the fragment's trailing trim.
    /// Closing it before trimming left it one scalar past the verse text, and `finish()` dropped it.
    @Test func esvRedLettersEndingInWhitespaceSurviveTheVerseBoundary() throws {
        let closed = """
        <p><b class="verse-num">1&nbsp;</b>He said, <span class="woc">Follow me. </span>\
        <b class="verse-num">2&nbsp;</b>And they went.</p>
        """
        let passage = ESVPassageHTML.parse(closed, in: ChapterRef(.john, 1))
        #expect(passage.verses.count == 2)
        let first = try #require(passage.verses.first)
        #expect(first.text == "He said, Follow me.")
        let red = try #require(first.red.first, "the red span was dropped")
        let range = try #require(Range(red, in: first.text))
        #expect(String(first.text[range]) == "Follow me.")
        #expect(passage.verses[1].red.isEmpty)

        let open = """
        <p><b class="verse-num">1&nbsp;</b>He said, <span class="woc">Follow me. \
        <b class="verse-num">2&nbsp;</b>Come and see.</span> And they went.</p>
        """
        let carried = ESVPassageHTML.parse(open, in: ChapterRef(.john, 1))
        #expect(carried.verses.count == 2)
        for (verse, quoted) in zip(carried.verses, ["Follow me.", "Come and see."]) {
            let red = try #require(verse.red.first, "\(verse.ref) lost its red letters")
            let range = try #require(Range(red, in: verse.text))
            #expect(String(verse.text[range]) == quoted)
        }
    }

    // MARK: API.Bible markup

    @Test func apiBibleWordsOfJesusBecomeRedRanges() throws {
        let html = """
        <p class="p"><span data-number="16" data-sid="JHN 3:16" class="v">16</span>\
        <span class="wj">For God loved the world.</span></p>
        """
        let passage = APIBiblePassageHTML.parse(html, in: ChapterRef(.john, 3))
        let verse = try #require(passage.verses.first)
        #expect(verse.ref == VerseRef(.john, 3, 16))
        #expect(verse.text == "For God loved the world.")
        let range = try #require(Range(verse.red.first ?? NSRange(), in: verse.text))
        #expect(String(verse.text[range]) == "For God loved the world.")
    }

    @Test func apiBibleRedLettersEndingInWhitespaceSurvive() throws {
        let html = """
        <p class="p"><span data-number="16" data-sid="JHN 3:16" class="v">16</span>\
        <span class="wj">For God loved the world. </span></p>\
        <p class="p"><span data-number="17" data-sid="JHN 3:17" class="v">17</span>Next. </p>
        """
        let passage = APIBiblePassageHTML.parse(html, in: ChapterRef(.john, 3))
        let verse = try #require(passage.verses.first)
        #expect(verse.text == "For God loved the world.")
        let red = try #require(verse.red.first, "the red span was dropped")
        let range = try #require(Range(red, in: verse.text))
        #expect(String(verse.text[range]) == "For God loved the world.")
    }

    /// A heading's words go on the block, the way the bundled stores keep them — not in fragments,
    /// which the layout writer never writes for a heading.
    @Test func apiBibleHeadingsCarryTheirWordsOnTheBlock() throws {
        let html = """
        <p class="s1">A Heading</p><p class="p">\
        <span data-number="1" data-sid="JHN 1:1" class="v">1</span>In the beginning.</p>
        """
        let passage = APIBiblePassageHTML.parse(html, in: ChapterRef(.john, 1))
        let heading = try #require(passage.blocks.first)
        #expect(heading.kind == .heading)
        #expect(heading.heading == "A Heading")
        #expect(heading.fragments.isEmpty)
    }

    /// The markers are USFM, which is the vocabulary this app's own layout already speaks.
    @Test func apiBibleClassesMapOntoTheAppsBlockKinds() {
        let html = """
        <p class="s1">The Good Shepherd</p><p class="d">A psalm of David.</p>\
        <p class="q1"><span data-number="1" data-sid="PSA 23:1" class="v">1</span>The first line; </p>\
        <p data-vid="PSA 23:1" class="q">the rest of verse one. </p>\
        <p class="b"></p>\
        <p class="q1"><span data-number="2" data-sid="PSA 23:2" class="v">2</span>Verse two. </p>
        """
        let passage = APIBiblePassageHTML.parse(html, in: ChapterRef(.psalms, 23))
        let kinds = passage.blocks.map(\.kind)
        #expect(kinds.contains(.heading))
        #expect(kinds.contains(.title))
        #expect(kinds.contains(.poetry1))
        #expect(kinds.contains(.stanzaBreak))
        // `data-vid` is how an unnumbered continuation line says which verse it belongs to.
        // Without honouring it that line would be attributed to no verse at all.
        #expect(passage.verses.first?.text == "The first line; the rest of verse one.")
        #expect(passage.verses.count == 2)
    }

    @Test func apiBibleHeadingsAreNotVerseText() {
        let html = """
        <p class="s1">A Heading</p><p class="p">\
        <span data-number="1" data-sid="JHN 1:1" class="v">1</span>In the beginning.</p>
        """
        let passage = APIBiblePassageHTML.parse(html, in: ChapterRef(.john, 1))
        #expect(passage.verses.count == 1)
        #expect(passage.verses[0].text == "In the beginning.")
        #expect(!passage.verses[0].text.contains("Heading"))
    }

    // MARK: The real responses

    /// Captured from both services and kept outside the repository, because they are licensed
    /// text. Present on the machine that captured them; skipped everywhere else.
    static let captures = URL(fileURLWithPath: NSHomeDirectory())
        .appending(path: ".scripture-alone-import/online")

    static func capture(_ name: String) -> String? {
        try? String(contentsOf: captures.appending(path: name), encoding: .utf8)
    }

    @Test func theRealESVResponsesParse() throws {
        guard let john = Self.capture("esv-JHN3.html"),
              let psalm = Self.capture("esv-PSA23.html"),
              let matthew = Self.capture("esv-MAT5.html") else { return }

        let john3 = ESVPassageHTML.parse(john, in: ChapterRef(.john, 3))
        #expect(john3.verses.count == 36)
        #expect(john3.verses.contains { !$0.red.isEmpty })
        // Every red range must be a valid range in the verse it belongs to.
        for verse in john3.verses {
            for range in verse.red { #expect(Range(range, in: verse.text) != nil) }
        }
        // No markup may reach a reader.
        for verse in john3.verses {
            #expect(!verse.text.contains("<"))
            #expect(!verse.text.contains("&nbsp;"))
        }

        let psalm23 = ESVPassageHTML.parse(psalm, in: ChapterRef(.psalms, 23))
        #expect(psalm23.verses.count == 6)
        #expect(psalm23.blocks.contains { $0.kind == .title })
        #expect(psalm23.blocks.contains { $0.kind == .poetry1 })

        // The Sermon on the Mount is nearly all red.
        let matthew5 = ESVPassageHTML.parse(matthew, in: ChapterRef(.matthew, 5))
        #expect(matthew5.verses.count == 48)
        let reddened = matthew5.verses.filter { !$0.red.isEmpty }.count
        #expect(reddened > 40, "only \(reddened) of \(matthew5.verses.count) verses carry red")
    }

    @Test func theRealAPIBibleResponsesParse() throws {
        guard let john = Self.capture("apibible-JHN3.html"),
              let psalm = Self.capture("apibible-PSA23.html") else { return }

        let john3 = APIBiblePassageHTML.parse(john, in: ChapterRef(.john, 3))
        #expect(john3.verses.count == 36)
        #expect(john3.verses.contains { !$0.red.isEmpty })
        for verse in john3.verses {
            #expect(!verse.text.contains("<"))
            for range in verse.red { #expect(Range(range, in: verse.text) != nil) }
        }

        let psalm23 = APIBiblePassageHTML.parse(psalm, in: ChapterRef(.psalms, 23))
        #expect(psalm23.verses.count == 6)
        #expect(psalm23.blocks.contains { $0.kind == .title })
        #expect(psalm23.blocks.contains { $0.kind == .poetry1 })
        // Every verse must have landed somewhere; a dropped continuation line is silent data loss.
        #expect(psalm23.verses.allSatisfy { !$0.text.isEmpty })
    }
}
