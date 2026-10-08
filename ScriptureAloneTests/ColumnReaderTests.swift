import Foundation
import SwiftUI
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// The column reader as it runs (1.1.4): John 3 from the sealed ASV set in a real paged column view,
/// turned a spread at a time by the bottom bar's arrows (`ColumnSpreads`), handing the chapter on past
/// either end; and the rounded highlight bands `ChapterLayoutManager` actually paints.
@MainActor
struct ColumnReaderTests {
    let john3 = ChapterRef(.john, 3)

    func style(size: CGFloat = 18) -> ReaderStyle {
        ReaderStyle(family: .newYork, size: size, lineSpacing: 1.4, layout: .paragraphs, redLetters: true,
                    verseNumbers: true, headings: true, footnotes: true,
                    palette: ReaderTheme.light.palette(for: .light), paletteID: "light")
    }

    func rendered(highlights: [Int: String] = [:], size: CGFloat = 18) throws -> RenderedChapter {
        let package = try #require(SealedTranslations.shared.package("ASV"))
        let style = style(size: size)
        let input = ChapterRenderInput(chapter: john3, translation: "ASV", style: ReaderStyleKey(style),
                                       highlights: highlights, notes: [:], selection: [],
                                       nextTitle: "John 4", copyright: "Public domain.", compactHeader: false)
        return ChapterRenderer.render(layout: try package.layout(for: john3), input: input, style: style)
    }

    /// Records what the column view asks of the reader.
    final class Calls {
        var swipes: [Bool] = []
        var topVerses: [Int] = []
        var taps: [ChapterTap] = []
        var scrolledToTarget = 0
    }

    func configuration(_ content: RenderedChapter, calls: Calls, scrollTarget: Int? = nil,
                       reveal: Int? = nil) -> ChapterTextConfiguration {
        ChapterTextConfiguration(content: content, background: .white, scrollTarget: scrollTarget, autoScrollSpeed: 0,
                                 onTap: { calls.taps.append($0) }, onSwipe: { calls.swipes.append($0) },
                                 onTopVerseChange: { calls.topVerses.append($0) },
                                 onScrolledToTarget: { calls.scrolledToTarget += 1 },
                                 onReachedEnd: {}, onUserScroll: {}, revealVerse: reveal)
    }

    /// Lets queued main-actor work (spread reports are deferred a turn of the run loop) run.
    func settle(_ turns: Int = 5) async {
        for _ in 0..<turns { await Task.yield(); try? await Task.sleep(for: .milliseconds(10)) }
    }

    // MARK: - Spreads

    @Test func spreadsAreInactiveUntilAColumnViewAttaches() {
        let spreads = ColumnSpreads()
        #expect(!spreads.active)
        #expect(!spreads.hasPrevious)
        #expect(!spreads.hasNext)
        spreads.turn(forward: true) // no column view: nothing to turn, and no crash
        #expect(spreads.page == 0)
    }

    #if os(iOS)
    func pagedView(columns: Int = 2, size: CGSize = CGSize(width: 1024, height: 640), text: NSAttributedString,
                   coordinator: ColumnChapterView.Coordinator) -> ColumnChapterView.PagedColumnsView {
        let view = ColumnChapterView.PagedColumnsView(frame: CGRect(origin: .zero, size: size))
        view.coordinator = coordinator
        view.delegate = coordinator
        coordinator.view = view
        view.setContent(text, columns: columns)
        return view
    }

    @Test func aChapterIsSetInSpreadsOfColumnsAndHoldsEveryCharacter() throws {
        let chapter = try rendered()
        let coordinator = ColumnChapterView.Coordinator()
        let view = pagedView(text: chapter.text, coordinator: coordinator)
        #expect(view.pageCount >= 2, "John 3 at 18 points fills more than one spread")
        #expect(view.textViews.count > view.pageCount, "two columns to a spread")
        #expect(view.contentSize.width == CGFloat(view.pageCount) * 1024)
        // Columns sit side by side within a spread, the margin and gutter apart, all one width.
        let first = view.textViews[0].frame, second = view.textViews[1].frame
        #expect(first.minX == ReaderColumns.margin)
        #expect(second.minX == first.maxX + ReaderColumns.gutter)
        #expect(first.width == second.width)
        // Every character is in a column, the first on the first spread, the last on the last.
        #expect(view.page(ofCharacter: 0) == 0)
        #expect(view.page(ofCharacter: chapter.text.length - 1) == view.pageCount - 1)
        #expect(view.column(ofCharacter: chapter.text.length) == nil)
        // One (wider) column to a spread: a spread per column, never fewer spreads than two to a spread.
        let singleCoordinator = ColumnChapterView.Coordinator()
        let single = pagedView(columns: 1, text: chapter.text, coordinator: singleCoordinator)
        #expect(single.pageCount == single.textViews.count)
        #expect(single.pageCount >= view.pageCount)
    }

