import SwiftUI
import ScriptureAloneCore

/// Whether a chapter is set in two columns side by side or read as one scrolling column
/// (`ChapterTextView`), and the geometry the columns share.
///
/// Two columns whenever each can hold a comfortable measure at the reader's own font and size —
/// about the line of a printed Bible's column — and enough lines to read down before turning.
/// It used to be a fixed 18 ems a column on a pane at least 360 points tall. On iPhone the reader's
/// size is the slider's times the system text size, so an iPhone Pro Max held sideways (an
/// 832-point-wide pane) lost its columns at any system text size above the default — 19 points
/// becomes 21 at the next one up, and two 18-em columns there need 19.8 or less — and it cleared
/// the height by two points, which the pane measured including the top bar's 78. A smaller phone
/// (an iPhone Pro, or a Pro Max with Display Zoom: a 324-point pane) never got columns at all. Now
/// the measure is the font's own, and the height is the page between the top and bottom bars,
/// counted in lines; the floating bars take nothing from it.
enum ReaderColumns {
    static let margin: CGFloat = 40
    static let gutter: CGFloat = 40

    /// The fewest characters a column's line may average: a narrow Bible column (printed ones run
    /// about 30 to 40), and the least a line can hold before the eye spends more time returning than
    /// reading.
    static let minimumCharacters: CGFloat = 30
    /// The same for Chinese, Japanese and Korean, whose characters are each about an em wide.
    static let minimumIdeographs: CGFloat = 16
    /// The fewest lines a column may hold. Fewer and the page turns more than it is read.
    static let minimumLines: CGFloat = 6

    /// What a column's text takes: an average character's width and a line's height.
    struct Metrics: Equatable {
        var characterWidth: CGFloat
        var lineHeight: CGFloat
        var minimumCharacters: CGFloat = ReaderColumns.minimumCharacters
    }

    /// Measures `font` on a line of text in `language`, at the reader's `lineSpacing`.
    static func metrics(font: PlatformFont, lineSpacing: CGFloat, language: String) -> Metrics {
        let ideographic = ["zh", "ja", "ko"].contains(String(language.prefix(2)).lowercased())
        let sample = ideographic
            ? "太初有道，道與神同在，道就是神。神愛世人，甚至將他的獨生子賜給他們。"
            : "And he said unto them, Go ye into all the world, and preach the gospel to every creature."
        let width = (sample as NSString).size(withAttributes: [.font: font]).width / CGFloat(sample.count)
        #if os(iOS)
        let line = font.lineHeight
        #else
        let line = NSLayoutManager().defaultLineHeight(for: font)
        #endif
        return Metrics(characterWidth: max(1, width), lineHeight: max(1, line * max(1, lineSpacing)),
                       minimumCharacters: ideographic ? minimumIdeographs : minimumCharacters)
    }

    /// The width of each of `columns` columns across a page `width` wide.
    static func columnWidth(width: CGFloat, columns: Int) -> CGFloat {
        (width - margin * 2 - gutter * CGFloat(columns - 1)) / CGFloat(columns)
    }

    /// How many columns to set across a page `width` × `height` whose bars cover `safeTop` and
    /// `safeBottom` of it: two if each holds a comfortable measure and enough lines, one otherwise.
    /// Two at most, like an open Bible's page: three read as a newspaper, not a Bible.
    static func count(width: CGFloat, height: CGFloat, safeTop: CGFloat, safeBottom: CGFloat,
                      compactHeight: Bool, metrics: Metrics) -> Int {
        let characters = columnWidth(width: width, columns: 2) / metrics.characterWidth
        let lines = columnHeight(height: height, safeTop: safeTop, safeBottom: safeBottom,
                                 compactHeight: compactHeight, minimum: 0) / metrics.lineHeight
        return characters >= metrics.minimumCharacters && lines >= minimumLines ? 2 : 1
    }

