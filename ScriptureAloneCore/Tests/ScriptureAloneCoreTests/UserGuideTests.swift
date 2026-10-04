import Foundation
import Testing
@testable import ScriptureAloneCore

/// The User Guide's package format: what `Tools/manual_json.py` writes and the apps draw.
@Suite struct UserGuideTests {
    static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    /// Every block kind the converter writes, in the converter's own shape.
    static let sample = """
    {"schema":1,"language":"en","title":"Scripture Alone — User Guide","contents":"Contents",
     "cover":{"eyebrow":"User Guide","title":"Scripture Alone Bible","subtitle":"Everything.","edition":"v1","images":["01-reader.png"]},
     "chapters":[{"title":"Welcome","summary":"What it is","lede":[{"text":"Hello "},{"text":"Aa","ui":true}],
      "blocks":[
       {"type":"paragraph","inline":[{"text":"Press "},{"text":"⌘L","kbd":true},{"text":"\\n","br":true},{"text":"site","link":"https://example.org"}]},
       {"type":"paragraph","inline":[{"text":"fine print"}],"fine":true},
       {"type":"heading","inline":[{"text":"A heading","bold":true}]},
       {"type":"list","items":[[{"text":"one"}],[{"text":"two","code":true}]]},
       {"type":"steps","items":[[{"type":"paragraph","inline":[{"text":"Step"}]}]]},
       {"type":"table","header":[[{"text":"A"}],[{"text":"B"}]],"rows":[[[{"text":"1"}],[{"text":"2","small":true}]]]},
       {"type":"callout","style":"tip","label":"Tip","blocks":[{"type":"list","items":[[{"text":"x"}]]}]},
       {"type":"figure","image":"03-study.png","device":"phone","caption":"Study"},
       {"type":"feature","figure":{"type":"figure","image":"w02-verse.png","device":"watch","caption":"Watch"},"blocks":[],"flip":true},
       {"type":"feature","figure":null,"mock":{"bar":{"leading":"Cancel","title":"Keys","trailing":"Done"},
         "sections":[{"header":"Crossway","rows":[{"text":"API key","style":"field","highlight":true},
           {"text":"Remove Key","style":"destructive","icon":"🗑"},{"text":"CSB","style":"plain","detail":"English","checked":true}],
           "footer":"Free tier."}]},"blocks":[{"type":"heading","inline":[{"text":"Keys"}]}]},
       {"type":"sparkles","whatever":1}
      ]}]}
    """

    @Test func decodesEveryBlockKind() throws {
        let guide = try JSONDecoder().decode(UserGuide.self, from: Data(Self.sample.utf8))
        let blocks = guide.chapters[0].blocks
        #expect(blocks.count == 11)
        guard case .paragraph(let runs, false) = blocks[0] else { Issue.record("paragraph"); return }
        #expect(runs[1].kbd && runs[2].lineBreak && runs[3].link == "https://example.org")
        guard case .paragraph(_, true) = blocks[1] else { Issue.record("fine"); return }
        guard case .table(let header, let rows) = blocks[5] else { Issue.record("table"); return }
        #expect(header.count == 2 && rows[0][1][0].small)
        guard case .callout(.tip, "Tip", let inner) = blocks[6], case .list = inner.first else { Issue.record("callout"); return }
        guard case .feature(let fig?, nil, _, true) = blocks[8] else { Issue.record("watch feature"); return }
        #expect(fig.device == .watch)
        guard case .feature(nil, let mock?, _, false) = blocks[9] else { Issue.record("mock feature"); return }
        #expect(mock.sections[0].rows.map(\.style) == [.field, .destructive, .plain])
        #expect(mock.sections[0].rows[2].checked == true)
        #expect(blocks[10] == .unknown, "a block kind from a newer guide is skipped, not fatal")
        #expect(guide.chapters[0].lede[1].ui)
    }

    @Test func roundTrips() throws {
        let guide = try JSONDecoder().decode(UserGuide.self, from: Data(Self.sample.utf8))
        let again = try JSONDecoder().decode(UserGuide.self, from: JSONEncoder().encode(guide))
        #expect(again == guide)
    }

    @Test func picksTheLanguage() {
        #expect(UserGuidePackage.language(for: ["ja"]) == "ja")
        #expect(UserGuidePackage.language(for: ["zh-Hans-CN"]) == "zh-Hans")
        #expect(UserGuidePackage.language(for: ["pt-PT"]) == "pt-BR")
        #expect(UserGuidePackage.language(for: ["de_AT"]) == "de")
        #expect(UserGuidePackage.language(for: ["nl", "fr"]) == "fr")
        #expect(UserGuidePackage.language(for: ["nl"]) == "en")
    }

