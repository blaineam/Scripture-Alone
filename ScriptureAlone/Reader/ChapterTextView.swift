import SwiftUI
import ScriptureAloneCore

/// What a tap in the chapter landed on. Rects are in the view's visible coordinates,
/// ready to anchor a SwiftUI popover.
enum ChapterTap {
    case verse(Int)
    case notes([String], CGRect)
    case footnote(String, CGRect)
    case action(String)
}

/// Callbacks and one-shot requests from SwiftUI into the platform text view.
struct ChapterTextConfiguration {
    var content: RenderedChapter
    var background: PlatformColor
    /// Verse key to bring to the top once, then cleared through `onScrolledToTarget`.
    var scrollTarget: Int?
    /// Points per second; 0 stops.
    var autoScrollSpeed: Double
    var onTap: (ChapterTap) -> Void
    var onSwipe: (_ forward: Bool) -> Void
    var onTopVerseChange: (Int) -> Void
    var onScrolledToTarget: () -> Void
    var onReachedEnd: () -> Void
    var onUserScroll: () -> Void
    /// Verse being read aloud; scrolled into view whenever it changes and isn't visible.
    var revealVerse: Int? = nil
}

/// Shared TextKit 1 geometry, used by both platforms.
@MainActor
enum ChapterGeometry {
    static let maxLineWidth: CGFloat = 700

    static func horizontalInset(for width: CGFloat) -> CGFloat {
        let margin: CGFloat = width < 500 ? 22 : 40
        return max(margin, (width - maxLineWidth) / 2)
    }

    static func characterIndex(at point: CGPoint, layoutManager: NSLayoutManager, container: NSTextContainer) -> Int? {
        guard layoutManager.numberOfGlyphs > 0 else { return nil }
        var fraction: CGFloat = 0
        let glyph = layoutManager.glyphIndex(for: point, in: container, fractionOfDistanceThroughGlyph: &fraction)
        // Ignore taps in blank margins beside a short line.
        let lineRect = layoutManager.lineFragmentUsedRect(forGlyphAt: glyph, effectiveRange: nil)
        guard lineRect.insetBy(dx: -8, dy: -2).contains(point) else { return nil }
        return layoutManager.characterIndexForGlyph(at: glyph)
    }

    static func rect(forCharacters range: NSRange, layoutManager: NSLayoutManager, container: NSTextContainer) -> CGRect {
        let glyphs = layoutManager.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
        return layoutManager.boundingRect(forGlyphRange: glyphs, in: container)
    }

    static func tap(at index: Int, in text: NSAttributedString, rect: (NSRange) -> CGRect) -> ChapterTap? {
        guard index < text.length else { return nil }
        var effective = NSRange()
        let attrs = text.attributes(at: index, effectiveRange: &effective)
        if let ids = attrs[.noteIDs] as? [String] { return .notes(ids, rect(effective)) }
        if let note = attrs[.footnote] as? String { return .footnote(note, rect(effective)) }
        if let action = attrs[.readerAction] as? String { return .action(action) }
        if let key = attrs[.verseKey] as? Int { return .verse(key) }
        return nil
    }

    static func firstRange(ofVerse key: Int, in text: NSAttributedString) -> NSRange? {
        var found: NSRange?
        text.enumerateAttribute(.verseKey, in: NSRange(location: 0, length: text.length)) { value, range, stop in
            if (value as? Int) == key {
                found = range
                stop.pointee = true
            }
        }
        return found
    }

    static func verse(atOrAfter index: Int, in text: NSAttributedString) -> Int? {
        guard index < text.length else { return nil }
        var result: Int?
        text.enumerateAttribute(.verseKey, in: NSRange(location: index, length: text.length - index)) { value, _, stop in
            if let key = value as? Int {
                result = key
                stop.pointee = true
            }
        }
        return result
    }
}