    /// Where a column's text starts and how much of the page's foot it leaves, inside a column view
    /// whose safe area (the top and bottom bars over it) is `safeTop`/`safeBottom`.
    ///
    /// The text fills the page from bar to bar. The selection and Now Playing bars float over its
    /// last lines while they are up: they are dismissed in a moment and nothing under them is lost,
    /// so no room is kept for them. (The foot used to keep 110 points clear for them, which on a
    /// phone held sideways left a column five lines and a band of empty page.) A phone held sideways
    /// (`compactHeight`) starts and ends a little closer to the bars.
    static func verticalInsets(safeTop: CGFloat, safeBottom: CGFloat, compactHeight: Bool) -> (top: CGFloat, bottom: CGFloat) {
        compactHeight ? (safeTop + 8, safeBottom + 6) : (safeTop + 20, safeBottom + 20)
    }

    /// The height of each column's text container in a view `height` tall.
    static func columnHeight(height: CGFloat, safeTop: CGFloat, safeBottom: CGFloat, compactHeight: Bool,
                             minimum: CGFloat = 120) -> CGFloat {
        let insets = verticalInsets(safeTop: safeTop, safeBottom: safeBottom, compactHeight: compactHeight)
        return max(minimum, height - insets.top - insets.bottom)
    }
}

/// The spread the column reader shows, for the bottom bar's arrows (Android's `Spreads`): while the
/// chapter is set in columns they turn spreads, labelled Previous Page and Next Page, and change the
/// chapter only past the first or last spread. In the scrolling reader they change the chapter.
@Observable
final class ColumnSpreads {
    private(set) var page = 0
    private(set) var count = 1
    /// Whether a column reader is showing at all.
    private(set) var active = false
    @ObservationIgnored fileprivate var turner: ((Bool) -> Void)?

    var hasPrevious: Bool { active && page > 0 }
    var hasNext: Bool { active && page < count - 1 }

    /// The next or previous spread — or, past either end, the chapter.
    func turn(forward: Bool) { turner?(forward) }

    /// What the column view shows. Deferred a turn of the run loop: it is reported from layout,
    /// which may run while SwiftUI is updating views.
    fileprivate func update(page: Int, count: Int) {
        Task { @MainActor in
            if self.page != page { self.page = page }
            if self.count != count { self.count = count }
        }
    }

    @ObservationIgnored private var owner: ObjectIdentifier?

    /// A column view came on screen; `owner` is its coordinator.
    fileprivate func attach(_ owner: AnyObject, turner: @escaping (Bool) -> Void) {
        self.owner = ObjectIdentifier(owner)
        self.turner = turner
        Task { @MainActor in self.active = true }
    }

    /// A column view left. A newer one may already have taken over, so only its own owner detaches.
    fileprivate func detach(_ owner: AnyObject) {
        guard self.owner == ObjectIdentifier(owner) else { return }
        self.owner = nil
        turner = nil
        Task { @MainActor in
            if self.owner == nil { self.active = false }
        }
    }
}

/// The chapter laid out in columns side by side, turned a spread at a time — a printed Bible's
/// page on a screen wide enough for one (iPad, a Mac window, a phone held sideways).
///
/// It draws exactly what the scrolling reader draws (`RenderedChapter`), through one TextKit
/// layout manager flowing into a column-sized text container per column, and answers the same
/// configuration: taps, notes and footnotes, jumping to a verse, following the verse read aloud,
/// reporting the verse at the top. Turning past the last spread moves to the next chapter, and
/// before the first to the previous one, as a swipe does in the scrolling reader.
#if os(iOS)
struct ColumnChapterView: UIViewRepresentable {
    var configuration: ChapterTextConfiguration
    var columns: Int
    var spreads: ColumnSpreads?

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> PagedColumnsView {
        let view = PagedColumnsView()
        view.coordinator = context.coordinator
        view.delegate = context.coordinator
        context.coordinator.view = view
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        view.addGestureRecognizer(tap)
        context.coordinator.spreads = spreads
        spreads?.attach(context.coordinator) { [weak view] forward in view?.turn(forward: forward) }
        return view
    }

