import Foundation
import CoreText
import CoreGraphics
import ScriptureAloneCore

/// Typesets notes into a PDF with Core Text, identically on iPhone, iPad and Mac:
/// a title block, then each note with its passages quoted in the chosen translation,
/// its text, and when it was written. Letter paper in the US, A4 elsewhere.
enum NotesPDFRenderer {
    struct Options {
        var title: String
        var subtitle: String?
        /// Translation abbreviation for quoted passages, or nil to leave verse text out.
        var translation: String?
        /// The publisher's copyright line for the quoted translation, set at the end of the document —
        /// quotations that leave the device must carry their attribution. Nil or blank for public domain.
        var notice: String?
    }

    static func render(_ notes: [KeepsakeNote], options: Options, verseText: NotesTextExport.VerseText) -> Data {
        let pageSize = Locale.current.measurementSystem == .us ? CGSize(width: 612, height: 792) : CGSize(width: 595.28, height: 841.89)
        let margin = CGSize(width: 66, height: 72)
        let text = document(notes, options: options, verseText: verseText)

        let data = NSMutableData()
        var mediaBox = CGRect(origin: .zero, size: pageSize)
        let info: [CFString: Any] = [kCGPDFContextTitle: options.title, kCGPDFContextCreator: "Scripture Alone"]
        guard let consumer = CGDataConsumer(data: data as CFMutableData),
              let context = CGContext(consumer: consumer, mediaBox: &mediaBox, info as CFDictionary) else { return Data() }

        let framesetter = CTFramesetterCreateWithAttributedString(text)
        let frameRect = CGRect(x: margin.width, y: margin.height, width: pageSize.width - margin.width * 2,
                               height: pageSize.height - margin.height * 2)
        var location = 0
        var page = 1
        repeat {
            context.beginPDFPage(nil)
            let path = CGPath(rect: frameRect, transform: nil)
            let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: location, length: 0), path, nil)
            CTFrameDraw(frame, context)
            drawFooter(in: context, page: page, title: options.title, pageSize: pageSize, margin: margin)
            context.endPDFPage()
            let visible = CTFrameGetVisibleStringRange(frame)
            guard visible.length > 0 else { break }
            location += visible.length
            page += 1
        } while location < text.length
        context.closePDF()
        return data as Data
    }

    // MARK: Document

    private static func document(_ notes: [KeepsakeNote], options: Options, verseText: NotesTextExport.VerseText) -> NSAttributedString {
        let out = NSMutableAttributedString()

        out.append(line(options.title, font: serif(26, .bold), color: Palette.ink, spacingAfter: 4))
        if let subtitle = options.subtitle {
            out.append(line(subtitle, font: serif(11, .italic), color: Palette.secondary, spacingAfter: 6))
        }
        let date = Date.now.formatted(date: .long, time: .omitted)
        var summary = String(localized: "\(notes.count) notes · \(date)", comment: "Subtitle of an exported PDF of notes. %lld is the number of notes; %@ is today's date.")
        if let translation = options.translation {
            summary += String(localized: " · Scripture from the \(translation)", comment: "Appended to the subtitle of an exported PDF of notes. %@ is a translation abbreviation, e.g. “KJV”.")
        }
        out.append(line(summary, font: sans(9.5), color: Palette.secondary, spacingAfter: 26))

        for (index, note) in notes.enumerated() {
            if index > 0 {
                out.append(line("·   ·   ·", font: serif(12), color: Palette.rule, alignment: .center, spacingBefore: 6, spacingAfter: 20))
            }
            out.append(line(note.displayTitle, font: serif(17, .bold), color: Palette.ink, spacingAfter: 3))
            if !note.anchors.isEmpty {
                out.append(line(note.anchorSummary.uppercased(), font: sans(8.5, bold: true), color: Palette.accent,
                                kern: 0.9, spacingAfter: 10))
            }
            if let translation = options.translation {
                for range in note.anchors {
                    guard let verses = verseText(range), !verses.isEmpty else { continue }
                    out.append(line(verses, font: serif(10.5, .italic), color: Palette.quote, indent: 16, lineHeight: 1.3, spacingAfter: 2))
                    out.append(line("— \(range.display) (\(translation))", font: sans(8.5), color: Palette.secondary,
                                    indent: 16, spacingAfter: 10))
                }
            }
            let body = note.body.trimmingCharacters(in: .whitespacesAndNewlines)
            if !body.isEmpty {
                for paragraph in body.components(separatedBy: "\n") {
                    out.append(line(paragraph.isEmpty ? " " : paragraph, font: serif(11.5), color: Palette.ink,
                                    lineHeight: 1.38, spacingAfter: 5))
                }
            }
            out.append(line(NotesTextExport.dateLine(note), font: sans(8.5), color: Palette.secondary, spacingBefore: 6, spacingAfter: 18))
        }
        if let notice = options.notice?.trimmingCharacters(in: .whitespacesAndNewlines), !notice.isEmpty {
            out.append(line(notice, font: sans(8.5), color: Palette.secondary, lineHeight: 1.3, spacingBefore: 12))
        }
        return out
    }

    private static func line(_ string: String, font: CTFont, color: CGColor, alignment: CTTextAlignment = .left,
                             kern: CGFloat = 0, indent: CGFloat = 0, lineHeight: CGFloat = 1.15,
                             spacingBefore: CGFloat = 0, spacingAfter: CGFloat = 0) -> NSAttributedString {
        var align = alignment
        var multiple = lineHeight
        var before = spacingBefore
        var after = spacingAfter
        var head = indent
        let style: CTParagraphStyle = withUnsafeBytes(of: &align) { a in
            withUnsafeBytes(of: &multiple) { m in
                withUnsafeBytes(of: &before) { b in
                    withUnsafeBytes(of: &after) { f in
                        withUnsafeBytes(of: &head) { h in
                            let settings = [
                                CTParagraphStyleSetting(spec: .alignment, valueSize: MemoryLayout<CTTextAlignment>.size, value: a.baseAddress!),
                                CTParagraphStyleSetting(spec: .lineHeightMultiple, valueSize: MemoryLayout<CGFloat>.size, value: m.baseAddress!),
                                CTParagraphStyleSetting(spec: .paragraphSpacingBefore, valueSize: MemoryLayout<CGFloat>.size, value: b.baseAddress!),
                                CTParagraphStyleSetting(spec: .paragraphSpacing, valueSize: MemoryLayout<CGFloat>.size, value: f.baseAddress!),
                                CTParagraphStyleSetting(spec: .headIndent, valueSize: MemoryLayout<CGFloat>.size, value: h.baseAddress!),
                                CTParagraphStyleSetting(spec: .firstLineHeadIndent, valueSize: MemoryLayout<CGFloat>.size, value: h.baseAddress!),
                            ]
                            // Created inside the closures, while the setting pointers are valid.
                            return CTParagraphStyleCreate(settings, settings.count)
                        }
                    }
                }
            }
        }
        let attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): color,
            NSAttributedString.Key(kCTParagraphStyleAttributeName as String): style,
            NSAttributedString.Key(kCTKernAttributeName as String): kern,
        ]
        return NSAttributedString(string: string + "\n", attributes: attributes)
    }

    private static func drawFooter(in context: CGContext, page: Int, title: String, pageSize: CGSize, margin: CGSize) {
        let attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): sans(8),
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): Palette.secondary,
        ]
        let left = CTLineCreateWithAttributedString(NSAttributedString(string: title, attributes: attributes))
        let right = CTLineCreateWithAttributedString(NSAttributedString(string: "\(page)", attributes: attributes))
        let y = margin.height / 2
        context.textPosition = CGPoint(x: margin.width, y: y)
        CTLineDraw(left, context)
        let width = CTLineGetTypographicBounds(right, nil, nil, nil)
        context.textPosition = CGPoint(x: pageSize.width - margin.width - width, y: y)
        CTLineDraw(right, context)
    }

    // MARK: Type and color

    private enum Weight { case regular, bold, italic }

    private static func serif(_ size: CGFloat, _ weight: Weight = .regular) -> CTFont {
        let name = switch weight {
        case .regular: "IowanOldStyle-Roman"
        case .bold: "IowanOldStyle-Bold"
        case .italic: "IowanOldStyle-Italic"
        }
        return CTFontCreateWithName(name as CFString, size, nil)
    }

    private static func sans(_ size: CGFloat, bold: Bool = false) -> CTFont {
        CTFontCreateUIFontForLanguage(bold ? .emphasizedSystem : .system, size, nil)
            ?? CTFontCreateWithName("Helvetica" as CFString, size, nil)
    }

    private enum Palette {
        static let ink = CGColor(srgbRed: 0.11, green: 0.10, blue: 0.09, alpha: 1)
        static let quote = CGColor(srgbRed: 0.27, green: 0.24, blue: 0.21, alpha: 1)
        static let secondary = CGColor(srgbRed: 0.45, green: 0.43, blue: 0.40, alpha: 1)
        static let accent = CGColor(srgbRed: 0.60, green: 0.42, blue: 0.18, alpha: 1)
        static let rule = CGColor(srgbRed: 0.70, green: 0.66, blue: 0.60, alpha: 1)
    }
}
