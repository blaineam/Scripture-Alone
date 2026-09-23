import AppIntents
import SwiftUI
import ScriptureAloneCore

// MARK: Verse of the Day

/// Today's verse, spoken or shown without opening the app. The snippet's button opens the app to
/// the verse through the same `scripturealone://open?ref=` route as the Verse of the Day widget.
nonisolated struct VerseOfTheDayIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Verse of the Day", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Gets today’s verse — the same one the Verse of the Day widget shows.",
                                                  comment: "App Intent description"))
    }

    @MainActor
    func perform() async throws -> some ReturnsValue<String> & ProvidesDialog & ShowsSnippetView {
        let verse = DailyVerseLibrary.verse() ?? DailyVerseLibrary.placeholder
        // The reader's translation when the list carries it, else the ASV the list falls back to.
        let wanted = IntentLibrary.currentTranslationID
        let translation = verse.text[wanted] == nil ? DailyVerseCatalog.fallbackTranslation : wanted
        let text = verse.text(in: translation).replacingOccurrences(of: "¶ ", with: "")
        let range = verse.range
        let numbering = IntentLibrary.source(for: translation)?.numbering ?? .identity
        let reference = range.map { (numbering.nativeRange($0) ?? $0).display } ?? ""
        let theme = verse.localizedTheme
        let value = "\(text)\n— \(reference) (\(translation))"
        return .result(
            value: value,
            dialog: IntentDialog(LocalizedStringResource("\(reference): \(text)",
                                                         comment: "Siri reads the verse of the day. %1$@ is its reference, %2$@ the verse.")),
            view: VerseSnippet(theme: theme, text: text, reference: reference, translation: translation,
                               open: range.map { OpenVerseLinkIntent(ranges: [$0]) })
        )
    }
}

/// Opens a verse from a snippet button. Deliberately the widget's own route: the link it carries is
/// `scripturealone://open?ref=<stored range>`, parsed by `AppLink` like any other link, so the
/// widget, the watch complication and Siri all land the same way.
struct OpenVerseLinkIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Open Verse", comment: "App Intent title: opens a verse in the reader")
    }
    static var isDiscoverable: Bool { false }
    static var supportedModes: IntentModes { .foreground(.immediate) }

    @Parameter(title: LocalizedStringResource("Link", comment: "App Intent parameter: a link to a verse"))
    var url: URL

    init() {}

    init(ranges: [VerseRange]) {
        url = AppLink.openURL(for: ranges) ?? URL(string: "scripturealone://open")!
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        AppCommandCenter.shared.open(url)
        return .result()
    }
}

/// A verse as Siri and Shortcuts show it: the text, its reference, and a way into the app.
struct VerseSnippet: View {
    var theme: String?
    let text: String
    let reference: String
    let translation: String
    let open: OpenVerseLinkIntent?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let theme, !theme.isEmpty {
                Text(theme.uppercased())
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            Text(text)
                .font(.system(.body, design: .serif))
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Text("\(reference) · \(translation)", comment: "A verse's reference and translation, e.g. “John 3:16 · ASV”.")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tint)
                Spacer()
                if let open {
                    Button(intent: open) {
                        Label(String(localized: "Open", comment: "Button in a Siri/Shortcuts result that opens the verse in the app"),
                              systemImage: "book")
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding()
    }
}

// MARK: Get verses

struct GetVersesIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Get Verses", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Gets the text of a passage from a translation on this device.",
                                                  comment: "App Intent description"))
    }
    static var parameterSummary: some ParameterSummary {
        Summary("Get \(\.$passage) in \(\.$translation)")
    }

    @Parameter(title: LocalizedStringResource("Passage", comment: "App Intent parameter: a Bible reference like John 3:16"),
               requestValueDialog: IntentDialog(LocalizedStringResource("Which passage?", comment: "Siri asks for a Bible reference")))
    var passage: String

    @Parameter(title: LocalizedStringResource("Translation", comment: "App Intent parameter: a Bible translation"))
    var translation: TranslationEntity?

    init() {}

    @MainActor
    func perform() async throws -> some ReturnsValue<String> & ProvidesDialog & ShowsSnippetView {
        let id = translation?.id ?? IntentLibrary.currentTranslationID
        guard let source = IntentLibrary.source(for: id) else { throw ScriptureIntentError.translationUnavailable(id) }
        let resolved = try IntentLibrary.resolve(passage, in: source)
        let info = source.info
        guard info.rights.mayQuote(verseCount: IntentLibrary.verseCount(resolved, in: source)), info.mayCopy else {
            throw ScriptureIntentError.quotationNotPermitted(info.abbreviation)
        }
        let quotation = IntentLibrary.quotation(resolved, in: source)
        let reference = resolved.map(\.native.display).joined(separator: ", ")
        let text = resolved.compactMap { try? source.verses(in: $0.kjv) }.map(IntentLibrary.text(of:)).joined(separator: " … ")
        return .result(
            value: quotation,
            dialog: IntentDialog(LocalizedStringResource("\(reference): \(text)",
                                                         comment: "Siri reads a passage. %1$@ is its reference, %2$@ the verses.")),
            view: VerseSnippet(text: text, reference: reference, translation: info.abbreviation,
                               open: OpenVerseLinkIntent(ranges: resolved.map(\.kjv)))
        )
    }
}

