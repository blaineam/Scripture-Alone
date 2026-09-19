import SwiftUI
import ScriptureAloneCore

/// A keepsake file waiting to be looked at and added.
struct KeepsakeImportRequest: Identifiable {
    let id = UUID()
    let url: URL
}

extension View {
    /// Supplies the keepsake library and this window's legacy session, and opens
    /// `.scripturelegacy` files handed to the app (AirDrop, Messages, Files, Finder).
    /// Apply inside `.environment(ReaderModel)` so the import sheet can open the keepsake.
    func legacySupport() -> some View { modifier(LegacySupport()) }
}

private struct LegacySupport: ViewModifier {
    @State private var session = LegacySession()
    @State private var pendingImport: KeepsakeImportRequest?

    func body(content: Content) -> some View {
        content
            .environment(LegacyLibrary.shared)
            .environment(session)
            // Live family sharing: shared Bibles, the owner's mirror, and share invitations.
            .familySharing(session: session)
            .onOpenURL { url in
                guard url.isFileURL, url.pathExtension.lowercased() == KeepsakeArchive.fileExtension else { return }
                pendingImport = KeepsakeImportRequest(url: url)
            }
            .sheet(item: $pendingImport) { request in
                KeepsakeImportSheet(url: request.url)
                    .environment(LegacyLibrary.shared)
                    .environment(session)
            }
    }
}

/// Shows who a keepsake is from before adding it, asking for its passphrase if it has one.
struct KeepsakeImportSheet: View {
    let url: URL
    @Environment(LegacyLibrary.self) private var library
    @Environment(LegacySession.self) private var session
    @Environment(ReaderModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    private enum Phase {
        case loading
        case locked(KeepsakeManifest, Data)
        case ready(Keepsake)
        case added(Keepsake, replaced: Date?)
        case failed(String)
    }

    @State private var phase = Phase.loading
    @State private var passphrase = ""
    @State private var passphraseError: String?
    @State private var unlocking = false

    var body: some View {
        NavigationStack {
            Group {
                switch phase {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                case .locked(let manifest, let data):
                    lockedForm(manifest, data: data)
                case .ready(let keepsake):
                    preview(keepsake)
                case .added(let keepsake, let replaced):
                    added(keepsake, replaced: replaced)
                case .failed(let message):
                    ContentUnavailableView {
                        Label("Can’t Open This Keepsake", systemImage: "book.closed")
                    } description: {
                        Text(message)
                    }
                }
            }
            .navigationTitle("Legacy Bible")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(isFinished ? "Done" : "Cancel") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 440)
        #endif
        .task { await load() }
    }

    private var isFinished: Bool {
        if case .added = phase { return true }
        if case .failed = phase { return true }
        return false
    }

    // MARK: Phases

    private func lockedForm(_ manifest: KeepsakeManifest, data: Data) -> some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Image(systemName: "lock.fill").font(.title2).foregroundStyle(.secondary)
                    Text("This keepsake is protected").font(.headline)
                    Text("Whoever made it chose a passphrase so only family could open it. It may be written down with the file, or with their papers.")
                        .font(.callout).foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }
            Section {
                SecureField("Passphrase", text: $passphrase)
                    .onSubmit { unlock(data) }
                    .disabled(unlocking)
                if let hint = manifest.passphraseHint {
                    LabeledContent("Hint", value: hint)
                }
            } footer: {
                if let passphraseError { Text(passphraseError).foregroundStyle(.red) }
            }
            Section {
                Button {
                    unlock(data)
                } label: {
                    HStack {
                        Text("Open")
                        if unlocking { Spacer(); ProgressView() }
                    }
                }
                .disabled(passphrase.isEmpty || unlocking)
            }
        }
        .formStyle(.grouped)
    }

    private func preview(_ keepsake: Keepsake) -> some View {
        Form {
            KeepsakeSummaryHeader(manifest: keepsake.manifest)
            Section {
                Button("Add to My Library") { add(keepsake) }
                    .bold()
            } footer: {
                Text("Kept on this device, separate from your own highlights and notes. It’s read-only; nothing in it can be changed.")
            }
        }
        .formStyle(.grouped)
    }

    private func added(_ keepsake: Keepsake, replaced: Date?) -> some View {
        Form {
            KeepsakeSummaryHeader(manifest: keepsake.manifest)
            Section {
                Button("Open \(keepsake.manifest.displayTitle)") {
                    session.open(keepsake, model: model)
                    dismiss()
                }
                .bold()
            } footer: {
                if let replaced {
                    Text("This replaced the copy from \(replaced.formatted(date: .long, time: .omitted)).")
                } else {
                    Text("You’ll find it again under Appearance → Legacy & Export.")
                }
            }
        }
        .formStyle(.grouped)
    }

    // MARK: Actions

    private func load() async {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try Data(contentsOf: url)
            let manifest = try KeepsakeArchive.peek(data)
            if manifest.isEncrypted {
                phase = .locked(manifest, data)
            } else {
                phase = .ready(try KeepsakeArchive.decode(data))
            }
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    private func unlock(_ data: Data) {
        guard !passphrase.isEmpty, !unlocking else { return }
        unlocking = true
        passphraseError = nil
        let phrase = passphrase
        Task {
            // Key derivation is deliberately slow; keep it off the main thread.
            let result = await Task.detached(priority: .userInitiated) { () -> Result<Keepsake, KeepsakeError> in
                do { return .success(try KeepsakeArchive.decode(data, passphrase: phrase)) }
                catch let error as KeepsakeError { return .failure(error) }
                catch { return .failure(.damaged("\(error.localizedDescription)")) }
            }.value
            unlocking = false
            switch result {
            case .success(let keepsake): phase = .ready(keepsake)
            case .failure(.wrongPassphrase):
                passphraseError = KeepsakeError.wrongPassphrase.localizedDescription
                passphrase = ""
            case .failure(let error): phase = .failed(error.localizedDescription)
            }
        }
    }

    private func add(_ keepsake: Keepsake) {
        do {
            switch try library.add(keepsake) {
            case .added: phase = .added(keepsake, replaced: nil)
            case .replaced(let previous): phase = .added(keepsake, replaced: previous)
            }
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }
}

/// Name, dedication and what's inside — shared by the import sheet and the library list.
struct KeepsakeSummaryHeader: View {
    let manifest: KeepsakeManifest

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: "book.closed.fill")
                    .font(.largeTitle)
                    .foregroundStyle(.tint)
                Text(manifest.displayTitle)
                    .font(.system(.title, design: .serif).weight(.semibold))
                if let dedication = manifest.dedication, !dedication.isEmpty {
                    Text(dedication)
                        .font(.system(.body, design: .serif).italic())
                        .textSelection(.enabled)
                }
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 6)
        }
    }

    private var detail: String {
        var parts: [String] = []
        if let counts = manifest.counts {
            parts.append("\(counts.highlights) \(counts.highlights == 1 ? "highlight" : "highlights")")
            parts.append("\(counts.notes) \(counts.notes == 1 ? "note" : "notes")")
        }
        if let range = manifest.dateRange {
            let start = range.start.formatted(.dateTime.year())
            let end = range.end.formatted(.dateTime.year())
            parts.append(start == end ? start : "\(start)–\(end)")
        }
        if let translation = manifest.preferredTranslation { parts.append("read in the \(translation)") }
        return parts.joined(separator: " · ")
    }
}
