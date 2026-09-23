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
    /// Apply inside the `ReaderModel` environment.
    func shareSupport() -> some View { modifier(ShareSupport()) }
}

private struct ShareSupport: ViewModifier {
    @Environment(ReaderModel.self) private var model
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
            .onOpenURL { open($0) }
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                if let url = activity.webpageURL { open(url) }
            }
            #if os(macOS)
            // Open links in the frontmost window instead of spawning a new one.
            .handlesExternalEvents(preferring: ["*"], allowing: ["*"])
            #endif
    }

    private func open(_ url: URL) {
        guard let link = AppLink(url: url) else { return }
        switch link {
        case .open(let ranges):
            reveal(ranges)
        case .share(let payload):
            reveal(payload.ranges)
            // Rebuild the card from the sender's translation when it's installed here, else the reader's own.
            guard let from = model.source(for: payload.translation) ?? model.source,
                  let source = ShareSource(source: from, ranges: payload.ranges, linkStyle: payload) else { return }
            // A sheet presented while the scene is still activating for the URL is dropped; wait a beat.
            Task {
                try? await Task.sleep(for: .milliseconds(450))
                coordinator.designer = source
            }
        }
    }

    /// Goes to the passage and selects it. A link carries KJV keys; the reader lands on, and
    /// selects, the verses as the translation being read numbers them. Any source will do — the
    /// sealed ASV has no `store`, which used to make a link open nothing.
    private func reveal(_ ranges: [VerseRange]) {
        guard let first = ranges.first, let source = model.source else { return }
        if coordinator.designer != nil { coordinator.designer = nil }
        model.go(to: first.start)
        let native = ranges.compactMap { source.numbering.nativeRange($0) }
        model.selection = Set(native.flatMap { Self.verseKeys(in: $0, store: source) })
    }

    static func verseKeys(in range: VerseRange, store: any ChapterTextSource) -> [Int] {
        var keys: [Int] = []
        var chapter = range.start.chapterKey
        var verse = range.start.verse
        while keys.count < 2_000 {
            let count = store.verseCount(chapter)
            if verse > count {
                guard count > 0, let next = chapter.next else { break }
                chapter = next
                verse = 1
                continue
            }
            let key = VerseRef(chapter.book, chapter.chapter, verse).key
            if key > range.end.key { break }
            keys.append(key)
            verse += 1
        }
        return keys
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