    @Test func theArrowsTurnSpreadsThenHandTheChapterOnPastEitherEnd() throws {
        let chapter = try rendered()
        let calls = Calls()
        let coordinator = ColumnChapterView.Coordinator()
        coordinator.configuration = configuration(chapter, calls: calls)
        let view = pagedView(text: chapter.text, coordinator: coordinator)
        let last = view.pageCount - 1

        view.turn(forward: false)
        #expect(calls.swipes == [false], "before the first spread: the previous chapter")
        #expect(view.currentPage == 0)

        for page in 1...last {
            view.show(page: page, animated: false)
            #expect(view.currentPage == page)
        }
        view.turn(forward: true)
        #expect(calls.swipes == [false, true], "past the last spread: the next chapter")
        #expect(view.currentPage == last)

        // Out-of-range pages are clamped, not scrolled past.
        view.show(page: last + 5, animated: false)
        #expect(view.currentPage == last)
        view.show(page: -3, animated: false)
        #expect(view.currentPage == 0)
        #expect(calls.swipes.count == 2)
    }

    @Test func pullingPastEitherEndChangesTheChapter() throws {
        let chapter = try rendered()
        let calls = Calls()
        let coordinator = ColumnChapterView.Coordinator()
        coordinator.configuration = configuration(chapter, calls: calls)
        let view = pagedView(text: chapter.text, coordinator: coordinator)
        view.contentOffset = CGPoint(x: -100, y: 0)
        coordinator.scrollViewDidEndDragging(view, willDecelerate: false)
        view.contentOffset = CGPoint(x: view.contentSize.width - view.bounds.width + 100, y: 0)
        coordinator.scrollViewDidEndDragging(view, willDecelerate: false)
        // A small pull is just a bounce.
        view.contentOffset = CGPoint(x: -20, y: 0)
        coordinator.scrollViewDidEndDragging(view, willDecelerate: false)
        #expect(calls.swipes == [false, true])
    }

    @Test func aRotationLaysTheColumnsOutAgainAndKeepsTheSpread() throws {
        let chapter = try rendered()
        let coordinator = ColumnChapterView.Coordinator()
        let view = pagedView(text: chapter.text, coordinator: coordinator)
        view.show(page: 1, animated: false)
        let before = view.textViews.count
        view.frame = CGRect(x: 0, y: 0, width: 1366, height: 1000)
        view.layoutSubviews()
        #expect(view.textViews.count < before, "a bigger page needs fewer columns")
        #expect(view.currentPage == min(1, view.pageCount - 1))
        #expect(view.textViews.allSatisfy { $0.frame.height <= 1000 })
    }

    /// The whole representable in a window, as the reader shows it: the spreads attach and report,
    /// the arrows turn, a scroll target is shown and cleared, and the view leaving detaches them.
    @Test func inAWindowTheSpreadsFollowTheColumnView() async throws {
        let chapter = try rendered()
        let calls = Calls()
        let spreads = ColumnSpreads()
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 1024, height: 640)
        let host = UIHostingController(rootView: AnyView(ColumnChapterView(configuration: configuration(chapter, calls: calls, scrollTarget: VerseRef(.john, 3, 16).key),
                                                                           columns: 2, spreads: spreads)))
        window.rootViewController = host
        window.isHidden = false
        host.view.layoutIfNeeded()
        await settle(20)
        #expect(spreads.active)
        #expect(spreads.count >= 2)
        #expect(calls.scrolledToTarget == 1)
        let shownAt = spreads.page
        #expect(shownAt > 0, "John 3:16 is past the first spread")
        #expect(calls.topVerses.last.map { $0 <= VerseRef(.john, 3, 16).key } == true)

