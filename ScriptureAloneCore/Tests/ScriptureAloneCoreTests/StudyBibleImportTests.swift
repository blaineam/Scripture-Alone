import Foundation
import Testing
@testable import ScriptureAloneCore

/// Study-Bible ePub shapes: files split into scripture, study notes, footnotes and cross
/// references; chapter numbers set as drop caps; essays boxed into the text. Text is public domain
/// or invented.
@Suite struct StudyBibleImportTests {
    private func extract(_ documents: [(String, String)]) throws -> ExtractedBible {
        try BibleTextExtractor().extract(documents: documents.map { (path: $0.0, xhtml: page($0.1)) })
    }

    private func page(_ body: String) -> String {
        "<?xml version=\"1.0\"?><html xmlns:epub=\"http://www.idpf.org/2007/ops\"><body>\(body)</body></html>"
    }

    private func text(_ bible: ExtractedBible, _ book: BookID, _ chapter: Int, _ verse: Int) -> String? {
        bible.verses[VerseRef(book, chapter, verse)]?.text
    }

    @Test func aDropCapChapterNumberStartsVerseOne() throws {
        let bible = try extract([("ruth.xhtml", """
            <section title="Ruth"><p class="p-first"><span class="chapter-num">1</span> And it came to pass in the days
            when the judges judged. <span class="verse-num">2</span> And the name of the man was Elimelech.</p>
            <p class="p-first"><span class="chapter-num">2</span> And Naomi had a kinsman. <span class="verse-num">2</span> And Ruth said.</p></section>
            """)])
        #expect(text(bible, .ruth, 1, 1) == "And it came to pass in the days when the judges judged.")
        #expect(text(bible, .ruth, 2, 1) == "And Naomi had a kinsman.")
        #expect(bible.verseNumbers(in: ChapterRef(.ruth, 2)) == [1, 2])
    }

    @Test func aPrintedVerseOneAfterTheDropCapIsTheSameVerse() throws {
        let bible = try extract([("ruth.xhtml", """
            <section title="Ruth"><p><span class="chapter-num">1</span> <span class="verse-num">1</span> And it came to pass.
            <span class="verse-num">2</span> And the name.</p></section>
            """)])
        #expect(text(bible, .ruth, 1, 1) == "And it came to pass.")
        #expect(bible.verseNumbers(in: ChapterRef(.ruth, 1)) == [1, 2])
    }

    @Test func theSectionTitleNamesTheBook() throws {
        let bible = try extract([
            ("a.xhtml", "<section title=\"Obadiah\"><p><span class=\"verse-num\">1</span> The vision of Obadiah.</p></section>"),
            ("b.xhtml", "<section title=\"Jonah\"><p><span class=\"verse-num\">1</span> Now the word of Jehovah came unto Jonah.</p></section>"),
        ])
        #expect(text(bible, .obadiah, 1, 1) == "The vision of Obadiah.")
        #expect(text(bible, .jonah, 1, 1) == "Now the word of Jehovah came unto Jonah.")
    }

    @Test func aTitleNamingABookAlreadyReadIsASlip() throws {
        let bible = try extract([
            ("gen.xhtml", "<section title=\"Genesis\"><p><span class=\"chapter-num\">1</span> In the beginning.</p></section>"),
            ("ex.xhtml", "<section title=\"Exodus\"><p><span class=\"chapter-num\">1</span> Now these are the names.</p></section>"),
            ("ex2.xhtml", "<section title=\"Genesis\"><p><span class=\"chapter-num\">2</span> And there went a man.</p></section>"),
        ])
        #expect(text(bible, .exodus, 2, 1) == "And there went a man.")
        #expect(bible.verseNumbers(in: ChapterRef(.genesis, 2)).isEmpty)
    }

    @Test func anIdThatContradictsTheTitleDoesNotMove() throws {
        let bible = try extract([("3jn.xhtml", """
            <section title="3 John"><div id="john3"><p><span class="verse-num">1</span> The elder unto Gaius the beloved.</p></div></section>
            """)])
        #expect(text(bible, .thirdJohn, 1, 1) == "The elder unto Gaius the beloved.")
        #expect(!bible.books.contains(.john))
    }

