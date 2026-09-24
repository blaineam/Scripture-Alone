import Foundation
import Testing
@testable import ScriptureAloneCore

@Suite struct CrisisSupportTests {
    @Test func crisisSearchesAreRecognisedInEveryLanguage() {
        for query in ["suicide", "I want to die", "i don't want to live anymore", "Kill myself",
                      "Selbstmord", "quiero morir", "je veux mourir", "voglio morire", "quero morrer",
                      "死にたい", "자살", "想死", "SUICIDIO", "suicídio"] {
            #expect(CrisisSupport.isCrisis(query), "\(query)")
        }
    }

    @Test func ordinarySearchesAreNot() {
        for query in ["", "John 3:16", "love", "peace that passes understanding", "die to self",
                      "worried", "kill", "Lazarus died", "hope"] {
            #expect(!CrisisSupport.isCrisis(query), "\(query)")
        }
    }

    @Test func helplinesFollowTheRegionAndFallBackToTheDirectory() {
        #expect(CrisisSupport.helpline(forRegion: "US")?.dial == "988")
        #expect(CrisisSupport.helpline(forRegion: "us")?.textURL?.absoluteString == "sms:988")
        #expect(CrisisSupport.helpline(forRegion: "GB")?.callURL?.absoluteString == "tel:116123")
        #expect(CrisisSupport.helpline(forRegion: "DE")?.textURL == nil)
        #expect(CrisisSupport.helpline(forRegion: "ZZ") == nil)
        #expect(CrisisSupport.helpline(forRegion: nil) == nil)
        for line in CrisisSupport.helplines.values {
            #expect(line.dial.allSatisfy { $0.isNumber }, "\(line.name)")
            #expect(line.callURL != nil)
        }
    }
}
