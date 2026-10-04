import SwiftUI
import ScriptureAloneCore

/// The User Guide, drawn natively from the downloaded package so it reflows to any width and
/// follows Dynamic Type — the print edition's look (`docs/manual/template.html`), in light and dark.
struct UserGuideView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var store = UserGuideStore.shared

    var body: some View {
        NavigationStack {
            Group {
                switch store.phase {
                case .ready:
                    if let guide = store.guide {
                        GuideDocument(guide: guide)
                    }
                case .failed:
                    failed
                case .idle, .downloading:
                    downloading
                }
            }
            .background(GuideStyle.paper)
            .navigationTitle("User Guide")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    ShareLink(item: UserGuidePackage.pdfURL(language: store.language)) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                }
            }
        }
        .tint(GuideStyle.accent)
        .environment(store)
        .onAppear { store.open() }
    }

    private var downloading: some View {
        VStack(spacing: 18) {
            Image(systemName: "book.pages")
                .font(.system(size: 44, weight: .light))
                .foregroundStyle(GuideStyle.accent)
                .accessibilityHidden(true)
            Text("Downloading the User Guide…", comment: "Shown while the User Guide downloads the first time it is opened")
                .font(.headline)
                .multilineTextAlignment(.center)
            Group {
                if case .downloading(let fraction?) = store.phase {
                    ProgressView(value: fraction)
                } else {
                    ProgressView(value: 0)
                }
            }
            .progressViewStyle(.linear)
            .frame(maxWidth: 220)
            if let size = store.downloadSize {
                Text(size.formatted(.byteCount(style: .file)))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }

    private var failed: some View {
        ContentUnavailableView {
            Label("User Guide", systemImage: "wifi.exclamationmark")
        } description: {
            Text("The User Guide couldn't be downloaded. Check your connection and try again.",
                 comment: "Shown when the User Guide download fails (offline or the server can’t be reached)")
        } actions: {
            Button("Try Again") { store.retry() }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
        }
    }
}

// MARK: - Document

/// Whether the column is wide enough for text and pictures side by side.
private struct GuideWideKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    var guideWide: Bool {
        get { self[GuideWideKey.self] }
        set { self[GuideWideKey.self] = newValue }
    }
}

private struct GuideDocument: View {
    let guide: UserGuide
    @State private var width: CGFloat = 0
    @State private var jump: String?

    private static let contentsID = "contents"

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    GuideCover(cover: guide.cover)
                        .padding(.top, 8)
                    GuideContents(guide: guide) { jump = Self.chapterID($0) }
                        .id(Self.contentsID)
                        .padding(.top, 36)
                    ForEach(Array(guide.chapters.enumerated()), id: \.offset) { offset, chapter in
                        GuideChapter(number: offset + 1, chapter: chapter)
                            .id(Self.chapterID(offset))
                            .padding(.top, 56)
                    }
                    Text(guide.cover.edition)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)
                        .padding(.vertical, 40)
                }
                .frame(maxWidth: 680, alignment: .leading)
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
                .environment(\.guideWide, width >= 540)
                .padding(.horizontal, horizontalMargin)
                .frame(maxWidth: .infinity)
            }
            .scrollContentBackground(.hidden)
            #if DEBUG
            // `-userGuideChapter 5`: opens at that chapter, for checking its layout.
            .task {
                let n = UserDefaults.standard.integer(forKey: "userGuideChapter")
                guard n > 0 else { return }
                try? await Task.sleep(for: .milliseconds(400))
                jump = Self.chapterID(n - 1)
            }
            #endif
            .onChange(of: jump) {
                guard let target = jump else { return }
                withAnimation(.snappy) { proxy.scrollTo(target, anchor: .top) }
                jump = nil
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { jump = Self.contentsID } label: {
                        Text("Contents", comment: "User Guide menu item: back to the table of contents")
                    }
                    Divider()
                    ForEach(Array(guide.chapters.enumerated()), id: \.offset) { offset, chapter in
                        Button { jump = Self.chapterID(offset) } label: {
                            Text(verbatim: "\(offset + 1). \(chapter.title)")
                        }
                    }
                } label: {
                    Label {
                        Text("Chapters", comment: "User Guide toolbar menu listing its chapters, to jump to one")
                    } icon: {
                        Image(systemName: "list.bullet")
                    }
                }
            }
        }
    }

    private var horizontalMargin: CGFloat {
        #if os(macOS)
        32
        #else
        22
        #endif
    }

    private static func chapterID(_ offset: Int) -> String { "chapter-\(offset)" }
}

// MARK: Cover