// MARK: Verse image

struct CreateVerseImageIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Create Verse Image", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Makes a verse image of a passage, like Share Image in the app.",
                                                  comment: "App Intent description"))
    }
    static var parameterSummary: some ParameterSummary {
        Summary("Create an image of \(\.$passage)") {
            \.$translation
            \.$design
            \.$shape
        }
    }

    @Parameter(title: LocalizedStringResource("Passage", comment: "App Intent parameter: a Bible reference like John 3:16"),
               requestValueDialog: IntentDialog(LocalizedStringResource("Which passage?", comment: "Siri asks for a Bible reference")))
    var passage: String

    @Parameter(title: LocalizedStringResource("Translation", comment: "App Intent parameter: a Bible translation"))
    var translation: TranslationEntity?

    @Parameter(title: LocalizedStringResource("Design", comment: "App Intent parameter: a verse-image design"))
    var design: VerseImageTemplate?

    @Parameter(title: LocalizedStringResource("Shape", comment: "App Intent parameter: a verse-image shape"))
    var shape: VerseImageShape?

    init() {}

    @MainActor
    func perform() async throws -> some ReturnsValue<IntentFile> & ProvidesDialog {
        let id = translation?.id ?? IntentLibrary.currentTranslationID
        guard let source = IntentLibrary.source(for: id) else { throw ScriptureIntentError.translationUnavailable(id) }
        let resolved = try IntentLibrary.resolve(passage, in: source)
        let info = source.info
        // The designer's own gate: a verse image is a quotation that leaves the app.
        guard info.mayRenderVerseImage,
              info.rights.mayQuote(verseCount: IntentLibrary.verseCount(resolved, in: source)) else {
            throw ScriptureIntentError.imageNotPermitted(info.abbreviation)
        }
        guard let share = ShareSource(source: source, ranges: resolved.map(\.kjv)) else {
            throw ScriptureIntentError.passageNotFound(passage)
        }
        // The reader's remembered designer choices, with whatever this run asked for on top.
        let defaults = UserDefaults.standard
        var style = ShareStyle()
        style.template = design.flatMap { ShareTemplate(rawValue: $0.rawValue) }
            ?? defaults.string(forKey: ShareSettingsKey.template).flatMap(ShareTemplate.init(rawValue:)) ?? .parchment
        style.aspect = shape.flatMap { ShareAspect(rawValue: $0.rawValue) }
            ?? defaults.string(forKey: ShareSettingsKey.aspect).flatMap(ShareAspect.init(rawValue:)) ?? .square
        style.family = defaults.string(forKey: ShareSettingsKey.family).flatMap(FontFamily.init(rawValue:)) ?? .newYork
        style.alignment = defaults.string(forKey: ShareSettingsKey.alignment).flatMap(ShareAlignment.init(rawValue:)) ?? .center
        style.redLetters = defaults.object(forKey: ShareSettingsKey.redLetters) as? Bool ?? true
        style.verseNumbers = defaults.object(forKey: ShareSettingsKey.verseNumbers) as? Bool ?? true
        style.wordmark = defaults.object(forKey: ShareSettingsKey.wordmark) as? Bool ?? true

        let fit = ShareCardFitter.fit(verses: share.verses, info: info, style: style, verseCount: source.verseCount)
        guard let rendered = ShareRenderer.render(fit.content, style: style) else { throw ScriptureIntentError.imageFailed }
        let file = IntentFile(data: rendered.image.png, filename: rendered.image.filename, type: .png)
        return .result(
            value: file,
            dialog: IntentDialog(LocalizedStringResource("Here’s \(fit.content.reference).",
                                                         comment: "Siri presents a verse image. %@ is the passage reference."))
        )
    }
}

