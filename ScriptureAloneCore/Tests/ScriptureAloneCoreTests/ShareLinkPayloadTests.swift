import Foundation
import Testing
@testable import ScriptureAloneCore

@Suite struct ShareLinkPayloadTests {
    private func substring(_ text: String, _ range: NSRange) -> String {
        (text as NSString).substring(with: range)
    }

    private func json(_ payload: ShareLinkPayload) throws -> String {
        let data = try #require(ShareLinkPayload.base64URLDecode(try payload.encoded()))
        return try #require(String(data: data, encoding: .utf8))
    }

    @Test func roundTripsThroughTheWebURL() throws {
        let payload = ShareLinkPayload(reference: "John 3:16–17", keys: "43003016-43003017", translation: "ASV",
                                       text: "16 For God so loved the world… 17 For God sent not the Son…",
                                       red: [NSRange(location: 3, length: 27)],
                                       template: "parchment", font: "serif", aspect: "square")
        let url = try payload.webURL()
        #expect(url.absoluteString.hasPrefix("https://wemiller.com/apps/scripture-alone/#s="))
        let encoded = try payload.encoded()
        #expect(!encoded.contains("="))
        #expect(!encoded.contains("+"))
        #expect(!encoded.contains("/"))
        #expect(try ShareLinkPayload(url: url) == payload)
        #expect(try ShareLinkPayload(encoded: encoded) == payload)
        #expect(payload.ranges == [VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 17))])
    }

    @Test func jsonMatchesTheContract() throws {
        let payload = ShareLinkPayload(reference: "John 3:16", keys: "43003016-43003016", translation: "ASV",
                                       text: "For God so loved the world", red: [NSRange(location: 0, length: 7)])
        let object = try JSONSerialization.jsonObject(with: Data(try json(payload).utf8))
        let fields = try #require(object as? [String: Any])
        #expect(fields["v"] as? Int == 1)
        #expect(fields["ref"] as? String == "John 3:16")
        #expect(fields["k"] as? String == "43003016-43003016")
        #expect(fields["tr"] as? String == "ASV")
        #expect(fields["t"] as? String == "For God so loved the world")
        #expect(fields["red"] as? [[Int]] == [[0, 7]])
        // Optional keys are omitted rather than sent empty.
        #expect(fields["tp"] == nil && fields["f"] == nil && fields["a"] == nil)

        let plain = ShareLinkPayload(reference: "Genesis 1:1", keys: "1001001-1001001", translation: "KJV", text: "In the beginning")
        #expect(try !json(plain).contains("\"red\""))
    }

    @Test func survivesUnicode() throws {
        // Curly quotes, an em dash, an ellipsis, Hebrew letters (Psalm 119 acrostic) and an astral-plane
        // character (two UTF-16 units) ahead of a red range.
        let text = "א ALEPH. “Blessed are they”—perfect in the way… 𝔊 “I am the way”"
        let quoteStart = (text as NSString).range(of: "“I am the way”")
        let payload = ShareLinkPayload(reference: "Psalms 119:1", keys: "19119001-19119001", translation: "ASV",
                                       text: text, red: [quoteStart], template: "night", font: "iowan", aspect: "story")
        let decoded = try ShareLinkPayload(url: try payload.webURL())
        #expect(decoded == payload)
        #expect(decoded.text == text)
        #expect(substring(decoded.text, decoded.red[0]) == "“I am the way”")
        // UTF-8 JSON, not \u escapes: Hebrew stays compact.
        let raw = try json(payload)
        #expect(raw.contains("א ALEPH"))
        #expect(raw.contains("—"))
    }

    @Test func dropsBadRedRangesInsteadOfFailing() throws {
        let json = #"{"v":1,"ref":"John 3:16","k":"43003016","tr":"ASV","t":"For God","red":[[0,3],[-1,2],[5,99],[40,2],[1],[2,0]]}"#
        let payload = try ShareLinkPayload(encoded: ShareLinkPayload.base64URLEncode(Data(json.utf8)))
        #expect(payload.red == [NSRange(location: 0, length: 3), NSRange(location: 5, length: 2)])
        #expect(payload.ranges == [VerseRange(VerseRef(.john, 3, 16))])
    }

