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

    /// Translations the app is licensed to ship sealed (docs/lockman/README.md). Their packages are
    /// never in this repository: Xcode Cloud copies them into `Resources/Packages/` at build time
    /// (ci_scripts/ci_post_clone.sh), together with the one-time signing key each was sealed with
    /// (`<id>-signing.pub`), and their content key comes from the build's secret seed
    /// (`ContentKeySeed`), never from the published one below. A build without them simply doesn't
    /// offer them, and the ASV is the default as before.
    static let licensedIdentifiers = ["NASB2020"]

    /// The licensed translation a fresh install opens to, when this build ships it.
    static let licensedDefault = "NASB2020"

    /// Translation identifiers the app ships sealed, in the order they appear to a reader.
    static let identifiers = licensedIdentifiers + ["ASV"]

    /// Seeds the ASV's content key. See the note above: deliberately not a secret.
    static let seed = Data("SCRIPTURE-ALONE-BUNDLED-SEED-v1".utf8)

    /// Whether this build carries everything a licensed translation needs to open: its package, the
    /// key it was signed with, and the secret seed. Read from the bundle, not from whether the package
    /// has opened yet, so a translation that is merely locked at launch is still the reader's default.
    static func ships(_ id: String) -> Bool {
        guard licensedIdentifiers.contains(id) else { return identifiers.contains(id) }
        return Bundle.main.url(forResource: id, withExtension: "sabible") != nil
            && Bundle.main.url(forResource: "\(id)-signing", withExtension: "pub") != nil
            && ContentKeySeed.data != nil
    }

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
    /// **The package ships inside the app.** The ASV is the translation a fresh install opens to,
    /// so nothing may stand between launch and its first chapter. It was briefly an `essential`
    /// Background Assets pack, and App Review's iPad launched to a spinner that never ended (1.0.0
    /// build 40): an essential pack is *meant* to arrive with the install, but "meant to" is not
    /// something the first screen can rest on. 16 MB in the binary is. A copy an earlier build left
    /// in Application Support is still read when the bundle has none, so nothing is lost either way.
    ///
    /// The publisher key it is verified against stays inside the app binary: a trust anchor that
    /// came down the same channel as the thing it vouches for would vouch for nothing.
    private func open(_ id: String) {
        let licensed = Self.licensedIdentifiers.contains(id)
        // Each licensed package pins the key it was signed with, and only that key: one translation's
        // signer can never vouch for another's package.
        let keyName = licensed ? "\(id)-signing" : "bundled-signing"
        let seed = licensed ? ContentKeySeed.data : Self.seed
        guard let keyURL = Bundle.main.url(forResource: keyName, withExtension: "pub"),
              let publisherKey = try? Data(contentsOf: keyURL), let seed else {
            failures[id] = String(localized: "\(id) isn't in this build.", comment: "Error. %@ is a translation abbreviation, e.g. “ASV”.")
            return
        }
        guard let packageURL = Bundle.main.url(forResource: id, withExtension: "sabible")
                ?? AssetPack(translationID: id).flatMap({ AssetLibrary.shared.url(of: $0) }) else {
            failures[id] = String(localized: "\(id) isn't in this build.", comment: "Error. %@ is a translation abbreviation, e.g. “ASV”.")
            return
        }
        do {
            let keyring = try PublisherKeyring(rawPublicKeys: [publisherKey])
            packages[id] = try TranslationPackage.open(url: packageURL, keyring: keyring,
                                                       contentKey: contentKey(for: id, seed: seed))
            failures[id] = nil
        } catch {
            failures[id] = error.localizedDescription
        }
    }

    /// The content key, from the device vault when it cooperates and derived directly when not.
    ///
    /// The key is derived from the seed and sealed to this device on first launch; afterwards the
    /// keychain holds ciphertext only the Secure Enclave can open. But a vault that can't be read —
    /// a sealed blob that outlived its Enclave key, a keychain that refuses the item — must not
    /// cost the reader their Bible. So a failure re-seals once from the seed, and if that fails
    /// too the key is derived in memory for this launch. That gives up nothing: the seed is
    /// published (see above), and the vault only ever raised the floor.
    private func contentKey(for id: String, seed: Data) -> SymmetricKey {
        let vault = ContentKeyVault(translationID: id)
        do {
            try vault.bootstrapIfNeeded(seed: seed)
            return try vault.contentKey()
        } catch {
            vault.erase()
            if (try? vault.bootstrapIfNeeded(seed: seed)) != nil, let key = try? vault.contentKey() {
                return key
            }
            return ContentKeyVault.deriveContentKey(seed: seed, account: id)
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
