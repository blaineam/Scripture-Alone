import Foundation
import Observation
import ScriptureAloneCore

/// A translation the app reads over the network because no one may ship its text.
///
/// One mechanism, several providers: Crossway's own API serves the ESV, and API.Bible (American
/// Bible Society) serves CSB, NASB, NKJV and others. In both cases the key belongs to the reader,
/// not the app — the free tiers are per-key allowances, and a key shipped inside the app would be
/// spent by a handful of people and would abuse a non-commercial grant.
///
/// If a publisher ever licenses the text to this app, the provider is replaced by a bundled store
/// and everything above this layer — the picker, the reader, notes, highlights — is unchanged.
enum OnlineProvider: String, CaseIterable, Identifiable, Sendable {
    case crossway
    case apiBible

    var id: String { rawValue }

    var title: String {
        switch self {
        case .crossway: "Crossway (ESV)"
        case .apiBible: "API.Bible (CSB, NASB, NKJV…)"
        }
    }

    /// Where the reader registers for their own free key.
    var signupURL: URL {
        switch self {
        case .crossway: ESVClient.signupURL
        case .apiBible: URL(string: "https://api.bible/")!
        }
    }

    var explanation: String {
        switch self {
        case .crossway:
            "Crossway's free tier allows 5,000 requests a day for non-commercial use. The key is "
                + "yours, and the ESV is read over the network — up to 500 verses are kept on the "
                + "device, which is Crossway's limit."
        case .apiBible:
            "The American Bible Society's free Starter plan is for non-commercial use and lets you "
                + "pick three copyrighted translations. The key is yours."
        }
    }
}

/// The keys the reader has entered.
///
/// A key is a credential, so it lives in the keychain rather than UserDefaults — and it syncs
/// through iCloud Keychain, because it is the same person's key on their own phone, iPad and Mac.
/// The free tiers are per-key daily allowances rather than per-device ones, and one person
/// reading chapters comes nowhere near 5,000 requests a day across three devices.
@MainActor
@Observable
final class OnlineTranslationKeys {
    private(set) var keys: [OnlineProvider: String] = [:]

    init() {
        for provider in OnlineProvider.allCases {
            if let key = Self.read(provider) { keys[provider] = key }
        }
    }

    func set(_ key: String, for provider: OnlineProvider) {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            remove(provider)
        } else {
            Self.write(trimmed, provider)
            keys[provider] = trimmed
        }
    }

    func remove(_ provider: OnlineProvider) {
        Self.delete(provider)
        keys[provider] = nil
    }

    func key(for provider: OnlineProvider) -> String? { keys[provider] }

    // MARK: Keychain

    private static func query(_ provider: OnlineProvider) -> [String: Any] {
        // `synchronizable` has to be in the query too, or a synced item is invisible to lookups.
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.blainemiller.ScriptureAlone.online",
         kSecAttrAccount as String: provider.rawValue,
         kSecAttrSynchronizable as String: kCFBooleanTrue as Any]
    }

    private static func read(_ provider: OnlineProvider) -> String? {
        var q = query(provider)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data, let key = String(data: data, encoding: .utf8) else { return nil }
        return key
    }

    private static func write(_ key: String, _ provider: OnlineProvider) {
        let q = query(provider)
        let data = Data(key.utf8)
        // A synchronizable item cannot be "this device only"; AfterFirstUnlock is the usual
        // pairing, and it lets a widget or a background refresh read the key as well.
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecAttrSynchronizable as String: kCFBooleanTrue as Any,
        ]
        if SecItemCopyMatching(q as CFDictionary, nil) == errSecSuccess {
            SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        } else {
            SecItemAdd(q.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
    }

    private static func delete(_ provider: OnlineProvider) {
        SecItemDelete(query(provider) as CFDictionary)
    }
}
