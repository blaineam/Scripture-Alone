import SwiftUI
import ScriptureAloneCore

/// "About Study Resources": every dataset Study mode draws on, with its license and attribution.
struct StudySourcesView: View {
    @Environment(StudyModel.self) private var study

    var body: some View {
        List {
            Section {
                Text("Study mode uses only public-domain and openly licensed works. Everything is bundled with the app and works offline; nothing you look up leaves your device.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            ForEach(study.store?.sources ?? []) { source in
                Section(source.kind == .crossReferences ? "Cross References" : "Commentary") {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(source.name).font(.headline)
                        Text(source.author).font(.subheadline)
                        Text(source.year).font(.caption).foregroundStyle(.secondary)
                        Text(source.attribution).font(.caption).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 2)
                    }
                    .padding(.vertical, 4)
                    LabeledContent("License") {
                        if let url = source.licenseURL {
                            Link(source.license, destination: url)
                        } else {
                            Text(source.license)
                        }
                    }
                    if let url = source.url {
                        Link(destination: url) {
                            Label("Source", systemImage: "arrow.up.right.square")
                        }
                    }
                }
            }
        }
        .navigationTitle("Study Resources")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }
}
