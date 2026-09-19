import SwiftUI
import ScriptureAloneCore

/// Designs a verse image on device: a live card preview, templates and options, then share or save.
struct ShareDesigner: View {
    let source: ShareSource
    @Environment(\.dismiss) private var dismiss

    @AppStorage(ShareSettingsKey.template) private var template = ShareTemplate.parchment
    @AppStorage(ShareSettingsKey.aspect) private var aspect = ShareAspect.square
    @AppStorage(ShareSettingsKey.family) private var family = FontFamily.newYork
    @AppStorage(ShareSettingsKey.alignment) private var alignment = ShareAlignment.center
    @AppStorage(ShareSettingsKey.redLetters) private var redLetters = true
    @AppStorage(ShareSettingsKey.verseNumbers) private var verseNumbers = true
    @AppStorage(ShareSettingsKey.wordmark) private var wordmark = true

    @State private var rendered: (image: ShareImage, cgImage: CGImage, key: RenderKey)?
    @State private var previewWidth: CGFloat = 360
    @State private var status: String?
    @State private var saving = false
    #if os(macOS)
    @State private var exporting = false
    #endif

    private var style: ShareStyle {
        ShareStyle(template: template, aspect: aspect, family: family, alignment: alignment,
                   redLetters: redLetters, verseNumbers: verseNumbers, wordmark: wordmark)
    }

    private var hasRed: Bool { source.verses.contains { !$0.red.isEmpty } }

    var body: some View {
        let style = style
        let fit = ShareCardFitter.fit(verses: source.verses, info: source.info, style: style,
                                      verseCount: source.store.verseCount)
        NavigationStack {
            ScrollView {
                #if os(macOS)
                HStack(alignment: .top, spacing: 28) {
                    VStack(spacing: 14) {
                        preview(fit, style: style, maxHeight: 520)
                        trimNote(fit)
                    }
                    .frame(maxWidth: .infinity)
                    controls(fit).frame(width: 300)
                }
                .padding(24)
                #else
                VStack(spacing: 18) {
                    preview(fit, style: style, maxHeight: 400)
                    trimNote(fit)
                    controls(fit)
                }
                .padding()
                #endif
            }
            .navigationTitle("Share Image")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { toolbar(fit, style: style) }
        }
        .task(id: RenderKey(content: fit.content, style: style)) {
            // Every option is a discrete tap, so render straight away (no debounce whose cancellation
            // could leave the share button waiting). The key keeps a stale image from being shared.
            let key = RenderKey(content: fit.content, style: style)
            guard rendered?.key != key else { return }
            // ImageRenderer can come back empty mid-transition; try again briefly rather than leave the button waiting.
            for _ in 0..<5 {
                if let output = ShareRenderer.render(fit.content, style: style) {
                    rendered = (output.image, output.cgImage, key)
                    return
                }
                try? await Task.sleep(for: .milliseconds(120))
                if Task.isCancelled { return }
            }
        }
        .onAppear(perform: applyLinkStyle)
        #if os(macOS)
        .fileExporter(isPresented: $exporting, item: rendered?.image, contentTypes: [.png],
                      defaultFilename: rendered?.image.filename) { result in
            if case .failure(let error) = result { status = error.localizedDescription }
        }
        #endif
    }

    private struct RenderKey: Equatable {
        let content: ShareCardContent
        let style: ShareStyle
    }

    // MARK: Preview

    private func preview(_ fit: ShareCardFitter.Result, style: ShareStyle, maxHeight: CGFloat) -> some View {
        let size = style.aspect.size
        let scale = min(previewWidth / size.width, maxHeight / size.height)
        return ShareCard(content: fit.content, style: style)
            .scaleEffect(scale, anchor: .topLeading)
            .frame(width: size.width * scale, height: size.height * scale, alignment: .topLeading)
            .clipShape(.rect(cornerRadius: 14))
            .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
            .frame(maxWidth: .infinity)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { previewWidth = max(120, $0) }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Preview: \(fit.content.reference), \(style.template.title)")
            .animation(.snappy, value: style.aspect)
    }

