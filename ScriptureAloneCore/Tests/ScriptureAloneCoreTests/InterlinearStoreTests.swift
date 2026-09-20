import Foundation
import Testing
@testable import ScriptureAloneCore

/// Runs against the interlinear database the app bundles (built by Tools/build_interlinear.py) and
/// the BSB it aligns to.
@Suite struct InterlinearStoreTests {
    static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    func store() throws -> InterlinearStore {
        try InterlinearStore(url: Self.root.appending(path: "ScriptureAlone/Resources/Study/Interlinear.sqlite"))
    }

    func bible(_ id: String = "BSB") throws -> BibleStore {
        try BibleStore(url: Self.root.appending(path: "ScriptureAlone/Resources/Bibles/\(id).sqlite"))
    }

    // MARK: Words

    @Test func genesisOneOneIsSevenHebrewWords() throws {
        let words = try store().words(for: VerseRef(.genesis, 1, 1), from: try bible())
        #expect(words.count == 7)
        #expect(words.allSatisfy { $0.language == .hebrew })
        #expect(words.map(\.strongs) == ["H7225", "H0430", "H0853", "H1254", "H8064", "H0853", "H0776"])
        // English order 1,3,4,2,5,6,7 in the original — a permutation of 1…7, not the identity.
        #expect(words.map(\.originalOrder).sorted() == Array(1...7))
        #expect(words.map(\.originalOrder) != Array(1...7))
        #expect(words.map(\.english) == ["In the beginning", "God", "", "created", "the heavens", "and", "the earth"])
        #expect(words[0].transliteration == "bə·rê·šîṯ")
        #expect(words[3].parsingDescription == "Verb - Qal - Perfect - third person masculine singular")
        #expect(words[3].parsing?.code == "V-Qal-Perf-3ms")
        // אֵת, the direct object marker: a real word the BSB renders with nothing, so no highlight.
        #expect(words[2].english.isEmpty)
        #expect(words[2].range == nil)
        #expect(words.allSatisfy { !$0.isSuperscription })
    }

    @Test func johnOneOneIsGreekAndSaysLogosThreeTimes() throws {
        let words = try store().words(for: VerseRef(.john, 1, 1), from: try bible())
        #expect(words.allSatisfy { $0.language == .greek })
        #expect(!words.isEmpty)
        let forms = words.map { $0.original.precomposedStringWithCanonicalMapping.lowercased() }
        #expect(forms.filter { $0 == "λόγος" }.count == 3)
        #expect(forms.contains("θεόν"))
        #expect(words.map(\.originalOrder).sorted() == Array(1...words.count))
        let reading = words.map(\.english).filter { !$0.isEmpty }.joined(separator: " ")
        #expect(reading.hasPrefix("In the beginning was the Word"))
        #expect(words.first { $0.strongs == "G3056" }?.parsing?.code == "N-NMS")
    }

    /// Genesis to Revelation: every range this store hands out has to land on real, trimmed text of
    /// the BSB verse it came from. This is the invariant the whole design rests on — the English is
    /// not stored, it is a slice — so it is checked over the whole Bible rather than a sample.
    @Test func everyRangeSlicesRealTextOutOfTheBSBVerse() throws {
        let store = try store()
        let bible = try bible()
        var checked = 0, verses = 0, superscriptions = 0, unaligned = 0
        var failures: [String] = []
        for book in BookID.allCases {
            for chapter in 1...book.chapterCount {
                for verseText in try bible.verses(in: VerseRange(VerseRef(book, chapter, 1),
                                                                 VerseRef(book, chapter, 999))) {
                    let text = verseText.text as NSString
                    let words = try store.words(for: verseText.ref, in: verseText.text)
                    verses += 1
                    if words.contains(where: \.isSuperscription) { superscriptions += 1 }
                    if !words.isEmpty, words.allSatisfy({ $0.range == nil && !$0.isSuperscription }) { unaligned += 1 }
                    // The original-language order is a permutation of 1…n for every verse.
                    if !words.isEmpty, words.map(\.originalOrder).sorted() != Array(1...words.count) {
                        failures.append("\(verseText.ref.display): original order \(words.map(\.originalOrder))")
                    }
                    for word in words {
                        guard let range = word.range else { continue }
                        let ok = range.location >= 0 && range.length > 0 && range.upperBound <= text.length
                        guard ok else {
                            failures.append("\(verseText.ref.display) word \(word.position): range \(range) vs length \(text.length)")
                            continue
                        }
                        let slice = text.substring(with: range)
                        if slice != word.english {
                            failures.append("\(verseText.ref.display) word \(word.position): \(slice.debugDescription) != \(word.english.debugDescription)")
                        } else if slice != slice.trimmingCharacters(in: .whitespacesAndNewlines) {
                            failures.append("\(verseText.ref.display) word \(word.position): \(slice.debugDescription) has edge space")
                        }
                        checked += 1
                    }
                }
            }
        }
        #expect(failures.isEmpty, "\(failures.count) bad words, first: \(failures.prefix(5))")
        #expect(verses == 31_086)
        #expect(checked == 385_561)
        // Psalm titles and Zechariah 12:1's oracle heading, and the three verses the build could
        // not align (their words keep their own English and carry no range).
        #expect(superscriptions == 117)
        #expect(unaligned == 3)
    }

