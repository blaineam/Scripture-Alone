import SwiftUI
import ScriptureAloneCore

/// What a card says, after fitting: the passage, its reference and the translation's credit.
struct ShareCardContent: Equatable {
    var passage: SharePassageText
    /// "John 3:16–17"
    var reference: String
    /// "ASV"
    var translation: String
    /// The translation's required notice (licensed translations only; public-domain texts need none).
    var notice: String?
    /// Point size of the passage text, chosen by `ShareCardFitter`.
    var fontSize: CGFloat
}

/// Proportions of a card, derived from its size so the three aspects share one design.
/// Mirrored in docs/share-links.md for the web renderer.
struct ShareCardMetrics {
    let size: CGSize

    var short: CGFloat { min(size.width, size.height) }
    var horizontalPadding: CGFloat { size.width * 0.09 }
    var verticalPadding: CGFloat { size.height * 0.09 }
    var referenceSize: CGFloat { max(18, short * 0.034) }
    var wordmarkSize: CGFloat { max(14, short * 0.024) }
    var ruleWidth: CGFloat { referenceSize * 1.6 }
    var textWidth: CGFloat { size.width - horizontalPadding * 2 }
    /// Rule, gap and reference line under the passage.
    var referenceBlock: CGFloat { referenceSize * 3.6 }
    var wordmarkBlock: CGFloat { wordmarkSize * 2.4 }
    var maxFontSize: CGFloat { (size.width * size.height).squareRoot() * 0.066 }
    var minFontSize: CGFloat { max(20, short * 0.022) }
    var lineSpacingRatio: CGFloat { 0.28 }

    func textHeight(footer: Bool) -> CGFloat {
        size.height - verticalPadding * 2 - referenceBlock - (footer ? wordmarkBlock : 0)
    }
}

/// Chooses the largest type that fits, and trims long passages to whole verses when even the
/// smallest size can't hold them.
enum ShareCardFitter {
    /// More than this many verses stops being a card and becomes a page.
    static let maxVerses = 12

    struct Result: Equatable {
        var content: ShareCardContent
        var shownVerses: Int
        var totalVerses: Int
        var trimmed: Bool { shownVerses < totalVerses }
    }

    static func fit(verses: [VerseText], info: TranslationInfo, style: ShareStyle,
                    verseCount: (ChapterRef) -> Int) -> Result {
        let metrics = ShareCardMetrics(size: style.aspect.size)
        let notice = noticeText(for: info)
        let footer = style.wordmark || notice != nil
        let height = metrics.textHeight(footer: footer)
        var count = min(verses.count, maxVerses)
        var chosen: (SharePassageText, CGFloat)?
        while count > 0 {
            let passage = SharePassageText(verses: Array(verses.prefix(count)), numbered: style.verseNumbers)
            if let size = largestFittingSize(passage, style: style, metrics: metrics, height: height) {
                chosen = (passage, size)
                break
            }
            if count == 1 { break }
            count -= 1
        }
        // A single verse too long for the smallest size still renders; the card's scale factor catches it.
        let (passage, size) = chosen ?? (SharePassageText(verses: Array(verses.prefix(1)), numbered: style.verseNumbers),
                                         metrics.minFontSize)
        let shown = max(1, count)
        let keys = verses.prefix(shown).map(\.ref.key)
        let reference = VerseRange.ranges(from: keys, verseCount: verseCount).map(\.display).joined(separator: ", ")
        return Result(content: ShareCardContent(passage: passage, reference: reference, translation: info.abbreviation,
                                                notice: notice, fontSize: size),
                      shownVerses: shown, totalVerses: verses.count)
    }

    /// Public-domain texts carry no notice; a licensed translation's `copyright` rides on the card.
    static func noticeText(for info: TranslationInfo) -> String? { info.attributionNotice }

    private static func largestFittingSize(_ passage: SharePassageText, style: ShareStyle, metrics: ShareCardMetrics,
                                           height: CGFloat) -> CGFloat? {
        func fits(_ size: CGFloat) -> Bool {
            let text = measurementString(passage, size: size, style: style, metrics: metrics)
            let bounds = text.boundingRect(with: CGSize(width: metrics.textWidth, height: .greatestFiniteMagnitude),
                                           options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
            // A little slack for the difference between this measurement and SwiftUI's layout.
            return ceil(bounds.height) <= height * 0.95
        }
        var low = metrics.minFontSize
        var high = metrics.maxFontSize
        guard fits(low) else { return nil }
        if fits(high) { return high }
        for _ in 0..<12 {
            let mid = (low + high) / 2
            if fits(mid) { low = mid } else { high = mid }
        }
        return floor(low)
    }

    private static func measurementString(_ passage: SharePassageText, size: CGFloat, style: ShareStyle,
                                          metrics: ShareCardMetrics) -> NSAttributedString {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineSpacing = size * metrics.lineSpacingRatio
        paragraph.alignment = style.alignment == .center ? .center : .natural
        let text = NSMutableAttributedString(string: passage.text, attributes: [
            .font: style.family.font(size: size), .paragraphStyle: paragraph,
        ])
        let numberFont = style.family.font(size: size * ShareCard.numberScale)
        for range in passage.numbers {
            text.addAttributes([.font: numberFont, .baselineOffset: size * ShareCard.numberRise], range: range)
        }
        return text
    }
}

/// The card itself, laid out at `style.aspect.size` points. The designer shows it scaled down;
/// the export renders it at 2×.
struct ShareCard: View {
    static let numberScale: CGFloat = 0.55
    static let numberRise: CGFloat = 0.32

