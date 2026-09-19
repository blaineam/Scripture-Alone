import Foundation
import ScriptureAloneCore
#if os(iOS)
import UIKit
#else
import AppKit
#endif

extension NSAttributedString.Key {
    /// Int verse key on every character belonging to a verse.
    static let verseKey = NSAttributedString.Key("sa.verseKey")
    /// [String] note UUIDs on a note marker.
    static let noteIDs = NSAttributedString.Key("sa.noteIDs")
    /// String footnote text on a footnote marker.
    static let footnote = NSAttributedString.Key("sa.footnote")
    /// String action name ("next") on tappable chrome.
    static let readerAction = NSAttributedString.Key("sa.action")
}

/// A rendered chapter plus a fingerprint so views can skip identical updates.
struct RenderedChapter {
    let chapter: ChapterRef
    let text: NSAttributedString
    let fingerprint: Int
}

struct ChapterRenderInput: Hashable {
    let chapter: ChapterRef
    let translation: String
    let style: ReaderStyleKey
    let highlights: [Int: String]
    let notes: [Int: [String]]
    let selection: Set<Int>
    let nextTitle: String?
    let copyright: String
}

/// ReaderStyle isn't Hashable (colors); this captures what matters for caching.
struct ReaderStyleKey: Hashable {
    let family: String, size: CGFloat, lineSpacing: CGFloat, layout: String
    let redLetters: Bool, verseNumbers: Bool, headings: Bool, footnotes: Bool
    let paletteID: String

    init(_ style: ReaderStyle) {
        family = style.family.rawValue
        size = style.size
        lineSpacing = style.lineSpacing
        layout = style.layout.rawValue
        redLetters = style.redLetters
        verseNumbers = style.verseNumbers
        headings = style.headings
        footnotes = style.footnotes
        paletteID = style.paletteID
    }
}

enum ChapterRenderer {
    static func render(layout: ChapterLayout, input: ChapterRenderInput, style: ReaderStyle) -> RenderedChapter {
        var builder = Builder(style: style, input: input, layout: layout)
        builder.build()
        return RenderedChapter(chapter: input.chapter, text: builder.output, fingerprint: input.hashValue)
    }

    private struct Builder {
        let style: ReaderStyle
        let input: ChapterRenderInput
        let layout: ChapterLayout
        let output = NSMutableAttributedString()

        let body: PlatformFont
        let numberFont: PlatformFont
        let indent: CGFloat
        /// verse → index of the last fragment that carries it, for note markers.
        var lastFragment: [Int: (Int, Int)] = [:]
        var footnoteCounter = 0

        init(style: ReaderStyle, input: ChapterRenderInput, layout: ChapterLayout) {
            self.style = style
            self.input = input
            self.layout = layout
            body = style.family.font(size: style.size)
            numberFont = PlatformFont.systemFont(ofSize: max(9, style.size * 0.58), weight: .semibold)
            indent = style.size * 1.25
            for (b, block) in layout.blocks.enumerated() {
                for (f, fragment) in block.fragments.enumerated() where fragment.verse > 0 {
                    lastFragment[fragment.verse] = (b, f)
                }
            }
        }

        mutating func build() {
            appendChapterHeader()
            if style.layout == .verses {
                buildVerseByVerse()
            } else {
                buildParagraphs()
            }
            appendFooter()
        }

        // MARK: Header / footer

        private func appendChapterHeader() {
            let book = input.chapter.book
            let caption = NSMutableParagraphStyle()
            caption.alignment = .center
            caption.paragraphSpacing = 2
            let bookFont = PlatformFont.systemFont(ofSize: max(11, style.size * 0.62), weight: .semibold)
            output.append(NSAttributedString(string: book.name.uppercased() + "\n", attributes: [
                .font: bookFont, .foregroundColor: style.palette.secondary, .kern: 2.2, .paragraphStyle: caption,
            ]))
            let numberStyle = NSMutableParagraphStyle()
            numberStyle.alignment = .center
            numberStyle.paragraphSpacing = style.size * 1.1
            let big = style.family.font(size: style.size * 2.6)
            let number = book.isSingleChapter ? "" : "\(input.chapter.chapter)"
            output.append(NSAttributedString(string: number + "\n", attributes: [
                .font: big, .foregroundColor: style.palette.accent, .paragraphStyle: numberStyle,
            ]))
        }

