import SwiftUI
import SwiftData
import ScriptureAloneCore

/// A popover anchored inside the chapter text.
struct ReaderPopover: Identifiable {
    enum Kind { case notes([String]), footnote(String) }
    let id = UUID()
    let kind: Kind
    let rect: CGRect
}

struct ReaderView: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.modelContext) private var context

    @AppStorage(SettingsKey.theme) private var theme = ReaderTheme.system
    @AppStorage(SettingsKey.fontFamily) private var family = FontFamily.newYork
    @AppStorage(SettingsKey.fontSize) private var fontSize = 19.0
    @AppStorage(SettingsKey.lineSpacing) private var lineSpacing = 1.35
    @AppStorage(SettingsKey.layout) private var layout = ReadingLayout.paragraphs
    @AppStorage(SettingsKey.redLetters) private var redLetters = true
    @AppStorage(SettingsKey.verseNumbers) private var verseNumbers = true
    @AppStorage(SettingsKey.headings) private var headings = true
    @AppStorage(SettingsKey.footnotes) private var footnotes = true
    @AppStorage(SettingsKey.autoScrollSpeed) private var autoScrollSpeed = 28.0

    @State private var showPicker = false
    @State private var showAppearance = false
    @State private var showNotes = false
    @State private var notesPath: [UUID] = []
    @State private var popover: ReaderPopover?
    @State private var autoScrolling = false
    @State private var study = StudyModel()

    #if os(iOS)
    @ScaledMetric(relativeTo: .body) private var dynamicTypeScale: CGFloat = 1
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    private var style: ReaderStyle {
        let palette = theme.palette(for: colorScheme)
        #if os(iOS)
        let size = fontSize * dynamicTypeScale
        #else
        let size = fontSize
        #endif
        return ReaderStyle(family: family, size: size, lineSpacing: lineSpacing, layout: layout,
                           redLetters: redLetters, verseNumbers: verseNumbers, headings: headings,
                           footnotes: footnotes, palette: palette,
                           paletteID: "\(theme.rawValue)-\(colorScheme == .dark ? "dark" : "light")")
    }

    var body: some View {
        NavigationStack {
            ChapterPane(chapter: model.location, style: style, autoScrollSpeed: autoScrolling ? autoScrollSpeed : 0,
                        onTap: handle, onReachedEnd: advanceWhileScrolling, onUserScroll: { autoScrolling = false })
                .ignoresSafeArea(edges: .bottom)
                .background(Color(style.palette.page))
                .popover(item: $popover, attachmentAnchor: .rect(.rect(popover?.rect ?? .zero))) { item in
                    ReaderPopoverView(item: item, openNote: openNote)
                        .presentationCompactAdaptation(.popover)
                }
                .safeAreaInset(edge: .bottom) {
                    VStack(spacing: 8) {
                        if ListenController.shared.isListening(in: model) {
                            NowPlayingBar()
                                .padding(.horizontal)
                                .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                        if !model.selection.isEmpty {
                            SelectionBar(onNote: createNoteFromSelection)
                                .padding(.horizontal)
                                .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                    .padding(.bottom, 8)
                }
                .animation(.snappy, value: model.selection.isEmpty)
                .animation(.snappy, value: ListenController.shared.isListening(in: model))
                .toolbar { toolbar }
                .contextReaderHooks()
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                .toolbarBackground(Color(style.palette.page), for: .navigationBar, .bottomBar)
                #endif
                .navigationTitle(model.location.display)
                #if os(macOS)
                // The passage button already names the chapter; don't repeat it as the window title.
                .toolbar(removing: .title)
                #endif
        }
        .inspector(isPresented: inspectorShown) {
            Group {
                if study.isOn && !studyAsSheet {
                    StudyPanel(onNote: createNoteFromSelection)
                } else {
                    NotesPanel(path: $notesPath)
                }
            }
            .inspectorColumnWidth(min: 300, ideal: 360, max: 480)
        }
        .sheet(isPresented: studySheetShown) {
            StudyPanel(isSheet: true, onNote: createNoteFromSelection)
        }
        .environment(study)
        .sheet(isPresented: $showPicker) {
            PassagePicker()
                #if os(macOS)
                .frame(minWidth: 560, minHeight: 620)
                #endif
        }
        .preferredColorScheme(theme.colorScheme)
        .background(keyboardShortcuts)
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        #if os(iOS)
        ToolbarItemGroup(placement: .topBarLeading) {
            notesButton
            studyButton
        }
        ToolbarItem(placement: .principal) { passageButton }
        ToolbarItemGroup(placement: .topBarTrailing) {
            translationMenu
            appearanceButton
        }
        ToolbarItemGroup(placement: .bottomBar) {
            previousButton
            Spacer()
            autoScrollControl
            listenButton
            Spacer()
            nextButton
        }
        #else
        ToolbarItemGroup(placement: .navigation) {
            previousButton
            nextButton
        }
        ToolbarItem(placement: .principal) { passageButton }
        ToolbarItemGroup(placement: .primaryAction) {
            autoScrollControl
            listenButton
            translationMenu
            appearanceButton
            notesButton
            studyButton
        }
        #endif
    }

    private var passageButton: some View {
        Button { showPicker = true } label: {
            HStack(spacing: 4) {
                Text(model.location.display).font(.headline)
                Image(systemName: "chevron.down").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            }
        }
        .buttonStyle(.plain)
        .keyboardShortcut("l", modifiers: .command)
        .accessibilityLabel("Go to passage, currently \(model.location.display)")
    }

    private var notesButton: some View {
        Button {
            showNotes.toggle()
            if showNotes { study.isOn = false }
        } label: { Label("Notes", systemImage: "note.text") }
            .keyboardShortcut("n", modifiers: [.command, .shift])
    }

    /// Study mode: the panel follows the last verse tapped; taps still select as usual.
    private var studyButton: some View {
        Button {
            if study.isOn {
                study.isOn = false
            } else {
                showNotes = false
                study.turnOn(selection: model.selection)
            }
        } label: {
            Label("Study", systemImage: study.isOn ? "book.and.wrench.fill" : "book.and.wrench")
        }
        .keyboardShortcut("s", modifiers: [.command, .option])
        .accessibilityValue(study.isOn ? "On" : "Off")
        .accessibilityHint("Shows cross references and commentary for the verse you tap.")
    }

    /// On iPhone, Study is a resizable sheet over the text; elsewhere it shares the inspector column.
    private var studyAsSheet: Bool {
        #if os(iOS)
        horizontalSizeClass == .compact
        #else
        false
        #endif
    }

    /// One inspector column, shared by Notes and Study.
    private var inspectorShown: Binding<Bool> {
        Binding(get: { showNotes || (study.isOn && !studyAsSheet) }, set: { shown in
            if !shown {
                showNotes = false
                study.isOn = false
            }
        })
    }

    private var studySheetShown: Binding<Bool> {
        Binding(get: { study.isOn && studyAsSheet && !showNotes }, set: { if !$0 { study.isOn = false } })
    }

    private var previousButton: some View {
        Button { model.previous() } label: { Label("Previous Chapter", systemImage: "chevron.left") }
            .disabled(model.location.previous == nil)
            .keyboardShortcut("[", modifiers: .command)
    }

    private var nextButton: some View {
        Button { model.next() } label: { Label("Next Chapter", systemImage: "chevron.right") }
            .disabled(model.location.next == nil)
            .keyboardShortcut("]", modifiers: .command)
    }

    private var translationMenu: some View {
        Menu {
            Picker("Translation", selection: Binding(get: { model.translationID }, set: { model.selectTranslation($0) })) {
                ForEach(model.translations) { entry in
                    Text("\(entry.id) — \(entry.name)").tag(entry.id)
                }
            }
        } label: {
            Text(model.translationID).font(.subheadline.weight(.semibold))
        }
        .accessibilityLabel("Translation")
    }

    private var appearanceButton: some View {
        Button { showAppearance.toggle() } label: { Label("Appearance", systemImage: "textformat.size") }
            .popover(isPresented: $showAppearance) {
                AppearanceView()
                    .frame(minWidth: 320, idealWidth: 360, minHeight: 420, idealHeight: 620)
                    .presentationDetents([.medium, .large])
            }
    }

    private var autoScrollControl: some View {
        Menu {
            Section("Speed") {
                ForEach([("Slow", 16.0), ("Relaxed", 28.0), ("Steady", 44.0), ("Brisk", 64.0)], id: \.1) { name, speed in
                    Button {
                        autoScrollSpeed = speed
                        autoScrolling = true
                    } label: {
                        if autoScrollSpeed == speed { Label(name, systemImage: "checkmark") } else { Text(name) }
                    }
                }
            }
        } label: {
            Label(autoScrolling ? "Pause Scrolling" : "Auto-Scroll",
                  systemImage: autoScrolling ? "pause.circle.fill" : "arrow.down.circle")
        } primaryAction: {
            autoScrolling.toggle()
        }
        .accessibilityHint("Scrolls the chapter hands-free. Hold for speed.")
    }

    private var listenButton: some View {
        let listening = ListenController.shared.isListening(in: model) && ListenController.shared.isPlaying
        return Button { ListenController.shared.toolbarAction(in: model) } label: {
            Label(listening ? "Pause Listening" : "Listen", systemImage: listening ? "headphones.circle.fill" : "headphones")
        }
        .accessibilityHint("Reads the chapter aloud from the top of the screen.")
    }

    /// Hidden buttons for text-size shortcuts (⌘+ / ⌘−).
    private var keyboardShortcuts: some View {
        Group {
            Button("Larger Text") { fontSize = min(40, fontSize + 1) }.keyboardShortcut("+", modifiers: .command)
            Button("Smaller Text") { fontSize = max(12, fontSize - 1) }.keyboardShortcut("-", modifiers: .command)
        }
        .opacity(0)
        .accessibilityHidden(true)
    }

    // MARK: Actions

    private func handle(_ tap: ChapterTap) {
        switch tap {
        case .verse(let key):
            model.toggle(key)
            if study.isOn { study.follow(key) }
        case .notes(let ids, let rect):
            popover = ReaderPopover(kind: .notes(ids), rect: rect)
        case .footnote(let text, let rect):
            popover = ReaderPopover(kind: .footnote(text), rect: rect)
        case .action("next"):
            model.next()
        case .action:
            break
        }
    }

    private func advanceWhileScrolling() {
        guard model.location.next != nil else {
            autoScrolling = false
            return
        }
        model.next()
    }

    private func createNoteFromSelection() {
        let note = Note(anchors: model.selectedRanges)
        context.insert(note)
        model.selection.removeAll()
        openNote(note.uuid)
    }

    private func openNote(_ id: UUID) {
        popover = nil
        study.isOn = false
        notesPath = [id]
        showNotes = true
    }
}

/// The chapter text plus its per-chapter queries (highlights), rendered for the platform view.
private struct ChapterPane: View {
    @Environment(ReaderModel.self) private var model
    let chapter: ChapterRef
    let style: ReaderStyle
    let autoScrollSpeed: Double
    let onTap: (ChapterTap) -> Void
    let onReachedEnd: () -> Void
    let onUserScroll: () -> Void

    @Query private var highlights: [Highlight]
    @Query(sort: \Note.updatedAt, order: .reverse) private var notes: [Note]
    @State private var cache = RenderCache()

    init(chapter: ChapterRef, style: ReaderStyle, autoScrollSpeed: Double, onTap: @escaping (ChapterTap) -> Void,
         onReachedEnd: @escaping () -> Void, onUserScroll: @escaping () -> Void) {
        self.chapter = chapter
        self.style = style
        self.autoScrollSpeed = autoScrollSpeed
        self.onTap = onTap
        self.onReachedEnd = onReachedEnd
        self.onUserScroll = onUserScroll
        let low = chapter.keyRange.lowerBound
        let high = chapter.keyRange.upperBound
        _highlights = Query(filter: #Predicate<Highlight> { $0.verseKey >= low && $0.verseKey <= high })
    }

    var body: some View {
        if let layout = model.layout, let store = model.store {
            let rendered = cache.render(layout: layout, input: renderInput(store: store), style: style)
            ChapterTextView(configuration: ChapterTextConfiguration(
                content: rendered,
                background: style.palette.page,
                scrollTarget: model.scrollTarget,
                autoScrollSpeed: autoScrollSpeed,
                onTap: onTap,
                onSwipe: { forward in forward ? model.next() : model.previous() },
                onTopVerseChange: { model.updateTopVerse($0) },
                onScrolledToTarget: { model.scrollTarget = nil },
                onReachedEnd: onReachedEnd,
                onUserScroll: onUserScroll,
                revealVerse: ListenController.shared.speakingVerse(in: model)
            ))
        } else if let error = model.loadError {
            ContentUnavailableView("Can’t Open This Chapter", systemImage: "book.closed", description: Text(error))
        } else {
            ProgressView()
        }
    }

    private func renderInput(store: BibleStore) -> ChapterRenderInput {
        // Newest highlight wins when two devices colored the same verse.
        var colors: [Int: (String, Date)] = [:]
        for highlight in highlights {
            if let existing = colors[highlight.verseKey], existing.1 > highlight.createdAt { continue }
            colors[highlight.verseKey] = (highlight.colorName, highlight.createdAt)
        }
        var noteMarkers: [Int: [String]] = [:]
        for note in notes {
            for anchor in note.anchors where anchor.overlaps(chapter) {
                // Mark the last verse of the range that falls in this chapter.
                let end = anchor.end.chapterKey == chapter
                    ? anchor.end.key
                    : VerseRef(chapter.book, chapter.chapter, store.verseCount(chapter)).key
                noteMarkers[end, default: []].append(note.uuid.uuidString)
            }
        }
        let next = chapter.next.map { "\($0.display)" }
        return ChapterRenderInput(chapter: chapter, translation: store.info.id, style: ReaderStyleKey(style),
                                  highlights: colors.mapValues(\.0), notes: noteMarkers,
                                  selection: model.selection, nextTitle: next, copyright: store.info.copyright,
                                  speakingVerse: ListenController.shared.speakingVerse(in: model))
    }
}

/// Keeps the last render so unrelated view updates don't re-typeset the chapter.
@MainActor
final class RenderCache {
    private var last: (Int, RenderedChapter)?

    func render(layout: ChapterLayout, input: ChapterRenderInput, style: ReaderStyle) -> RenderedChapter {
        let key = input.hashValue
        if let last, last.0 == key { return last.1 }
        let rendered = ChapterRenderer.render(layout: layout, input: input, style: style)
        last = (key, rendered)
        return rendered
    }
}

private struct ReaderPopoverView: View {
    let item: ReaderPopover
    let openNote: (UUID) -> Void
    @Query(sort: \Note.updatedAt, order: .reverse) private var notes: [Note]

    var body: some View {
        switch item.kind {
        case .footnote(let text):
            Text(text)
                .font(.callout)
                .padding()
                .frame(idealWidth: 320)
                .fixedSize(horizontal: false, vertical: true)
        case .notes(let ids):
            let matching = notes.filter { ids.contains($0.uuid.uuidString) }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(matching) { note in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(note.displayTitle).font(.headline)
                            Text(note.anchorSummary).font(.caption).foregroundStyle(.secondary)
                            if !note.body.isEmpty {
                                Text(note.body).font(.callout).lineLimit(10)
                            }
                            Button("Open Note") { openNote(note.uuid) }
                                .font(.callout.weight(.semibold))
                        }
                    }
                }
                .padding()
            }
            .frame(idealWidth: 340, maxHeight: 420)
        }
    }
}