#if os(iOS)
struct ChapterTextView: UIViewRepresentable {
    var configuration: ChapterTextConfiguration

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> ReaderTextView {
        let storage = NSTextStorage()
        let layoutManager = NSLayoutManager()
        storage.addLayoutManager(layoutManager)
        let container = NSTextContainer(size: CGSize(width: 0, height: CGFloat.greatestFiniteMagnitude))
        container.widthTracksTextView = true
        layoutManager.addTextContainer(container)
        let view = ReaderTextView(frame: .zero, textContainer: container)
        view.isEditable = false
        view.isSelectable = false
        view.alwaysBounceVertical = true
        view.contentInsetAdjustmentBehavior = .automatic
        view.delegate = context.coordinator
        view.adjustsFontForContentSizeCategory = false
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        view.addGestureRecognizer(tap)
        for direction in [UISwipeGestureRecognizer.Direction.left, .right] {
            let swipe = UISwipeGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.swiped(_:)))
            swipe.direction = direction
            view.addGestureRecognizer(swipe)
        }
        context.coordinator.view = view
        return view
    }

    func updateUIView(_ view: ReaderTextView, context: Context) {
        let coordinator = context.coordinator
        coordinator.configuration = configuration
        view.backgroundColor = configuration.background
        view.indicatorStyle = configuration.background.isDarkBackground ? .white : .black

        let content = configuration.content
        if coordinator.fingerprint != content.fingerprint {
            let sameChapter = coordinator.chapter == content.chapter
            let offset = view.contentOffset
            view.attributedText = content.text
            view.layoutIfNeeded()
            if sameChapter {
                view.setContentOffset(offset, animated: false)
            } else if configuration.scrollTarget == nil {
                view.setContentOffset(CGPoint(x: 0, y: -view.adjustedContentInset.top), animated: false)
            }
            coordinator.fingerprint = content.fingerprint
            coordinator.chapter = content.chapter
        }
        if let target = configuration.scrollTarget {
            Task { @MainActor in
                // A link can arrive while the app is still coming to the foreground; wait for the view.
                for _ in 0..<20 {
                    if coordinator.scroll(toVerse: target) { break }
                    try? await Task.sleep(for: .milliseconds(50))
                }
                configuration.onScrolledToTarget()
            }
        }
        coordinator.setAutoScroll(configuration.autoScrollSpeed)
        coordinator.revealIfNeeded(configuration)
    }

    static func dismantleUIView(_ view: ReaderTextView, coordinator: Coordinator) {
        coordinator.setAutoScroll(0)
    }

    final class ReaderTextView: UITextView {
        override func layoutSubviews() {
            let inset = ChapterGeometry.horizontalInset(for: bounds.width)
            let desired = UIEdgeInsets(top: 20, left: inset, bottom: 140, right: inset)
            if textContainerInset != desired { textContainerInset = desired }
            super.layoutSubviews()
        }
    }

    @MainActor
    final class Coordinator: NSObject, UITextViewDelegate {
        weak var view: ReaderTextView?
        var configuration: ChapterTextConfiguration?
        var fingerprint: Int?
        var chapter: ChapterRef?
        private var lastTopVerse: Int?
        private var displayLink: CADisplayLink?
        private var speed: Double = 0
        private var lastTimestamp: CFTimeInterval = 0
        private var carry: CGFloat = 0

        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard let view, let configuration else { return }
            var point = gesture.location(in: view)
            point.x -= view.textContainerInset.left
            point.y -= view.textContainerInset.top
            guard let index = ChapterGeometry.characterIndex(at: point, layoutManager: view.layoutManager,
                                                             container: view.textContainer),
                  let tap = ChapterGeometry.tap(at: index, in: view.attributedText, rect: { visibleRect(for: $0) })
            else { return }
            configuration.onTap(tap)
        }

        @objc func swiped(_ gesture: UISwipeGestureRecognizer) {
            configuration?.onSwipe(gesture.direction == .left)
        }

        private func visibleRect(for range: NSRange) -> CGRect {
            guard let view else { return .zero }
            let rect = ChapterGeometry.rect(forCharacters: range, layoutManager: view.layoutManager, container: view.textContainer)
            return rect.offsetBy(dx: view.textContainerInset.left - view.contentOffset.x,
                                 dy: view.textContainerInset.top - view.contentOffset.y)
        }

        /// Returns false when the view can't be positioned yet (not on screen, or not sized).
        @discardableResult
        func scroll(toVerse key: Int) -> Bool {
            guard let view, view.window != nil, view.bounds.height > 0,
                  let range = ChapterGeometry.firstRange(ofVerse: key, in: view.attributedText) else { return false }
            view.layoutIfNeeded()
            // UITextView lays out lazily; without the full layout contentSize is still short and the
            // offset below gets clamped to the top (seen when a link opened John 3:16 from Psalm 23).
            view.layoutManager.ensureLayout(for: view.textContainer)
            view.layoutIfNeeded()
            let rect = ChapterGeometry.rect(forCharacters: range, layoutManager: view.layoutManager, container: view.textContainer)
            let maxY = max(-view.adjustedContentInset.top,
                           view.contentSize.height - view.bounds.height + view.adjustedContentInset.bottom)
            let y = min(maxY, rect.minY + view.textContainerInset.top - view.adjustedContentInset.top - 12)
            view.setContentOffset(CGPoint(x: 0, y: max(-view.adjustedContentInset.top, y)), animated: false)
            return true
        }

        private var revealedVerse: Int?

        func revealIfNeeded(_ configuration: ChapterTextConfiguration) {
            guard configuration.scrollTarget == nil else { return }
            guard let verse = configuration.revealVerse else {
                revealedVerse = nil
                return
            }
            guard verse != revealedVerse else { return }
            revealedVerse = verse
            Task { @MainActor in self.reveal(verse: verse) }
        }

        /// Brings a verse into view only if it's off screen (or behind the bottom bars), so
        /// reading along doesn't jolt the page every verse.
        func reveal(verse key: Int) {
            guard let view, let range = ChapterGeometry.firstRange(ofVerse: key, in: view.attributedText) else { return }
            let rect = ChapterGeometry.rect(forCharacters: range, layoutManager: view.layoutManager, container: view.textContainer)
                .offsetBy(dx: 0, dy: view.textContainerInset.top)
            let top = view.contentOffset.y + view.adjustedContentInset.top
            let bottom = view.contentOffset.y + view.bounds.height - max(view.adjustedContentInset.bottom, 0) - 180
            guard rect.minY < top || rect.minY + min(rect.height, 60) > bottom else { return }
            let maxY = max(-view.adjustedContentInset.top,
                           view.contentSize.height - view.bounds.height + view.adjustedContentInset.bottom)
            let y = min(maxY, rect.minY - view.adjustedContentInset.top - view.bounds.height * 0.18)
            view.setContentOffset(CGPoint(x: 0, y: max(-view.adjustedContentInset.top, y)), animated: true)
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            guard let view else { return }
            let point = CGPoint(x: 4, y: view.contentOffset.y + view.adjustedContentInset.top - view.textContainerInset.top + 8)
            let glyph = view.layoutManager.glyphIndex(for: point, in: view.textContainer)
            let index = view.layoutManager.characterIndexForGlyph(at: glyph)
            if let key = ChapterGeometry.verse(atOrAfter: index, in: view.attributedText), key != lastTopVerse {
                lastTopVerse = key
                configuration?.onTopVerseChange(key)
            }
        }

        func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
            if speed > 0 { configuration?.onUserScroll() }
        }

        func setAutoScroll(_ newSpeed: Double) {
            speed = newSpeed
            if newSpeed > 0, displayLink == nil {
                let link = CADisplayLink(target: self, selector: #selector(step(_:)))
                link.add(to: .main, forMode: .common)
                displayLink = link
                lastTimestamp = 0
            } else if newSpeed <= 0 {
                displayLink?.invalidate()
                displayLink = nil
            }
        }

        @objc private func step(_ link: CADisplayLink) {
            guard let view else { return }
            defer { lastTimestamp = link.timestamp }
            guard lastTimestamp > 0 else { return }
            carry += CGFloat(speed * (link.timestamp - lastTimestamp))
            let whole = carry.rounded(.down)
            guard whole >= 1 else { return }
            carry -= whole
            let maxY = view.contentSize.height - view.bounds.height + view.adjustedContentInset.bottom
            if view.contentOffset.y >= maxY {
                configuration?.onReachedEnd()
                return
            }
            view.contentOffset.y = min(maxY, view.contentOffset.y + whole)
        }
    }
}