        // The arrows: back to the first spread, then before it hands back the chapter.
        for _ in 0..<shownAt { spreads.turn(forward: false) }
        try? await Task.sleep(for: .milliseconds(600)) // the animated turn
        await settle()
        #expect(spreads.page == 0 || calls.swipes.isEmpty)
        #expect(!spreads.hasPrevious || spreads.page > 0)

        // The reader moves on (the scrolling reader replaces the columns): the column view is dismantled.
        host.rootView = AnyView(Color.clear)
        host.view.layoutIfNeeded()
        await settle(10)
        #expect(!spreads.active, "the column view left")
        window.isHidden = true
    }
    #endif

    // MARK: - Rounded highlights, as drawn

    /// A highlight over several wrapped lines is painted as one band: inside it is filled, its outer
    /// top-left corner (rounded) is left clear, and where one line meets the next nothing is clear.
    @Test func aHighlightIsPaintedAsOneRoundedBand() throws {
        let highlight = PlatformColor(red: 1, green: 0, blue: 0, alpha: 1)
        let words = String(repeating: "and the light shineth in the darkness ", count: 12)
        let text = NSMutableAttributedString(string: "Before. " + words + "After.", attributes: [
            .font: PlatformFont.systemFont(ofSize: 20),
        ])
        let range = NSRange(location: 8, length: (words as NSString).length)
        text.addAttribute(.backgroundColor, value: highlight, range: range)

        let storage = NSTextStorage(attributedString: text)
        let manager = ChapterLayoutManager()
        storage.addLayoutManager(manager)
        let container = NSTextContainer(size: CGSize(width: 300, height: 10_000))
        container.lineFragmentPadding = 0
        manager.addTextContainer(container)
        let glyphs = manager.glyphRange(for: container)
        let used = manager.usedRect(for: container)
        let lines = manager.boundingRect(forGlyphRange: manager.glyphRange(forCharacterRange: range, actualCharacterRange: nil), in: container)
        #expect(lines.height > 80, "the highlight wraps over several lines")

        let size = CGSize(width: 300, height: ceil(used.height))
        let pixels = try draw(size: size) { manager.drawBackground(forGlyphRange: glyphs, at: .zero) }
        func isRed(_ x: Int, _ y: Int) -> Bool { pixels.isRed(x: x, y: y) }

        // The first highlighted line starts after "Before. ": its top-left corner is rounded off.
        let start = manager.boundingRect(forGlyphRange: NSRange(location: range.location, length: 1), in: container)
        #expect(!isRed(Int(start.minX), Int(start.minY)), "the band's outer corner is rounded")
        #expect(isRed(Int(start.minX) + 8, Int(start.midY)), "inside the band is filled")
        // Between the first and second line, mid-paragraph, the band is continuous: no gap, no corner.
        let second = manager.boundingRect(forGlyphRange: NSRange(location: range.location + 60, length: 1), in: container)
        #expect(isRed(Int(second.midX), Int(second.minY)))
        #expect(isRed(Int(second.midX), Int(second.minY) - 1))
        // Nothing outside the highlight is painted.
        #expect(!isRed(1, 1), "\"Before.\" has no background")
    }

    struct Pixels {
        let width: Int, height: Int
        let data: [UInt8]
        func isRed(x: Int, y: Int) -> Bool {
            guard x >= 0, y >= 0, x < width, y < height else { return false }
            let i = (y * width + x) * 4
            return data[i] > 200 && data[i + 1] < 60 && data[i + 2] < 60 && data[i + 3] > 200
        }
    }

    /// Draws into a premultiplied RGBA bitmap, top-left origin as TextKit draws on both platforms.
    func draw(size: CGSize, _ body: () -> Void) throws -> Pixels {
        let width = Int(size.width), height = Int(size.height)
        let context = try #require(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                             space: CGColorSpaceCreateDeviceRGB(),
                                             bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        #if os(iOS)
        UIGraphicsPushContext(context)
        body()
        UIGraphicsPopContext()
        #else
        let previous = NSGraphicsContext.current
        NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: true)
        body()
        NSGraphicsContext.current = previous
        #endif
        let buffer = try #require(context.data)
        // A bitmap context's memory starts with the image's top row — which, flipped as above, is y = 0.
        let raw = UnsafeBufferPointer(start: buffer.assumingMemoryBound(to: UInt8.self), count: width * height * 4)
        return Pixels(width: width, height: height, data: Array(raw))
    }
}
