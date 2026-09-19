import Testing
@testable import ScriptureAloneCore

/// The catalogue is a third-party file we don't control, so the parser is tested against the
/// shapes eBible actually publishes — quoted commas in copyright lines, added columns, and rows
/// we must not offer.
struct EBibleCatalogTests {
    /// Header taken verbatim from https://ebible.org/Scriptures/translations.csv.
    static let header = """
    "languageCode","translationId","languageName","languageNameInEnglish","dialect","homeDomain",\
    "title","description","Redistributable","Copyright","UpdateDate","publicationURL","OTbooks",\
    "OTchapters","OTverses","NTbooks","NTchapters","NTverses","DCbooks","DCchapters","DCverses",\
    "FCBHID","Certified","inScript","swordName","rodCode","textDirection","downloadable","font",\
    "shortTitle","PODISBN","script","sourceDate"
    """

    static func row(id: String = "engwebp", language: String = "English", title: String = "World English Bible",
                    redistributable: String = "True", downloadable: String = "True",
                    copyright: String = "Public Domain", ot: Int = 39, otVerses: Int = 23145,
                    nt: Int = 27, ntVerses: Int = 7957, direction: String = "ltr",
                    short: String = "WEB") -> String {
        """
        "eng","\(id)","\(language)","\(language)","","ebible.org","\(title)","A description",\
        "\(redistributable)","\(copyright)","2026-01-01","https://ebible.org/\(id)/","\(ot)","929",\
        "\(otVerses)","\(nt)","260","\(ntVerses)","0","0","0","ENGWEB","True","","\(id)","","\(direction)",\
        "\(downloadable)","Gentium","\(short)","","Latin","2026-01-01"
        """
    }

    @Test func readsATranslation() throws {
        let entries = try EBibleCatalog.parse(csv: Self.header + "\n" + Self.row() + "\n")
        #expect(entries.count == 1)
        let web = try #require(entries.first)
        #expect(web.id == "engwebp")
        #expect(web.title == "World English Bible")
        #expect(web.shortTitle == "WEB")
        #expect(web.copyright == "Public Domain")
        #expect(web.scope == "Complete Bible")
        #expect(web.verseCount == 31102)
        #expect(!web.isRightToLeft)
        #expect(web.downloadURL.absoluteString == "https://ebible.org/Scriptures/engwebp_usfm.zip")
    }

    @Test func dropsWhatWeMayNotOffer() throws {
        let csv = [Self.header,
                   Self.row(id: "keep"),
                   Self.row(id: "norights", redistributable: "False"),
                   Self.row(id: "nofile", downloadable: "False"),
                   Self.row(id: "empty", ot: 0, otVerses: 0, nt: 0, ntVerses: 0)].joined(separator: "\n")
        let ids = try EBibleCatalog.parse(csv: csv).map(\.id)
        #expect(ids == ["keep"])
    }

    /// Copyright lines routinely contain commas, and some contain quoted names.
    @Test func handlesCommasAndQuotesInsideFields() throws {
        let line = Self.row(copyright: "Copyright © 2009 Wycliffe Bible Translators, Inc., all rights reserved")
        let entry = try #require(try EBibleCatalog.parse(csv: Self.header + "\n" + line).first)
        #expect(entry.copyright == "Copyright © 2009 Wycliffe Bible Translators, Inc., all rights reserved")
    }

    @Test func handlesDoubledQuotes() throws {
        let csv = Self.header + "\n" + Self.row(title: "The \"\"Good News\"\" Bible")
        let entry = try #require(try EBibleCatalog.parse(csv: csv).first)
        #expect(entry.title == "The \"Good News\" Bible")
    }

    /// Columns are addressed by name, so eBible adding one in the middle must not shift the rest.
    @Test func survivesAnInsertedColumn() throws {
        let header = Self.header.replacingOccurrences(of: "\"title\"", with: "\"newColumn\",\"title\"")
        let row = Self.row().replacingOccurrences(of: "\"World English Bible\"",
                                                  with: "\"something\",\"World English Bible\"")
        let entry = try #require(try EBibleCatalog.parse(csv: header + "\n" + row).first)
        #expect(entry.title == "World English Bible")
        #expect(entry.copyright == "Public Domain")
    }

    @Test func rejectsACatalogueMissingItsColumns() {
        #expect(throws: EBibleCatalog.Failure.self) {
            try EBibleCatalog.parse(csv: "\"a\",\"b\"\n\"1\",\"2\"\n")
        }
    }

    @Test func readsScopeAndDirection() throws {
        let nt = try #require(try EBibleCatalog.parse(
            csv: Self.header + "\n" + Self.row(ot: 0, otVerses: 0, direction: "rtl")).first)
        #expect(nt.scope == "New Testament")
        #expect(nt.isRightToLeft)
    }

    @Test func toleratesCRLFAndATrailingNewline() throws {
        let csv = (Self.header + "\r\n" + Self.row() + "\r\n")
        #expect(try EBibleCatalog.parse(csv: csv).count == 1)
    }
}
