import Testing
@testable import ScriptureAloneCore

/// The rule that decides whether a translation's text may leave the device.
struct TranslationRightsTests {
    func info(license: String, copyright: String) -> TranslationInfo {
        TranslationInfo(id: "X", name: "X", abbreviation: "X", copyright: copyright, license: license)
    }

    @Test func bundledPublicDomainTextsAreFree() {
        let asv = info(license: "Public domain", copyright: "American Standard Version (1901). Public domain.")
        #expect(asv.isPublicDomain)
        #expect(asv.attributionNotice == nil)
        #expect(asv.mayHandOffToOtherApps)
    }

    /// An imported translation is judged by its licence, not by having been imported — the WEB
    /// arrives the same way a licensed text would.
    @Test func importedPublicDomainIsStillFree() {
        let web = info(license: "public domain", copyright: "public domain")
        #expect(web.isPublicDomain)
        #expect(web.mayHandOffToOtherApps)
    }

    @Test func aLicensedTranslationCarriesItsNoticeAndStaysOnTheDevice() {
        let csb = info(license: "All rights reserved",
                       copyright: "Copyright © 2017 by Holman Bible Publishers")
        #expect(!csb.isPublicDomain)
        #expect(csb.attributionNotice == "Copyright © 2017 by Holman Bible Publishers")
        #expect(!csb.mayHandOffToOtherApps)
    }

    @Test func quotationIsCappedForLicensedTextOnly() {
        let csb = info(license: "All rights reserved", copyright: "Copyright © 2017 Holman")
        let asv = info(license: "Public domain", copyright: "")
        #expect(csb.mayQuote(verseCount: 12))
        #expect(!csb.mayQuote(verseCount: 501))
        #expect(asv.mayQuote(verseCount: 31_102))
    }

    @Test func aBlankCopyrightIsTreatedAsFree() {
        #expect(info(license: "", copyright: "   ").isPublicDomain)
    }
}
