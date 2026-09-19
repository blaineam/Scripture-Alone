import SwiftUI
import ScriptureAloneCore

/// "Shared with You" in Legacy & Export: family Bibles shared live, beside received keepsakes.
struct SharedBiblesSection: View {
    let open: (SharedBibleLibrary.Entry) -> Void
    @Environment(LegacySession.self) private var session
    @State private var pendingRemoval: SharedBibleLibrary.Entry?
    @State private var keeping: SharedBibleLibrary.Entry?

    var body: some View {
        let library = SharedBibleLibrary.shared
        if !library.entries.isEmpty {
            Section {
                ForEach(library.entries) { entry in
                    Button {
                        open(entry)
                    } label: {
                        SharedBibleRow(entry: entry, isOpen: session.source == .live(entry.id))
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button("Open", systemImage: "book") { open(entry) }
                        if entry.isLive {
                            Button("Refresh", systemImage: "arrow.clockwise") { Task { await library.refresh() } }
                        }
                        Button("Keep a Copy as a Keepsake…", systemImage: "gift") { keeping = entry }
                        Button(entry.isLive ? "Leave…" : "Remove…", systemImage: "trash", role: .destructive) { pendingRemoval = entry }
                    }
                    .swipeActions {
                        Button(entry.isLive ? "Leave" : "Remove", systemImage: "trash") { pendingRemoval = entry }.tint(.red)
                        Button("Keep", systemImage: "gift") { keeping = entry }.tint(.accentColor)
                    }
                }
                if let error = library.lastError {
                    Label(error, systemImage: "exclamationmark.icloud")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
            } header: {
                Text("Shared with You")
            } footer: {
                Text("Updated from their iCloud while they share it, and kept on this device so it reads offline. A live share lasts only as long as their iCloud account — keep a copy as a keepsake to have it for good.")
            }
            .confirmationDialog(removalTitle, isPresented: Binding(get: { pendingRemoval != nil }, set: { if !$0 { pendingRemoval = nil } }),
                                titleVisibility: .visible, presenting: pendingRemoval) { entry in
                Button(entry.isLive ? "Leave and Remove" : "Remove from This Device", role: .destructive) {
                    Task { await library.remove(entry.id) }
                }
            } message: { entry in
                Text(entry.isLive
                     ? "You’ll stop receiving updates and this copy will be removed. They can invite you again."
                     : "This last copy will be removed from this device. Keep it as a keepsake first if you’d like to hold on to it.")
            }
            .sheet(item: $keeping) { entry in
                KeepSharedBibleSheet(entry: entry)
            }
        }
    }

    private var removalTitle: String {
        guard let entry = pendingRemoval else { return "" }
        return entry.isLive ? "Leave \(entry.title)?" : "Remove \(entry.title)?"
    }
}

private struct SharedBibleRow: View {
    let entry: SharedBibleLibrary.Entry
    let isOpen: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: entry.isLive ? "person.2.fill" : "book.closed.fill")
                .font(.title2)
                .foregroundStyle(.tint)
                .frame(width: 30)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.title).font(.system(.headline, design: .serif))
                if let dedication = entry.snapshot.profile?.dedication, !dedication.isEmpty {
                    Text(dedication).font(.system(.subheadline, design: .serif).italic()).foregroundStyle(.secondary).lineLimit(2)
                }
                SharedBibleStatusText(entry: entry)
                    .font(.caption)
                    .foregroundStyle(entry.isLive ? AnyShapeStyle(.secondary) : AnyShapeStyle(.orange))
                Text("\(entry.snapshot.highlights.count) highlights · \(entry.snapshot.notes.count) notes · \(entry.snapshot.favorites.count) favorites")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if isOpen {
                Text("Open").font(.caption.weight(.semibold)).foregroundStyle(.tint)
            }
        }
        .contentShape(Rectangle())
        .padding(.vertical, 2)
    }
}

/// "Shared live · updated 5 min. ago" or "Sharing ended 2 hr. ago", kept current.
struct SharedBibleStatusText: View {
    let entry: SharedBibleLibrary.Entry
    static let relative = Date.RelativeFormatStyle(presentation: .named, unitsStyle: .abbreviated)

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { _ in
            if let ended = entry.ended {
                Text("Sharing ended \(ended, format: Self.relative)")
            } else if let fetched = entry.lastFetched {
                Text("Shared live · updated \(fetched, format: Self.relative)")
            } else {
                Text("Shared live · waiting for iCloud")
            }
        }
    }
}

/// The banner's second line while a live share is open.
struct LiveShareCaption: View {
    let id: String
    @State private var keeping: SharedBibleLibrary.Entry?

