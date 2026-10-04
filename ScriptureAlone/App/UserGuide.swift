import SwiftUI
import PDFKit

/// The bundled user guide: a PDF per app language, built by `Tools/build_manual.py` from
/// `docs/manual/`. It ships inside the app so it reads offline like everything else.
enum UserGuide {
    /// The guide in the language the app is running in, English failing that.
    static var url: URL? {
        let languages = Bundle.main.preferredLocalizations + ["en"]
        for language in languages {
            if let url = Bundle.main.url(forResource: "UserGuide-\(code(for: language))", withExtension: "pdf") {
                return url
            }
        }
        return nil
    }

    /// Bundle localizations ("de", "pt-BR", "zh-Hans") to the guide's file suffix.
    private static func code(for language: String) -> String {
        switch language {
        case "pt", "pt-BR": "pt-BR"
        case let l where l.hasPrefix("zh"): "zh-Hans"
        default: String(language.prefix(while: { $0 != "-" && $0 != "_" }))
        }
    }
}

/// The Settings row that opens the guide.
struct UserGuideRow: View {
    @State private var showing = false

    var body: some View {
        if UserGuide.url != nil {
            Button { showing = true } label: {
                Label("User Guide", systemImage: "book.pages")
            }
            .sheet(isPresented: $showing) {
                UserGuideView()
                    #if os(macOS)
                    .frame(minWidth: 560, idealWidth: 640, minHeight: 700, idealHeight: 820)
                    #endif
            }
        }
    }
}

struct UserGuideView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let url = UserGuide.url {
                    GuidePDFView(url: url)
                        .ignoresSafeArea(edges: .bottom)
                } else {
                    ContentUnavailableView("User Guide", systemImage: "book.pages")
                }
            }
            .navigationTitle("User Guide")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                if let url = UserGuide.url {
                    ToolbarItem(placement: .primaryAction) {
                        ShareLink(item: url) { Label("Share", systemImage: "square.and.arrow.up") }
                    }
                }
            }
        }
    }
}

#if os(iOS)
private struct GuidePDFView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        configure(view)
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {}

    private func configure(_ view: PDFView) {
        view.document = PDFDocument(url: url)
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.backgroundColor = .secondarySystemBackground
    }
}
#else
private struct GuidePDFView: NSViewRepresentable {
    let url: URL

    func makeNSView(context: Context) -> PDFView {
        let view = PDFView()
        view.document = PDFDocument(url: url)
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.backgroundColor = .windowBackgroundColor
        return view
    }

    func updateNSView(_ view: PDFView, context: Context) {}
}
#endif
