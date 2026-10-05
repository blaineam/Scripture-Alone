import Foundation
import Testing
@testable import ScriptureAloneCore

/// A share link made on an iPhone opens on Android and on the web, and the reverse: both encoders
/// must write the same bytes for the same passage. The golden link lives with Android's test
/// resources (`share/golden-share-link.txt`), where `ShareLinkGoldenTest.kt` holds Kotlin to it too.
struct ShareLinkGoldenTests {
    static let golden: String = {
        let url = URL(filePath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "android/app/src/test/resources/share/golden-share-link.txt")
        return ((try? String(contentsOf: url, encoding: .utf8)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }()

    static let payload = ShareLinkPayload(
        reference: "John 14:5–6", keys: "43014005-43014006", translation: "ASV",
        text: "5 Thomas saith unto him, Lord, we know not whither thou goest; 6 Jesus saith unto him, “I am the way.”",
        red: [NSRange(location: 76, length: 15)], template: "parchment", font: "serif", aspect: "square")

    @Test func encodesToTheSharedGoldenLink() throws {
        #expect(!Self.golden.isEmpty, "golden fixture missing")
        #expect(try Self.payload.webURL().absoluteString == Self.golden)
    }

    @Test func decodesTheSharedGoldenLink() throws {
        let decoded = try ShareLinkPayload(url: try #require(URL(string: Self.golden)))
        #expect(decoded == Self.payload)
        #expect(decoded.ranges == [VerseRange(VerseRef(.john, 14, 5), VerseRef(.john, 14, 6))])
        #expect(AppLink(url: try #require(URL(string: Self.golden))) == .share(Self.payload))
    }
}