        private func appendFooter() {
            let center = NSMutableParagraphStyle()
            center.alignment = .center
            center.paragraphSpacingBefore = style.size * 2
            if let next = input.nextTitle {
                output.append(NSAttributedString(string: "\n\(next)  →\n", attributes: [
                    .font: PlatformFont.systemFont(ofSize: style.size * 0.8, weight: .semibold),
                    .foregroundColor: style.palette.accent, .paragraphStyle: center, .readerAction: "next",
                ]))
            }
            let fine = NSMutableParagraphStyle()
            fine.alignment = .center
            fine.paragraphSpacingBefore = style.size
            output.append(NSAttributedString(string: "\n\(input.copyright)\n", attributes: [
                .font: PlatformFont.systemFont(ofSize: max(10, style.size * 0.55)),
                .foregroundColor: style.palette.secondary, .paragraphStyle: fine,
            ]))
        }

        // MARK: Layout modes

        private mutating func buildParagraphs() {
            var pendingBreak = false
            for (b, block) in layout.blocks.enumerated() {
                switch block.kind {
                case .stanzaBreak:
                    pendingBreak = true
                    continue
                case _ where block.kind.isHeading:
                    if style.headings { appendHeading(block) }
                case .unknown:
                    continue
                default:
                    let paragraph = NSMutableAttributedString()
                    for (f, fragment) in block.fragments.enumerated() {
                        if paragraph.length > 0 {
                            var attrs: [NSAttributedString.Key: Any] = [.font: body]
                            if f > 0, let color = highlightColor(fragment.verse),
                               color == highlightColor(block.fragments[f - 1].verse) {
                                attrs[.backgroundColor] = color
                            }
                            paragraph.append(NSAttributedString(string: " ", attributes: attrs))
                        }
                        paragraph.append(renderFragment(fragment, blockIndex: b, fragmentIndex: f, kind: block.kind))
                    }
                    guard paragraph.length > 0 else { continue }
                    paragraph.append(NSAttributedString(string: "\n", attributes: [.font: body]))
                    let ps = paragraphStyle(for: block.kind, extraSpaceBefore: pendingBreak)
                    paragraph.addAttribute(.paragraphStyle, value: ps, range: NSRange(location: 0, length: paragraph.length))
                    output.append(paragraph)
                }
                pendingBreak = false
            }
        }

        private mutating func buildVerseByVerse() {
            var current: NSMutableAttributedString?
            func flush(_ into: NSMutableAttributedString, _ current: inout NSMutableAttributedString?, _ style: NSParagraphStyle, _ font: PlatformFont) {
                guard let line = current, line.length > 0 else { return }
                line.append(NSAttributedString(string: "\n", attributes: [.font: font]))
                line.addAttribute(.paragraphStyle, value: style, range: NSRange(location: 0, length: line.length))
                into.append(line)
                current = nil
            }
            let hanging = NSMutableParagraphStyle()
            hanging.lineHeightMultiple = style.lineSpacing
            hanging.paragraphSpacing = style.size * 0.45
            hanging.headIndent = style.verseNumbers ? style.size * 1.5 : 0
            hanging.firstLineHeadIndent = 0

            for (b, block) in layout.blocks.enumerated() {
                if block.kind.isHeading {
                    flush(output, &current, hanging, body)
                    if style.headings { appendHeading(block) }
                    continue
                }
                if block.kind == .title {
                    flush(output, &current, hanging, body)
                    let title = NSMutableAttributedString()
                    for (f, fragment) in block.fragments.enumerated() {
                        title.append(renderFragment(fragment, blockIndex: b, fragmentIndex: f, kind: .title))
                    }
                    title.append(NSAttributedString(string: "\n", attributes: [.font: body]))
                    title.addAttribute(.paragraphStyle, value: paragraphStyle(for: .title, extraSpaceBefore: false),
                                       range: NSRange(location: 0, length: title.length))
                    output.append(title)
                    continue
                }
                for (f, fragment) in block.fragments.enumerated() {
                    if fragment.numbered {
                        flush(output, &current, hanging, body)
                        current = NSMutableAttributedString()
                    } else if let line = current, line.length > 0 {
                        line.append(NSAttributedString(string: " ", attributes: [.font: body, .verseKey: verseKey(fragment.verse)]))
                    }
                    if current == nil { current = NSMutableAttributedString() }
                    current?.append(renderFragment(fragment, blockIndex: b, fragmentIndex: f, kind: .continuation))
                }
            }
            flush(output, &current, hanging, body)
        }

        // MARK: Pieces

        private func highlightColor(_ verse: Int) -> PlatformColor? {
            guard verse > 0 else { return nil }
            return input.highlights[verseKey(verse)].flatMap(HighlightColor.init(rawValue:))?
                .platformColor(isDark: style.palette.isDark)
        }