    static func dismantleUIView(_ view: PagedColumnsView, coordinator: Coordinator) {
        coordinator.spreads?.detach(coordinator)
    }

    func updateUIView(_ view: PagedColumnsView, context: Context) {
        let coordinator = context.coordinator
        coordinator.configuration = configuration
        view.backgroundColor = configuration.background
        let content = configuration.content
        if coordinator.fingerprint != content.fingerprint || view.columns != columns {
            let sameChapter = coordinator.chapter == content.chapter
            let page = view.currentPage
            view.setContent(content.text, columns: columns)
            if sameChapter { view.show(page: page, animated: false) } else if configuration.scrollTarget == nil { view.show(page: 0, animated: false) }
            coordinator.fingerprint = content.fingerprint
            coordinator.chapter = content.chapter
        }
        if let target = configuration.scrollTarget {
            Task { @MainActor in
                for _ in 0..<20 {
                    if coordinator.show(verse: target) { break }
                    try? await Task.sleep(for: .milliseconds(50))
                }
                configuration.onScrolledToTarget()
            }
        }
        coordinator.revealIfNeeded(configuration)
    }

    final class PagedColumnsView: UIScrollView {
        weak var coordinator: Coordinator?
        private let storage = NSTextStorage()
        let layoutManager: NSLayoutManager = ChapterLayoutManager()
        private(set) var textViews: [UITextView] = []
        private(set) var columns = 2
        private var laidOut: CGSize = .zero