    @Test func rejectsMalformedPayloads() {
        #expect(throws: ShareLinkPayload.DecodingError.missingPayload) {
            try ShareLinkPayload(url: URL(string: "https://wemiller.com/apps/scripture-alone/")!)
        }
        #expect(throws: ShareLinkPayload.DecodingError.notBase64) { try ShareLinkPayload(encoded: "!!!") }
        #expect(throws: ShareLinkPayload.DecodingError.malformed) {
            try ShareLinkPayload(encoded: ShareLinkPayload.base64URLEncode(Data("{\"v\":1}".utf8)))
        }
        #expect(throws: ShareLinkPayload.DecodingError.unsupportedVersion(2)) {
            try ShareLinkPayload(encoded: ShareLinkPayload.base64URLEncode(Data(#"{"v":2,"ref":"x","t":"y"}"#.utf8)))
        }
    }

    @Test func composesNumberedVersesWithShiftedRedRanges() throws {
        let verses = [
            VerseText(ref: VerseRef(.john, 3, 16), text: "For God so loved the world.", red: [NSRange(location: 0, length: 27)]),
            VerseText(ref: VerseRef(.john, 3, 17), text: "“For God sent not the Son.”", red: [NSRange(location: 1, length: 25)]),
        ]
        let passage = SharePassageText(verses: verses)
        #expect(passage.text == "16 For God so loved the world. 17 “For God sent not the Son.”")
        #expect(passage.numbers.map { substring(passage.text, $0) } == ["16", "17"])
        #expect(passage.red.map { substring(passage.text, $0) } == ["For God so loved the world.", "For God sent not the Son."])

        let single = SharePassageText(verses: [verses[0]])
        #expect(single.text == "For God so loved the world.")
        #expect(single.numbers.isEmpty)

        let unnumbered = SharePassageText(verses: verses, numbered: false)
        #expect(unnumbered.text == "For God so loved the world. “For God sent not the Son.”")
        #expect(unnumbered.red.map { substring(unnumbered.text, $0) } == ["For God so loved the world.", "For God sent not the Son."])
    }

    @Test func numbersAChapterBreakWithItsChapter() {
        let verses = [
            VerseText(ref: VerseRef(.genesis, 1, 31), text: "It was very good.", red: []),
            VerseText(ref: VerseRef(.genesis, 2, 1), text: "Thus the heavens were finished.", red: []),
        ]
        #expect(SharePassageText(verses: verses).text == "31 It was very good. 2:1 Thus the heavens were finished.")
    }

    @Test func composesRealVersesFromTheStore() throws {
        let store = try BibleStore(url: BibleStoreTests.biblesDirectory.appending(path: "ASV.sqlite"))
        let range = VerseRange(VerseRef(.john, 14, 5), VerseRef(.john, 14, 6))
        let verses = try store.verses(in: range)
        let passage = SharePassageText(verses: verses)
        let payload = ShareLinkPayload(ranges: [range], reference: range.display, translation: store.info.abbreviation,
                                       passage: passage, template: "parchment", font: "serif", aspect: "square")
        let decoded = try ShareLinkPayload(url: try payload.webURL())
        #expect(decoded.reference == "John 14:5–6")
        #expect(decoded.keys == "43014005-43014006")
        #expect(decoded.text.hasPrefix("5 Thomas saith"))
        #expect(decoded.red.contains { substring(decoded.text, $0).hasPrefix("I am the way") })
        #expect(decoded.fitsInLink)
    }

    @Test func parsesAppLinks() throws {
        let open = try #require(AppLink(url: URL(string: "scripturealone://open?ref=43003016-43003017")!))
        #expect(open == .open([VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 17))]))

        let multiple = try #require(AppLink(url: URL(string: "scripturealone://open?ref=45008001-45008004,45008028")!))
        #expect(multiple == .open([VerseRange(VerseRef(.romans, 8, 1), VerseRef(.romans, 8, 4)), VerseRange(VerseRef(.romans, 8, 28))]))

        let ranges = [VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 17))]
        #expect(AppLink.openURL(for: ranges)?.absoluteString == "scripturealone://open?ref=43003016-43003017")

        let payload = ShareLinkPayload(reference: "John 3:16–17", keys: "43003016-43003017", translation: "ASV", text: "16 For God")
        #expect(AppLink(url: try payload.webURL()) == .share(payload))
        #expect(AppLink(url: URL(string: "scripturealone://open#s=\(try payload.encoded())")!) == .share(payload))

        #expect(AppLink(url: URL(string: "https://example.com/apps/scripture-alone/#s=\(try payload.encoded())")!) == nil)
        #expect(AppLink(url: URL(string: "https://wemiller.com/apps/scripture-alone/")!) == nil)
        #expect(AppLink(url: URL(string: "scripturealone://open?ref=nonsense")!) == nil)
    }

    @Test func capsLinkTextLength() {
        let long = ShareLinkPayload(reference: "Psalms 119", keys: "19119001-19119176", translation: "ASV",
                                    text: String(repeating: "a", count: ShareLinkPayload.maxTextLength + 1))
        #expect(!long.fitsInLink)
    }
}
