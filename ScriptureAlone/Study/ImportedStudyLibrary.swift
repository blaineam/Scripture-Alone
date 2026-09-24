import SwiftUI
import Observation
import ScriptureAloneCore

/// The study material that came with the reader's imports — a study Bible's notes, book
/// introductions, essays and maps — opened once for the whole app.
///
/// Each import carries its own in its store file (`ImportedStudyStore`), so this follows the
/// imported library: an import added here or arriving from another device through iCloud shows
/// up, and one removed goes.
@MainActor
@Observable
final class ImportedStudyLibrary {
    static let shared = ImportedStudyLibrary()

    private(set) var stores: [ImportedStudyStore] = []

    private init() {
        reload()
        NotificationCenter.default.addObserver(forName: ImportedBibleSync.changedNotification, object: nil,
                                               queue: .main) { _ in
            MainActor.assumeIsolated { ImportedStudyLibrary.shared.reload() }
        }
    }

    func reload() {
        let files = (try? FileManager.default.contentsOfDirectory(at: ImportedLibrary.directory,
                                                                  includingPropertiesForKeys: nil)) ?? []
        stores = files.filter { $0.pathExtension == "sqlite" }
            .compactMap { url in
                guard let info = try? BibleStore(url: url).info else { return nil }
                // Reuse the one already open for this file, so a view holding it keeps working.
                return stores.first { $0.url == url } ?? ImportedStudyStore(url: url, info: info)
            }
            .sorted { $0.source.name.localizedCaseInsensitiveCompare($1.source.name) == .orderedAscending }
    }

    var sources: [StudySource] { stores.map(\.source) }

    func store(for sourceID: String) -> ImportedStudyStore? {
        stores.first { $0.source.id == sourceID }
    }

    var isEmpty: Bool { stores.isEmpty }
}

/// A study Bible's maps and pictures for the chapter, in the Context tab.
struct ImportedImagesSection: View {
    let chapter: ChapterRef
    @State private var shown: ShownImage?

    struct ShownImage: Identifiable {
        let id: Int
        let caption: String
        let store: ImportedStudyStore
    }

    var body: some View {
        let library = ImportedStudyLibrary.shared
        let found = library.stores.flatMap { store in store.images(in: chapter).map { (store, $0) } }
        if !found.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Label("Maps & Images", systemImage: "photo.on.rectangle")
                    .font(.title3.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                ScrollView(.horizontal) {
                    HStack(spacing: 12) {
                        ForEach(found, id: \.1.id) { store, image in
                            Button {
                                shown = ShownImage(id: image.id, caption: image.caption, store: store)
                            } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    StudyImageView(store: store, id: image.id)
                                        .frame(width: 180, height: 130)
                                        .clipShape(.rect(cornerRadius: 10))
                                    Text(image.caption.capitalized)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                        .frame(width: 180, alignment: .leading)
                                }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text(verbatim: image.caption))
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
            .sheet(item: $shown) { image in
                NavigationStack {
                    ScrollView([.horizontal, .vertical]) {
                        StudyImageView(store: image.store, id: image.id, fit: false)
                    }
                    .navigationTitle(Text(verbatim: image.caption.capitalized))
                    #if os(iOS)
                    .navigationBarTitleDisplayMode(.inline)
                    #endif
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { shown = nil }
                        }
                    }
                }
                #if os(macOS)
                .frame(minWidth: 640, minHeight: 520)
                #endif
            }
        }
    }
}

/// One picture from an imported study Bible, decoded off the main thread.
struct StudyImageView: View {
    let store: ImportedStudyStore
    let id: Int
    var fit = true
    @State private var image: Image?

    var body: some View {
        Group {
            if let image {
                if fit {
                    image.resizable().scaledToFill()
                } else {
                    image
                }
            } else {
                Rectangle().fill(.quaternary)
            }
        }
        .task(id: id) {
            let store = store, id = id
            let data = await Task.detached(priority: .userInitiated) { store.imageData(id) }.value
            guard let data else { return }
            #if os(iOS)
            if let decoded = UIImage(data: data) { image = Image(uiImage: decoded) }
            #else
            if let decoded = NSImage(data: data) { image = Image(nsImage: decoded) }
            #endif
        }
    }
}