        override init(frame: CGRect) {
            super.init(frame: frame)
            storage.addLayoutManager(layoutManager)
            isPagingEnabled = true
            alwaysBounceHorizontal = true
            showsHorizontalScrollIndicator = false
            showsVerticalScrollIndicator = false
            contentInsetAdjustmentBehavior = .never
            // Held sideways or upright decides where the text starts and ends (`verticalInsets`): lay
            // out again when that changes, even if it arrives after the new size has been laid out.
            registerForTraitChanges([UITraitVerticalSizeClass.self]) { (view: PagedColumnsView, _) in
                view.laidOut = .zero
                view.setNeedsLayout()
            }
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

        var text: NSAttributedString { storage }

        var pageCount: Int { max(1, (textViews.count + columns - 1) / columns) }

        var currentPage: Int {
            guard bounds.width > 0 else { return 0 }
            return max(0, min(pageCount - 1, Int((contentOffset.x / bounds.width).rounded())))
        }

        func setContent(_ text: NSAttributedString, columns: Int) {
            self.columns = columns
            storage.setAttributedString(text)
            relayout()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            if bounds.size != laidOut { relayout() }
        }

        /// The bars' safe area can settle after the size does (turning the phone): the columns follow.
        override func safeAreaInsetsDidChange() {
            super.safeAreaInsetsDidChange()
            laidOut = .zero
            setNeedsLayout()
        }

        /// One text container per column, added until the whole chapter is placed.
        private func relayout() {
            guard bounds.width > 0, bounds.height > 0 else { return }
            let page = currentPage
            laidOut = bounds.size
            for view in textViews { view.removeFromSuperview() }
            textViews = []
            while !layoutManager.textContainers.isEmpty { layoutManager.removeTextContainer(at: 0) }

            let insets = safeAreaInsets
            // A phone held sideways: the page is short, and the text fills it from bar to bar.
            let compactHeight = traitCollection.verticalSizeClass == .compact
            let top = ReaderColumns.verticalInsets(safeTop: insets.top, safeBottom: insets.bottom, compactHeight: compactHeight).top
            let columnWidth = ReaderColumns.columnWidth(width: bounds.width, columns: columns)
            let columnHeight = ReaderColumns.columnHeight(height: bounds.height, safeTop: insets.top, safeBottom: insets.bottom,
                                                          compactHeight: compactHeight)
            let glyphs = layoutManager.numberOfGlyphs
            var index = 0
            repeat {
                let container = NSTextContainer(size: CGSize(width: columnWidth, height: columnHeight))
                layoutManager.addTextContainer(container)
                let page = index / columns
                let column = index % columns
                let frame = CGRect(x: CGFloat(page) * bounds.width + ReaderColumns.margin + CGFloat(column) * (columnWidth + ReaderColumns.gutter),
                                   y: top, width: columnWidth, height: columnHeight)
                let view = UITextView(frame: frame, textContainer: container)
                view.accessibilityIdentifier = "reader.text"
                view.isEditable = false
                view.isSelectable = false
                view.isScrollEnabled = false
                view.backgroundColor = .clear
                view.textContainerInset = .zero
                addSubview(view)
                textViews.append(view)
                index += 1
            } while NSMaxRange(layoutManager.glyphRange(for: layoutManager.textContainers.last!)) < glyphs && index < 600
            contentSize = CGSize(width: CGFloat(pageCount) * bounds.width, height: bounds.height)
            show(page: min(page, pageCount - 1), animated: false)
        }

        /// Next or previous spread; past either end, the chapter — as a swipe past the end does.
        func turn(forward: Bool) {
            let page = currentPage
            if forward {
                if page + 1 < pageCount { show(page: page + 1, animated: true) } else { coordinator?.configuration?.onSwipe(true) }
            } else {
                if page > 0 { show(page: page - 1, animated: true) } else { coordinator?.configuration?.onSwipe(false) }
            }
        }

        func show(page: Int, animated: Bool) {
            setContentOffset(CGPoint(x: CGFloat(max(0, min(page, pageCount - 1))) * bounds.width, y: 0), animated: animated)
            if !animated { coordinator?.spreads?.update(page: currentPage, count: pageCount) }
        }

        /// The column and page that hold a character.
        func column(ofCharacter index: Int) -> Int? {
            guard index < storage.length else { return nil }
            let glyph = layoutManager.glyphIndexForCharacter(at: index)
            guard let container = layoutManager.textContainer(forGlyphAt: glyph, effectiveRange: nil) else { return nil }
            return layoutManager.textContainers.firstIndex { $0 === container }
        }

        func page(ofCharacter index: Int) -> Int? { column(ofCharacter: index).map { $0 / columns } }
    }

    @MainActor
    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var view: PagedColumnsView?
        var configuration: ChapterTextConfiguration?
        var spreads: ColumnSpreads?
        var fingerprint: Int?
        var chapter: ChapterRef?
        private var lastTopVerse: Int?
        private var revealedVerse: Int?

        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard let view, let configuration else { return }
            let location = gesture.location(in: view)
            guard let textView = view.textViews.first(where: { $0.frame.contains(location) }) else { return }
            let point = view.convert(location, to: textView)
            guard let index = ChapterGeometry.characterIndex(at: point, layoutManager: view.layoutManager,
                                                             container: textView.textContainer),
                  let tap = ChapterGeometry.tap(at: index, in: view.text, rect: { range in
                      let rect = ChapterGeometry.rect(forCharacters: range, layoutManager: view.layoutManager,
                                                      container: textView.textContainer)
                      return rect.offsetBy(dx: textView.frame.minX - view.contentOffset.x, dy: textView.frame.minY - view.contentOffset.y)
                  })
            else { return }
            configuration.onTap(tap)
        }

        @discardableResult
        func show(verse key: Int) -> Bool {
            guard let view, view.window != nil, view.bounds.width > 0,
                  let range = ChapterGeometry.firstRange(ofVerse: key, in: view.text),
                  let page = view.page(ofCharacter: range.location) else { return false }
            view.show(page: page, animated: false)
            report()
            return true
        }

