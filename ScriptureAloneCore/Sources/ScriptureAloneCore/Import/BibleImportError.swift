// Importing is an iPhone, iPad and Mac feature: the watch has no file picker and no
// catalogue. It is also 32-bit (arm64_32), where the ZIP64 sentinel 0xFFFF_FFFF does not
// fit in an Int at all — so this code is not merely unused there, it cannot compile.
#if !os(watchOS)
import Foundation

/// Why an import stopped. Every failure is typed so the UI can say something true, and so the
/// refusal cases can be asserted in tests.
public enum BibleImportError: Error, LocalizedError, Equatable, Sendable {
    /// The file could not be read off disk at all.
    case unreadableFile(String)
    /// Not a ZIP container (an ePub is a ZIP).
    case notAZipArchive
    /// A ZIP that is truncated, mis-indexed, or whose contents fail their checksum.
    case damagedArchive(String)
    /// An entry that claims a size we will not inflate.
    case entryTooLarge(String)
    /// A ZIP that is not an ePub: wrong mimetype, no container.xml, no package document.
    case notAnEPUB(String)
    /// A file the engine has no reader for.
    case unsupportedFormat(String)
    /// The file is protected. The engine refuses it and reads none of its content.
    case protectedByDRM(DRMEvidence)
    /// The ePub opened and parsed, but nothing in it looked like scripture.
    case noScriptureFound
    /// An import must carry a copyright line forward; the ePub had none and the caller supplied none.
    case missingCopyright
    /// Writing the SQLite store failed.
    case databaseWrite(String)

    public var errorDescription: String? {
        switch self {
        case .unreadableFile(let message): String(localized: "Couldn’t read that file: \(message)", bundle: .module, comment: "Error. %@ is a technical detail.")
        case .notAZipArchive: String(localized: "That file isn’t an ePub — it isn’t a ZIP container.", bundle: .module)
        case .damagedArchive(let message): String(localized: "That ePub is damaged: \(message)", bundle: .module, comment: "Error. %@ is a technical detail.")
        case .entryTooLarge(let name): String(localized: "That ePub contains an implausibly large file (\(name)).", bundle: .module, comment: "Error. %@ is a file name inside the ePub.")
        case .notAnEPUB(let message): String(localized: "That file isn’t a readable ePub: \(message)", bundle: .module, comment: "Error. %@ is a technical detail.")
        case .unsupportedFormat(let message): String(localized: "This app can’t read that file: \(message)", bundle: .module, comment: "Error. %@ is a technical detail.")
        case .protectedByDRM(let evidence): String(localized: "\(evidence.explanation) This app cannot open protected files.", bundle: .module, comment: "Error. %@ is a sentence saying how the file is protected.")
        case .noScriptureFound: String(localized: "No Bible text was found in that ePub.", bundle: .module)
        case .missingCopyright: String(localized: "That ePub carries no copyright line. Enter the publisher’s copyright notice to continue.", bundle: .module)
        case .databaseWrite(let message): String(localized: "Couldn’t save the imported text: \(message)", bundle: .module, comment: "Error. %@ is a technical detail.")
        }
    }
}

/// What made the engine decide a file is protected. Named, not described, so the refusal is
/// a tested invariant rather than a string comparison.
///
/// The engine ships no decryption of any kind: circumventing a technical protection measure is a
/// separate violation from copyright, and owning the file does not excuse it. These cases exist so
/// the engine can *stop*, never so it can *proceed differently*. A visible watermark is not DRM and
/// is not detected here.
public enum DRMEvidence: String, Sendable, Equatable, Hashable, Codable {
    /// `META-INF/encryption.xml` — the OCF encryption manifest.
    case encryptionManifest
    /// Adobe ADEPT: `META-INF/rights.xml`, or an `Adept.*` key in the package metadata.
    case adobeADEPT
    /// Readium LCP: `META-INF/license.lcpl`.
    case readiumLCP
    /// Apple FairPlay: `META-INF/sinf.xml`.
    case appleFairPlay
    /// The ZIP's own entry-encryption bit is set.
    case zipEntryEncryption

    public var explanation: String {
        switch self {
        case .encryptionManifest: String(localized: "That ePub is encrypted (it carries an encryption manifest).", bundle: .module)
        case .adobeADEPT: String(localized: "That ePub is protected with Adobe DRM.", bundle: .module)
        case .readiumLCP: String(localized: "That ePub is protected with an LCP licence.", bundle: .module)
        case .appleFairPlay: String(localized: "That ePub is protected with Apple’s FairPlay DRM.", bundle: .module)
        case .zipEntryEncryption: String(localized: "That ePub’s contents are password-encrypted.", bundle: .module)
        }
    }
}
#endif