    var body: some View {
        if let entry = SharedBibleLibrary.shared.entry(id) {
            if entry.isLive {
                SharedBibleStatusText(entry: entry)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                Button {
                    keeping = entry
                } label: {
                    Text("Sharing ended \(entry.ended ?? .now, format: SharedBibleStatusText.relative) · \(Text("Keep a Copy").fontWeight(.semibold))")
                        .font(.caption2)
                        .multilineTextAlignment(.leading)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.orange)
                .sheet(item: $keeping) { KeepSharedBibleSheet(entry: $0) }
            }
        } else {
            Text("Shared live · read-only").font(.caption2).foregroundStyle(.secondary)
        }
    }
}

/// Saves a shared Bible's current copy as an ordinary keepsake — the permanent copy.
struct KeepSharedBibleSheet: View {
    let entry: SharedBibleLibrary.Entry
    @Environment(LegacySession.self) private var session
    @Environment(ReaderModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var failure: String?

    private var existing: LegacyLibrary.Entry? { LegacyLibrary.shared.entry(entry.keepsake.id) }

    var body: some View {
        NavigationStack {
            Form {
                KeepsakeSummaryHeader(manifest: entry.keepsake.manifest)
                Section {
                    Button("Keep This Copy") { keep() }.bold()
                    if let failure { Text(failure).foregroundStyle(.red) }
                } footer: {
                    if let existing {
                        Text("This replaces the keepsake of \(existing.title) from \(existing.manifest.createdAt.formatted(date: .long, time: .omitted)).")
                    } else if entry.isLive {
                        Text("A keepsake is a snapshot: it won’t change as they keep reading, and it stays even if the live share ends. You’ll still have the live share too.")
                    } else {
                        Text("Sharing has ended, so this is the last copy there will be. As a keepsake it stays under Legacy & Export, and you can save it to Files or give it to others.")
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Keep as a Keepsake")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }

    private func keep() {
        do {
            guard let keepsake = try SharedBibleLibrary.shared.keepAsKeepsake(entry.id) else { return }
            if !entry.isLive {
                // Ended: the keepsake takes its place.
                if session.source == .live(entry.id) { session.close(model: model); session.open(keepsake, model: model) }
                Task { await SharedBibleLibrary.shared.remove(entry.id) }
            }
            dismiss()
        } catch {
            failure = error.localizedDescription
        }
    }
}

/// Shown after accepting an invitation.
struct SharedBibleWelcome: View {
    let id: String
    @Environment(LegacySession.self) private var session
    @Environment(ReaderModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let library = SharedBibleLibrary.shared
        NavigationStack {
            Form {
                if let entry = library.entry(id) {
                    KeepsakeSummaryHeader(manifest: entry.keepsakeManifest)
                    Section {
                        Button("Read \(entry.title)") {
                            session.openLive(entry, model: model)
                            dismiss()
                        }
                        .bold()
                        .disabled(entry.snapshot.isEmpty && library.refreshing)
                        if library.refreshing { HStack { ProgressView(); Text("Fetching from iCloud…").foregroundStyle(.secondary) } }
                    } footer: {
                        Text("Shared with you live and read-only: their highlights and notes appear in the text as they add them, kept apart from your own. You’ll find it again under Appearance → Legacy & Export.")
                    }
                    Section {
                        Text("A live share lasts as long as their iCloud account. For a copy that’s yours for good, keep one as a keepsake from Legacy & Export — or ask them to make you a keepsake too.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                } else if let error = library.lastError {
                    ContentUnavailableView("Couldn’t Open the Invitation", systemImage: "exclamationmark.icloud", description: Text(error))
                } else {
                    ProgressView()
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Shared with You")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Later") { dismiss() } }
            }
        }
    }
}

/// A live share's favorites in the Notes panel's Favorites tab, read-only.
struct LegacyFavoritesList: View {
    let favorites: [FamilyFavorite]
    let search: String
    @Environment(ReaderModel.self) private var model

    private var rows: [(favorite: FamilyFavorite, range: VerseRange, text: String)] {
        let term = search.trimmingCharacters(in: .whitespaces)
        return favorites.compactMap { favorite in
            guard let range = favorite.range else { return nil }
            let text = (try? model.store?.verses(in: range))?.map(\.text).joined(separator: " ") ?? ""
            if !term.isEmpty, !(range.display.localizedStandardContains(term) || text.localizedStandardContains(term)) { return nil }
            return (favorite, range, text)
        }
    }

    var body: some View {
        let rows = rows
        if rows.isEmpty {
            ContentUnavailableView(search.isEmpty ? "No Favorites" : "No Matches", systemImage: "heart",
                                   description: Text(search.isEmpty ? "No favorites are shared in this Bible." : "Try another word."))
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
        } else {
            ForEach(rows, id: \.favorite.id) { row in
                Button { model.go(to: row.range.start) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(row.range.display).font(.headline).lineLimit(1)
                            Spacer()
                            Image(systemName: "heart").font(.caption).foregroundStyle(.red).accessibilityHidden(true)
                        }
                        if !row.text.isEmpty {
                            Text(row.text).font(.callout).foregroundStyle(.secondary).lineLimit(3)
                        }
                    }
                    .padding(.vertical, 2)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityHint("Opens the passage")
            }
        }
    }
}