        func revealIfNeeded(_ configuration: ChapterTextConfiguration) {
            guard configuration.scrollTarget == nil else { return }
            guard let verse = configuration.revealVerse else {
                revealedVerse = nil
                return
            }
            guard verse != revealedVerse else { return }
            revealedVerse = verse
            Task { @MainActor in
                guard let view = self.view, let range = ChapterGeometry.firstRange(ofVerse: verse, in: view.text),
                      let page = view.page(ofCharacter: range.location), page != view.currentPage else { return }
                view.show(page: page, animated: true)
            }
        }

        /// The verse at the top of the spread, for the reading position.
        private func report() {
            guard let view else { return }
            spreads?.update(page: view.currentPage, count: view.pageCount)
            let first = view.currentPage * view.columns
            guard first < view.layoutManager.textContainers.count else { return }
            let glyphs = view.layoutManager.glyphRange(for: view.layoutManager.textContainers[first])
            let index = view.layoutManager.characterIndexForGlyph(at: glyphs.location)
            if let key = ChapterGeometry.verse(atOrAfter: index, in: view.text), key != lastTopVerse {
                lastTopVerse = key
                configuration?.onTopVerseChange(key)
            }
        }

        func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { report() }
        func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) { report() }

        /// Pulled past the first or last spread: the chapter before or after.
        func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
            let overshoot: CGFloat = 60
            let maxX = max(0, scrollView.contentSize.width - scrollView.bounds.width)
            if scrollView.contentOffset.x < -overshoot {
                configuration?.onSwipe(false)
            } else if scrollView.contentOffset.x > maxX + overshoot {
                configuration?.onSwipe(true)
            }
        }
    }
}

#else

struct ColumnChapterView: NSViewRepresentable {
    var configuration: ChapterTextConfiguration
    var columns: Int
    var spreads: ColumnSpreads?

    func makeCoordinator() -> Coordinator { Coordinator() }

    static func dismantleNSView(_ view: PagedColumnsView, coordinator: Coordinator) {
        coordinator.spreads?.detach(coordinator)
    }

    func makeNSView(context: Context) -> PagedColumnsView {
        let view = PagedColumnsView()
        view.coordinator = context.coordinator
        context.coordinator.view = view
        context.coordinator.spreads = spreads
        spreads?.attach(context.coordinator) { [weak view] forward in view?.turn(forward: forward) }
        return view
    }

    func updateNSView(_ view: PagedColumnsView, context: Context) {
        let coordinator = context.coordinator
        coordinator.configuration = configuration
        view.background = configuration.background
        let content = configuration.content
        if coordinator.fingerprint != content.fingerprint || view.columns != columns {
            let sameChapter = coordinator.chapter == content.chapter
            let page = view.page
            view.setContent(content.text, columns: columns)
            view.show(page: sameChapter ? page : 0)
            coordinator.fingerprint = content.fingerprint
            coordinator.chapter = content.chapter
        }
        if let target = configuration.scrollTarget {
            Task { @MainActor in
                coordinator.show(verse: target)
                configuration.onScrolledToTarget()
            }
        }
        coordinator.revealIfNeeded(configuration)
    }

    /// Spreads side by side, one shown at a time. Arrow keys, the space bar, a scroll wheel or a
    /// trackpad swipe turn them; past either end the chapter changes.
    final class PagedColumnsView: NSView {
        weak var coordinator: Coordinator?
        private let storage = NSTextStorage()
        let layoutManager: NSLayoutManager = ChapterLayoutManager()
        private let pages = NSView()
        private(set) var textViews: [NSTextView] = []
        private(set) var columns = 2
        private(set) var page = 0
        private var laidOut: CGSize = .zero
        private var wheel: CGFloat = 0
        private var turnedThisGesture = false
        private var lastWheelTurn: TimeInterval = 0
        var background: NSColor = .textBackgroundColor { didSet { layer?.backgroundColor = background.cgColor } }