private extension UIColor {
    var isDarkBackground: Bool {
        var white: CGFloat = 0
        getWhite(&white, alpha: nil)
        return white < 0.5
    }
}

#else

struct ChapterTextView: NSViewRepresentable {
    var configuration: ChapterTextConfiguration

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeNSView(context: Context) -> NSScrollView {
        let storage = NSTextStorage()
        let layoutManager = NSLayoutManager()
        storage.addLayoutManager(layoutManager)
        let container = NSTextContainer(size: NSSize(width: 0, height: CGFloat.greatestFiniteMagnitude))
        container.widthTracksTextView = true
        layoutManager.addTextContainer(container)

        let textView = ReaderTextView(frame: .zero, textContainer: container)
        textView.isEditable = false
        textView.isSelectable = false
        textView.isVerticallyResizable = true
        textView.isHorizontallyResizable = false
        textView.autoresizingMask = [.width]
        textView.drawsBackground = true
        textView.coordinator = context.coordinator

        let scroll = NSScrollView()
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.drawsBackground = true
        scroll.documentView = textView
        scroll.contentView.postsBoundsChangedNotifications = true
        NotificationCenter.default.addObserver(context.coordinator, selector: #selector(Coordinator.boundsChanged(_:)),
                                               name: NSView.boundsDidChangeNotification, object: scroll.contentView)
        context.coordinator.scrollView = scroll
        context.coordinator.textView = textView
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        let coordinator = context.coordinator
        coordinator.configuration = configuration
        guard let textView = coordinator.textView else { return }
        textView.backgroundColor = configuration.background
        scroll.backgroundColor = configuration.background

        let content = configuration.content
        if coordinator.fingerprint != content.fingerprint {
            let sameChapter = coordinator.chapter == content.chapter
            let origin = scroll.contentView.bounds.origin
            textView.textStorage?.setAttributedString(content.text)
            textView.layoutManager?.ensureLayout(for: textView.textContainer!)
            if sameChapter {
                scroll.contentView.scroll(to: origin)
            } else if configuration.scrollTarget == nil {
                scroll.contentView.scroll(to: .zero)
            }
            scroll.reflectScrolledClipView(scroll.contentView)
            coordinator.fingerprint = content.fingerprint
            coordinator.chapter = content.chapter
        }
        if let target = configuration.scrollTarget {
            Task { @MainActor in
                coordinator.scroll(toVerse: target)
                configuration.onScrolledToTarget()
            }
        }
        coordinator.setAutoScroll(configuration.autoScrollSpeed)
        coordinator.revealIfNeeded(configuration)
    }

