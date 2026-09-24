import Foundation
import Testing
@testable import ScriptureAloneCore

/// Runs the importer against a real, user-owned file named by `SA_IMPORT_PROBE`. Skipped otherwise:
/// the file is copyrighted and never enters the repo. Writes a report next to `SA_IMPORT_PROBE_OUT`.
@Suite struct RealFileProbeTests {
    @Test(.enabled(if: ProcessInfo.processInfo.environment["SA_IMPORT_PROBE"] != nil))
    func probe() throws {
        let env = ProcessInfo.processInfo.environment
        let url = URL(filePath: env["SA_IMPORT_PROBE"]!)
        let out = URL(filePath: env["SA_IMPORT_PROBE_OUT"] ?? NSTemporaryDirectory())
        let (bible, preview) = try BibleFileImporter().read(url)
        let report = ImportCoverageReport(bible)
        var lines = ["format: \(preview.format) docs: \(preview.documentCount)",
                     "identity: \(preview.identity)",
                     report.summary, "quality: \(report.quality.score) continuity \(report.quality.continuity) clean \(report.quality.cleanliness)",
                     "--- problems"] + report.problems
        let study = bible.study
        lines.append("--- study: notes \(study.notes.count) articles \(study.articles.count) (intros \(study.articles.filter { $0.kind == .introduction }.count)) images \(study.images.count) bytes \(study.images.reduce(0) { $0 + ($1.data?.count ?? 0) })")
        for note in study.orderedNotes.prefix(4) { lines.append("NOTE \(note.start.display)–\(note.end.display): \(note.text.prefix(120))") }
        for article in study.articles.prefix(3) { lines.append("ARTICLE \(article.kind) \(article.book.name) \(article.anchor?.display ?? "-") \(article.title): \(article.text.prefix(80))") }
        for image in study.images.prefix(3) { lines.append("IMAGE \(image.anchor?.display ?? image.book?.name ?? "-") \(image.caption) \(image.path)") }
        lines.append("--- shapes")
        let spine = preview.format == .epub ? try EPUBPackage(url: url).spine : []
        let package = preview.format == .epub ? try EPUBPackage(url: url) : nil
        for item in spine {
            let scanned = DocumentScanner(options: .init()).scan(try package!.document(item))
            let shape = report.markupShapes[item.path] ?? .none
            lines.append("\(item.path)\t\(shape)\tverses=\(scanned.shapeCounts[shape] ?? 0)\tchapters=\(scanned.chapterMarkers)\tbacklinks=\(scanned.backLinks)")
        }
        lines.append("--- samples")
        let samples: [(BookID, Int, Int)] = [(.genesis, 1, 1), (.psalms, 23, 1), (.psalms, 119, 176), (.isaiah, 53, 5),
                                             (.john, 3, 16), (.romans, 8, 28), (.revelation, 22, 21), (.thirdJohn, 1, 6)]
        for (b, c, v) in samples {
            lines.append("\(b.name) \(c):\(v)\t\(bible.verses[VerseRef(b, c, v)]?.text ?? "<MISSING>")")
        }
        try lines.joined(separator: "\n").write(to: out.appending(path: "probe.txt"), atomically: true, encoding: .utf8)
        // Every verse, for grepping.
        let all = bible.verses.values.sorted { ($0.ref.book.rawValue, $0.ref.chapter, $0.ref.verse) < ($1.ref.book.rawValue, $1.ref.chapter, $1.ref.verse) }
            .map { "\($0.ref.book.name) \($0.ref.chapter):\($0.ref.verse)\t\($0.text)" }
        try all.joined(separator: "\n").write(to: out.appending(path: "verses.tsv"), atomically: true, encoding: .utf8)
        let headings = bible.chapterOrder.flatMap { ref in bible.blocks(for: ref).compactMap { $0.heading.map { "\(ref.book.name) \(ref.chapter)\t\($0)" } } }
        try headings.joined(separator: "\n").write(to: out.appending(path: "headings.tsv"), atomically: true, encoding: .utf8)
        let dir = out.appending(path: "store"); try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var id = preview.identity
        if id.copyright.isEmpty { id.copyright = "probe" }
        let reference = env["SA_IMPORT_PROBE_RED"].flatMap { try? BibleStore(url: URL(filePath: $0)) }
        let result = try BibleFileImporter().importBible(at: url, as: id, into: dir, redLetters: reference)
        if reference != nil {
            let written = try BibleStore(url: result.storeURL)
            var redLines: [String] = []
            for ref in [VerseRef(.john, 3, 16), VerseRef(.john, 14, 6), VerseRef(.matthew, 5, 3), VerseRef(.mark, 1, 15), VerseRef(.john, 3, 1)] {
                guard let verse = try written.verses(in: VerseRange(ref, ref)).first else { continue }
                let ns = verse.text as NSString
                redLines.append("\(ref.display): \(verse.text) || RED: " + verse.red.map { ns.substring(with: $0) }.joined(separator: " | "))
            }
            try redLines.joined(separator: "\n").write(to: out.appending(path: "red.txt"), atomically: true, encoding: .utf8)
        }
        if let studyStore = ImportedStudyStore(url: result.storeURL, info: try BibleStore(url: result.storeURL).info) {
            var studyLines = ["source: \(studyStore.source.name) (\(studyStore.source.shortName))"]
            for entry in studyStore.commentary(on: VerseRef(.john, 3, 16)) { studyLines.append("JOHN 3:16 [\(entry.range?.display ?? "-")] \(entry.text.prefix(160))") }
            if let intro = studyStore.introduction(to: ChapterRef(.genesis, 1)) { studyLines.append("GEN INTRO \(intro.text.prefix(200))") }
            for image in studyStore.images(in: ChapterRef(.genesis, 10)) { studyLines.append("GEN 10 IMAGE \(image.caption) \(studyStore.imageData(image.id)?.count ?? 0) bytes") }
            try studyLines.joined(separator: "\n").write(to: out.appending(path: "study.txt"), atomically: true, encoding: .utf8)
        }
        print("PROBE store: \(result.storeURL.path) — \(report.summary)")
    }
}
