import Foundation
import SwiftUI
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The native User Guide's renderer: a section's inline marks become the right attributes (and only
/// web and mail links are followed), and every kind of block a guide package carries — headings,
/// lists, steps, tables, callouts, figures, features beside a drawn screen, drawn screens — lays out,
/// side by side when the column is wide and stacked when it isn't.
@MainActor
struct UserGuideRenderTests {
    /// One of every block kind, as `Tools/manual_json.py` writes them.
    static let blocksJSON = #"""
    [
      {"type": "heading", "inline": [{"text": "Reading"}]},
      {"type": "paragraph", "inline": [{"text": "Tap "}, {"text": "Aa", "ui": true}, {"text": " for "}, {"text": "Appearance", "bold": true}, {"br": true, "text": ""}, {"text": "or press "}, {"text": "⌘F", "kbd": true}]},
      {"type": "paragraph", "fine": true, "inline": [{"text": "Fine print with "}, {"text": "code", "code": true}, {"text": " and ", "small": true}, {"text": "a link", "link": "https://example.org/guide"}]},
      {"type": "list", "items": [[{"text": "One"}], [{"text": "Two", "bold": true}]]},
      {"type": "steps", "items": [[{"type": "paragraph", "inline": [{"text": "Open Go To."}]}], [{"type": "paragraph", "inline": [{"text": "Type John 3:16."}]}]]},
      {"type": "table", "header": [[{"text": "Key"}], [{"text": "Does"}]], "rows": [[[{"text": "⌘]", "kbd": true}], [{"text": "Next chapter"}]], [[{"text": "⌘["}], [{"text": "Previous chapter"}]]]},
      {"type": "callout", "style": "tip", "label": "Tip", "blocks": [{"type": "paragraph", "inline": [{"text": "Long-press a verse."}]}]},
      {"type": "callout", "style": "warn", "label": "", "blocks": [{"type": "paragraph", "inline": [{"text": "Careful."}]}]},
      {"type": "figure", "image": "missing.png", "device": "phone", "caption": "The reader"},
      {"type": "feature", "flip": true, "blocks": [{"type": "paragraph", "inline": [{"text": "Beside a drawn screen."}]}],
       "mock": {"bar": {"leading": "Back", "title": "Settings", "trailing": "Done"},
                "sections": [{"header": "Text", "rows": [{"text": "Size", "style": "plain", "detail": "18"},
                                                         {"text": "Open", "style": "link", "icon": "book"},
                                                         {"text": "Name", "style": "field"},
                                                         {"text": "Verse Numbers", "style": "plain", "checked": true, "highlight": true},
                                                         {"text": "Delete", "style": "destructive"}], "footer": "Shown in every translation."}]}},
      {"type": "feature", "figure": {"image": "watch.png", "device": "watch", "caption": "On the watch"}, "blocks": [{"type": "heading", "inline": [{"text": "Watch"}]}]},
      {"type": "mock", "bar": {"leading": "", "title": "Translations", "trailing": ""}, "sections": [{"rows": [{"text": "ASV", "style": "plain", "checked": true}]}]},
      {"type": "a-kind-from-a-newer-guide"}
    ]
    """#

    func blocks() throws -> [UserGuide.Block] {
        try JSONDecoder().decode([UserGuide.Block].self, from: Data(Self.blocksJSON.utf8))
    }

    @Test func inlineMarksBecomeAttributes() {
        let text = GuideRuns.attributed([
            UserGuide.Run(text: "Tap "), UserGuide.Run(text: "Add Note", ui: true),
            UserGuide.Run(text: " then ", bold: true), UserGuide.Run(text: "", lineBreak: true),
            UserGuide.Run(text: "site", link: "https://wemiller.com"),
            UserGuide.Run(text: "mail", link: "mailto:help@example.org"),
            UserGuide.Run(text: "script", link: "javascript:alert(1)"),
        ])
        let plain = String(text.characters)
        // A control's name stays on one line, padded inside its tint.
        #expect(plain.contains("\u{202F}Add\u{00A0}Note\u{202F}"))
        #expect(plain.contains("then \n"))
        let runs = Array(text.runs)
        #expect(runs.contains { $0.inlinePresentationIntent == .stronglyEmphasized })
        let links = runs.compactMap(\.link).map(\.absoluteString)
        #expect(links == ["https://wemiller.com", "mailto:help@example.org"], "only web and mail links are followed: \(links)")
    }

    @Test func everyBlockKindDecodesAndAnUnknownOneIsSkipped() throws {
        let decoded = try blocks()
        #expect(decoded.count == 13)
        #expect(decoded.last == .unknown)
    }

    func render(wide: Bool, width: CGFloat) throws -> CGSize {
        let view = GuideBlocks(blocks: try blocks())
            .environment(UserGuideStore.shared)
            .environment(\.guideWide, wide)
            .frame(width: width)
        let renderer = ImageRenderer(content: view)
        renderer.proposedSize = ProposedViewSize(width: width, height: nil)
        let image = try #require(renderer.cgImage, "the guide didn't render")
        return CGSize(width: image.width, height: image.height)
    }

    @Test func everyBlockLaysOutNarrowAndWide() throws {
        let narrow = try render(wide: false, width: 360)
        let wide = try render(wide: true, width: 900)
        #expect(narrow.height > 900, "thirteen blocks stacked in a phone's column: \(narrow)")
        // Side by side, a feature's words and its picture take less height than stacked.
        #expect(wide.height < narrow.height, "narrow \(narrow) vs wide \(wide)")
    }
}