    @Test func namesEachDevicesEdition() throws {
        #expect(UserGuidePackage.edition(language: "ja", device: .iphone) == "ja")
        #expect(UserGuidePackage.edition(language: "ja", device: .ipad) == "ipad-ja")
        #expect(UserGuidePackage.edition(language: "pt-BR", device: .mac) == "mac-pt-BR")
        let index = try JSONDecoder().decode(UserGuidePackage.Index.self, from: Data("""
        {"schema":1,"packages":{"ja":{"sha256":"a","size":1},"ipad-ja":{"sha256":"b","size":1}}}
        """.utf8))
        #expect(UserGuidePackage.entryName(edition: "ipad-ja", language: "ja", index: index) == "ipad-ja")
        #expect(UserGuidePackage.entryName(edition: "mac-ja", language: "ja", index: index) == "ja",
                "an edition CI hasn't published yet falls back to the iPhone edition")
    }

    @Test func unpacksAPackage() throws {
        let zip = ImportFixtures.zip([
            .init("guide.json", Self.sample, deflate: true),
            .init("images/01-reader.png", data: Data([0x89, 0x50, 0x4E, 0x47])),
        ])
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("guide-\(UUID().uuidString)/en")
        defer { try? FileManager.default.removeItem(at: dir.deletingLastPathComponent()) }
        let guide = try UserGuidePackage.unpack(zip, into: dir)
        #expect(guide.chapters.count == 1)
        #expect(FileManager.default.fileExists(atPath: dir.appendingPathComponent("images/01-reader.png").path))
        #expect(UserGuidePackage.load(from: dir) == guide)
        // Unpacking again replaces the copy in place.
        try UserGuidePackage.unpack(zip, into: dir)
        #expect(UserGuidePackage.load(from: dir) == guide)
    }

    @Test func refusesAPathOutOfTheDirectory() throws {
        let zip = ImportFixtures.zip([
            .init("guide.json", Self.sample),
            .init("images/../../escape.png", data: Data([1])),
        ])
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("guide-\(UUID().uuidString)/en")
        defer { try? FileManager.default.removeItem(at: dir.deletingLastPathComponent()) }
        #expect(throws: UserGuidePackage.PackageError.self) { try UserGuidePackage.unpack(zip, into: dir) }
        #expect(!FileManager.default.fileExists(atPath: dir.path))
    }

    @Test func refusesANewerSchema() throws {
        let newer = Self.sample.replacingOccurrences(of: "\"schema\":1", with: "\"schema\":99")
        let zip = ImportFixtures.zip([.init("guide.json", newer)])
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("guide-\(UUID().uuidString)/en")
        #expect(throws: UserGuidePackage.PackageError.unsupportedSchema(99)) {
            try UserGuidePackage.unpack(zip, into: dir)
        }
    }

    /// The real packages, when `python3 Tools/build_manual.py --packages` has been run: every one
    /// decodes, holds every image it names, and has no block this build can't draw.
    @Test func everyBuiltPackageReads() throws {
        let dist = Self.root.appendingPathComponent("dist/manual")
        let zips = ((try? FileManager.default.contentsOfDirectory(at: dist, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "zip" }
        for url in zips {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("guide-\(UUID().uuidString)/x")
            defer { try? FileManager.default.removeItem(at: dir.deletingLastPathComponent()) }
            let guide = try UserGuidePackage.unpack(try Data(contentsOf: url), into: dir)
            #expect(guide.chapters.count >= 10, "\(url.lastPathComponent)")
            var images: [String] = guide.cover.images
            func walk(_ blocks: [UserGuide.Block]) {
                for b in blocks {
                    switch b {
                    case .figure(let f): images.append(f.image)
                    case .feature(let f, _, let inner, _): if let f { images.append(f.image) }; walk(inner)
                    case .callout(_, _, let inner): walk(inner)
                    case .steps(let items): items.forEach(walk)
                    case .unknown: Issue.record("\(url.lastPathComponent) has a block this build can't draw")
                    default: break
                    }
                }
            }
            guide.chapters.forEach { walk($0.blocks) }
            for name in Set(images) {
                #expect(FileManager.default.fileExists(atPath: dir.appendingPathComponent("images/\(name)").path),
                        "\(url.lastPathComponent) is missing \(name)")
            }
        }
    }

    @Test func verifiesAPackageAgainstItsHash() {
        let data = Data("abc".utf8)
        let abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        #expect(UserGuidePackage.sha256(of: data) == abc)
        #expect(UserGuidePackage.verify(data, sha256: abc))
        #expect(UserGuidePackage.verify(data, sha256: abc.uppercased()))
        #expect(!UserGuidePackage.verify(Data("abd".utf8), sha256: abc))
        #expect(!UserGuidePackage.verify(data, sha256: ""))
    }

    @Test func updatesOnlyWhenTheIndexDiffers() throws {
        let index = try JSONDecoder().decode(UserGuidePackage.Index.self, from: Data("""
        {"schema":1,"packages":{"en":{"sha256":"AA11","size":10},"ja":{"sha256":"bb22","size":12}}}
        """.utf8))
        #expect(!UserGuidePackage.needsUpdate(held: "aa11", index: index, language: "en"))
        #expect(UserGuidePackage.needsUpdate(held: "aa11", index: index, language: "ja"))
        #expect(UserGuidePackage.needsUpdate(held: nil, index: index, language: "ja"))
        #expect(!UserGuidePackage.needsUpdate(held: "x", index: index, language: "ko"))
        var newer = index
        newer.schema = UserGuide.supportedSchema + 1
        #expect(!UserGuidePackage.needsUpdate(held: nil, index: newer, language: "en"))
    }

    @Test func choosesAGuideLanguage() {
        #expect(UserGuidePackage.language(for: ["ja"]) == "ja")
        #expect(UserGuidePackage.language(for: ["zh-Hant", "en"]) == "zh-Hans")
        #expect(UserGuidePackage.language(for: ["pt-PT"]) == "pt-BR")
        #expect(UserGuidePackage.language(for: ["de_AT"]) == "de")
        #expect(UserGuidePackage.language(for: ["nl", "fr"]) == "fr")
        #expect(UserGuidePackage.language(for: ["nl"]) == "en")
    }

    @Test func offersTheGuideOnce() {
        #expect(UserGuidePrompt.shouldOffer(alreadyShown: false, openedForSomethingElse: false, automated: false))
        #expect(!UserGuidePrompt.shouldOffer(alreadyShown: true, openedForSomethingElse: false, automated: false))
        #expect(!UserGuidePrompt.shouldOffer(alreadyShown: false, openedForSomethingElse: true, automated: false))
        #expect(!UserGuidePrompt.shouldOffer(alreadyShown: false, openedForSomethingElse: false, automated: true))
        #expect(UserGuidePrompt.isTestRun)
    }
}