        override init(frame: NSRect) {
            super.init(frame: frame)
            wantsLayer = true
            storage.addLayoutManager(layoutManager)
            addSubview(pages)
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

        override var isFlipped: Bool { true }
        override var acceptsFirstResponder: Bool { true }

        var text: NSAttributedString { storage }
        var pageCount: Int { max(1, (textViews.count + columns - 1) / columns) }

        func setContent(_ text: NSAttributedString, columns: Int) {
            self.columns = columns
            storage.setAttributedString(text)
            relayout()
        }

        override func layout() {
            super.layout()
            if bounds.size != laidOut { relayout() }
        }

        private func relayout() {
            guard bounds.width > 0, bounds.height > 0 else { return }
            laidOut = bounds.size
            for view in textViews { view.removeFromSuperview() }
            textViews = []
            while !layoutManager.textContainers.isEmpty { layoutManager.removeTextContainer(at: 0) }
            // Text from the top of the window to its foot; the Now Playing and selection bars float.
            let top: CGFloat = 28
            let bottom: CGFloat = 28
            let columnWidth = ReaderColumns.columnWidth(width: bounds.width, columns: columns)
            let columnHeight = max(120, bounds.height - top - bottom)
            let glyphs = layoutManager.numberOfGlyphs
            var index = 0
            repeat {
                let container = NSTextContainer(size: NSSize(width: columnWidth, height: columnHeight))
                layoutManager.addTextContainer(container)
                let spread = index / columns
                let column = index % columns
                let frame = NSRect(x: CGFloat(spread) * bounds.width + ReaderColumns.margin + CGFloat(column) * (columnWidth + ReaderColumns.gutter),
                                   y: top, width: columnWidth, height: columnHeight)
                let view = ColumnTextView(frame: frame, textContainer: container)
                view.owner = self
                view.isEditable = false
                view.isSelectable = false
                view.drawsBackground = false
                view.textContainerInset = .zero
                pages.addSubview(view)
                textViews.append(view)
                index += 1
            } while NSMaxRange(layoutManager.glyphRange(for: layoutManager.textContainers.last!)) < glyphs && index < 600
            pages.frame = NSRect(x: 0, y: 0, width: CGFloat(pageCount) * bounds.width, height: bounds.height)
            show(page: min(page, pageCount - 1), animated: false)
        }

        func show(page newPage: Int, animated: Bool = false) {
            page = max(0, min(newPage, pageCount - 1))
            let origin = NSPoint(x: -CGFloat(page) * bounds.width, y: 0)
            if animated {
                NSAnimationContext.runAnimationGroup { context in
                    context.duration = 0.25
                    pages.animator().setFrameOrigin(origin)
                }
            } else {
                pages.setFrameOrigin(origin)
            }
            coordinator?.report()
        }

        /// Next or previous spread; past either end, the chapter.
        func turn(forward: Bool) {
            if forward {
                if page + 1 < pageCount { show(page: page + 1, animated: true) } else { coordinator?.configuration?.onSwipe(true) }
            } else {
                if page > 0 { show(page: page - 1, animated: true) } else { coordinator?.configuration?.onSwipe(false) }
            }
        }

        override func keyDown(with event: NSEvent) {
            switch event.keyCode {
            case 124, 121, 49: turn(forward: !(event.keyCode == 49 && event.modifierFlags.contains(.shift)))   // →, page down, space
            case 123, 116: turn(forward: false)                                                                // ←, page up
            default: super.keyDown(with: event)
            }
        }

        override func scrollWheel(with event: NSEvent) {
            // A trackpad gesture turns at most one spread; a mouse wheel turns one per notch,
            // no faster than a reader could mean to.
            if event.hasPreciseScrollingDeltas {
                if event.phase == .began { wheel = 0; turnedThisGesture = false }
                guard event.momentumPhase == [], !turnedThisGesture else { return }
                wheel += abs(event.scrollingDeltaX) > abs(event.scrollingDeltaY) ? -event.scrollingDeltaX : -event.scrollingDeltaY
                if abs(wheel) >= 60 {
                    turnedThisGesture = true
                    turn(forward: wheel > 0)
                }
            } else {
                let delta = abs(event.scrollingDeltaX) > abs(event.scrollingDeltaY) ? -event.scrollingDeltaX : -event.scrollingDeltaY
                guard delta != 0, event.timestamp - lastWheelTurn > 0.25 else { return }
                lastWheelTurn = event.timestamp
                turn(forward: delta > 0)
            }
        }

        override func swipe(with event: NSEvent) {
            if event.deltaX != 0 { turn(forward: event.deltaX < 0) }
        }

        func column(ofCharacter index: Int) -> Int? {
            guard index < storage.length else { return nil }
            let glyph = layoutManager.glyphIndexForCharacter(at: index)
            guard let container = layoutManager.textContainer(forGlyphAt: glyph, effectiveRange: nil) else { return nil }
            return layoutManager.textContainers.firstIndex { $0 === container }
        }

        func page(ofCharacter index: Int) -> Int? { column(ofCharacter: index).map { $0 / columns } }

        func clicked(_ event: NSEvent, in textView: NSTextView) {
            window?.makeFirstResponder(self)
            guard let container = textView.textContainer else { return }
            let point = textView.convert(event.locationInWindow, from: nil)
            guard let index = ChapterGeometry.characterIndex(at: point, layoutManager: layoutManager, container: container),
                  let tap = ChapterGeometry.tap(at: index, in: storage, rect: { range in
                      let rect = ChapterGeometry.rect(forCharacters: range, layoutManager: self.layoutManager, container: container)
                      return self.convert(rect, from: textView)
                  })
            else { return }
            coordinator?.configuration?.onTap(tap)
        }
    }