    @ViewBuilder
    private func trimNote(_ fit: ShareCardFitter.Result) -> some View {
        if fit.trimmed {
            Label("A card holds \(fit.shownVerses) of these \(fit.totalVerses) verses, so it shows \(fit.content.reference). Select fewer verses, or share the text for the whole passage.",
                  systemImage: "text.badge.minus")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: Controls

    private func controls(_ fit: ShareCardFitter.Result) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            templateStrip

            Picker("Shape", selection: $aspect) {
                ForEach(ShareAspect.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .accessibilityLabel("Shape")

            HStack {
                Text("Typeface")
                Spacer()
                Picker("Typeface", selection: $family) {
                    ForEach(FontFamily.allCases) { Text($0.title).font($0.swiftUIFont(size: 15)).tag($0) }
                }
                .labelsHidden()
            }

            Picker("Alignment", selection: $alignment) {
                ForEach(ShareAlignment.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .accessibilityLabel("Alignment")

            VStack(alignment: .leading, spacing: 12) {
                Toggle("Words of Christ in red", isOn: $redLetters).disabled(!hasRed)
                if source.verses.count > 1 {
                    Toggle("Verse numbers", isOn: $verseNumbers)
                }
                Toggle("Scripture Alone wordmark", isOn: $wordmark)
            }

            Text("Text sizes itself to fit. Made on this device — nothing is uploaded.")
                .font(.caption)
                .foregroundStyle(.secondary)

            actions
        }
    }

    private var templateStrip: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 10) {
                ForEach(ShareTemplate.allCases) { item in
                    Button { template = item } label: {
                        VStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 10)
                                .fill(item.backgroundStyle)
                                .overlay {
                                    Text("Aa")
                                        .font(family.swiftUIFont(size: 17))
                                        .foregroundStyle(Color(PlatformColor(hex: item.ink)))
                                }
                                .overlay {
                                    RoundedRectangle(cornerRadius: 10)
                                        .strokeBorder(template == item ? Color.accentColor : Color.primary.opacity(0.12),
                                                      lineWidth: template == item ? 3 : 1)
                                }
                                .frame(width: 56, height: 56)
                            Text(item.title)
                                .font(.caption2)
                                .foregroundStyle(template == item ? .primary : .secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(item.title)
                    .accessibilityAddTraits(template == item ? .isSelected : [])
                }
            }
            .padding(.vertical, 4)
        }
        .scrollIndicators(.hidden)
    }

    @ViewBuilder
    private var actions: some View {
        let link = source.link(style: style)
        VStack(alignment: .leading, spacing: 10) {
            #if os(iOS)
            Button {
                saveToPhotos()
            } label: {
                Label(saving ? "Saving…" : "Save to Photos", systemImage: "square.and.arrow.down")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.glass)
            .disabled(rendered == nil || saving)
            #else
            Button {
                exporting = true
            } label: {
                Label("Save Image…", systemImage: "square.and.arrow.down").frame(maxWidth: .infinity)
            }
            .buttonStyle(.glass)
            .disabled(rendered == nil)
            .keyboardShortcut("s", modifiers: .command)
            #endif

            if let link {
                HStack {
                    ShareLink(item: link) { Label("Share Link", systemImage: "link").frame(maxWidth: .infinity) }
                        .buttonStyle(.glass)
                    Button {
                        SharePasteboard.copy(link)
                        flash("Link copied")
                    } label: {
                        Label("Copy Link", systemImage: "doc.on.doc").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.glass)
                }
                Text("The link carries the verse itself, so the card rebuilds in any browser. No server stores it.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Text(source.linksAllowed ? "This passage is too long for a link — share the image or the text instead."
                                         : "Links aren’t available for this translation — share the image instead.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let status {
                Text(status).font(.footnote.weight(.semibold)).transition(.opacity)
            }
        }
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private func toolbar(_ fit: ShareCardFitter.Result, style: ShareStyle) -> some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button("Done") { dismiss() }
        }
        ToolbarItem(placement: .primaryAction) {
            if let rendered, rendered.key == RenderKey(content: fit.content, style: style) {
                ShareLink(item: rendered.image,
                          preview: SharePreview(fit.content.reference, image: Image(decorative: rendered.cgImage, scale: ShareRenderer.scale))) {
                    Label("Share Image", systemImage: "square.and.arrow.up")
                }
            } else {
                ProgressView().controlSize(.small)
            }
        }
    }

    // MARK: Actions

    /// A share link's template, typeface and aspect become this card's starting point.
    private func applyLinkStyle() {
        guard let payload = source.linkStyle else { return }
        if let value = payload.template.flatMap(ShareTemplate.init(rawValue:)) { template = value }
        if let value = payload.aspect.flatMap(ShareAspect.init(rawValue:)) { aspect = value }
        if let value = payload.font.flatMap(FontFamily.init(shareToken:)) { family = value }
    }

    #if os(iOS)
    private func saveToPhotos() {
        guard let png = rendered?.image.png else { return }
        saving = true
        Task {
            do {
                try await PhotoSaver.save(png)
                flash("Saved to Photos")
            } catch {
                flash(error.localizedDescription)
            }
            saving = false
        }
    }
    #endif

    private func flash(_ message: String) {
        withAnimation { status = message }
        Task {
            try? await Task.sleep(for: .seconds(2.5))
            withAnimation { if status == message { status = nil } }
        }
    }
}
