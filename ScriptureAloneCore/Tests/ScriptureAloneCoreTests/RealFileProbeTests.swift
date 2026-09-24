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
                     report.summary, "--- problems"] + report.problems
        lines.append("--- shapes")
        let package = try EPUBPackage(url: url)
        for item in package.spine {
            let scanned = DocumentScanner(options: .init()).scan(try package.document(item))
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
        let result = try BibleFileImporter().importBible(at: url, as: id, into: dir)
        print("PROBE store: \(result.storeURL.path) — \(report.summary)")
    }
}