// MARK: Opening the app

struct OpenPassageIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Open Passage", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Opens Scripture Alone to a passage — a reference like John 3:16, in any language the app reads, or OSIS like John.3.16.",
                                                  comment: "App Intent description. “OSIS” and “John.3.16” stay as they are."))
    }
    static var supportedModes: IntentModes { .foreground(.immediate) }
    static var parameterSummary: some ParameterSummary { Summary("Open \(\.$passage)") }

    @Parameter(title: LocalizedStringResource("Passage", comment: "App Intent parameter: a Bible reference like John 3:16"),
               requestValueDialog: IntentDialog(LocalizedStringResource("Which passage?", comment: "Siri asks for a Bible reference")))
    var passage: String

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let link = AppLink.reference(passage) else { throw ScriptureIntentError.passageNotFound(passage) }
        AppCommandCenter.shared.post(.link(link))
        return .result()
    }
}

struct SearchBibleIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Search Bible", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Opens Go To with words to search for in the translation you’re reading.",
                                                  comment: "App Intent description. “Go To” is the name of the app's search screen."))
    }
    static var supportedModes: IntentModes { .foreground(.immediate) }
    static var parameterSummary: some ParameterSummary { Summary("Search the Bible for \(\.$query)") }

    @Parameter(title: LocalizedStringResource("Search For", comment: "App Intent parameter: words to search the Bible for"),
               requestValueDialog: IntentDialog(LocalizedStringResource("What should I search for?", comment: "Siri asks for words to search the Bible for")))
    var query: String

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        AppCommandCenter.shared.post(.link(.search(query)))
        return .result()
    }
}

nonisolated struct ContinueReadingIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Continue Reading", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Opens Scripture Alone where you left off, on this device or another.",
                                                  comment: "App Intent description"))
    }
    static var supportedModes: IntentModes { .foreground(.immediate) }

    @MainActor
    func perform() async throws -> some IntentResult {
        AppCommandCenter.shared.post(.continueReading)
        return .result()
    }
}

struct ListenToChapterIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Listen to Chapter", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Reads a chapter aloud — the one you’re reading, or the passage you name.",
                                                  comment: "App Intent description"))
    }
    static var supportedModes: IntentModes { .foreground(.immediate) }
    static var parameterSummary: some ParameterSummary { Summary("Listen to \(\.$passage)") }

    @Parameter(title: LocalizedStringResource("Passage", comment: "App Intent parameter: a Bible reference like John 3:16"))
    var passage: String?

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        var link: AppLink?
        if let passage, !passage.trimmingCharacters(in: .whitespaces).isEmpty {
            guard let found = AppLink.reference(passage) else { throw ScriptureIntentError.passageNotFound(passage) }
            link = found
        }
        AppCommandCenter.shared.post(.listen(link))
        return .result()
    }
}

nonisolated struct ShowFavoritesIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Show Favorites", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Opens your favorite verses.", comment: "App Intent description"))
    }
    static var supportedModes: IntentModes { .foreground(.immediate) }

    @MainActor
    func perform() async throws -> some IntentResult {
        AppCommandCenter.shared.post(.link(.favorites))
        return .result()
    }
}

nonisolated struct ShowNotesIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Show Notes", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Opens your notes.", comment: "App Intent description"))
    }
    static var supportedModes: IntentModes { .foreground(.immediate) }

    @MainActor
    func perform() async throws -> some IntentResult {
        AppCommandCenter.shared.post(.link(.notes))
        return .result()
    }
}
