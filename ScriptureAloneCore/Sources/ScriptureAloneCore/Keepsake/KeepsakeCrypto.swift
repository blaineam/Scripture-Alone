import Foundation
import CryptoKit
import CommonCrypto

/// Optional passphrase protection for a keepsake.
///
/// - Key: PBKDF2-HMAC-SHA256 over the passphrase (Unicode NFC, UTF-8) with a random 16-byte
///   salt and 600,000 iterations (OWASP's 2023 guidance for PBKDF2-SHA256), giving 32 bytes.
/// - Cipher: AES-256-GCM (CryptoKit) with a random 12-byte nonce. The stored payload is
///   CryptoKit's "combined" form: nonce ‖ ciphertext ‖ 16-byte tag.
/// - Associated data: the exact bytes of the outer `manifest.json`, so the salt, iteration
///   count and version beside the payload can't be altered without the open failing.
///
/// PBKDF2 is used rather than a memory-hard KDF because it ships with every Apple OS
/// (CommonCrypto) and has implementations in every language — a family member's
/// technically-minded grandchild can still open the file in fifty years.
public enum KeepsakeCrypto {
    public static let algorithm = "AES-256-GCM"
    public static let kdf = "PBKDF2-HMAC-SHA256"
    public static let defaultIterations = 600_000
    public static let saltLength = 16

    public static func randomSalt() -> Data {
        var bytes = [UInt8](repeating: 0, count: saltLength)
        var generator = SystemRandomNumberGenerator()
        for i in bytes.indices { bytes[i] = UInt8.random(in: .min ... .max, using: &generator) }
        return Data(bytes)
    }

    public static func deriveKey(passphrase: String, salt: Data, iterations: Int) throws -> SymmetricKey {
        guard iterations >= 1, iterations <= 50_000_000, !salt.isEmpty else { throw KeepsakeError.damaged("encryption settings") }
        let password = Array(passphrase.precomposedStringWithCanonicalMapping.utf8)
        guard !password.isEmpty else { throw KeepsakeError.passphraseRequired }
        var derived = [UInt8](repeating: 0, count: 32)
        let status = password.withUnsafeBufferPointer { passwordBytes in
            salt.withUnsafeBytes { saltBytes in
                passwordBytes.baseAddress!.withMemoryRebound(to: CChar.self, capacity: password.count) { passwordPointer in
                    CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2),
                                         passwordPointer, password.count,
                                         saltBytes.bindMemory(to: UInt8.self).baseAddress, salt.count,
                                         CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), UInt32(iterations),
                                         &derived, derived.count)
                }
            }
        }
        guard status == kCCSuccess else { throw KeepsakeError.damaged("key derivation failed (\(status))") }
        return SymmetricKey(data: derived)
    }

    static func seal(_ plaintext: Data, key: SymmetricKey, associatedData: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: key, authenticating: associatedData)
        guard let combined = box.combined else { throw KeepsakeError.damaged("sealing failed") }
        return combined
    }

    static func open(_ combined: Data, key: SymmetricKey, associatedData: Data) throws -> Data {
        let box: AES.GCM.SealedBox
        do {
            box = try AES.GCM.SealedBox(combined: combined)
        } catch {
            throw KeepsakeError.damaged("encrypted payload")
        }
        do {
            return try AES.GCM.open(box, using: key, authenticating: associatedData)
        } catch {
            // GCM can't tell a wrong key from tampering; a wrong passphrase is by far the likelier.
            throw KeepsakeError.wrongPassphrase
        }
    }
}