    static func dismantleNSView(_ view: NSScrollView, coordinator: Coordinator) {
        coordinator.setAutoScroll(0)
        NotificationCenter.default.removeObserver(coordinator)
    }

    final class ReaderTextView: NSTextView {
        weak var coordinator: Coordinator?

        override func layout() {
            let inset = ChapterGeometry.horizontalInset(for: bounds.width)
            let desired = NSSize(width: inset, height: 28)
            if textContainerInset != desired { textContainerInset = desired }
            super.layout()
        }

        override func mouseDown(with event: NSEvent) {
            let local = convert(event.locationInWindow, from: nil)
            coordinator?.clicked(at: local)
        }

        override func swipe(with event: NSEvent) {
            if event.deltaX != 0 { coordinator?.configuration?.onSwipe(event.deltaX < 0) }
        }

        override func resetCursorRects() {
            addCursorRect(visibleRect, cursor: .pointingHand)
        }
    }

    @MainActor
    final class Coordinator: NSObject {
        weak var scrollView: NSScrollView?
        weak var textView: ReaderTextView?
        var configuration: ChapterTextConfiguration?
        var fingerprint: Int?
        var chapter: ChapterRef?
        private var lastTopVerse: Int?
        private var displayLink: CADisplayLink?
        private var speed: Double = 0
        private var lastTimestamp: CFTimeInterval = 0
        private var carry: CGFloat = 0
        private var programmaticScroll = false

        func clicked(at point: CGPoint) {
            guard let textView, let layoutManager = textView.layoutManager, let container = textView.textContainer else { return }
            let origin = textView.textContainerOrigin
            let inContainer = CGPoint(x: point.x - origin.x, y: point.y - origin.y)
            guard let index = ChapterGeometry.characterIndex(at: inContainer, layoutManager: layoutManager, container: container),
                  let tap = ChapterGeometry.tap(at: index, in: textView.attributedString(), rect: { visibleRect(for: $0) })
            else { return }
            configuration?.onTap(tap)
        }

