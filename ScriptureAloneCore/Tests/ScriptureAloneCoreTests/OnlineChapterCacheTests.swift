import Foundation
import Testing
@testable import ScriptureAloneCore

/// The on-demand chapter cache. Fixture text is invented — the ESV never appears in this repository.
@Suite struct OnlineChapterCacheTests {
    static func directory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appending(path: "online-cache-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// A cache with a small ceiling, so eviction can be exercised with small fixtures.
    func cache(limit: Int = 10, translation: OnlineTranslation = .esv) throws -> OnlineChapterCache {
        let url = try Self.directory().appending(path: "\(translation.id).sqlite")
        return try OnlineChapterCache(url: url, translation: translation, verseLimit: limit)
    }

    func verses(_ chapter: ChapterRef, _ numbers: ClosedRange<Int>,
                text: (Int) -> String = { "Invented verse \($0)." }) -> [VerseText] {
        numbers.map { VerseText(ref: VerseRef(chapter.book, chapter.chapter, $0), text: text($0), red: []) }
    }

    // MARK: - Round trip

    @Test func storesAChapterTheReaderCanOpen() throws {
        let cache = try cache()
        let write = try cache.store(verses(ChapterRef(.john, 3), 1...4), for: ChapterRef(.john, 3))
        #expect(write.versesStored == 4)
        #expect(write.cachedVerses == 4)
        #expect(write.evicted.isEmpty)
        #expect(write.exceedsLimit == false)

        let store = try BibleStore(url: cache.url)
        #expect(store.info.id == "ESV")
        #expect(store.info.abbreviation == "ESV")
        // The publisher's required notice has to be there for the reader to print.
        #expect(store.info.copyright == ESVClient.requiredCopyright)
        #expect(store.contains(ChapterRef(.john, 3)))
        #expect(store.verseCount(ChapterRef(.john, 3)) == 4)

        let rows = try store.verses(in: VerseRange(VerseRef(.john, 3, 1), VerseRef(.john, 3, 4)))
        #expect(rows.map(\.text) == (1...4).map { "Invented verse \($0)." })
        // The same verse key the bundled stores use, so highlights and notes line up.
        #expect(rows.first?.ref.key == 43_003_001)
    }

    @Test func layoutIsOneNumberedProseParagraph() throws {
        let cache = try cache()
        try cache.store(verses(ChapterRef(.john, 3), 1...3), for: ChapterRef(.john, 3))
        let layout = try BibleStore(url: cache.url).layout(for: ChapterRef(.john, 3))
        #expect(layout.blocks.count == 1)
        let block = try #require(layout.blocks.first)
        #expect(block.kind == .paragraph)
        #expect(block.fragments.map(\.verse) == [1, 2, 3])
        #expect(block.fragments.filter { !$0.numbered }.isEmpty)
        #expect(block.fragments.first?.text == "Invented verse 1.")
        // Matches what the in-memory equivalent produces.
        let prose = ChapterLayout.prose(verses(ChapterRef(.john, 3), 1...3))
        #expect(prose.blocks.first?.fragments.map(\.verse) == block.fragments.map(\.verse))
        #expect(prose.blocks.first?.kind == block.kind)
    }

    @Test func theFullTextIndexFindsACachedVerse() throws {
        let cache = try cache()
        try cache.store([
            VerseText(ref: VerseRef(.john, 3, 16), text: "A sentence about a shepherd.", red: []),
            VerseText(ref: VerseRef(.john, 3, 17), text: "A sentence about a gate.", red: []),
        ], for: ChapterRef(.john, 3))
        let store = try BibleStore(url: cache.url)
        #expect(try store.search("shepherd").map(\.ref) == [VerseRef(.john, 3, 16)])
        #expect(try store.search("gate").map(\.ref) == [VerseRef(.john, 3, 17)])
        #expect(try store.search("camel").isEmpty)
    }

    // MARK: - Replacing

    @Test func replacingAChapterLeavesNoStaleVerses() throws {
        let cache = try cache()
        let chapter = ChapterRef(.john, 3)
        try cache.store(verses(chapter, 1...6, text: { "First fetch \($0)." }), for: chapter)
        let write = try cache.store(verses(chapter, 1...2, text: { "Second fetch \($0)." }), for: chapter)

        #expect(write.versesStored == 2)
        #expect(write.cachedVerses == 2)      // the four extra rows are gone, not merged
        #expect(write.evicted.isEmpty)
        let store = try BibleStore(url: cache.url)
        let rows = try store.verses(in: VerseRange(VerseRef(.john, 3, 1), VerseRef(.john, 3, 6)))
        #expect(rows.map(\.text) == ["Second fetch 1.", "Second fetch 2."])
        #expect(store.verseCount(chapter) == 2)
        #expect(try cache.cachedChapters() == [chapter])
        // The index has to forget them too.
        #expect(try store.search("First").isEmpty)
    }

    // MARK: - The ceiling

    @Test func evictsLeastRecentlyReadUntilTheWriteFits() throws {
        let cache = try cache(limit: 10)
        try cache.store(verses(ChapterRef(.genesis, 1), 1...4), for: ChapterRef(.genesis, 1))
        try cache.store(verses(ChapterRef(.genesis, 2), 1...4), for: ChapterRef(.genesis, 2))
        #expect(try cache.cachedVerseCount() == 8)

        let write = try cache.store(verses(ChapterRef(.genesis, 3), 1...4), for: ChapterRef(.genesis, 3))
        #expect(write.evicted == [ChapterRef(.genesis, 1)])
        #expect(write.cachedVerses == 8)
        #expect(try cache.cachedVerseCount() <= 10)
        #expect(cache.contains(ChapterRef(.genesis, 1)) == false)
        #expect(cache.contains(ChapterRef(.genesis, 2)))
        #expect(cache.contains(ChapterRef(.genesis, 3)))
        // Most recently read first.
        #expect(try cache.cachedChapters() == [ChapterRef(.genesis, 3), ChapterRef(.genesis, 2)])

        let store = try BibleStore(url: cache.url)
        #expect(store.contains(ChapterRef(.genesis, 1)) == false)
        #expect(try store.verses(in: VerseRange(VerseRef(.genesis, 1, 1), VerseRef(.genesis, 1, 4))).isEmpty)
    }

    @Test func evictsAsManyChaptersAsItTakes() throws {
        let cache = try cache(limit: 10)
        for chapter in 1...5 {
            try cache.store(verses(ChapterRef(.genesis, chapter), 1...2), for: ChapterRef(.genesis, chapter))
        }
        #expect(try cache.cachedVerseCount() == 10)
        let write = try cache.store(verses(ChapterRef(.genesis, 6), 1...6), for: ChapterRef(.genesis, 6))
        // Six incoming verses need three of the two-verse chapters gone, oldest first.
        #expect(write.evicted == [ChapterRef(.genesis, 1), ChapterRef(.genesis, 2), ChapterRef(.genesis, 3)])
        #expect(write.cachedVerses == 10)
    }

    @Test func markReadChangesWhatGetsEvicted() throws {
        let cache = try cache(limit: 10)
        try cache.store(verses(ChapterRef(.genesis, 1), 1...4), for: ChapterRef(.genesis, 1))
        try cache.store(verses(ChapterRef(.genesis, 2), 1...4), for: ChapterRef(.genesis, 2))
        // The reader goes back to Genesis 1, so Genesis 2 is now the stale one.
        try cache.markRead(ChapterRef(.genesis, 1))
        #expect(try cache.cachedChapters() == [ChapterRef(.genesis, 1), ChapterRef(.genesis, 2)])

        let write = try cache.store(verses(ChapterRef(.genesis, 3), 1...4), for: ChapterRef(.genesis, 3))
        #expect(write.evicted == [ChapterRef(.genesis, 2)])
        #expect(cache.contains(ChapterRef(.genesis, 1)))
        #expect(cache.contains(ChapterRef(.genesis, 2)) == false)
    }

    @Test func markingAnUncachedChapterDoesNothing() throws {
        let cache = try cache()
        try cache.store(verses(ChapterRef(.genesis, 1), 1...2), for: ChapterRef(.genesis, 1))
        try cache.markRead(ChapterRef(.revelation, 22))
        #expect(try cache.cachedChapters() == [ChapterRef(.genesis, 1)])
        #expect(try cache.cachedVerseCount() == 2)
    }

    /// The ceiling is on what is *kept*, never a reason to fail the chapter the reader is looking
    /// at. Nothing canonical comes close — Psalm 119, the longest, is 176 verses.
    @Test func aChapterLargerThanTheCeilingIsStillStored() throws {
        let cache = try cache(limit: 3)
        try cache.store(verses(ChapterRef(.genesis, 1), 1...2), for: ChapterRef(.genesis, 1))
        let write = try cache.store(verses(ChapterRef(.psalms, 119), 1...8), for: ChapterRef(.psalms, 119))
        #expect(write.exceedsLimit)
        #expect(write.versesStored == 8)
        #expect(write.evicted == [ChapterRef(.genesis, 1)])   // everything else goes first
        #expect(try cache.cachedChapters() == [ChapterRef(.psalms, 119)])

        let store = try BibleStore(url: cache.url)
        #expect(store.verseCount(ChapterRef(.psalms, 119)) == 8)
        #expect(try store.verses(in: VerseRange(VerseRef(.psalms, 119, 1), VerseRef(.psalms, 119, 8))).count == 8)
    }

    @Test func theRealCeilingIsCrosswaysFiveHundred() throws {
        let cache = try cache(limit: ESVClient.cacheVerseLimit)
        #expect(cache.verseLimit == 500)
        // Ten chapters of 60 verses is 600, over the cap, so the oldest are dropped.
        for chapter in 1...10 {
            try cache.store(verses(ChapterRef(.psalms, chapter), 1...60), for: ChapterRef(.psalms, chapter))
        }
        #expect(try cache.cachedVerseCount() <= 500)
        #expect(try cache.cachedVerseCount() == 480)
        #expect(cache.contains(ChapterRef(.psalms, 1)) == false)
        #expect(cache.contains(ChapterRef(.psalms, 10)))
    }

    // MARK: - Clearing

    @Test func clearEmptiesTheCacheButKeepsTheStoreOpenable() throws {
        let cache = try cache()
        try cache.store(verses(ChapterRef(.john, 3), 1...4), for: ChapterRef(.john, 3))
        try cache.clear()

        #expect(try cache.cachedVerseCount() == 0)
        #expect(try cache.cachedChapters().isEmpty)
        #expect(cache.contains(ChapterRef(.john, 3)) == false)

        let store = try BibleStore(url: cache.url)
        #expect(store.contains(ChapterRef(.john, 3)) == false)
        #expect(try store.verses(in: VerseRange(VerseRef(.john, 3, 1), VerseRef(.john, 3, 4))).isEmpty)
        #expect(try store.search("Invented").isEmpty)
        // The identity survives, so the reader can still name what it is showing.
        #expect(store.info.copyright == ESVClient.requiredCopyright)

        // …and it still works afterwards.
        try cache.store(verses(ChapterRef(.john, 3), 1...2), for: ChapterRef(.john, 3))
        #expect(try cache.cachedVerseCount() == 2)
    }

    // MARK: - The file

    @Test func opensAnExistingCacheWithoutLosingIt() throws {
        let url = try Self.directory().appending(path: "ESV.sqlite")
        let first = try OnlineChapterCache(url: url, translation: .esv, verseLimit: 10)
        try first.store(verses(ChapterRef(.john, 3), 1...4), for: ChapterRef(.john, 3))

        let second = try OnlineChapterCache(url: url, translation: .esv, verseLimit: 10)
        #expect(second.contains(ChapterRef(.john, 3)))
        #expect(try second.cachedVerseCount() == 4)
        // The read counter persists, so eviction order survives a relaunch.
        try second.store(verses(ChapterRef(.john, 4), 1...4), for: ChapterRef(.john, 4))
        let write = try second.store(verses(ChapterRef(.john, 5), 1...4), for: ChapterRef(.john, 5))
        #expect(write.evicted == [ChapterRef(.john, 3)])
    }

    /// A cache is disposable. A file that is corrupt, or simply not one of ours, is replaced rather
    /// than left to strand the reader.
    @Test func replacesAFileThatIsNotACache() throws {
        let url = try Self.directory().appending(path: "ESV.sqlite")
        try Data("this is not a database".utf8).write(to: url)
        let cache = try OnlineChapterCache(url: url, translation: .esv, verseLimit: 10)
        try cache.store(verses(ChapterRef(.john, 3), 1...2), for: ChapterRef(.john, 3))
        #expect(try cache.cachedVerseCount() == 2)
        #expect(try BibleStore(url: url).verseCount(ChapterRef(.john, 3)) == 2)
    }

    /// `BibleStore` opens with `immutable=1` and reads its chapter table once, in `init`. An
    /// instance made before a write therefore cannot see that write — it is not a bug, it is the
    /// contract, and it is what "the chapter didn't load" will turn out to be.
    @Test func aReaderOpenedBeforeAWriteMustBeReopened() throws {
        let cache = try cache()
        try cache.store(verses(ChapterRef(.john, 3), 1...2), for: ChapterRef(.john, 3))
        let stale = try BibleStore(url: cache.url)
        #expect(stale.contains(ChapterRef(.john, 4)) == false)

        try cache.store(verses(ChapterRef(.john, 4), 1...2), for: ChapterRef(.john, 4))

        // The old instance still shows the world as it was, and does not crash or corrupt: the
        // copy-and-replace left it holding an unlinked inode that really is immutable.
        #expect(stale.contains(ChapterRef(.john, 4)) == false)
        #expect(try stale.verses(in: VerseRange(VerseRef(.john, 4, 1), VerseRef(.john, 4, 2))).isEmpty)
        #expect(try stale.verses(in: VerseRange(VerseRef(.john, 3, 1), VerseRef(.john, 3, 2))).count == 2)

        // Re-opening is all it takes.
        let fresh = try BibleStore(url: cache.url)
        #expect(fresh.contains(ChapterRef(.john, 4)))
        #expect(try fresh.verses(in: VerseRange(VerseRef(.john, 4, 1), VerseRef(.john, 4, 2))).count == 2)
    }

    @Test func wordsOfChristSurviveTheUTF16ToScalarConversion() throws {
        // An emoji ahead of the span makes the two offset systems disagree, which is the whole
        // point of the conversion.
        let text = "🕊 He said, Follow me."
        let quoted = "Follow me."
        let location = (text as NSString).range(of: quoted)
        let cache = try cache()
        try cache.store([VerseText(ref: VerseRef(.john, 1, 43), text: text, red: [location])],
                        for: ChapterRef(.john, 1))
        let store = try BibleStore(url: cache.url)
        let row = try #require(try store.verses(in: VerseRange(VerseRef(.john, 1, 43))).first)
        let red = try #require(row.red.first)
        #expect((row.text as NSString).substring(with: red) == quoted)
    }

    @Test func writesOffTheMainActor() async throws {
        let cache = try cache()
        let chapter = ChapterRef(.john, 3)
        let rows = verses(chapter, 1...4)
        let write = try await Task.detached { try cache.store(rows, for: chapter) }.value
        #expect(write.versesStored == 4)
        #expect(try BibleStore(url: cache.url).verseCount(chapter) == 4)
    }
}
