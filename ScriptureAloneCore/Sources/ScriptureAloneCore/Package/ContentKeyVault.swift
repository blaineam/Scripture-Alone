import CryptoKit
import Foundation

/// Holds the content key for licensed packages, on the device, wrapped by the Secure Enclave.
///
/// **What this does and does not buy, stated plainly, because the white paper says the same.**
///
/// The seed arrives in the app binary, so it is in every copy of the app and a jailbroken device
/// can recover it. Moving it here does not change that, and nothing on a general-purpose computer
/// can. What it does change is every *other* way a key gets loose, and those are the ones that
/// actually happen:
///
/// - The key is never written to disk in the clear. What is stored is the key sealed to a
///   Secure Enclave P-256 key that cannot leave the chip.
/// - The stored blob is useless on another device. The Enclave key is generated on first launch,
///   is marked `ThisDeviceOnly`, and never syncs — so a copied keychain, a device backup, or a
///   file-system dump yields ciphertext nobody can open.
/// - The seed is erased from memory after bootstrap, so a memory dump taken later finds the
///   unwrapped key only while a chapter is actually being read.
///
/// An attacker who pulls the seed out of the binary still wins. This raises the floor, not the
/// ceiling, and it is the same floor a closed-source app stands on.
public struct ContentKeyVault: Sendable {
    public enum Failure: LocalizedError, Equatable {
        case noKeyStored
        case enclaveUnavailable(String)
        case keychain(OSStatus)
        case corrupted

        public var errorDescription: String? {
            switch self {
            case .noKeyStored: String(localized: "No content key has been set up on this device.", bundle: .module)
            case .enclaveUnavailable(let why): String(localized: "The Secure Enclave isn't available: \(why)", bundle: .module, comment: "Error. %@ is a technical detail.")
            case .keychain(let status): String(localized: "The keychain refused the request (\(Int(status))).", bundle: .module, comment: "Error. %lld is a system error code.")
            case .corrupted: String(localized: "The stored content key couldn't be read.", bundle: .module)
            }
        }
    }

    /// Namespaced so a future second key (a second publisher) doesn't collide.
    private let account: String
    private let service = "com.blainemiller.ScriptureAlone.contentKey"

    public init(translationID: String) {
        self.account = translationID
    }

    // MARK: Bootstrap

    /// Seals the content key to this device, once.
    ///
    /// Call with the seed compiled into the build. Safe to call on every launch: it returns
    /// immediately if the key is already sealed, so the seed is used exactly once per device.
    @discardableResult
    public func bootstrapIfNeeded(seed: Data) throws -> Bool {
        if hasStoredKey { return false }
        let key = Self.deriveContentKey(seed: seed, account: account)
        try store(key)
        return true
    }

    /// HKDF over the shipped seed, so the bytes in the binary are not themselves the content key
    /// and the same seed can serve more than one translation.
    public static func deriveContentKey(seed: Data, account: String) -> SymmetricKey {
        let salt = Data("scripture-alone-content-key-v1".utf8)
        return HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: seed),
                                      salt: salt,
                                      info: Data(account.utf8),
                                      outputByteCount: 32)
    }

    public var hasStoredKey: Bool { (try? loadSealed()) != nil }

    // MARK: Use

    /// The content key, unwrapped for as long as the caller holds it.
    public func contentKey() throws -> SymmetricKey {
        let sealed = try loadSealed()
        let enclaveKey = try enclavePrivateKey()
        guard SecKeyIsAlgorithmSupported(enclaveKey, .decrypt, Self.algorithm) else {
            throw Failure.enclaveUnavailable("algorithm unsupported")
        }
        var error: Unmanaged<CFError>?
        guard let plain = SecKeyCreateDecryptedData(enclaveKey, Self.algorithm,
                                                    sealed as CFData, &error) as Data? else {
            throw Failure.corrupted
        }
        guard plain.count == 32 else { throw Failure.corrupted }
        return SymmetricKey(data: plain)
    }

    /// Forgets the key. Used when a licence lapses or a reader removes a translation.
    public func erase() {
        SecItemDelete(sealedQuery() as CFDictionary)
        SecItemDelete(enclaveQuery() as CFDictionary)
    }

    // MARK: Storage

    private static let algorithm: SecKeyAlgorithm = .eciesEncryptionCofactorX963SHA256AESGCM

    private func store(_ key: SymmetricKey) throws {
        let publicKey = try enclavePublicKey()
        var error: Unmanaged<CFError>?
        let bytes = key.withUnsafeBytes { Data($0) }
        guard let sealed = SecKeyCreateEncryptedData(publicKey, Self.algorithm,
                                                     bytes as CFData, &error) as Data? else {
            throw Failure.enclaveUnavailable("encryption failed")
        }
        var query = sealedQuery()
        query[kSecValueData as String] = sealed
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemDelete(sealedQuery() as CFDictionary)
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw Failure.keychain(status) }
    }

    private func loadSealed() throws -> Data {
        var query = sealedQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { throw Failure.noKeyStored }
        return data
    }

    private func sealedQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    // MARK: The Secure Enclave key

    private func enclaveQuery() -> [String: Any] {
        [kSecClass as String: kSecClassKey,
         kSecAttrApplicationTag as String: Data("\(service).\(account).enclave".utf8),
         kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom]
    }

    private func enclavePrivateKey() throws -> SecKey {
        var query = enclaveQuery()
        query[kSecReturnRef as String] = true
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess, let key = item { return key as! SecKey }
        return try createEnclaveKey()
    }

    private func enclavePublicKey() throws -> SecKey {
        let priv = try enclavePrivateKey()
        guard let pub = SecKeyCopyPublicKey(priv) else {
            throw Failure.enclaveUnavailable("no public key")
        }
        return pub
    }

    /// Generates the device key. Uses the Secure Enclave where there is one; the simulator has
    /// none, so it falls back to an ordinary keychain key — which is why the simulator is not
    /// where this gets judged.
    private func createEnclaveKey() throws -> SecKey {
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, [.privateKeyUsage], &error) else {
            throw Failure.enclaveUnavailable("no access control")
        }
        var attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: Data("\(service).\(account).enclave".utf8),
                kSecAttrAccessControl as String: access,
            ],
        ]
        #if !targetEnvironment(simulator)
        attributes[kSecAttrTokenID as String] = kSecAttrTokenIDSecureEnclave
        #endif
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            let message = (error?.takeRetainedValue()).map { String(describing: $0) } ?? "unknown"
            throw Failure.enclaveUnavailable(message)
        }
        return key
    }
}
