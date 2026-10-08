import Foundation

/// The WatchConnectivity vocabulary shared by the phone (`WatchLink`) and the watch
/// (`WatchPhoneLink`). Compiled into both targets so the two cannot drift apart.
enum WatchLinkKeys {
    /// Phone → watch, application context and file metadata: a translation identifier.
    nonisolated static let translation = "translation"
    /// Phone → watch, application context: when the reader switched to it on the phone, as a
    /// time interval since 1970. Recorded at the switch, not at send time, so a phone launch that
    /// merely re-reports an old choice cannot override a newer pick made on the watch.
    nonisolated static let changedAt = "changedAt"
    /// Watch → phone, application context: identifiers of the editions the phone has sent that the
    /// watch still holds.
    nonisolated static let editions = "editions"
    /// Watch → phone, application context: identifier → `version` of each edition it holds, so a
    /// translation re-imported on the phone is sent again.
    nonisolated static let editionVersions = "editionVersions"
    /// Phone → watch, application context: every imported translation the phone offers the watch.
    /// A received import missing from it was removed on the phone, and the watch removes it too.
    nonisolated static let imports = "imports"
    /// Phone → watch, application context: the reader's accent colour, as 0xRRGGBB (its dark-page
    /// value, the watch's face being always dark).
    nonisolated static let accent = "accent"
    /// Watch → phone, application context: the translations inside the watch app, which the phone
    /// never sends. A watch app that doesn't say (1.1.0 and earlier) carries `legacyBundled`.
    nonisolated static let bundled = "bundled"
    nonisolated static let legacyBundled = ["ASV", "BSB", "KJV"]
    /// Translations sent as their sealed package (`<id>.sabible`) rather than a compact edition: the
    /// licensed text stays encrypted on the watch too, and the ASV is only ever a package.
    nonisolated static let sealed: Set<String> = ["ASV", "NASB1995", "NASB2020"]
    /// Phone → watch, application context: true when the reader's current translation is one whose
    /// publisher keeps it off watches (`PackagePolicy.wearables`). The phone never sends it; the
    /// watch says why it isn't there instead of suggesting it is on its way.
    nonisolated static let translationNotForWatch = "translationNotForWatch"
    /// Phone → watch, application context: what the reader sees the current translation called on the
    /// phone (its abbreviation, "NASB 1995"), for the watch to name one it can't show. The identifier
    /// is internal ("NASB1995", "IMPORT-NN0XUW"); a phone app that doesn't send this leaves the watch
    /// falling back to it.
    nonisolated static let translationLabel = "translationLabel"
    /// Phone → watch, file metadata: a fingerprint of the store the edition was made from.
    nonisolated static let version = "version"
    /// Phone → watch, file metadata: "import" for a translation the reader imported, otherwise absent.
    nonisolated static let kind = "kind"
    nonisolated static let importKind = "import"

    /// What the watch calls the phone's translation: the label the phone sent, else its identifier.
    /// Bounded, since it is drawn as-is in a footer.
    nonisolated static func label(_ label: String?, for id: String) -> String {
        guard let label = label?.trimmingCharacters(in: .whitespacesAndNewlines),
              !label.isEmpty, label.count <= 64 else { return id }
        return label
    }

    /// A received file is saved under its translation's identifier, so the identifier has to be a
    /// safe file name: no separators, no dots, nothing that could climb out of the directory.
    nonisolated static func isSafeID(_ id: String) -> Bool {
        !id.isEmpty && id.count <= 64
            && id.unicodeScalars.allSatisfy { $0.isASCII && ($0.properties.isAlphabetic || ("0"..."9").contains($0)
                                                             || $0 == "_" || $0 == "-") }
    }
}
