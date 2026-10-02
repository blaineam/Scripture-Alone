import Foundation
import Testing
@testable import ScriptureAloneCore

/// The layout additions for licensed translations: a footnote's own label and quotation small caps.
@Suite struct ChapterLayoutDecodingTests {
    func fragment(_ json: String) throws -> ChapterLayout.Fragment {
        let layout = try JSONDecoder().decode(ChapterLayout.self, from: Data(#"{"b":[{"k":"p","f":[\#(json)]}]}"#.utf8))
        return try #require(layout.blocks.first?.fragments.first)
    }

    @Test func footnoteKeepsItsOwnLabel() throws {
        let notes = try fragment(#"{"v":1,"n":1,"t":"He vopped.","fn":[[3,"Historical present","*"],[10,"Or zib"]]}"#).footnotes
        #expect(notes.map(\.label) == ["*", nil])
        #expect(notes.map(\.text) == ["Historical present", "Or zib"])
    }

    @Test func quotationStyleDecodesAndUnknownStylesStayNil() throws {
        let spans = try fragment(#"{"v":1,"t":"It says, Vop.","s":[[9,3,"k"],[0,2,"z"]]}"#).spans
        #expect(spans.map(\.style) == [.quotation, nil])
    }
}
