import SwiftUI
import ScriptureAloneCore

/// Designs a verse image on device: a live card preview, a row of ready-made styles, and — one tap
/// deeper, under Customize — the ground, text color, shadow, typeface and what to show. Then share
/// or save. The last design is remembered (`ShareStyle(defaults:)`).
struct ShareDesigner: View {
    let source: ShareSource
    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var displayScale
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif

    @State private var style = ShareStyle(defaults: .standard)
    @AppStorage(ShareSettingsKey.customizing) private var customizing = false

    @State private var rendered: (image: ShareImage, cgImage: CGImage, key: RenderKey)?
    @State private var backdrop: (key: BackdropKey, image: CGImage, colors: ShareColors)?
    @State private var previewWidth: CGFloat = 360
    @State private var status: String?
    @State private var saving = false
    #if os(macOS)
    @State private var exporting = false
    #endif

    private var hasRed: Bool { source.verses.contains { !$0.red.isEmpty } }

    /// Side by side on a Mac and a regular-width iPad; the preview pinned above the controls on a phone.
    private var sideBySide: Bool {
        #if os(macOS)
        true
        #else
        sizeClass == .regular
        #endif
    }

    var body: some View {
        let style = style
        let fit = ShareCardFitter.fit(verses: source.verses, info: source.info, style: style,
                                      verseCount: source.source.verseCount)
        NavigationStack {
            Group {
                if sideBySide {
                    HStack(alignment: .top, spacing: 28) {
                        VStack(spacing: 14) {
                            preview(fit, style: style, maxHeight: 560)
                            trimNote(fit)
                        }
                        .frame(maxWidth: .infinity)
                        .padding([.leading, .vertical], 24)
                        ScrollView {
                            controls(fit).padding(.vertical, 24).padding(.trailing, 24)
                        }
                        .frame(width: 340)
                    }
                } else {
                    VStack(spacing: 0) {
                        preview(fit, style: style, maxHeight: 300)
                            .padding(.horizontal)
                            .padding(.vertical, 12)
                        Divider().opacity(0.5)
                        ScrollView {
                            VStack(spacing: 14) {
                                trimNote(fit)
                                controls(fit)
                            }
                            .padding()
                        }
                    }
                }
            }
            .navigationTitle("Share Image")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { toolbar(fit, style: style) }
        }
        .task(id: BackdropKey(style: style, width: previewPixels(style))) {
            // The preview's ground at its own pixel size, drawn off the main thread.
            let key = BackdropKey(style: style, width: previewPixels(style))
            guard backdrop?.key != key else { return }
            let size = style.aspect.size
            let pixelsPerPoint = CGFloat(key.width) / size.width
            let (background, ink, shadow) = (style.background, style.ink, style.shadow)
            let result = await Task.detached(priority: .userInitiated) {
                (ShareBackdrop.image(background, size: size, pixelsPerPoint: pixelsPerPoint),
                 ShareContrast.resolve(background: background, ink: ink, shadow: shadow, stats: ShareBackdrop.stats(background)))
            }.value
            if !Task.isCancelled, let image = result.0 { backdrop = (key, image, result.1) }
        }
        .task(id: RenderKey(content: fit.content, style: style)) {
            // Every option is a discrete tap, so render straight away (no debounce whose cancellation
            // could leave the share button waiting). The key keeps a stale image from being shared.
            let key = RenderKey(content: fit.content, style: style)
            guard rendered?.key != key else { return }
            // The export-size ground first, off the main thread; ImageRenderer then only lays out text.
            let (background, size, ink, shadow) = (style.background, style.aspect.size, style.ink, style.shadow)
            _ = await Task.detached(priority: .userInitiated) {
                ShareRenderer.prepare(background, size: size, ink: ink, shadow: shadow)
            }.value
            if Task.isCancelled { return }
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
        .onChange(of: style) { _, new in new.save(to: .standard) }
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

    private struct BackdropKey: Equatable {
        let background: ShareBackground
        let aspect: ShareAspect
        let ink: UInt32?
        let shadow: ShareShadow
        let width: Int

        init(style: ShareStyle, width: Int) {
            (background, aspect, ink, shadow, self.width) = (style.background, style.aspect, style.ink, style.shadow, width)
        }
    }

    /// The colors to draw with now: the measured ones once the ground is ready, else its own.
    private func colors(for style: ShareStyle) -> ShareColors {
        if let backdrop, backdrop.key.background == style.background, backdrop.key.ink == style.ink,
           backdrop.key.shadow == style.shadow {
            return backdrop.colors
        }
        let stops = style.background.colors
        let stats = ShareBackdropStats(mean: ShareContrast.average(stops), lightest: stops.max { ShareContrast.luminance($0) < ShareContrast.luminance($1) }!,
                                       darkest: stops.min { ShareContrast.luminance($0) < ShareContrast.luminance($1) }!)
        return style.colors(on: stats)
    }

    // MARK: Preview

    private func previewScale(_ style: ShareStyle, maxHeight: CGFloat) -> CGFloat {
        let size = style.aspect.size
        return min(previewWidth / size.width, maxHeight / size.height)
    }

    /// The preview ground's width in pixels (rounded up to 64 so a resize doesn't redraw it constantly).
    private func previewPixels(_ style: ShareStyle) -> Int {
        let points = style.aspect.size.width * previewScale(style, maxHeight: sideBySide ? 560 : 300)
        return max(64, Int((points * displayScale / 64).rounded(.up)) * 64)
    }

    private func preview(_ fit: ShareCardFitter.Result, style: ShareStyle, maxHeight: CGFloat) -> some View {
        let size = style.aspect.size
        let scale = previewScale(style, maxHeight: maxHeight)
        let ground = backdrop.flatMap { $0.key.background == style.background && $0.key.aspect == style.aspect ? $0.image : nil }
        return ShareCard(content: fit.content, style: style, colors: colors(for: style), backdrop: ground)
            .scaleEffect(scale, anchor: .topLeading)
            .frame(width: size.width * scale, height: size.height * scale, alignment: .topLeading)
            .clipShape(.rect(cornerRadius: 14))
            .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
            .frame(maxWidth: .infinity)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { previewWidth = max(120, $0) }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Preview: \(fit.content.reference), \(style.background.title)")
            .accessibilityIdentifier("share.preview")
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
            VStack(alignment: .leading, spacing: 8) {
                sectionTitle("Styles")
                styleStrip
            }

            Picker("Shape", selection: $style.aspect) {
                ForEach(ShareAspect.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .accessibilityLabel("Shape")
            .accessibilityIdentifier("share.shape")

            customizeButton
            if customizing { fineControls }

            Text("Text sizes itself to fit. Made on this device — nothing is uploaded.")
                .font(.caption)
                .foregroundStyle(.secondary)

            actions
        }
    }

    private func sectionTitle(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.secondary)
            .accessibilityAddTraits(.isHeader)
    }

    /// The ready-made styles: each ground with the text color, shadow and typeface made for it.
    private var styleStrip: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 10) {
                    ForEach(ShareBackground.allCases) { item in
                        let chosen = style.background == item
                        Button {
                            style.apply(item)
                        } label: {
                            VStack(spacing: 6) {
                                StyleTile(background: item, family: FontFamily(rawValue: item.presetFamily) ?? .newYork,
                                          shadow: item.presetShadow, chosen: chosen)
                                Text(item.title)
                                    .font(.caption2)
                                    .foregroundStyle(chosen ? .primary : .secondary)
                                    .lineLimit(1)
                            }
                            .frame(width: 72)
                        }
                        .buttonStyle(.plain)
                        .id(item)
                        .accessibilityLabel(item.title)
                        .accessibilityHint(Text("Applies this style’s background, text color, shadow and typeface", comment: "VoiceOver hint on a verse-image style"))
                        .accessibilityIdentifier("share.style.\(item.rawValue)")
                        .accessibilityAddTraits(chosen ? .isSelected : [])
                    }
                }
                .padding(.vertical, 4)
            }
            .scrollIndicators(.hidden)
            .frame(height: 100)
            .onAppear { proxy.scrollTo(style.background, anchor: .center) }
        }
    }

    private var customizeButton: some View {
        Button {
            withAnimation(.snappy) { customizing.toggle() }
        } label: {
            HStack {
                Label("Customize", systemImage: "slider.horizontal.3")
                Spacer()
                Image(systemName: "chevron.down")
                    .rotationEffect(.degrees(customizing ? 180 : 0))
                    .foregroundStyle(.secondary)
            }
            .contentShape(.rect)
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("share.customize")
        .accessibilityValue(customizing ? Text("Shown", comment: "VoiceOver value: the verse-image options are open")
                                        : Text("Hidden", comment: "VoiceOver value: the verse-image options are closed"))
    }

    @ViewBuilder
    private var fineControls: some View {
        let colors = colors(for: style)
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 8) {
                sectionTitle("Background")
                backgroundRow("Clean", ShareBackground.clean)
                backgroundRow("Textured", ShareBackground.textured)
            }

            VStack(alignment: .leading, spacing: 8) {
                sectionTitle("Text Color")
                inkRow
                if colors.adjusted {
                    Label("Adjusted to stay readable on this background.", systemImage: "circle.lefthalf.filled")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                sectionTitle("Shadow")
                Picker("Shadow", selection: $style.shadow) {
                    ForEach(ShareShadow.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .accessibilityLabel("Shadow")
                .accessibilityIdentifier("share.shadow")
            }

            HStack {
                Text("Typeface")
                Spacer()
                Picker("Typeface", selection: $style.family) {
                    ForEach(FontFamily.allCases) { Text($0.title).font($0.swiftUIFont(size: 15)).tag($0) }
                }
                .labelsHidden()
            }

            Picker("Alignment", selection: $style.alignment) {
                ForEach(ShareAlignment.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .accessibilityLabel("Alignment")

            VStack(alignment: .leading, spacing: 12) {
                Toggle("Words of Christ in red", isOn: $style.redLetters).disabled(!hasRed)
                if source.verses.count > 1 {
                    Toggle("Verse numbers", isOn: $style.verseNumbers)
                }
                Toggle("Scripture Alone wordmark", isOn: $style.wordmark)
            }
        }
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    private func backgroundRow(_ title: LocalizedStringKey, _ items: [ShareBackground]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(items) { item in
                        let chosen = style.background == item
                        Button {
                            // A new ground keeps the typeface and shadow; the text color follows it
                            // unless the reader picked one.
                            style.background = item
                        } label: {
                            BackdropSwatch(background: item, side: 44)
                                .clipShape(.rect(cornerRadius: 9))
                                .overlay {
                                    RoundedRectangle(cornerRadius: 9)
                                        .strokeBorder(chosen ? Color.accentColor : Color.primary.opacity(0.12), lineWidth: chosen ? 3 : 1)
                                }
                                .padding(2)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(item.title)
                        .accessibilityIdentifier("share.background.\(item.rawValue)")
                        .accessibilityAddTraits(chosen ? .isSelected : [])
                    }
                }
            }
            .scrollIndicators(.hidden)
            .frame(height: 52)
        }
    }

    /// Automatic (the ground's own color, picked again whenever the ground changes), five that suit
    /// the ground, and any color at all.
    private var inkRow: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 10) {
                inkChip(style.background.ink, selected: style.ink == nil, label: Text("Automatic")) {
                    style.ink = nil
                }
                .overlay {
                    Image(systemName: "wand.and.sparkles")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color(PlatformColor(hex: ShareContrast.luminance(style.background.ink) > 0.4 ? 0x111111 : 0xFFFFFF)))
                        .allowsHitTesting(false)
                }
                .accessibilityIdentifier("share.ink.automatic")
                ForEach(style.background.palette) { swatch in
                    inkChip(swatch.hex, selected: style.ink == swatch.hex, label: Text(swatch.name)) {
                        style.ink = swatch.hex
                    }
                }
                ColorPicker(selection: customInk, supportsOpacity: false) {
                    Text("Custom Color", comment: "Verse-image text color: pick any color")
                }
                .labelsHidden()
                .accessibilityLabel(Text("Custom Color", comment: "Verse-image text color: pick any color"))
                .accessibilityIdentifier("share.ink.custom")
                .padding(4)
                .overlay {
                    if let ink = style.ink, !style.background.palette.contains(where: { $0.hex == ink }) {
                        Circle().strokeBorder(Color.accentColor, lineWidth: 2.5)
                    }
                }
            }
            .padding(.vertical, 2)
        }
        .scrollIndicators(.hidden)
    }

    private func inkChip(_ hex: UInt32, selected: Bool, label: Text, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Circle()
                .fill(Color(PlatformColor(hex: hex)))
                .frame(width: 34, height: 34)
                .overlay { Circle().strokeBorder(Color.primary.opacity(0.18), lineWidth: 1) }
                .padding(3)
                .overlay { if selected { Circle().strokeBorder(Color.accentColor, lineWidth: 2.5) } }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var customInk: Binding<Color> {
        Binding {
            Color(PlatformColor(hex: style.ink ?? style.background.ink))
        } set: { color in
            style.ink = Self.hex(of: color)
        }
    }

    private static func hex(of color: Color) -> UInt32 {
        #if os(iOS)
        var (r, g, b, a): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        #else
        let srgb = NSColor(color).usingColorSpace(.sRGB) ?? .black
        let (r, g, b) = (srgb.redComponent, srgb.greenComponent, srgb.blueComponent)
        #endif
        func channel(_ value: CGFloat) -> UInt32 { UInt32(max(0, min(255, (value * 255).rounded()))) }
        return channel(r) << 16 | channel(g) << 8 | channel(b)
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
                        flash(String(localized: "Link copied"))
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
                .accessibilityIdentifier("share.export")
            } else {
                ProgressView().controlSize(.small)
            }
        }
    }

    // MARK: Actions

    /// A share link's template, typeface and aspect become this card's starting point.
    private func applyLinkStyle() {
        guard let payload = source.linkStyle else { return }
        if let value = payload.template.flatMap(ShareBackground.init(rawValue:)) {
            style.background = value
            style.ink = nil
        }
        if let value = payload.aspect.flatMap(ShareAspect.init(rawValue:)) { style.aspect = value }
        if let value = payload.font.flatMap(FontFamily.init(shareToken:)) { style.family = value }
    }

    #if os(iOS)
    private func saveToPhotos() {
        guard let png = rendered?.image.png else { return }
        saving = true
        Task {
            do {
                try await PhotoSaver.save(png)
                flash(String(localized: "Saved to Photos"))
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

/// A ground drawn small, for the style and background rows.
struct BackdropSwatch: View {
    let background: ShareBackground
    let side: CGFloat
    @Environment(\.displayScale) private var displayScale
    @State private var image: CGImage?

    var body: some View {
        ZStack {
            if let image {
                Image(decorative: image, scale: 1).resizable().interpolation(.high)
            } else {
                Rectangle().fill(background.backgroundStyle)
            }
        }
        .frame(width: side, height: side)
        .task(id: background) {
            // A square card's ground drawn at the swatch's pixel size: textures read at a glance.
            let size = ShareAspect.square.size
            let pixelsPerPoint = side * displayScale / size.width
            let background = background
            image = await Task.detached(priority: .utility) {
                ShareBackdrop.image(background, size: size, pixelsPerPoint: pixelsPerPoint)
            }.value
        }
    }
}

/// A ready-made style: its ground with "Aa" in its typeface, text color and shadow.
private struct StyleTile: View {
    let background: ShareBackground
    let family: FontFamily
    let shadow: ShareShadow
    let chosen: Bool

    var body: some View {
        let ink = Color(PlatformColor(hex: background.ink))
        let glow = ShareContrast.luminance(background.ink) <= 0.4
        let tint = Color(PlatformColor(hex: glow ? 0xFFFFFF : 0x000000))
        let layer = shadow.layers(size: 20, glow: glow).first
        BackdropSwatch(background: background, side: 64)
            .overlay {
                Text("Aa")
                    .font(family.swiftUIFont(size: 20))
                    .foregroundStyle(ink)
                    .shadow(color: tint.opacity(layer?.opacity ?? 0), radius: layer?.radius ?? 0, y: layer?.y ?? 0)
            }
            .clipShape(.rect(cornerRadius: 12))
            .overlay {
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(chosen ? Color.accentColor : Color.primary.opacity(0.12), lineWidth: chosen ? 3 : 1)
            }
    }
}
