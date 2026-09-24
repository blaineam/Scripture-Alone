import Foundation
import Testing
@testable import ScriptureAloneCore

/// A study Bible's own material — notes, introductions, essays, pictures — kept apart from the
/// text and written beside it. Text is public domain or invented.
@Suite struct StudyMaterialImportTests {
    private func page(_ body: String) -> String {
        "<?xml version=\"1.0\"?><html xmlns:epub=\"http://www.idpf.org/2007/ops\"><body>\(body)</body></html>"
    }

    private func extract(_ documents: [(String, String)]) throws -> ExtractedBible {
        try BibleTextExtractor().extract(documents: documents.map { (path: $0.0, xhtml: page($0.1)) })
    }

    private let intro = ("rom-intro.xhtml", """
        <section title="Romans"><p class="heading">INTRODUCTION TO ROMANS</p>
        <p>Paul wrote this letter to the church at Rome before he had visited it, setting out the gospel he preached
        and the way it joins Jew and Gentile in one people.</p></section>
        """)

    private let text = ("rom.xhtml", """
        <section title="Romans"><p><span class="chapter-num">1</span><a epub:type="noteref" href="#vc1">[✞]</a> Paul, a servant of Christ.
        <span class="verse-num">2</span><a epub:type="noteref" href="#vc1">[✞]</a> Which he promised afore.</p>
        <div class="gbox"><p class="theohead">GRACE</p><p class="theobody">An essay about grace, set beside the text.</p></div>
        <p class="image"><img src="images/rome.jpg" alt="rome-and-its-provinces"/></p>
        <p><span class="verse-num">3</span> Concerning his Son.</p>
        <aside epub:type="footnote" id="vc1"><p><b>ROMANS 1:1, 2 Paul.</b> Ancient letters began with a formula.</p><p>A second paragraph.</p></aside></section>
        """)

    @Test func studyNotesCoverEveryVerseThatCallsThem() throws {
        let bible = try extract([intro, text])
        let note = try #require(bible.study.orderedNotes.first)
        #expect(note.start == VerseRef(.romans, 1, 1))
        #expect(note.end == VerseRef(.romans, 1, 2))
        #expect(note.text == "Paul. Ancient letters began with a formula.\n\nA second paragraph.")
        // None of it is scripture.
        #expect(bible.verses[VerseRef(.romans, 1, 1)]?.text == "Paul, a servant of Christ.")
        #expect(bible.verses[VerseRef(.romans, 1, 2)]?.text == "Which he promised afore.")
    }

    @Test func introductionsEssaysAndPicturesAreKept() throws {
        let bible = try extract([intro, text])
        let introduction = try #require(bible.study.articles.first { $0.kind == .introduction })
        #expect(introduction.book == .romans)
        #expect(introduction.title == "INTRODUCTION TO ROMANS")
        let essay = try #require(bible.study.articles.first { $0.kind == .essay })
        #expect(essay.title == "GRACE")
        #expect(essay.anchor == VerseRef(.romans, 1, 2))
        #expect(essay.text == "An essay about grace, set beside the text.")
        let image = try #require(bible.study.images.first)
        #expect(image.path == "images/rome.jpg")
        #expect(image.caption == "rome and its provinces")
        #expect(image.anchor == VerseRef(.romans, 1, 2))
    }

    @Test func studyMaterialCanBeLeftOut() throws {
        var options = BibleTextExtractor.Options()
        options.studyMaterial = false
        let bible = try BibleTextExtractor(options: options).extract(documents: [intro, text].map { (path: $0.0, xhtml: page($0.1)) })
        #expect(bible.study.isEmpty)
    }

    @Test func theStoreCarriesItAndTheStudyReaderReadsIt() throws {
        var bible = try extract([intro, text])
        bible.loadStudyImages { _ in Data([0xFF, 0xD8, 0xFF]) }
        let identity = ImportedTranslationIdentity(id: "IMPORT-STUDY", name: "Example Study Bible", abbreviation: "ESB",
                                                   copyright: "© Example")
        let url = try ImportFixtures.scratchDirectory().appending(path: "IMPORT-STUDY.sqlite")
        try ImportedBibleBuilder.write(bible, identity: identity, to: url)
        let info = try BibleStore(url: url).info
        let study = try #require(ImportedStudyStore(url: url, info: info))
        #expect(study.source.name == "Example Study Bible")
        #expect(study.commentary(on: VerseRef(.romans, 1, 2)).count == 2)   // the note, then the essay
        #expect(study.introduction(to: ChapterRef(.romans, 1))?.text.hasPrefix("INTRODUCTION TO ROMANS") == true)
        let images = study.images(in: ChapterRef(.romans, 1))
        #expect(images.count == 1)
        #expect(study.imageData(images[0].id) == Data([0xFF, 0xD8, 0xFF]))
    }

    @Test func aTranslationWithoutStudyMaterialHasNoStudyStore() throws {
        let bible = try extract([("rom.xhtml", "<section title=\"Romans\"><p><span class=\"chapter-num\">1</span> Paul, a servant.</p></section>")])
        let identity = ImportedTranslationIdentity(id: "IMPORT-PLAIN", name: "Plain", abbreviation: "PLN", copyright: "© Example")
        let url = try ImportFixtures.scratchDirectory().appending(path: "IMPORT-PLAIN.sqlite")
        try ImportedBibleBuilder.write(bible, identity: identity, to: url)
        #expect(ImportedStudyStore(url: url, info: try BibleStore(url: url).info) == nil)
    }
}
