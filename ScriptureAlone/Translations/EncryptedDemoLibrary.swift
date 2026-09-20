import CryptoKit
import Foundation
import Observation
import ScriptureAloneCore

/// The encrypted translation the app ships as a demonstration.
///
/// It is the Berean Standard Bible — public domain — packaged exactly the way a licensed
/// translation would be: signed by a publisher key the app pins, chapters sealed one at a time,
/// a sealed search index, and a policy the app obeys. It arrives as an on-demand resource, and its
/// content key is derived from the build's seed and sealed to this device's Secure Enclave.
///
/// The point is that a publisher can watch the whole chain run — download, unseal, read, search,
/// refuse what the policy forbids — on a text nobody has to licence first.
///
/// The seed for *this* package is published in `Tools/package_translation.py`. That is deliberate:
/// it protects a public-domain text, so secrecy would be theatre, and pretending otherwise would
/// undermine the honesty the rest of the design depends on. A real package uses a key its
/// publisher generates and keeps.
@MainActor
@Observable
final class EncryptedDemoLibrary {
    static let shared = EncryptedDemoLibrary()

    static let translationID = "BSBX"
    static let demoSeed = Data("SCRIPTURE-ALONE-DEMO-SEED-v1!!!".utf8)

    private(set) var package: TranslationPackage?
    private(set) var failure: String?

    private init() {}

    /// True once the package is open and readable.
    var isOpen: Bool { package != nil }

    /// Downloads the pack if needed, unseals the key, verifies the signature, and opens it.
    @discardableResult
    func prepare() async -> Bool {
        if package != nil { return true }
        failure = nil

        guard await OnDemandLibrary.shared.ensure(.encryptedDemo) else {
            failure = "The encrypted demonstration couldn't be downloaded."
            return false
        }
        guard let packageURL = Bundle.main.url(forResource: Self.translationID, withExtension: "sabible"),
              let keyURL = Bundle.main.url(forResource: "demo-signing", withExtension: "pub"),
              let publisherKey = try? Data(contentsOf: keyURL) else {
            failure = "The demonstration package isn't in this build."
            return false
        }

        do {
            // The key is derived from the build's seed and sealed to this device on first use;
            // afterwards the keychain holds ciphertext only the Secure Enclave can open.
            let vault = ContentKeyVault(translationID: Self.translationID)
            try vault.bootstrapIfNeeded(seed: Self.demoSeed)
            let contentKey = try vault.contentKey()

            let keyring = try PublisherKeyring(rawPublicKeys: [publisherKey])
            package = try TranslationPackage.open(url: packageURL, keyring: keyring,
                                                  contentKey: contentKey)
            return true
        } catch {
            failure = error.localizedDescription
            return false
        }
    }

    /// Forgets the sealed key, so the next open re-derives it. For the demonstration's own sake:
    /// a publisher should be able to watch it happen twice.
    func reset() {
        package = nil
        ContentKeyVault(translationID: Self.translationID).erase()
    }
}