    @Test func rangesRunForwardsInReadingOrder() throws {
        let store = try store()
        let bible = try bible()
        for verseText in try bible.verses(in: VerseRange(VerseRef(.romans, 8, 1), VerseRef(.romans, 8, 39))) {
            var previous = -1
            for word in try store.words(for: verseText.ref, in: verseText.text) {
                guard let range = word.range else { continue }
                #expect(range.location >= previous, "\(verseText.ref.display) word \(word.position) goes backwards")
                previous = range.location
            }
        }
    }

    @Test func psalmSuperscriptionWordsComeFirstAndAreFlagged() throws {
        let store = try store()
        let bible = try bible()
        let verse = VerseRef(.psalms, 3, 1)
        let text = try #require(try bible.verses(in: VerseRange(verse)).first?.text)
        let words = try store.words(for: verse, in: text)
        let title = words.filter(\.isSuperscription)
        #expect(title.count == 6)
        // The superscription is a block of its own above the verse: it comes first, and the BSB's
        // verses.text does not contain it, so none of its words can be highlighted.
        #expect(words.prefix(title.count).allSatisfy { $0.isSuperscription })
        #expect(title.allSatisfy { $0.range == nil })
        #expect(title.allSatisfy { !$0.english.isEmpty })
        #expect(title.map(\.english).joined(separator: " ")
            == "A Psalm of David when he fled from his son Absalom")
        #expect(title.first?.strongs == "H4210")
        // The verse proper still highlights normally.
        let body = words.filter { !$0.isSuperscription }
        #expect(body.count == 7)
        #expect(body.first?.english == "O LORD")
        #expect(body.allSatisfy { $0.range != nil })
    }

    @Test func verseWithoutDataReturnsEmpty() throws {
        let store = try store()
        // Nehemiah 7:68 is the one BSB verse with no original-language words at all.
        #expect(try store.words(for: VerseRef(.nehemiah, 7, 68), in: "").isEmpty)
        #expect(try store.hasWords(for: VerseRef(.nehemiah, 7, 68)) == false)
        // A key the database does not carry at all.
        #expect(try store.words(for: VerseRef(.john, 3, 99), in: "").isEmpty)
        #expect(try store.hasWords(for: VerseRef(.john, 3, 99)) == false)
        #expect(try store.hasWords(for: VerseRef(.john, 3, 16)) == true)
    }

    @Test func countsWordsPerVerseForOneChapter() throws {
        let counts = try store().wordCounts(in: ChapterRef(.john, 1))
        #expect(counts[VerseRef(.john, 1, 1).key] == 17)
        #expect(counts.count == 51)
        #expect(counts.keys.allSatisfy { ChapterRef(.john, 1).keyRange.contains($0) })
        #expect(counts.values.allSatisfy { $0 > 0 })
        // Nehemiah 7's empty verse is absent rather than present with zero.
        #expect(try store().wordCounts(in: ChapterRef(.nehemiah, 7))[VerseRef(.nehemiah, 7, 68).key] == nil)
    }

    // MARK: Alignment is to the BSB only

    @Test func refusesATranslationItIsNotAlignedTo() throws {
        let store = try store()
        #expect(InterlinearStore.alignsTo(try bible("BSB").info))
        for id in ["ASV", "KJV"] {
            let other = try bible(id)
            #expect(!InterlinearStore.alignsTo(other.info))
            #expect(throws: InterlinearStoreError.self) {
                try store.words(for: VerseRef(.genesis, 1, 1), from: other)
            }
        }
        // Handing the raw text of another translation through the text overload is caught too:
        // "There is therefore now no condemnation…" is shorter than the BSB spans claim.
        let asv = try #require(try bible("ASV").verses(in: VerseRange(VerseRef(.romans, 8, 1))).first?.text)
        #expect(throws: InterlinearStoreError.self) {
            try store.words(for: VerseRef(.romans, 8, 1), in: asv)
        }
    }

    // MARK: Lexicon

    @Test func strongsNumbersResolveToGlosses() throws {
        let store = try store()
        let god = try #require(try store.entry(for: "H0430"))
        #expect(god.strongs == "H0430")
        #expect(god.gloss == "God")
        #expect(god.lemma == "אֱלֹהִים")
        #expect(god.transliteration == "e.lo.him")
        #expect(god.senses.count == 3)
        #expect(god.senses.map(\.gloss) == ["God", "(LORD)-Elohe", "(Gibeath)-elohim"])
        #expect(god.definition.contains("gods"))
        #expect(god.senses.allSatisfy { $0.morphology == "H:N-M" })

        let word = try #require(try store.entry(for: "G3056"))
        #expect(word.gloss == "word")
        #expect(word.lemma == "λόγος")
        #expect(word.definition.contains("λόγος"))
        #expect((word.senses.first?.lines.count ?? 0) > 1)
    }

    @Test func everyWordOfAVerseResolvesToAnEntry() throws {
        let store = try store()
        let bible = try bible()
        for verse in [VerseRef(.genesis, 1, 1), VerseRef(.john, 1, 1), VerseRef(.psalms, 23, 1)] {
            for word in try store.words(for: verse, from: bible) {
                let strongs = try #require(word.strongs, "\(verse.display) word \(word.position) is untagged")
                let entry = try #require(try store.entry(for: strongs), "\(strongs) has no entry")
                #expect(!entry.gloss.isEmpty)
            }
        }
    }

    @Test func acceptsStrongsNumbersHoweverTheyAreWritten() throws {
        let store = try store()
        let canonical = try #require(try store.entry(for: "H0430"))
        for spelling in ["H430", "h430", "H0430", " H0430 ", "H0430G", "h430a"] {
            #expect(try store.entry(for: spelling) == canonical, "\(spelling)")
        }
        #expect(InterlinearStore.normalize("G3056") == "G3056")
        #expect(InterlinearStore.normalize("430") == nil)
        #expect(InterlinearStore.normalize("") == nil)
        #expect(InterlinearStore.normalize("X430") == nil)
        #expect(try store.entry(for: "H9999") == nil)
        #expect(try store.entry(for: "nonsense") == nil)
    }

    // MARK: Attribution

    @Test func exposesTheAttributionBothLicencesRequire() throws {
        let attribution = try store().attribution
        #expect(attribution.requiredLines.count == 3)
        #expect(attribution.requiredLines.allSatisfy { !$0.isEmpty })
        #expect(attribution.words.contains("Berean Standard Bible Translation Tables"))
        #expect(attribution.words.contains("public domain"))
        #expect(attribution.wordsLicense == "Public domain")
        #expect(attribution.wordsLicenseURL != nil)
        #expect(attribution.lexicon.contains("STEPBible"))
        #expect(attribution.lexicon.contains("CC BY 4.0"))
        #expect(attribution.lexiconLicense == "CC BY 4.0")
        #expect(attribution.lexiconLicenseURL?.absoluteString == "https://creativecommons.org/licenses/by/4.0/")
        #expect(attribution.lexiconSourceURL?.absoluteString == "https://github.com/STEPBible/STEPBible-Data")
        // CC BY 4.0 requires that changes be stated.
        #expect(attribution.lexiconChanges.hasPrefix("Changes made for this app"))
    }

    @Test func reportsWhatTheBuildRecorded() throws {
        let statistics = try store().statistics
        #expect(statistics.version == "1")
        #expect(statistics.words == 443_625)
        #expect(statistics.taggedWords == 437_587)
        #expect(statistics.strongsNumbers == 13_876)
        #expect(statistics.verses == 31_086)
        #expect(statistics.versesAligned == 31_083)
        #expect(!statistics.lexiconCommit.isEmpty)
    }
}