        private func verseKey(_ verse: Int) -> Int {
            input.chapter.book.rawValue * 1_000_000 + input.chapter.chapter * 1_000 + verse
        }

        private func appendHeading(_ block: ChapterLayout.Block) {
            guard let text = block.text, !text.isEmpty else { return }
            let ps = NSMutableParagraphStyle()
            ps.lineHeightMultiple = 1.1
            var font: PlatformFont
            var color = style.palette.ink
            var kern: CGFloat = 0
            switch block.kind {
            case .heading:
                font = PlatformFont.systemFont(ofSize: style.size * 0.86, weight: .semibold)
                ps.paragraphSpacingBefore = style.size * 1.1
                ps.paragraphSpacing = style.size * 0.35
            case .subheading:
                font = style.family.font(size: style.size * 0.9).withTraits(italic: true)
                ps.paragraphSpacingBefore = style.size * 0.6
                ps.paragraphSpacing = style.size * 0.25
            case .parallel:
                font = PlatformFont.systemFont(ofSize: style.size * 0.68)
                color = style.palette.secondary
                ps.paragraphSpacing = style.size * 0.5
            case .majorSection:
                font = PlatformFont.systemFont(ofSize: style.size * 0.72, weight: .semibold)
                color = style.palette.secondary
                kern = 1.8
                ps.alignment = .center
                ps.paragraphSpacingBefore = style.size
                ps.paragraphSpacing = style.size * 0.6
            default: // acrostic letters
                font = PlatformFont.systemFont(ofSize: style.size * 0.78, weight: .semibold)
                color = style.palette.accent
                ps.alignment = .center
                ps.paragraphSpacingBefore = style.size * 0.8
                ps.paragraphSpacing = style.size * 0.2
            }
            let shown = block.kind == .majorSection ? text.uppercased() : text
            let heading = NSMutableAttributedString(string: shown + "\n", attributes: [
                .font: font, .foregroundColor: color, .paragraphStyle: ps, .kern: kern,
            ])
            if block.kind != .majorSection { applyDivineNameSmallCaps(heading, font: font) }
            output.append(heading)
        }

        private func paragraphStyle(for kind: ChapterLayout.Block.Kind, extraSpaceBefore: Bool) -> NSParagraphStyle {
            let ps = NSMutableParagraphStyle()
            ps.lineHeightMultiple = style.lineSpacing
            ps.paragraphSpacing = style.size * 0.45
            if extraSpaceBefore { ps.paragraphSpacingBefore = style.size * 0.7 }
            switch kind {
            case .paragraph:
                ps.firstLineHeadIndent = indent
            case .embedded:
                ps.firstLineHeadIndent = indent
                ps.headIndent = indent
                ps.tailIndent = -indent
            case .centered:
                ps.alignment = .center
            case .list1:
                ps.firstLineHeadIndent = indent
                ps.headIndent = indent * 2
            case .list2:
                ps.firstLineHeadIndent = indent * 2
                ps.headIndent = indent * 3
            case .poetry1:
                ps.firstLineHeadIndent = indent
                ps.headIndent = indent * 2.5
                ps.paragraphSpacing = style.size * 0.12
            case .poetry2:
                ps.firstLineHeadIndent = indent * 2.5
                ps.headIndent = indent * 3.5
                ps.paragraphSpacing = style.size * 0.12
            case .selah:
                ps.alignment = .right
                ps.paragraphSpacing = style.size * 0.3
            case .title:
                ps.alignment = .center
                ps.paragraphSpacing = style.size * 0.7
                ps.lineHeightMultiple = 1.1
            default:
                break
            }
            return ps
        }

        private mutating func renderFragment(_ fragment: ChapterLayout.Fragment, blockIndex: Int, fragmentIndex: Int,
                                             kind: ChapterLayout.Block.Kind) -> NSAttributedString {
            let key = verseKey(fragment.verse)
            let isTitle = kind == .title
            var font = isTitle ? body.withTraits(italic: true) : body
            if kind == .selah { font = body.withTraits(italic: true) }
            let ink = isTitle ? style.palette.secondary : style.palette.ink
            var base: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: ink]
            if fragment.verse > 0 && !isTitle { base[.verseKey] = key }