        private func visibleRect(for range: NSRange) -> CGRect {
            guard let textView, let scrollView, let layoutManager = textView.layoutManager,
                  let container = textView.textContainer else { return .zero }
            let rect = ChapterGeometry.rect(forCharacters: range, layoutManager: layoutManager, container: container)
            let origin = textView.textContainerOrigin
            let clip = scrollView.contentView.bounds.origin
            return rect.offsetBy(dx: origin.x - clip.x, dy: origin.y - clip.y)
        }

        func scroll(toVerse key: Int) {
            guard let textView, let scrollView, let layoutManager = textView.layoutManager, let container = textView.textContainer,
                  let range = ChapterGeometry.firstRange(ofVerse: key, in: textView.attributedString()) else { return }
            layoutManager.ensureLayout(for: container)
            let rect = ChapterGeometry.rect(forCharacters: range, layoutManager: layoutManager, container: container)
            let maxY = max(0, textView.frame.height - scrollView.contentView.bounds.height)
            programmaticScroll = true
            scrollView.contentView.scroll(to: CGPoint(x: 0, y: min(maxY, max(0, rect.minY + textView.textContainerOrigin.y - 16))))
            scrollView.reflectScrolledClipView(scrollView.contentView)
            programmaticScroll = false
        }

        private var revealedVerse: Int?

        func revealIfNeeded(_ configuration: ChapterTextConfiguration) {
            guard configuration.scrollTarget == nil else { return }
            guard let verse = configuration.revealVerse else {
                revealedVerse = nil
                return
            }
            guard verse != revealedVerse else { return }
            revealedVerse = verse
            Task { @MainActor in self.reveal(verse: verse) }
        }

        /// Brings a verse into view only if it's off screen or behind the now-playing bar.
        func reveal(verse key: Int) {
            guard let textView, let scrollView, let layoutManager = textView.layoutManager, let container = textView.textContainer,
                  let range = ChapterGeometry.firstRange(ofVerse: key, in: textView.attributedString()) else { return }
            let rect = ChapterGeometry.rect(forCharacters: range, layoutManager: layoutManager, container: container)
                .offsetBy(dx: 0, dy: textView.textContainerOrigin.y)
            let visible = scrollView.contentView.bounds
            guard rect.minY < visible.minY || rect.minY + min(rect.height, 60) > visible.maxY - 120 else { return }
            let maxY = max(0, textView.frame.height - visible.height)
            programmaticScroll = true
            scrollView.contentView.scroll(to: CGPoint(x: 0, y: min(maxY, max(0, rect.minY - visible.height * 0.18))))
            scrollView.reflectScrolledClipView(scrollView.contentView)
            programmaticScroll = false
        }

        @objc func boundsChanged(_ note: Notification) {
            guard let textView, let scrollView, let layoutManager = textView.layoutManager,
                  let container = textView.textContainer else { return }
            let y = scrollView.contentView.bounds.minY - textView.textContainerOrigin.y + 8
            let glyph = layoutManager.glyphIndex(for: CGPoint(x: 4, y: y), in: container)
            let index = layoutManager.characterIndexForGlyph(at: glyph)
            if let key = ChapterGeometry.verse(atOrAfter: index, in: textView.attributedString()), key != lastTopVerse {
                lastTopVerse = key
                configuration?.onTopVerseChange(key)
            }
        }

        func setAutoScroll(_ newSpeed: Double) {
            speed = newSpeed
            if newSpeed > 0, displayLink == nil, let scrollView {
                let link = scrollView.displayLink(target: self, selector: #selector(step(_:)))
                link.add(to: .main, forMode: .common)
                displayLink = link
                lastTimestamp = 0
            } else if newSpeed <= 0 {
                displayLink?.invalidate()
                displayLink = nil
            }
        }

        @objc private func step(_ link: CADisplayLink) {
            guard let scrollView, let textView else { return }
            defer { lastTimestamp = link.timestamp }
            guard lastTimestamp > 0 else { return }
            carry += CGFloat(speed * (link.timestamp - lastTimestamp))
            let whole = carry.rounded(.down)
            guard whole >= 1 else { return }
            carry -= whole
            let maxY = textView.frame.height - scrollView.contentView.bounds.height
            let y = scrollView.contentView.bounds.minY
            if y >= maxY {
                configuration?.onReachedEnd()
                return
            }
            scrollView.contentView.scroll(to: CGPoint(x: 0, y: min(maxY, y + whole)))
            scrollView.reflectScrolledClipView(scrollView.contentView)
        }
    }
}
#endif
