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
    @Environment(ImportedLibrary.self) private var library
    @Environment(OnlineTranslationKeys.self) private var onlineKeys
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.modelContext) private var context
    @Environment(LegacySession.self) private var legacy

    @AppStorage(SettingsKey.theme) private var theme = ReaderTheme.system
    @AppStorage(SettingsKey.accent) private var accent = ReaderAccent.sunrise
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
    @State private var showTranslations = false
    @State private var showCompare = false
    @State private var showNotes = false
    @State private var notesPath: [UUID] = []
    @State private var popover: ReaderPopover?
    /// The verse last tapped, kept above the Study sheet when it would open over it.
    @State private var tappedVerse: Int?
    /// A note to open once the popover that asked for it has finished dismissing.
    @State private var pendingNote: UUID?
    @State private var autoScrolling = false
    @State private var study = StudyModel()
    @Environment(ShareCoordinator.self) private var shareCoordinator

    #if os(iOS)
    @ScaledMetric(relativeTo: .body) private var dynamicTypeScale: CGFloat = 1
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    private var style: ReaderStyle {
        let palette = theme.palette(for: colorScheme).accented(accent)
        #if os(iOS)
        let size = fontSize * dynamicTypeScale
        #else
        let size = fontSize
        #endif
        return ReaderStyle(family: family, size: size, lineSpacing: lineSpacing, layout: layout,
                           redLetters: redLetters, verseNumbers: verseNumbers, headings: headings,
                           footnotes: footnotes, palette: palette,
                           paletteID: "\(theme.rawValue)-\(accent.rawValue)-\(colorScheme == .dark ? "dark" : "light")")
    }

    var body: some View {
        NavigationStack {
            ChapterPane(chapter: model.location,
                        markKeys: model.numbering.kjvKeyRange(of: model.location,
                                                              verseCount: model.source?.verseCount(model.location) ?? 0),
                        style: style, autoScrollSpeed: autoScrolling ? autoScrollSpeed : 0,
                        onTap: handle, onReachedEnd: advanceWhileScrolling, onUserScroll: { autoScrolling = false },
                        coveredBySheet: studySheetShown.wrappedValue, tappedVerse: tappedVerse)
                .ignoresSafeArea(edges: .bottom)
                .background(Color(style.palette.page))
                .popover(item: $popover, attachmentAnchor: .rect(.rect(popover?.rect ?? .zero))) { item in
                    Group {
                        if let keepsake = legacy.reading, case .notes(let ids) = item.kind {
                            LegacyNotesPopover(keepsake: keepsake, ids: ids, openNote: openNote)
                        } else {
                            ReaderPopoverView(item: item, openNote: openNote)
                        }
                    }
                    .presentationCompactAdaptation(.popover)
                    // popover(item:) has no onDismiss; the content disappears once dismissal finishes.
                    .onDisappear(perform: presentPendingNote)
                }
                .safeAreaInset(edge: .top) {
                    VStack(spacing: 6) {
                        LegacyBanner()
                        TranslationDownloadBanner()
                    }
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
                .contextReaderHooks()
                .toolbar { toolbar }
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
                    StudyPanel()
                } else if let keepsake = legacy.reading {
                    LegacyNotesPanel(keepsake: keepsake, path: $notesPath)
                } else {
                    NotesPanel(path: $notesPath)
                }
            }
            // Presented content gets its models explicitly. On iOS 27 a sheet presented while
            // another is dismissing (Study → Go To) came up without them, and the first view
            // pushed inside it crashed reading @Environment(ReaderModel.self).
            .environment(model)
            .environment(study)
            .inspectorColumnWidth(min: 300, ideal: 360, max: 480)
        }
        .sheet(isPresented: studySheetShown) {
            StudyPanel(isSheet: true)
                .environment(model)
                .environment(study)
        }
        .environment(study)
        .onChange(of: legacy.reading?.id) { notesPath = [] }
        .sheet(isPresented: $showCompare) {
            CompareView()
                .environment(model)
                #if os(macOS)
                .frame(minWidth: 680, minHeight: 620)
                #endif
        }
        .sheet(isPresented: $showTranslations) {
            TranslationsView()
                .environment(model)
                .environment(library)
                .environment(onlineKeys)
                #if os(macOS)
                .frame(minWidth: 520, minHeight: 560)
                #endif
        }
        .sheet(isPresented: $showPicker) {
            PassagePicker()
                .environment(model)
                .environment(study)
                #if os(macOS)
                .frame(minWidth: 560, minHeight: 620)
                #endif
        }
        .tint(Color(style.palette.accent))
        .preferredColorScheme(theme.colorScheme)
        .background(keyboardShortcuts)
        // Links, intents and Spotlight results, including any that arrived before this view did.
        .onChange(of: AppCommandCenter.shared.pending, initial: true) { performCommands() }
        #if DEBUG
        .task { await stageScreenshotScene() }
        #endif
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

    /// With Study up as a sheet (iPhone) the picker can't present over it; SwiftUI queued the
    /// request and showed the picker only once Study was closed some other way. Close Study
    /// first, then open Go To once it has finished dismissing.
    /// - Parameter query: words to search for, from Siri, Shortcuts or a link. Handed over through
    ///   `AppCommandCenter.searchQuery`: at a cold launch the sheet's content is built before any
    ///   state set here would reach it.
    private func openPassagePicker(query: String? = nil) {
        AppCommandCenter.shared.searchQuery = query
        guard study.isOn && studyAsSheet else {
            showPicker = true
            return
        }
        study.isOn = false
        Task {
            try? await Task.sleep(for: .milliseconds(450))
            showPicker = true
        }
    }

    private var passageButton: some View {
        Button { openPassagePicker() } label: {
            HStack(spacing: 4) {
                // A phone's toolbar leaves little room between the button groups, so the name
                // shrinks to fit. ViewThatFits mis-measures inside a principal toolbar item —
                // it dropped the title entirely at "John 10" — so scale the one Text instead.
                Text(model.location.display)
                    .font(.headline)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
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
                study.turnOn(selection: model.selectedKJVKeys)
            }
        } label: {
            Label("Study", systemImage: study.isOn ? "book.pages.fill" : "book.pages")
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
                    Text("\(entry.abbreviation) — \(entry.name)").tag(entry.id)
                }
            }
            Divider()
            Button("Compare Translations…", systemImage: "rectangle.split.2x1") { showCompare = true }
            Button("Manage Translations…", systemImage: "books.vertical") { showTranslations = true }
        } label: {
            Text(model.translationAbbreviation).font(.subheadline.weight(.semibold))
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
                ForEach([(String(localized: "Slow", comment: "Auto-scroll speed"), 16.0), (String(localized: "Relaxed", comment: "Auto-scroll speed"), 28.0),
                         (String(localized: "Steady", comment: "Auto-scroll speed"), 44.0), (String(localized: "Brisk", comment: "Auto-scroll speed"), 64.0)], id: \.1) { name, speed in
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
            // A keepsake is read-only: no selecting to highlight or annotate.
            if legacy.reading == nil { model.toggle(key) }
            tappedVerse = key
            if study.isOn { study.follow(model.numbering.kjv(forNative: key)) }
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

    #if DEBUG
    /// `-screenshotScene <name>`: sets up one App Store screenshot through the same state the
    /// toolbar and selection bar set. See `ScreenshotScene` and `Tools/capture_screenshots.sh`.
    private func stageScreenshotScene() async {
        guard let scene = ScreenshotScene.current else { return }
        // Let the first chapter lay out and the demo library land before moving.
        try? await Task.sleep(for: .milliseconds(700))
        let john3 = ChapterRef(.john, 3)
        switch scene {
        case .reader:
            model.show(john3, verse: 14)
        case .jump:
            model.show(john3, verse: 14)
            showPicker = true
        case .study:
            model.show(john3, verse: 14)
            let verse = ScreenshotScene.key(.john, 3, 16)
            model.selection = [verse]
            study.tab = .crossReferences
            study.turnOn(selection: model.selectedKJVKeys)
        case .maps:
            // Study's Context tab on the map (StudyContextBrowser opens on it for this scene): the
            // sheet on iPhone, the panel beside the text on iPad.
            model.show(ChapterRef(.acts, 13), verse: 1)
            study.tab = .context
            study.turnOn(selection: [])
            study.follow(ScreenshotScene.key(.acts, 13, 4))
        case .topics:
            // PassagePicker opens the Anxiety & Worry theme for this scene.
            model.show(john3, verse: 14)
            showPicker = true
        case .highlights:
            // NotesPanel starts on Highlights for this scene.
            model.show(john3, verse: 14)
            showNotes = true
        case .sermonNotes:
            // NotesPanel imports the sample slide once it's up.
            model.show(ChapterRef(.john, 10), verse: 7)
            showNotes = true
        case .listen:
            model.show(ChapterRef(.john, 14), verse: 1)
            try? await Task.sleep(for: .milliseconds(600))
            ListenController.shared.playChapter(in: model)
        case .share:
            model.show(john3, verse: 14)
            let ranges = [VerseRange(VerseRef(.john, 3, 16))]
            if let source = model.source { shareCoordinator.designer = ShareSource(source: source, ranges: ranges) }
        case .themes:
            model.show(ChapterRef(.john, 1), verse: 1)
            try? await Task.sleep(for: .milliseconds(400))
            showAppearance = true
        }
    }
    #endif

    private func advanceWhileScrolling() {
        guard model.location.next != nil else {
            autoScrolling = false
            return
        }
        model.next()
    }

    // MARK: Commands

    /// Carries out whatever links, intents and Spotlight have asked for.
    private func performCommands() {
        let commands = AppCommandCenter.shared.take()
        guard !commands.isEmpty else { return }
        Task {
            for command in commands { await perform(command) }
        }
    }

    private func perform(_ command: AppCommand) async {
        // Get the text into view: nothing modal may cover where the reader is being taken.
        popover = nil
        showPicker = false
        showCompare = false
        showTranslations = false
        showAppearance = false
        if shareCoordinator.designer != nil { shareCoordinator.designer = nil }
        // Notes and favorites are the reader's own; a keepsake being read shows someone else's.
        let ownNotes: Bool = switch command {
        case .link(.note), .link(.notes), .link(.favorites), .newNote: true
        default: false
        }
        if ownNotes, legacy.reading != nil { legacy.close(model: model) }

        switch command {
        case .link(let link):
            switch link {
            case .share(let payload):
                model.open(link)
                // Rebuild the card from the sender's translation when it's installed here, else the reader's own.
                guard let from = model.source(for: payload.translation) ?? model.source,
                      let source = ShareSource(source: from, ranges: payload.ranges, linkStyle: payload) else { return }
                await settle()
                shareCoordinator.designer = source
            case .open, .osis, .passage:
                model.open(link)
            case .search(let words):
                await settle()
                openPassagePicker(query: words)
            case .note(let id):
                await settle()
                openNote(id)
            case .notes, .favorites:
                AppCommandCenter.shared.notesScope = link == .favorites ? .favorites : .notes
                study.isOn = false
                notesPath = []
                await settle()
                showNotes = true
            }
        case .continueReading:
            model.continueReading()
        case .listen(let link):
            if let link { model.open(link) }
            model.selection.removeAll()
            // Let the chapter lay out before reading it.
            await settle()
            ListenController.shared.playChapter(in: model)
        case .newNote(let ranges):
            let anchors = ranges.isEmpty
                ? [model.numbering.kjvRange(VerseRange(VerseRef(model.location.book, model.location.chapter, 1),
                                                       VerseRef(model.location.book, model.location.chapter,
                                                                max(1, model.source?.verseCount(model.location) ?? 1))))]
                : ranges
            let note = Note(anchors: anchors)
            context.insert(note)
            await settle()
            openNote(note.uuid)
        }
    }

    /// A sheet presented while the scene is still activating for a link or an intent is dropped,
    /// and so is one presented in the same update that dismisses another. Wait a beat.
    private func settle() async {
        try? await Task.sleep(for: .milliseconds(450))
    }

    private func createNoteFromSelection() {
        let note = Note(anchors: model.selectedRanges)
        context.insert(note)
        model.selection.removeAll()
        openNote(note.uuid)
    }

    private func openNote(_ id: UUID) {
        study.isOn = false
        notesPath = [id]
        guard popover != nil else {
            showNotes = true
            return
        }
        // On iPhone the notes panel is a sheet, and presenting it in the same update that
        // dismisses the popover is silently dropped (showNotes stays true with nothing shown).
        // Present it once the popover has finished going away.
        pendingNote = id
        popover = nil
    }

    private func presentPendingNote() {
        guard let id = pendingNote else { return }
        pendingNote = nil
        notesPath = [id]
        showNotes = true
    }
}

/// The chapter text plus its per-chapter queries (highlights), rendered for the platform view.
private struct ChapterPane: View {
    @Environment(ReaderModel.self) private var model
    @Environment(LegacySession.self) private var legacy
    let chapter: ChapterRef
    let style: ReaderStyle
    let autoScrollSpeed: Double
    let onTap: (ChapterTap) -> Void
    let onReachedEnd: () -> Void
    let onUserScroll: () -> Void
    /// The iPhone Study sheet is up over the lower part of the page.
    let coveredBySheet: Bool
    let tappedVerse: Int?

    @Query private var highlights: [Highlight]
    @Query(sort: \Note.updatedAt, order: .reverse) private var notes: [Note]
    @State private var cache = RenderCache()
    @AppStorage(SettingsKey.columns) private var columnsEnabled = true

    /// - Parameter markKeys: the KJV keys this chapter's verses hold (`VerseNumbering.kjvKeyRange`)
    ///   — where its highlights are stored, which is not always this chapter's own numbers.
    init(chapter: ChapterRef, markKeys: ClosedRange<Int>, style: ReaderStyle, autoScrollSpeed: Double,
         onTap: @escaping (ChapterTap) -> Void, onReachedEnd: @escaping () -> Void, onUserScroll: @escaping () -> Void,
         coveredBySheet: Bool, tappedVerse: Int?) {
        self.chapter = chapter
        self.coveredBySheet = coveredBySheet
        self.tappedVerse = tappedVerse
        self.style = style
        self.autoScrollSpeed = autoScrollSpeed
        self.onTap = onTap
        self.onReachedEnd = onReachedEnd
        self.onUserScroll = onUserScroll
        let low = markKeys.lowerBound
        let high = markKeys.upperBound
        _highlights = Query(filter: #Predicate<Highlight> { $0.verseKey >= low && $0.verseKey <= high })
    }

    var body: some View {
        // Any source, not only a SQLite store: a packaged translation renders through the same
        // path, because everything this needs — verse counts, the translation's id, its copyright —
        // is a question `ChapterTextSource` answers.
        // `layoutChapter == chapter` is the guard that makes a mismatch impossible to draw rather
        // than merely unlikely: whatever races upstream, this pane never renders one chapter's
        // text beneath another's reference.
        if let layout = model.layout, model.layoutChapter == chapter, let source = model.source {
            let rendered = cache.render(layout: layout, input: renderInput(source: source), style: style)
            let configuration = ChapterTextConfiguration(
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
                    ?? (coveredBySheet ? tappedVerse : nil)
            )
            // Side-by-side columns when the window is wide enough for two at a comfortable
            // measure, as a printed page is set; one scrolling column otherwise, and while the
            // page scrolls itself.
            GeometryReader { geometry in
                let columns = ReaderColumns.count(width: geometry.size.width, height: geometry.size.height, fontSize: style.size)
                // The iPhone Study sheet rests at a little under half the screen, and the maps and
                // places it opens come up to about half: the text keeps to the half above them.
                let covered = coveredBySheet
                if columnsEnabled, columns >= 2, autoScrollSpeed == 0, !covered {
                    ColumnChapterView(configuration: configuration, columns: columns)
                } else {
                    ChapterTextView(configuration: covered ? configuration.covered(geometry.size.height * 0.42) : configuration)
                }
            }
        } else if let error = model.loadError {
            ContentUnavailableView {
                Label("Can’t Open This Chapter", systemImage: "book.closed")
            } description: {
                Text(error)
            } actions: {
                if model.source == nil, model.defaultTranslationMissing {
                    Button("Try Again") { model.retryDefaultTranslation() }
                }
            }
        } else {
            ProgressView()
        }
    }

    private func renderInput(source: any ChapterTextSource) -> ChapterRenderInput {
        if let keepsake = legacy.reading {
            // Someone else's Bible: their marks instead of the reader's own.
            let marks = keepsake.marks(for: chapter, verseCount: source.verseCount(chapter))
            return ChapterRenderInput(chapter: chapter, translation: source.info.id, style: ReaderStyleKey(style),
                                      highlights: marks.highlights, notes: marks.notes, selection: [],
                                      nextTitle: chapter.next.map(\.display), copyright: source.info.copyright,
                                      keepsake: true)
        }
        // Marks are stored under KJV keys; the renderer draws this translation's own verses.
        let numbering = source.numbering
        // Newest highlight wins when two devices colored the same verse.
        var colors: [Int: (String, Date)] = [:]
        for highlight in highlights {
            guard let key = numbering.native(forKJV: highlight.verseKey), key / 1_000 == chapter.keyRange.lowerBound / 1_000
            else { continue }
            if let existing = colors[key], existing.1 > highlight.createdAt { continue }
            colors[key] = (highlight.colorName, highlight.createdAt)
        }
        var noteMarkers: [Int: [String]] = [:]
        for note in notes {
            for stored in note.anchors {
                guard let anchor = numbering.nativeRange(stored), anchor.overlaps(chapter) else { continue }
                // Mark the last verse of the range that falls in this chapter.
                let end = anchor.end.chapterKey == chapter
                    ? anchor.end.key
                    : VerseRef(chapter.book, chapter.chapter, source.verseCount(chapter)).key
                noteMarkers[end, default: []].append(note.uuid.uuidString)
            }
        }
        let next = chapter.next.map { "\($0.display)" }
        return ChapterRenderInput(chapter: chapter, translation: source.info.id, style: ReaderStyleKey(style),
                                  highlights: colors.mapValues(\.0), notes: noteMarkers,
                                  selection: model.selection, nextTitle: next, copyright: source.info.copyright,
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