    let content: ShareCardContent
    let style: ShareStyle

    private var metrics: ShareCardMetrics { ShareCardMetrics(size: style.aspect.size) }
    private var template: ShareTemplate { style.template }

    var body: some View {
        let metrics = metrics
        let footer = style.wordmark || content.notice != nil
        ZStack {
            Rectangle().fill(template.backgroundStyle)
            if template.hasFrame {
                Rectangle()
                    .strokeBorder(color(template.accent).opacity(0.35), lineWidth: 1.5)
                    .padding(metrics.short * 0.035)
            }
            VStack(alignment: style.alignment.horizontal, spacing: 0) {
                Spacer(minLength: 0)
                Text(passageText)
                    .lineSpacing(content.fontSize * metrics.lineSpacingRatio)
                    .multilineTextAlignment(style.alignment.textAlignment)
                    .minimumScaleFactor(0.6)
                    .frame(maxWidth: .infinity, alignment: style.alignment.frameAlignment)
                    .layoutPriority(1)
                referenceBlock(metrics)
                Spacer(minLength: 0)
                if footer { footerView(metrics) }
            }
            .padding(.horizontal, metrics.horizontalPadding)
            .padding(.vertical, metrics.verticalPadding)
        }
        .frame(width: metrics.size.width, height: metrics.size.height)
        .environment(\.colorScheme, isDark ? .dark : .light)
    }

    private var isDark: Bool { [.ink, .night, .olive].contains(template) }

    private func color(_ hex: UInt32) -> Color { Color(PlatformColor(hex: hex)) }

    private var passageText: AttributedString {
        let size = content.fontSize
        let text = content.passage.text
        var result = AttributedString(text)
        result.font = Font(style.family.font(size: size) as CTFont)
        result.foregroundColor = color(template.ink)
        if style.redLetters {
            for range in content.passage.red {
                if let span = Range(range, in: result) { result[span].foregroundColor = color(template.red) }
            }
        }
        for range in content.passage.numbers {
            guard let span = Range(range, in: result) else { continue }
            result[span].font = Font(style.family.font(size: size * Self.numberScale) as CTFont)
            result[span].baselineOffset = size * Self.numberRise
            result[span].foregroundColor = color(template.accent)
        }
        return result
    }

    private func referenceBlock(_ metrics: ShareCardMetrics) -> some View {
        VStack(alignment: style.alignment.horizontal, spacing: 0) {
            Capsule()
                .fill(color(template.accent).opacity(0.7))
                .frame(width: metrics.ruleWidth, height: max(2, metrics.referenceSize * 0.08))
                .padding(.top, metrics.referenceSize * 1.2)
                .padding(.bottom, metrics.referenceSize * 0.9)
            Text("\(content.reference.uppercased())  ·  \(content.translation)")
                .font(Font(style.family.font(size: metrics.referenceSize).withTraits(bold: true) as CTFont))
                .tracking(metrics.referenceSize * 0.12)
                .foregroundStyle(color(template.accent))
                .lineLimit(2)
                .minimumScaleFactor(0.5)
                .multilineTextAlignment(style.alignment.textAlignment)
        }
        .frame(maxWidth: .infinity, alignment: style.alignment.frameAlignment)
    }

    private func footerView(_ metrics: ShareCardMetrics) -> some View {
        VStack(alignment: style.alignment.horizontal, spacing: 4) {
            if let notice = content.notice {
                Text(notice)
                    .font(.system(size: metrics.wordmarkSize * 0.7))
                    .lineLimit(2)
                    .minimumScaleFactor(0.6)
                    .foregroundStyle(color(template.ink).opacity(0.55))
            }
            if style.wordmark {
                Text("Scripture Alone")
                    .font(Font(style.family.font(size: metrics.wordmarkSize).withTraits(italic: true) as CTFont))
                    .foregroundStyle(color(template.accent).opacity(0.6))
            }
        }
        .multilineTextAlignment(style.alignment.textAlignment)
        .frame(maxWidth: .infinity, minHeight: metrics.wordmarkBlock, alignment: style.alignment == .center ? .bottom : .bottomLeading)
    }
}
