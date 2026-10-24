import Foundation
import ScriptureAloneCore

/// The last weekly study a reader opened, kept on the device so it can be read offline. One per source,
/// replaced by the next one fetched; it lives in Application Support, is never bundled or shared, and
/// goes when the plan does (`discard`).
enum StudyCache {
    static var directory = URL.applicationSupportDirectory.appending(path: "Plans/Studies", directoryHint: .isDirectory)

    private static func file(_ source: PlanSource) -> URL {
        directory.appending(path: "\(source.siteURL.absoluteString.unicodeScalars.reduce(UInt64(14695981039346656037)) { ($0 ^ UInt64($1.value)) &* 1099511628211 }).json")
    }

    static func save(_ post: StudyPost, for source: PlanSource) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? JSONEncoder().encode(post).write(to: file(source), options: .atomic)
    }

    static func load(for source: PlanSource) -> StudyPost? {
        (try? Data(contentsOf: file(source))).flatMap { try? JSONDecoder().decode(StudyPost.self, from: $0) }
    }

    static func discard(_ source: PlanSource) { try? FileManager.default.removeItem(at: file(source)) }
}
