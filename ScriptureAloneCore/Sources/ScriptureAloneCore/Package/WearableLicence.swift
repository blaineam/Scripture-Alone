import Foundation

/// The kind of device opening a package. A publisher can keep a translation off watches and other
/// wearables entirely (`PackagePolicy.wearables`); `TranslationPackage.open` enforces that on a
/// `.wearable`, and `.current` is `.wearable` in the watch app, so it cannot be forgotten there.
public enum PackageDevice: Sendable, Hashable {
    case handheld
    case wearable

    public static var current: PackageDevice {
        #if os(watchOS)
        .wearable
        #else
        .handheld
        #endif
    }
}

/// The wearables term, read where no key is held: on the phone, deciding whether to send a package
/// to the watch, and on the watch, sweeping out a package that should never have arrived.
///
/// These read the header **without verifying its signature**, and that is safe in both directions
/// because neither decision grants anything. Editing a header to say "allowed" breaks its signature,
/// so the package the phone then sends is one the watch refuses to open (`TranslationPackage.open`
/// verifies first, then checks the term). Editing one to say "prohibited" only gets a file the
/// attacker already held deleted. The gate that matters is the signed one; these keep the text from
/// travelling at all, and keep a watch from holding a file it may not read.
public enum WearableLicence {

    /// The policy in a package's header, unverified. Nil when the file is not a readable package.
    public static func unverifiedPolicy(ofPackageAt url: URL) -> PackagePolicy? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        let magic = TranslationPackageFormat.magic
        guard let preamble = try? handle.read(upToCount: magic.count + 6), preamble.count == magic.count + 6,
              preamble.prefix(magic.count) == magic else { return nil }
        let bytes = [UInt8](preamble)
        let length = bytes[(magic.count + 2)..<(magic.count + 6)].reduce(0) { $0 << 8 | Int($1) }
        guard length > 0, length <= TranslationPackageFormat.maximumHeaderBytes,
              let header = try? handle.read(upToCount: length), header.count == length,
              let decoded = try? JSONDecoder().decode(PolicyOnly.self, from: header) else { return nil }
        return decoded.policy
    }

    /// Whether a package may go to, or stay on, a watch. An unreadable file may not: a watch could
    /// not open it anyway, and "can't tell" must not become "send it".
    public static func allowsWearables(packageAt url: URL) -> Bool {
        unverifiedPolicy(ofPackageAt: url)?.allowsWearables ?? false
    }

    /// Deletes every `.sabible` in `directory` whose terms keep it off wearables — on the watch, at
    /// launch and whenever editions are reloaded, so one that arrived by any route (a restore, an
    /// older phone app, a copy made by hand) never stays. Files that aren't packages are left alone.
    /// Returns the files removed.
    @discardableResult
    public static func removeProhibitedPackages(in directory: URL) -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        var removed: [URL] = []
        for file in files where file.pathExtension == "sabible" {
            guard let policy = unverifiedPolicy(ofPackageAt: file), !policy.allowsWearables else { continue }
            if (try? FileManager.default.removeItem(at: file)) != nil { removed.append(file) }
        }
        return removed
    }

    private struct PolicyOnly: Decodable {
        let policy: PackagePolicy
    }
}

public extension VerseSnapshot {
    /// What a watch's complications may read: the Verse of the Day snapshot for `source`, the
    /// translation the watch reads — or, when it has none it may show (nothing opened, or one whose
    /// terms keep it off wearables), one in the public daily list's fallback translation with no text
    /// of its own, so no complication or tile keeps showing a prohibited translation's words from an
    /// earlier snapshot. `daily` supplies the coming days' passages from an allowed source.
    static func forWearable(source: (any ChapterTextSource)?, translation: String, generatedAt: Date = .now,
                            daily: (any ChapterTextSource) -> [String: DailyText]?) -> VerseSnapshot {
        guard let source, source.info.rights.allowWearables else {
            return VerseSnapshot(generatedAt: generatedAt, translation: DailyVerseCatalog.fallbackTranslation, items: [])
        }
        return VerseSnapshot(generatedAt: generatedAt, translation: translation, items: [],
                             abbreviation: source.info.abbreviation, daily: daily(source))
    }
}
