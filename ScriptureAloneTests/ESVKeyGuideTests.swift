import Foundation
import Testing
@testable import Scripture_Alone

/// The ESV key guide's native parts: the check that a scraped key really works (so a mis-scraped
/// string is never saved) and the script and words handed to Crossway's own pages. The web view
/// itself loads Crossway over the network, so it is not hosted here.
struct ESVKeyGuideTests {
    static let checkURL = "https://api.esv.org/v3/passage/text/?q=John%201:1&include-passage-references=false&include-footnotes=false&include-headings=false"

    @Test func aKeyCrosswayAcceptsIsValid() async {
        let (session, id) = StubURLProtocol.session([Self.checkURL: .init(body: Data("{}".utf8))])
        #expect(await ESVKeyCheck.validate("good-key", session: session) == true)
        #expect(StubURLProtocol.requests(id) == [Self.checkURL])
    }

    @Test func aKeyCrosswayRejectsIsInvalid() async {
        for status in [401, 403] {
            let (session, _) = StubURLProtocol.session([Self.checkURL: .init(status: status)])
            #expect(await ESVKeyCheck.validate("bad-key", session: session) == false)
        }
    }

    @Test func serverTroubleOrNoConnectionCannotTell() async {
        let (busy, _) = StubURLProtocol.session([Self.checkURL: .init(status: 503)])
        #expect(await ESVKeyCheck.validate("any", session: busy) == nil)
        let (missing, _) = StubURLProtocol.session([:]) // a 404
        #expect(await ESVKeyCheck.validate("any", session: missing) == nil)
    }

    @Test func theGuideScriptCarriesItsWordsAndTheApplicationDetails() {
        let strings = ESVKeyGuideScript.strings()
        #expect(strings["createURL"] == ESVKeyGuideScript.createApplicationURL)
        #expect(strings["registerURL"] == ESVKeyGuideScript.registerURL)
        #expect(strings["appName"] == "Scripture Alone (personal use)")
        #expect(strings.values.allSatisfy { !$0.isEmpty })
        let source = ESVKeyGuideScript.source()
        #expect(source.hasPrefix("window.__saT = {"))
        #expect(source.contains("Scripture Alone (personal use)"))
        #expect(source.count > 500, "the shared ESVKeyGuide.js is bundled and follows the strings")
    }
}
