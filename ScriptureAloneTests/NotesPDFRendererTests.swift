import Foundation
import PDFKit
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The notes PDF, read back with PDFKit: what a reader prints or sends must say what their notes say.
struct NotesPDFRendererTests {
    func note(_ title: String, _ body: String, _ anchors: [VerseRange] = []) -> KeepsakeNote {
        let date = Date(timeIntervalSince1970: 1_750_000_000)
        return KeepsakeNote(title: title, body: body, anchors: anchors, createdAt: date, updatedAt: date)
    }

    func text(of data: Data) throws -> (pages: Int, text: String) {
        let document = try #require(PDFDocument(data: data), "not a PDF")
        let text = (0..<document.pageCount).compactMap { document.page(at: $0)?.string }.joined(separator: "\n")
        return (document.pageCount, text)
    }

    /// PDF text extraction may break lines anywhere; compare with whitespace squeezed out.
    func squeezed(_ string: String) -> String { string.filter { !$0.isWhitespace } }

    @Test func aNoteWithItsPassageAndTheNotice() throws {
        let romans8 = VerseRange(VerseRef(.romans, 8, 1))
        let data = NotesPDFRenderer.render(
            [note("No Condemnation", "Pastor Jim, Sunday.\nSecond paragraph.", [romans8])],
            options: .init(title: "Sermon Notes", subtitle: "Spring 2026", translation: "ASV", notice: "A publisher's notice."),
            verseText: { $0 == romans8 ? "There is therefore now no condemnation" : nil })
        let (pages, text) = try text(of: data)
        #expect(pages == 1)
        for expected in ["Sermon Notes", "Spring 2026", "No Condemnation", "Pastor Jim, Sunday.", "Second paragraph.",
                         "There is therefore now no condemnation", "Romans 8:1 (ASV)", "A publisher's notice."] {
            #expect(squeezed(text).contains(squeezed(expected)), "missing “\(expected)”")
        }
    }

    @Test func withoutATranslationNoScriptureIsQuoted() throws {
        let data = NotesPDFRenderer.render(
            [note("Plain", "Just my words.", [VerseRange(VerseRef(.john, 3, 16))])],
            options: .init(title: "Notes", subtitle: nil, translation: nil, notice: nil),
            verseText: { _ in "For God so loved the world" })
        let (_, text) = try text(of: data)
        #expect(squeezed(text).contains("Justmywords."))
        #expect(!squeezed(text).contains("ForGodsoloved"))
    }

    @Test func longExportsFlowOntoMorePagesAndLoseNothing() throws {
        let paragraph = String(repeating: "Grace upon grace, and truth with it. ", count: 30)
        // "of 25" ends each title, so note 1 is not found inside "Note number 10".
        let notes = (1...25).map { note("Note number \($0) of 25", paragraph) }
        let data = NotesPDFRenderer.render(notes, options: .init(title: "Everything", subtitle: nil, translation: nil, notice: nil),
                                           verseText: { _ in nil })
        let (pages, text) = try text(of: data)
        #expect(pages > 3)
        for index in 1...25 {
            #expect(squeezed(text).contains("Notenumber\(index)of25"), "note \(index) fell off the end")
        }
    }

    @Test func anEmptyExportIsStillAOnePagePDF() throws {
        let data = NotesPDFRenderer.render([], options: .init(title: "Nothing yet", subtitle: nil, translation: nil, notice: nil),
                                           verseText: { _ in nil })
        let (pages, text) = try text(of: data)
        #expect(pages == 1)
        #expect(squeezed(text).contains("Nothingyet"))
    }
}
