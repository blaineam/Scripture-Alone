import Foundation
import Testing
@testable import ScriptureAloneCore

/// The Topics directory: the curated life themes (Data/topics/, built by Tools/build_topics.py)
/// and Nave's Topical Bible, against the files the app ships.
@Suite struct LifeThemeTests {
    static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    static let languages = ["de", "es", "fr", "it", "ja", "ko", "pt-BR", "zh-Hans"]

    var catalog: LifeThemeCatalog { .shared }

    @Test func catalogIsCuratedAndWellFormed() {
        #expect((60...80).contains(catalog.themes.count))
        #expect(Set(catalog.themes.map(\.id)).count == catalog.themes.count)
        #expect(Set(catalog.groups.map(\.id)).count == catalog.groups.count)
        for theme in catalog.themes {
            #expect((8...20).contains(theme.passages.count), "\(theme.id) has \(theme.passages.count) passages")
            #expect(Set(theme.passages).count == theme.passages.count, "\(theme.id) lists a passage twice")
            #expect(!theme.synonyms.isEmpty && !theme.description.isEmpty, "\(theme.id)")
            #expect(catalog.groups.contains { $0.id == theme.group }, "\(theme.id) has no group")
        }
        for group in catalog.groups { #expect(!catalog.themes(in: group).isEmpty, "\(group.id) is empty") }
    }

    /// The bundled catalog is the reviewed source, rebuilt: same themes, same passages.
    @Test func bundledCatalogMatchesTheSourceFile() throws {
        struct Source: Decodable {
            struct Theme: Decodable { let id: String; let refs: [String] }
            let themes: [Theme]
        }
        let source = try JSONDecoder().decode(Source.self, from: Data(contentsOf: Self.root.appending(path: "Data/topics/life-themes.json")))
        #expect(Set(source.themes.map(\.id)) == Set(catalog.themes.map(\.id)))
        for theme in source.themes {
            #expect(catalog.theme(id: theme.id)?.passages.count == theme.refs.count, "rebuild after editing \(theme.id)")
        }
    }

    /// Every verse of every passage is in both bundled English Bibles, whose numbering (the KJV's)
    /// is the one the passages are stored in.
    @Test(arguments: ["BSB", "KJV"])
    func everyPassageExists(translation: String) throws {
        let store = try BibleStore(url: Self.root.appending(path: "ScriptureAlone/Resources/Bibles/\(translation).sqlite"))
        for theme in catalog.themes {
            for passage in theme.passages {
                let expected = passage.end.key - passage.start.key + 1
                let verses = try store.verses(in: passage)
                #expect(passage.start.chapterKey == passage.end.chapterKey, "\(theme.id): \(passage.display) spans chapters")
                #expect(verses.count == expected, "\(theme.id): \(passage.display) has \(verses.count) of \(expected) verses in \(translation)")
            }
        }
    }

    /// Names, descriptions and search words are in the catalog for every app language.
    @Test func everyThemeIsTranslated() throws {
        let data = try Data(contentsOf: Self.root.appending(path: "ScriptureAloneCore/Sources/ScriptureAloneCore/Resources/Localizable.xcstrings"))
        let strings = try #require((try JSONSerialization.jsonObject(with: data) as? [String: Any])?["strings"] as? [String: Any])
        func translated(_ key: String) -> Bool {
            guard let entry = strings[key] as? [String: Any],
                  let localizations = entry["localizations"] as? [String: Any] else { return false }
            return Self.languages.allSatisfy { language in
                guard let unit = (localizations[language] as? [String: Any])?["stringUnit"] as? [String: Any],
                      let value = unit["value"] as? String else { return false }
                return !value.isEmpty
            }
        }
        for group in catalog.groups { #expect(translated(group.name), "group \(group.id)") }
        for theme in catalog.themes {
            #expect(translated(theme.name), "\(theme.id) name")
            #expect(translated(theme.description), "\(theme.id) description")
            #expect(translated(theme.synonyms.joined(separator: ", ")), "\(theme.id) search words")
        }
    }

    /// The compiled catalog answers in another language — what `localizedName` reads at run time.
    @Test func localizedNamesComeFromTheCompiledCatalog() throws {
        let german = try #require(Bundle.module.path(forResource: "de", ofType: "lproj").flatMap(Bundle.init(path:)))
        let anxiety = try #require(catalog.theme(id: "anxiety"))
        let name = german.localizedString(forKey: anxiety.name, value: nil, table: nil)
        #expect(name != anxiety.name && !name.isEmpty)
        let words = german.localizedString(forKey: anxiety.synonyms.joined(separator: ", "), value: nil, table: nil)
        #expect(words.contains(","))
    }

    @Test func normalizesForSearch() {
        #expect(TopicSearch.normalize("God’s  Love!") == "gods love")
        #expect(TopicSearch.normalize("Ansiedad y PREOCUPACIÓN") == "ansiedad y preocupacion")
        #expect(TopicSearch.normalize("self-worth") == "self worth")
    }

    @Test func scoresWholeWordsAboveFragments() {
        #expect(TopicSearch.score("anxious", against: "anxious") == 100)
        #expect(TopicSearch.score("anxiety", against: "anxi") == 80)
        #expect(TopicSearch.score("alone", against: "i feel so alone") == 70)
        #expect(TopicSearch.score("mental health", against: "health") == 60)
        #expect(TopicSearch.score("sad", against: "crusade") == 0)
        #expect(TopicSearch.score("不安", against: "とても不安です") == 70)
    }

    @Test func searchFindsThemesByWhatPeopleType() {
        #expect(catalog.search("anxious").first?.id == "anxiety")
        #expect(catalog.search("Worried").first?.id == "anxiety")
        #expect(catalog.search("grief").first?.id == "grief")
        #expect(catalog.search("I feel so alone").map(\.id).contains("loneliness"))
        #expect(catalog.search("burned out").first?.id == "weariness")
        #expect(catalog.search("forgive").first?.id == "forgiving")
        #expect(catalog.search("xyzzy").isEmpty)
        #expect(catalog.search("an").isEmpty)
        #expect(catalog.search("fear", limit: 2).count <= 2)
    }
}

@Suite struct TopicalIndexTests {
    static let url = LifeThemeTests.root.appending(path: "ScriptureAlone/Resources/Study/Topics.sqlite")

    func index() throws -> TopicalIndex { try TopicalIndex(url: Self.url) }

    @Test func opensWithItsSourceAndLicense() throws {
        let index = try index()
        #expect(index.topics.count > 5_000)
        #expect(index.name == "Nave’s Topical Bible")
        #expect(index.license == "Public domain")
        #expect(!index.attribution.isEmpty)
        #expect(index.topics.map(\.id) == index.topics.map(\.id).sorted())
    }

    @Test func readsATopicsLinesAndLinks() throws {
        let index = try index()
        let anxiety = try #require(index.topic(named: "anxiety"))
        let lines = try index.entries(for: anxiety)
        #expect(lines.flatMap(\.seeAlso).map(\.name) == ["Care"])

        let prayer = try #require(index.topic(named: "Prayer"))
        let entries = try index.entries(for: prayer)
        #expect(entries.count > 50)
        #expect(entries.contains { $0.passages.contains { $0.contains(VerseRef(.matthew, 6, 9)) } })
        #expect(entries.contains { $0.level == 1 })

        let aaron = try #require(index.topic(named: "Aaron"))
        let first = try #require(try index.entries(for: aaron).first)
        #expect(first.label == "Lineage of")
        #expect(first.passages.first == VerseRange(VerseRef(.exodus, 6, 16), VerseRef(.exodus, 6, 20)))
    }

    @Test func searchRanksNamesByHowWellTheyMatch() throws {
        let index = try index()
        #expect(index.search("prayer").first?.name == "Prayer")
        #expect(index.search("abra").first?.name == "Abraham")
        #expect(index.search("x").isEmpty)
    }

    /// The Nave's topics a life theme points to are all in the index.
    @Test func lifeThemesPointAtRealTopics() throws {
        let index = try index()
        for theme in LifeThemeCatalog.shared.themes {
            for name in theme.naveTopics { #expect(index.topic(named: name) != nil, "\(theme.id): \(name)") }
        }
    }

    @Test func decodesCompactEntries() {
        let json = Data(#"[["Of Saul",0,[9010027,9010027],[]],["",1,[],[7]],["bad"]]"#.utf8)
        let entries = TopicalIndex.decodeEntries(json) { IndexTopic(id: $0, name: "Topic \($0)") }
        #expect(entries.count == 2)
        #expect(entries[0].passages == [VerseRange(VerseRef(.firstSamuel, 10, 27))])
        #expect(entries[1].seeAlso == [IndexTopic(id: 7, name: "Topic 7")])
    }
}
