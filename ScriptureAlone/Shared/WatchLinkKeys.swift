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
    /// Phone → watch, file metadata: a fingerprint of the store the edition was made from.
    nonisolated static let version = "version"
    /// Phone → watch, file metadata: "import" for a translation the reader imported, otherwise absent.
    nonisolated static let kind = "kind"
    nonisolated static let importKind = "import"

    /// A received file is saved under its translation's identifier, so the identifier has to be a
    /// safe file name: no separators, no dots, nothing that could climb out of the directory.
    nonisolated static func isSafeID(_ id: String) -> Bool {
        !id.isEmpty && id.count <= 64
            && id.unicodeScalars.allSatisfy { $0.isASCII && ($0.properties.isAlphabetic || ("0"..."9").contains($0)
                                                             || $0 == "_" || $0 == "-") }
    }
}
