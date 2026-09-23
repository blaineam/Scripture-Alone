import Foundation
import Observation
import ScriptureAloneCore

/// Something the app was asked to do from outside the reader: a link, a Siri or Shortcuts intent,
/// a Spotlight result.
enum AppCommand: Equatable {
    /// Anything a URL can say — a passage (keys, OSIS, a typed reference), a search, a note, the
    /// notes list or favorites, a share link. Intents use the same vocabulary so every way into
    /// the app lands the same way.
    case link(AppLink)
    /// Back to where the reader left off, on this device or another.
    case continueReading
    /// Read a chapter aloud: the given passage's, or the one on screen.
    case listen(AppLink?)
    /// Start a note on these KJV ranges, or on the chapter on screen when empty.
    case newNote([VerseRange])
}

/// The mailbox between everything that can ask the app to do something and the reader, which owns
/// the sheets and the inspector that do it.
///
/// Commands wait here until a reader takes them, so one posted before any window exists — an
/// intent or a Spotlight tap that cold-launches the app — is carried out as soon as the first
/// reader appears rather than lost.
@Observable
final class AppCommandCenter {
    static let shared = AppCommandCenter()

    private(set) var pending: [AppCommand] = []

    /// Which scope the Notes panel should show next time it is up. One-shot; the panel clears it.
    var notesScope: NotesScopeRequest?

    /// Words the Go To sheet should open searching for. One-shot; the sheet clears it.
    var searchQuery: String?

    enum NotesScopeRequest: Equatable { case notes, favorites }

    private init() {}

    func post(_ command: AppCommand) {
        pending.append(command)
    }

    /// Posts a URL the app understands. Returns false when it isn't one.
    @discardableResult
    func open(_ url: URL) -> Bool {
        guard let link = AppLink(url: url) else { return false }
        post(.link(link))
        return true
    }

    /// Hands every waiting command to the caller, oldest first.
    func take() -> [AppCommand] {
        let commands = pending
        if !commands.isEmpty { pending = [] }
        return commands
    }
}

extension ReaderModel {
    /// Goes to stored (KJV) ranges and selects them. The reader lands on, and selects, the verses
    /// as the translation being read numbers them.
    func reveal(_ ranges: [VerseRange]) {
        guard let first = ranges.first else { return }
        go(to: first.start)
        guard let source else { return }
        let native = ranges.compactMap { source.numbering.nativeRange($0) }
        selection = Set(native.flatMap { Self.verseKeys(in: $0, store: source) })
        // `go(to:)` scrolls only past verse 1; within the chapter on screen, bring verse 1 up too.
        if let start = native.first?.start, start.verse == 1 { scrollTarget = start.key }
    }

    /// Opens what a link names. Whole chapters are opened without selecting anything; verses are
    /// opened and selected.
    func open(_ link: AppLink) {
        switch link {
        case .open(let ranges):
            reveal(ranges)
        case .share(let payload):
            reveal(payload.ranges)
        case .osis(let passages):
            // OSIS is KJV-numbered, like everything stored.
            guard let first = passages.first else { return }
            if first.isWholeChapter {
                go(to: VerseRef(first.book, first.clamped.startChapter, 1))
                selection.removeAll()
            } else {
                reveal(passages.map { kjvRange(of: $0) })
            }
        case .passage(let passages):
            // Typed the way the translation being read numbers its verses.
            guard let first = passages.first else { return }
            if first.isWholeChapter {
                go(to: first)
                selection.removeAll()
            } else {
                reveal(passages.map { numbering.kjvRange(nativeRange(of: $0)) })
            }
        case .search, .note, .notes, .favorites:
            break
        }
    }

    /// Back to the last place read — on this device or, through iCloud, another one. The position
    /// is a KJV key, like everything stored.
    func continueReading() {
        let cloud = NSUbiquitousKeyValueStore.default
        cloud.synchronize()
        let key = (cloud.object(forKey: "position") as? Int) ?? UserDefaults.standard.integer(forKey: "position")
        guard let ref = VerseRef(key: key) else { return }
        selection.removeAll()
        go(to: ref)
    }

    /// A passage in the translation's own numbering, resolved against its verse counts.
    func nativeRange(of passage: Passage) -> VerseRange {
        let clamped = passage.clamped
        return clamped.range { source?.verseCount($0) ?? 0 }
    }

    /// A KJV-numbered passage (OSIS) resolved to a stored range. An open end takes the chapter's
    /// last verse, which in the rare renumbered chapter may be one off — it only decides how far
    /// the selection runs.
    private func kjvRange(of passage: Passage) -> VerseRange {
        let clamped = passage.clamped
        return clamped.range { chapter in
            guard let source else { return 1 }
            let native = numbering.native(forKJV: VerseRef(chapter.book, chapter.chapter, 1).key)
                .flatMap(VerseRef.init(key:))?.chapterKey ?? chapter
            return max(1, source.verseCount(native))
        }
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