    final class ColumnTextView: NSTextView {
        weak var owner: PagedColumnsView?
        override func mouseDown(with event: NSEvent) { owner?.clicked(event, in: self) }
        override func scrollWheel(with event: NSEvent) { owner?.scrollWheel(with: event) }
        override func resetCursorRects() { addCursorRect(visibleRect, cursor: .pointingHand) }
    }

    @MainActor
    final class Coordinator: NSObject {
        weak var view: PagedColumnsView?
        var configuration: ChapterTextConfiguration?
        var spreads: ColumnSpreads?
        var fingerprint: Int?
        var chapter: ChapterRef?
        private var lastTopVerse: Int?
        private var revealedVerse: Int?

        func show(verse key: Int) {
            guard let view, let range = ChapterGeometry.firstRange(ofVerse: key, in: view.text),
                  let page = view.page(ofCharacter: range.location) else { return }
            view.show(page: page)
        }

        func revealIfNeeded(_ configuration: ChapterTextConfiguration) {
            guard configuration.scrollTarget == nil else { return }
            guard let verse = configuration.revealVerse else {
                revealedVerse = nil
                return
            }
            guard verse != revealedVerse else { return }
            revealedVerse = verse
            Task { @MainActor in
                guard let view = self.view, let range = ChapterGeometry.firstRange(ofVerse: verse, in: view.text),
                      let page = view.page(ofCharacter: range.location), page != view.page else { return }
                view.show(page: page, animated: true)
            }
        }

        func report() {
            guard let view else { return }
            spreads?.update(page: view.page, count: view.pageCount)
            let first = view.page * view.columns
            guard first < view.layoutManager.textContainers.count else { return }
            let glyphs = view.layoutManager.glyphRange(for: view.layoutManager.textContainers[first])
            let index = view.layoutManager.characterIndexForGlyph(at: glyphs.location)
            if let key = ChapterGeometry.verse(atOrAfter: index, in: view.text), key != lastTopVerse {
                lastTopVerse = key
                configuration?.onTopVerseChange(key)
            }
        }
    }
}
#endif
