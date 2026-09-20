import SwiftUI
import ScriptureAloneCore

/// Where the reader puts their own API keys, and picks which translations to add.
///
/// The app never ships a key. Both providers' free tiers are per-key allowances meant for one
/// person, and a key inside the app would be spent by strangers within a day.
struct OnlineKeysView: View {
    @Environment(OnlineTranslationKeys.self) private var keys
    @Environment(ReaderModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    @State private var entry: [OnlineProvider: String] = [:]
    @State private var available: [APIBibleTranslation] = []
    @State private var chosen: Set<String> = []
    @State private var checking = false
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            Form {
                ForEach(OnlineProvider.allCases) { provider in
                    Section {
                        SecureField("API key", text: binding(for: provider))
                            .textContentType(.password)
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            #endif
                        Button("Get a free key…", systemImage: "arrow.up.right.square") {
                            openURL(provider.signupURL)
                        }
                        if keys.key(for: provider) != nil {
                            Button("Remove Key", systemImage: "trash", role: .destructive) {
                                keys.remove(provider)
                                entry[provider] = ""
                                if provider == .apiBible { available = []; chosen = [] }
                                refreshTranslations()
                            }
                        }
                        if provider == .apiBible, keys.key(for: .apiBible) != nil {
                            apiBiblePicker
                        }
                    } header: {
                        Text(provider.title)
                    } footer: {
                        Text(provider.explanation)
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Online Translations")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { save(); dismiss() } }
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .onAppear {
                for provider in OnlineProvider.allCases {
                    entry[provider] = keys.key(for: provider) ?? ""
                }
                chosen = Set((model.translations + OnlineCatalog.restored(keys: keys))
                    .compactMap { entry -> String? in
                        if case .online(.apiBible, let remote) = entry.source { return remote }
                        return nil
                    })
                if keys.key(for: .apiBible) != nil { Task { await loadAvailable() } }
            }
            .alert("That didn't work", isPresented: .constant(failure != nil)) {
                Button("OK") { failure = nil }
            } message: {
                Text(failure ?? "")
            }
        }
    }

    @ViewBuilder
    private var apiBiblePicker: some View {
        if checking {
            HStack { ProgressView(); Text("Asking API.Bible what your key can read…").font(.callout) }
        } else if available.isEmpty {
            Button("Check My Translations", systemImage: "arrow.clockwise") {
                Task { await loadAvailable() }
            }
        } else {
            ForEach(available) { translation in
                Button {
                    if chosen.contains(translation.id) { chosen.remove(translation.id) }
                    else { chosen.insert(translation.id) }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(translation.name)
                            Text(translation.language).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if chosen.contains(translation.id) {
                            Image(systemName: "checkmark").foregroundStyle(.tint)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func binding(for provider: OnlineProvider) -> Binding<String> {
        Binding(get: { entry[provider] ?? "" }, set: { entry[provider] = $0 })
    }

    private func save() {
        for provider in OnlineProvider.allCases {
            keys.set(entry[provider] ?? "", for: provider)
        }
        refreshTranslations()
    }

    private func refreshTranslations() {
        let entries = OnlineCatalog.entries(keys: keys, apiBible: available, chosen: chosen)
        // Written down here, where the opaque ids are actually known, so the next launch can
        // rebuild the picker without opening this sheet or touching the network.
        OnlineCatalog.remember(entries)
        model.setOnlineTranslations(entries)
    }

    /// Bible ids are opaque, and which three a key can read is chosen on API.Bible's own
    /// dashboard — so the app asks rather than assuming.
    private func loadAvailable() async {
        guard let key = keys.key(for: .apiBible) ?? entry[.apiBible].flatMap({ $0.isEmpty ? nil : $0 })
        else { return }
        checking = true
        defer { checking = false }
        do {
            // Hundreds of open-access Bibles come back alongside the licensed picks. API.Bible
            // reports a language name rather than a code, so there is nothing reliable to match
            // against the device's languages — the list is simply sorted and searched by eye.
            available = try await APIBibleClient(key: key).availableTranslations()
                .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        } catch {
            failure = error.localizedDescription
        }
    }
}

/// Builds the translation entries for whatever keys and choices exist.
enum OnlineCatalog {

    // MARK: Remembering the picks

    /// Which API.Bible translations the reader picked, kept across launches.
    ///
    /// Bible ids are opaque strings from API.Bible's own catalogue, and which ones a key may read
    /// is chosen on their dashboard — so the app has to be told, and previously it was told only
    /// by the keys screen. That made the picks live in a `@State` on a sheet: open the app and
    /// they were gone until you went back and opened that sheet again.
    ///
    /// They are stored in `UserDefaults` rather than the keychain deliberately. The key is a
    /// credential and stays in the keychain; these are not — they are public catalogue ids and the
    /// names API.Bible gave them, and without the key they open nothing. Keeping them here means a
    /// launch restores the picker with no network call and no credential read.
    private static let storageKey = "onlineTranslations"

    private struct Remembered: Codable {
        var id: String
        var name: String
        var remoteID: String
    }

    /// Remembers the API.Bible picks among these entries. The ESV is not stored: it follows from
    /// the presence of a Crossway key, so storing it could only ever disagree with the keychain.
    static func remember(_ entries: [TranslationEntry], in defaults: UserDefaults = .standard) {
        let picks = entries.compactMap { entry -> Remembered? in
            guard case .online(.apiBible, let remote) = entry.source else { return nil }
            return Remembered(id: entry.id, name: entry.name, remoteID: remote)
        }
        guard let data = try? JSONEncoder().encode(picks) else { return }
        defaults.set(data, forKey: storageKey)
    }

    /// The entries to offer at launch: the ESV if a Crossway key exists, plus the remembered
    /// API.Bible picks — but only while a key that could read them exists. Removing the key
    /// removes the translations, without needing the keys screen to have been opened.
    static func restored(keys: OnlineTranslationKeys,
                         defaults: UserDefaults = .standard) -> [TranslationEntry] {
        var out: [TranslationEntry] = []
        if keys.key(for: .crossway) != nil {
            out.append(TranslationEntry(id: "ESV", name: "English Standard Version",
                                        source: .online(provider: .crossway, remoteID: "esv")))
        }
        guard keys.key(for: .apiBible) != nil,
              let data = defaults.data(forKey: storageKey),
              let picks = try? JSONDecoder().decode([Remembered].self, from: data) else { return out }
        out += picks.map {
            TranslationEntry(id: $0.id, name: $0.name,
                             source: .online(provider: .apiBible, remoteID: $0.remoteID))
        }
        return out
    }

    // MARK: Building from a live catalogue

    static func entries(keys: OnlineTranslationKeys,
                        apiBible: [APIBibleTranslation],
                        chosen: Set<String>) -> [TranslationEntry] {
        var out: [TranslationEntry] = []
        // The ESV is offered whenever a Crossway key exists; Crossway serves exactly one text.
        if keys.key(for: .crossway) != nil {
            out.append(TranslationEntry(id: "ESV", name: "English Standard Version",
                                        source: .online(provider: .crossway, remoteID: "esv")))
        }
        for translation in apiBible where chosen.contains(translation.id) {
            let id = translation.abbreviation.isEmpty ? translation.id : translation.abbreviation
            out.append(TranslationEntry(id: id.uppercased(), name: translation.name,
                                        source: .online(provider: .apiBible, remoteID: translation.id)))
        }
        return out
    }
}
