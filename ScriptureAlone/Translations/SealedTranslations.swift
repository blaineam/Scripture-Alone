import CryptoKit
import Foundation
import Observation
import ScriptureAloneCore

/// The translations the app ships as signed, encrypted packages rather than as plain databases.
///
/// The American Standard Version is one of them, and it is the translation the app opens by
/// default. That is the whole point. A sealed sample sitting beside the real translations would
/// prove only that the code path compiles; shipping a translation this way means every reader
/// exercises it on every launch, and a fault in it would be the first thing anyone noticed rather
/// than the last.
///
/// The text is public domain, so the package grants everything a public-domain text may do — the
/// reader loses nothing by it. What is sealed is the mechanism: a real Bible, at real size, read a
/// chapter at a time out of an authenticated package whose terms are signed by a key the app pins.
/// A licensed translation would travel this same path with different terms and a key its publisher
/// holds; see `docs/encrypted-translations.md`.
///
/// The seed below is published on purpose. It protects a public-domain text, so secrecy would be
/// theatre, and pretending otherwise would undermine the honesty the rest of the design rests on.
@MainActor
@Observable
final class SealedTranslations {
    static let shared = SealedTranslations()

    /// Translation identifiers the app ships sealed, in the order they appear to a reader.
    static let identifiers = ["ASV"]

    /// Seeds the content key. See the note above: deliberately not a secret.
    static let seed = Data("SCRIPTURE-ALONE-BUNDLED-SEED-v1".utf8)

    private(set) var packages: [String: TranslationPackage] = [:]
    private(set) var failures: [String: String] = [:]

    private init() {
        for id in Self.identifiers { open(id) }
    }

    func package(_ id: String) -> TranslationPackage? { packages[id] }

    func failure(_ id: String) -> String? { failures[id] }

    /// Derives the key, seals it to this device, verifies the signature and opens the package.
    ///
    /// Called once per identifier at startup. Opening reads and authenticates the header only —
    /// chapters are decrypted when they are read — so this costs a signature check, not a 16 MB
    /// decryption.
    ///
    /// **The package arrives as an asset pack, the key does not.** The `.sabible` file is the ASV's
    /// `essential` Background Assets pack, copied out of the pack on first launch (`AssetLibrary`).
    /// The publisher key it is verified against stays inside the app binary: a trust anchor that
    /// came down the same channel as the thing it vouches for would vouch for nothing.
    private func open(_ id: String) {
        guard let keyURL = Bundle.main.url(forResource: "bundled-signing", withExtension: "pub"),
              let publisherKey = try? Data(contentsOf: keyURL) else {
            failures[id] = "\(id) isn't in this build."
            return
        }
        guard let pack = AssetPack(translationID: id),
              AssetLibrary.shared.installIfLocal(pack),
              let packageURL = AssetLibrary.shared.url(of: pack) else {
            // Not on the device yet — the essential pack hasn't landed, which a normal App Store
            // install never sees. `ReaderModel` fetches it and calls `reopen`.
            failures[id] = "\(id) hasn't finished installing."
            return
        }
        do {
            // The key is derived from the seed and sealed to this device on first launch;
            // afterwards the keychain holds ciphertext only the Secure Enclave can open.
            let vault = ContentKeyVault(translationID: id)
            try vault.bootstrapIfNeeded(seed: Self.seed)
            let keyring = try PublisherKeyring(rawPublicKeys: [publisherKey])
            packages[id] = try TranslationPackage.open(url: packageURL, keyring: keyring,
                                                       contentKey: try vault.contentKey())
            failures[id] = nil
        } catch {
            failures[id] = error.localizedDescription
        }
    }

    /// Re-derives and re-opens. For the case where the sealed key could not be read because the
    /// device was locked at launch — the keychain item is `AfterFirstUnlock`, so a cold boot with
    /// a background launch can arrive before the user has ever unlocked.
    @discardableResult
    func reopen(_ id: String) -> Bool {
        open(id)
        return packages[id] != nil
    }
}