    @Test func aTitleHoldingTheChapterNumberIsNotAHeading() throws {
        let bible = try extract([("ps.xhtml", """
            <section title="Psalms"><p class="psalm-title"><span class="chapter-num">1</span> Blessed is the man</p>
            <p class="poetry">That walketh not in the counsel of the wicked. <span class="verse-num">2</span> But his delight.</p>
            <p class="psalm-acrostic-title">ALEPH</p>
            <p class="psalm-title-2"><span class="chapter-num">2</span> Why do the nations rage?</p></section>
            """)])
        #expect(text(bible, .psalms, 1, 1) == "Blessed is the man That walketh not in the counsel of the wicked.")
        #expect(text(bible, .psalms, 2, 1) == "Why do the nations rage?")
        #expect(bible.blocks(for: ChapterRef(.psalms, 2)).contains { $0.heading == "ALEPH" })
    }

    @Test func aHeadingBelongsToTheChapterItIntroduces() throws {
        let bible = try extract([("gen.xhtml", """
            <section title="Genesis"><p class="heading">The Creation</p>
            <p><span class="chapter-num">1</span> In the beginning. <span class="verse-num">2</span> And the earth.</p>
            <p class="heading">The Garden</p>
            <p><span class="chapter-num">2</span> And the heavens were finished.</p></section>
            """)])
        #expect(bible.blocks(for: ChapterRef(.genesis, 1)).first?.heading == "The Creation")
        #expect(bible.blocks(for: ChapterRef(.genesis, 1)).allSatisfy { $0.heading != "The Garden" })
        #expect(bible.blocks(for: ChapterRef(.genesis, 2)).first?.heading == "The Garden")
    }

    @Test func aChapterNumberSetBeforeTheLastVerseOfTheChapterBefore() throws {
        let bible = try extract([("jn.xhtml", """
            <section title="John"><p><span class="chapter-num">7</span> After these things. <span class="verse-num">2</span> Now the feast.</p>
            <p><span class="chapter-num">8</span><span class="verse-num">3</span> And they went every man unto his own house:
            <span class="verse-num">1</span> but Jesus went unto the mount of Olives. <span class="verse-num">2</span> And early.</p></section>
            """)])
        #expect(text(bible, .john, 7, 3) == "And they went every man unto his own house:")
        #expect(bible.verseNumbers(in: ChapterRef(.john, 8)) == [1, 2])
    }

    @Test func notesPagesAndIntroductionsAddNoText() throws {
        let bible = try extract([
            ("rom.xhtml", "<section title=\"Romans\"><p><span class=\"chapter-num\">1</span> Paul, a servant. <span class=\"verse-num\">2</span> Which he promised.</p></section>"),
            ("rom_notes.xhtml", """
                <section title="Romans"><p class="notesubhead">FOOTNOTES FOR ROMANS 1</p>
                <p class="note"><a href="rom.xhtml#nb1" id="nc1">1</a> Or <i>bondservant</i> <sup>2</sup></p>
                <p class="note"><a href="rom.xhtml#nb2" id="nc2">2</a> Or <i>beforehand</i></p></section>
                """),
            ("intro.xhtml", "<section title=\"Introduction\"><p>Author. The apostle wrote this letter.</p></section>"),
        ])
        #expect(text(bible, .romans, 1, 2) == "Which he promised.")
        #expect(bible.verseCount == 2)
    }

    @Test func essaysCrossReferencesCaptionsAndNavigationAreNotScripture() throws {
        let bible = try extract([("rom.xhtml", """
            <section title="Romans"><p><span class="chapter-num">1</span> Paul, <span class="crossref"><a epub:type="noteref" href="#cr1">a</a></span> a servant.</p>
            <div class="gbox"><p class="theohead">GRACE</p><p class="theobody">An essay about grace.</p></div>
            <p class="image"><img src="map.jpg"/><span>A map of Rome</span></p>
            <p><span class="verse-num">2</span> Which he promised.</p>
            <p class="centerr"><a href="1corintro.xhtml">Book of 1 Corinthians ⇨</a></p>
            <aside epub:type="footnote" id="cr1"><p>a Acts 9:15</p></aside></section>
            """)])
        #expect(text(bible, .romans, 1, 1) == "Paul, a servant.")
        #expect(text(bible, .romans, 1, 2) == "Which he promised.")
        let footnotes = bible.blocks(for: ChapterRef(.romans, 1)).flatMap(\.fragments).flatMap(\.footnotes)
        #expect(footnotes.isEmpty)
    }

