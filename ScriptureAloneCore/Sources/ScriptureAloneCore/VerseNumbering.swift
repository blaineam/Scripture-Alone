import Foundation

/// How one translation's verse numbers line up with the KJV's.
///
/// **Why there are two numberings.** Highlights, notes, favorites, cross-references, commentary and
/// the Hebrew and Greek are all keyed to one verse-key space — the KJV's — so they follow a reader
/// from one translation to another. But Bibles do not all number alike: Louis Segond counts a
/// psalm's title as verse 1, so the French Psalm 51:12 is the English 51:10; the Hebrew chapter
/// breaks put the French Exodus 7:26 at the English 8:1; a source can print nine English verses as
/// one. A French reader must see the French numbers — their printed Bible and their pastor's slides
/// use them — so each translation keeps its own, and this converts at the boundary.
///
/// **The one rule.** Everything stored, shared or looked up uses KJV keys; only what the reader
/// draws uses native ones. A `ChapterTextSource` reads its text by KJV key (`verses(in:)`), and
/// renders its chapters with native numbers (`layout(for:)`).
///
/// Built from a translation's `kjv_map` table (Tools/build_bibles.py), which holds only the verses
/// that differ. No rows — every English Bible, an import, a package — is `identity`.
public struct VerseNumbering: Sendable, Equatable {
    /// A native verse and the KJV verses it holds (usually one; a range or a merged verse, more).
    public struct Row: Sendable, Equatable {
        public let native: Int
        public let kjv: Int
        public let kjvLast: Int
        public init(native: Int, kjv: Int, kjvLast: Int) {
            self.native = native
            self.kjv = kjv
            self.kjvLast = max(kjv, kjvLast)
        }
    }

    public static let identity = VerseNumbering(rows: [])

    private let forward: [Int: Row]
    /// Reverse intervals sorted by `kjv`, one per distinct KJV span; where several native verses
    /// share a span (a psalm's title verses and its first verse all hold KJV verse 1) the last —
    /// the verse itself, not its title — answers.
    private let reverse: [Row]

    public var isIdentity: Bool { forward.isEmpty }

    public init(rows: [Row]) {
        var forward: [Int: Row] = [:]
        var bySpan: [Int: Row] = [:]
        for row in rows {
            forward[row.native] = row
            let key = row.kjv &* 1_000_000_000 &+ row.kjvLast
            if let existing = bySpan[key], existing.native > row.native { continue }
            bySpan[key] = row
        }
        self.forward = forward
        self.reverse = bySpan.values.sorted { ($0.kjv, $0.kjvLast) < ($1.kjv, $1.kjvLast) }
    }

    public static func == (lhs: VerseNumbering, rhs: VerseNumbering) -> Bool {
        lhs.forward == rhs.forward
    }

    // MARK: Native → KJV

    /// The KJV verses a native verse holds, as keys (first...last). Usually one.
    public func kjvKeys(forNative key: Int) -> ClosedRange<Int> {
        guard let row = forward[key] else { return key...key }
        return row.kjv...row.kjvLast
    }

    /// Every KJV key a native verse holds, for storing one highlight per KJV verse — so the
    /// color shows on each of them in a translation that numbers them separately. A span that runs
    /// into the next chapter (the Reina-Valera's Job 39:30 holds 39:27-40:5) is listed as the rest
    /// of the first chapter's verses the map names plus the second chapter's from verse 1; the KJV's
    /// own chapter lengths are not known here, so the first chapter stops at its first key.
    public func kjvKeyList(forNative key: Int) -> [Int] {
        let range = kjvKeys(forNative: key)
        let firstChapter = range.lowerBound / 1_000, lastChapter = range.upperBound / 1_000
        if firstChapter == lastChapter { return Array(range) }
        return [range.lowerBound] + Array((lastChapter * 1_000 + 1)...range.upperBound)
    }

    /// The KJV key a native verse is stored under: the first it holds.
    public func kjv(forNative key: Int) -> Int { forward[key]?.kjv ?? key }

    /// A native range as KJV keys, first verse's first KJV key to last verse's last.
    public func kjvRange(_ range: VerseRange) -> VerseRange {
        guard !isIdentity,
              let start = VerseRef(key: kjvKeys(forNative: range.start.key).lowerBound),
              let end = VerseRef(key: kjvKeys(forNative: range.end.key).upperBound) else { return range }
        return VerseRange(start, end)
    }

    // MARK: KJV → native

    /// The native verse that holds a KJV verse, or nil when this translation has no verse there
    /// (it merged that KJV verse into a neighbour the map does not name, or omits it).
    public func native(forKJV key: Int) -> Int? {
        if isIdentity { return key }
        if let row = containing(key) { return row.native }
        // A KJV verse no row mentions keeps its number — unless that native number was itself
        // moved elsewhere, in which case this translation simply has nothing there.
        return forward[key] == nil ? key : nil
    }

    /// A KJV range in native keys, widened to whole native verses. Nil if none of it is here.
    public func nativeRange(_ range: VerseRange) -> VerseRange? {
        if isIdentity { return range }
        let first = native(forKJV: range.start.key) ?? nearest(after: range.start.key, until: range.end.key)
        let last = native(forKJV: range.end.key) ?? nearest(before: range.end.key, from: range.start.key)
        guard let first, let last, first <= last,
              let start = VerseRef(key: first), let end = VerseRef(key: last) else { return nil }
        return VerseRange(start, end)
    }

    /// The KJV keys a native chapter's verses hold, for finding a chapter's stored marks. May run
    /// into a neighbouring KJV chapter (the French Exodus 8 holds the English 8:5 onward, its 7:26-29
    /// the English 8:1-4) — which is why marks are matched by key range, never by chapter number.
    public func kjvKeyRange(of chapter: ChapterRef, verseCount: Int) -> ClosedRange<Int> {
        guard !isIdentity, verseCount > 0 else { return chapter.keyRange }
        let first = VerseRef(chapter.book, chapter.chapter, 1).key
        let last = VerseRef(chapter.book, chapter.chapter, verseCount).key
        var low = kjvKeys(forNative: first).lowerBound
        var high = kjvKeys(forNative: last).upperBound
        for row in forward.values where row.native >= first && row.native <= last {
            low = min(low, row.kjv)
            high = max(high, row.kjvLast)
        }
        return low...high
    }

    // MARK: Private

    private func containing(_ key: Int) -> Row? {
        // Spans are short and few (a translation has at most ~1,500 rows); a binary search to the
        // last span starting at or before the key, then a short walk back over overlapping spans.
        var low = 0, high = reverse.count
        while low < high {
            let mid = (low + high) / 2
            if reverse[mid].kjv <= key { low = mid + 1 } else { high = mid }
        }
        var index = low - 1
        var best: Row?
        while index >= 0, reverse[index].kjv >= key - 1_000_000 {
            let row = reverse[index]
            if row.kjv <= key, key <= row.kjvLast, best.map({ row.native > $0.native }) ?? true { best = row }
            index -= 1
        }
        return best
    }

    private func nearest(after key: Int, until limit: Int) -> Int? {
        var candidate = key + 1
        while candidate <= limit {
            if let hit = native(forKJV: candidate) { return hit }
            candidate += 1
        }
        return nil
    }

    private func nearest(before key: Int, from limit: Int) -> Int? {
        var candidate = key - 1
        while candidate >= limit {
            if let hit = native(forKJV: candidate) { return hit }
            candidate -= 1
        }
        return nil
    }
}
