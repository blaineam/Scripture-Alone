import SwiftUI
import ScriptureAloneCore

/// A passage to share, read from one translation.
struct ShareSource: Identifiable {
    let id = UUID()
    let source: any ChapterTextSource
    let ranges: [VerseRange]
    let verses: [VerseText]
    /// Designer choices carried in by a share link (template, typeface, aspect).
    var linkStyle: ShareLinkPayload?

    init?(source: any ChapterTextSource, ranges: [VerseRange], linkStyle: ShareLinkPayload? = nil) {
        let verses = ranges.flatMap { (try? source.verses(in: $0)) ?? [] }
        guard !verses.isEmpty else { return nil }
        self.source = source
        self.ranges = ranges
        self.verses = verses
        self.linkStyle = linkStyle
    }

    var info: TranslationInfo { source.info }
    var reference: String { ranges.map(\.display).joined(separator: ", ") }

    /// Links carry the text itself, so they're offered for public-domain translations only; a licensed
    /// translation adds its terms (and the web its notice) before links are switched on for it.
    var linksAllowed: Bool { ShareCardFitter.noticeText(for: info) == nil }

    /// The share link for the whole selection, or nil when it's too long to travel in a URL.
    func link(style: ShareStyle) -> URL? {
        guard linksAllowed else { return nil }
        var passage = SharePassageText(verses: verses)
        if !style.redLetters { passage.red = [] }
        let payload = ShareLinkPayload(ranges: ranges, reference: reference, translation: info.abbreviation,
                                       passage: passage, template: style.template.rawValue,
                                       font: style.family.shareToken, aspect: style.aspect.rawValue)
        guard payload.fitsInLink else { return nil }
        return try? payload.webURL()
    }
}

/// Owns the verse-image designer sheet and opens incoming links.
@Observable
final class ShareCoordinator {
    var designer: ShareSource?
}

extension View {
    /// Installs sharing: the designer sheet, share-link and `scripturealone://` handling.
    func shareSupport() -> some View { modifier(ShareSupport()) }
}

private struct ShareSupport: ViewModifier {
    @State private var coordinator = ShareCoordinator()

    func body(content: Content) -> some View {
        content
            .environment(coordinator)
            .sheet(item: $coordinator.designer) { source in
                ShareDesigner(source: source)
                    #if os(macOS)
                    .frame(minWidth: 760, idealWidth: 860, minHeight: 640, idealHeight: 720)
                    #endif
            }
            // Links go through the same mailbox as Siri, Shortcuts and Spotlight; the reader
            // carries them out (`ReaderView.perform`), share links' designer included.
            .onOpenURL { AppCommandCenter.shared.open($0) }
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                if let url = activity.webpageURL { AppCommandCenter.shared.open(url) }
            }
            #if os(macOS)
            // Open links in the frontmost window instead of spawning a new one.
            .handlesExternalEvents(preferring: ["*"], allowing: ["*"])
            #endif
    }
}

/// The selection bar's share button: image, text or link.
struct ShareMenu: View {
    @Environment(ReaderModel.self) private var model
    @Environment(ShareCoordinator.self) private var coordinator
    let ranges: [VerseRange]
    let quotation: String

    @AppStorage(ShareSettingsKey.template) private var template = ShareTemplate.parchment
    @AppStorage(ShareSettingsKey.aspect) private var aspect = ShareAspect.square
    @AppStorage(ShareSettingsKey.family) private var family = FontFamily.newYork
    @AppStorage(ShareSettingsKey.redLetters) private var redLetters = true
    @State private var copied = false

    var body: some View {
        let source = model.source.flatMap { ShareSource(source: $0, ranges: ranges) }
        let link = source?.link(style: ShareStyle(template: template, aspect: aspect, family: family, redLetters: redLetters))
        Menu {
            Button("Share Image…", systemImage: "photo.on.rectangle") { coordinator.designer = source }
                .disabled(source == nil)
            ShareLink(item: quotation) { Label("Share Text", systemImage: "text.quote") }
            if let link {
                Button("Copy Link", systemImage: "link") { copy(link) }
            } else {
                Button("Copy Link", systemImage: "link") {}
                    .disabled(true)
                Text(source?.linksAllowed == false ? "Links aren’t available for this translation"
                                                   : "Too long for a link — share the image")
            }
        } label: {
            Image(systemName: copied ? "checkmark" : "square.and.arrow.up").frame(width: 28, height: 28)
        }
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
        .accessibilityLabel("Share")
    }

    private func copy(_ url: URL) {
        SharePasteboard.copy(url)
        copied = true
        Task {
            try? await Task.sleep(for: .seconds(1.2))
            copied = false
        }
    }
}