private struct GuideCover: View {
    let cover: UserGuide.Cover
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.guideWide) private var wide

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(cover.eyebrow)
                .font(.caption.weight(.semibold))
                .textCase(.uppercase)
                .tracking(2)
                .opacity(0.75)
            Text(cover.title)
                .font(.system(wide ? .largeTitle : .title, weight: .bold))
                .accessibilityAddTraits(.isHeader)
            Text(cover.subtitle)
                .font(GuideStyle.serif(.title3))
                .opacity(0.85)
                .fixedSize(horizontal: false, vertical: true)
            if !cover.images.isEmpty {
                PhoneFan(images: cover.images, height: wide ? 300 : 180)
                    .padding(.top, 18)
                    .padding(.bottom, -28) // the phones run off the bottom edge, as in print
            }
        }
        .foregroundStyle(colorScheme == .dark ? Color(hex: 0xFBE7C8) : Color(hex: 0x2B1A0C))
        .padding(.horizontal, wide ? 36 : 24)
        .padding(.top, wide ? 36 : 26)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background { background }
        .clipShape(.rect(cornerRadius: 22))
    }

    private var background: some View {
        let stops: [Color] = colorScheme == .dark
            ? [Color(hex: 0x4A3220), Color(hex: 0x6B4322), Color(hex: 0x8A4F1F)]
            : [Color(hex: 0xFBE7C8), Color(hex: 0xF2C58F), Color(hex: 0xB86A2C)]
        return ZStack {
            LinearGradient(colors: stops, startPoint: UnitPoint(x: 0.4, y: 0), endPoint: UnitPoint(x: 0.6, y: 1))
            GeometryReader { proxy in
                let size = max(proxy.size.width, proxy.size.height) * 0.9
                Circle()
                    .fill(RadialGradient(colors: [Color(hex: 0xFFF7E6).opacity(colorScheme == .dark ? 0.25 : 0.95),
                                                  Color(hex: 0xFFE2B4).opacity(colorScheme == .dark ? 0.12 : 0.5),
                                                  .clear],
                                         center: .center, startRadius: 0, endRadius: size / 2))
                    .frame(width: size, height: size)
                    .position(x: proxy.size.width * 0.95, y: proxy.size.height * 0.75)
            }
        }
    }
}

/// The cover's screenshots, fanned.
private struct PhoneFan: View {
    let images: [String]
    let height: CGFloat
    @Environment(UserGuideStore.self) private var store

    var body: some View {
        let shown = Array(images.prefix(3))
        HStack(alignment: .bottom, spacing: -height * 0.12) {
            ForEach(Array(shown.enumerated()), id: \.offset) { offset, name in
                let middle = shown.count == 3 && offset == 1
                if let image = store.image(named: name) {
                    image
                        .resizable()
                        .scaledToFit()
                        .frame(height: middle ? height : height * 0.88)
                        .rotationEffect(.degrees(shown.count == 3 ? Double(offset - 1) * 5 : 0), anchor: .bottom)
                        .zIndex(middle ? 1 : 0)
                        .shadow(color: .black.opacity(0.18), radius: 10, y: 6)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityHidden(true)
    }
}

// MARK: Contents

private struct GuideContents: View {
    let guide: UserGuide
    let open: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(guide.contents)
                .font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
                .padding(.bottom, 10)
            ForEach(Array(guide.chapters.enumerated()), id: \.offset) { offset, chapter in
                Button { open(offset) } label: {
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        Text(verbatim: "\(offset + 1)")
                            .font(.body.weight(.bold))
                            .foregroundStyle(GuideStyle.accent)
                            .frame(minWidth: 24, alignment: .leading)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(chapter.title)
                                .font(.body.weight(.medium))
                                .foregroundStyle(.primary)
                            if !chapter.summary.isEmpty {
                                Text(chapter.summary)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .multilineTextAlignment(.leading)
                        Spacer(minLength: 8)
                        Image(systemName: "chevron.forward")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.tertiary)
                            .accessibilityHidden(true)
                    }
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Divider().overlay(GuideStyle.rule)
            }
        }
    }
}

// MARK: Chapter

private struct GuideChapter: View {
    let number: Int
    let chapter: UserGuide.Chapter

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: "\(number)")
                    .font(.footnote.weight(.bold))
                    .tracking(1.5)
                    .foregroundStyle(GuideStyle.accent)
                    .accessibilityHidden(true)
                Text(chapter.title)
                    .font(.title.bold())
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityHeading(.h1)
            }
            if !chapter.lede.isEmpty {
                Text(GuideRuns.attributed(chapter.lede))
                    .font(GuideStyle.serif(.title3))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 4)
            }
            GuideBlocks(blocks: chapter.blocks)
        }
    }
}
