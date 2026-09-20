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
                chosen = Set(model.translations.compactMap { entry -> String? in
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
        model.setOnlineTranslations(OnlineCatalog.entries(keys: keys, apiBible: available,
                                                          chosen: chosen))
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