    @Test func translationFootnotesStayAndStudyNotesGo() throws {
        let bible = try extract([("rom.xhtml", """
            <section title="Romans"><p><span class="chapter-num">1</span><a epub:type="noteref" href="#vc1">[✞]</a> Paul, a servant<span class="note"><a epub:type="noteref" href="#nc1">1</a></span> of Christ.</p>
            <aside epub:type="footnote" id="vc1"><p><b>ROMANS 1:1 Paul.</b> Ancient letters began with a formula.</p></aside>
            <aside epub:type="footnote" id="nc1"><p>1 Or <i>bondservant</i></p></aside></section>
            """)])
        let notes = bible.blocks(for: ChapterRef(.romans, 1)).flatMap(\.fragments).flatMap(\.footnotes).map(\.text)
        #expect(notes == ["Or bondservant"])
        #expect(text(bible, .romans, 1, 1) == "Paul, a servant of Christ.")
    }

    @Test func boldNumbersAreVersesWhenNothingBetterIsThere() throws {
        let bible = try extract([("gal.xhtml", """
            <section title="Galatians"><p><span class="chapter-num">1</span> Paul, an apostle, <span class="b">2</span> and all the brethren.
            <span class="b">3</span> Grace to you.</p></section>
            """)])
        #expect(bible.verseNumbers(in: ChapterRef(.galatians, 1)) == [1, 2, 3])
        #expect(text(bible, .galatians, 1, 2) == "and all the brethren.")
    }

    @Test func aConcordanceIsNotScripture() throws {
        let bible = try extract([
            ("rev.xhtml", "<section title=\"Revelation\"><p><span class=\"chapter-num\">1</span> The Revelation of Jesus Christ.</p></section>"),
            ("conc.xhtml", """
                <section title="Concordance"><p>ABIDE <b>Ps</b> <b>15</b>:1 who shall a in your tent • <b>John</b> <b>15</b>:4 A in me</p>
                <p>ABRAHAM <b>Gen</b> <b>17</b>:5 your name shall be A • <b>Rom</b> <b>4</b>:3 A believed God</p></section>
                """),
        ])
        #expect(text(bible, .revelation, 1, 1) == "The Revelation of Jesus Christ.")
        #expect(bible.verseCount == 1)
    }

    @Test func indentedPoetryIsTheSecondLevel() throws {
        let bible = try extract([("ps.xhtml", """
            <section title="Psalms"><p class="poetry"><span class="chapter-num">1</span> Blessed is the man</p>
            <p class="poetry-indent">That walketh not in the counsel of the wicked.</p></section>
            """)])
        #expect(bible.blocks(for: ChapterRef(.psalms, 1)).map(\.kind) == [.poetry1, .poetry2])
    }

    // MARK: Red letters

    @Test func classesTheStylesheetColorsRedAreWordsOfChrist() throws {
        let css = """
            /* words of Christ */
            span.sgc-7 { color: #c8102e; }
            .quiet, p.note { color: #5a5a5a }
            .jesus{color:rgb(190, 20, 20) !important}
            div .nested { color: red }
            """
        #expect(DocumentScanner.redClasses(inStylesheets: [css]) == ["sgc-7", "jesus"])
        let scanner = DocumentScanner(options: .init(), styledRedClasses: ["sgc-7"])
        var assembler = Assembler(options: .init())
        assembler.consume(scanner.scan(page("""
            <section title="John"><p><span class="chapter-num">14</span> Jesus saith unto him, <span class="sgc-7">I am the way.</span></p></section>
            """)), path: "jn.xhtml")
        assembler.finish()
        let verse = try #require(assembler.bible.verses[VerseRef(.john, 14, 1)])
        #expect(verse.red.count == 1)
    }

    @Test func inlineRedStyleIsWordsOfChrist() {
        #expect(DocumentScanner.isRed(style: "font-weight:bold; color: #C00"))
        #expect(DocumentScanner.isRed(style: "color:darkred"))
        #expect(!DocumentScanner.isRed(style: "background-color: red"))
        #expect(!DocumentScanner.isRed(style: "color: #5a5a5a"))
    }
}