            let text = NSMutableAttributedString(string: fragment.text, attributes: base)
            for span in fragment.spans {
                let start = fragment.text.utf16Offset(ofScalar: span.start)
                let end = fragment.text.utf16Offset(ofScalar: span.start + span.length)
                let range = NSRange(location: start, length: max(0, end - start))
                guard range.upperBound <= text.length else { continue }
                switch span.style {
                case .wordsOfChrist where style.redLetters:
                    text.addAttribute(.foregroundColor, value: style.palette.red, range: range)
                case .supplied:
                    text.addAttribute(.font, value: font.withTraits(italic: true), range: range)
                default:
                    break
                }
            }
            applyDivineNameSmallCaps(text, font: font)

            if style.footnotes {
                for note in fragment.footnotes.reversed() {
                    let at = min(text.length, fragment.text.utf16Offset(ofScalar: note.position))
                    text.insert(footnoteMarker(note.text, verse: key), at: at)
                }
            }

            if !isTitle, fragment.verse > 0 {
                if input.selection.contains(key) {
                    let range = NSRange(location: 0, length: text.length)
                    text.addAttribute(.underlineStyle, value: NSUnderlineStyle.thick.union(.patternDot).rawValue, range: range)
                    text.addAttribute(.underlineColor, value: style.palette.accent, range: range)
                }
            }

            let result = NSMutableAttributedString()
            if fragment.numbered, style.verseNumbers, fragment.verse > 0 {
                // Narrow no-break space keeps the number on the same line as its first word.
                result.append(NSAttributedString(string: "\(fragment.verse)\u{202F}", attributes: [
                    .font: numberFont, .foregroundColor: style.palette.accent,
                    .baselineOffset: style.size * 0.32, .verseKey: key,
                ]))
            }
            result.append(text)
            if !isTitle, fragment.verse > 0, let color = highlightColor(fragment.verse) {
                result.addAttribute(.backgroundColor, value: color, range: NSRange(location: 0, length: result.length))
            }

            if let noteIDs = input.notes[key], let last = lastFragment[fragment.verse],
               last == (blockIndex, fragmentIndex) {
                result.append(noteMarker(ids: noteIDs))
            }
            return result
        }

        /// "LORD" / "GOD" printed in capitals stand for the divine name; show them as small caps.
        private func applyDivineNameSmallCaps(_ text: NSMutableAttributedString, font: PlatformFont) {
            let string = text.string as NSString
            let regex = Self.divineName
            for match in regex.matches(in: text.string, range: NSRange(location: 0, length: string.length)).reversed() {
                let tail = NSRange(location: match.range.location + 1, length: match.range.length - 1)
                let lower = string.substring(with: tail).lowercased()
                let attrs = text.attributes(at: tail.location, effectiveRange: nil)
                let current = (attrs[.font] as? PlatformFont) ?? font
                var replaced = attrs
                replaced[.font] = current.smallCaps
                text.replaceCharacters(in: tail, with: NSAttributedString(string: lower, attributes: replaced))
            }
        }

        static let divineName = try! NSRegularExpression(pattern: #"\b(LORD|GOD)(?=\b|’|')"#)

        private mutating func footnoteMarker(_ note: String, verse: Int) -> NSAttributedString {
            footnoteCounter += 1
            let letters = Array("abcdefghijklmnopqrstuvwxyz")
            let label = String(letters[(footnoteCounter - 1) % letters.count])
            return NSAttributedString(string: label, attributes: [
                .font: PlatformFont.systemFont(ofSize: max(9, style.size * 0.55), weight: .medium),
                .foregroundColor: style.palette.secondary,
                .baselineOffset: style.size * 0.38,
                .footnote: note,
            ])
        }

        private func noteMarker(ids: [String]) -> NSAttributedString {
            let attachment = NSTextAttachment()
            let side = style.size * 0.82
            #if os(iOS)
            let config = UIImage.SymbolConfiguration(pointSize: side, weight: .medium)
            attachment.image = UIImage(systemName: "text.bubble.fill", withConfiguration: config)?
                .withTintColor(style.palette.accent, renderingMode: .alwaysOriginal)
            #else
            let config = NSImage.SymbolConfiguration(pointSize: side, weight: .medium)
                .applying(NSImage.SymbolConfiguration(paletteColors: [style.palette.accent]))
            attachment.image = NSImage(systemSymbolName: "text.bubble.fill", accessibilityDescription: "Note")?
                .withSymbolConfiguration(config)
            #endif
            attachment.bounds = CGRect(x: 0, y: -side * 0.12, width: side * 1.1, height: side)
            let marker = NSMutableAttributedString(string: "\u{2009}")
            marker.append(NSAttributedString(attachment: attachment))
            marker.addAttribute(.noteIDs, value: ids, range: NSRange(location: 0, length: marker.length))
            return marker
        }
    }
}
