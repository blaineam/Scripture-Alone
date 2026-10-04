import SwiftUI
import ScriptureAloneCore

/// The print guide's palette and type, adapted for light and dark.
enum GuideStyle {
    static let accent = adaptive(0xA0662A, dark: 0xE0A466)
    /// Text on an accent fill: white on the light accent, deep brown on the dark one.
    static let onAccent = adaptive(0xFFFFFF, dark: 0x24160A)
    static let accentSoft = adaptive(0xF4E6D4, dark: 0x3B2A1A)
    static let panel = adaptive(0xF3EFE9, dark: 0x2A2622)
    static let rule = adaptive(0xE6DDD2, dark: 0x3D3630)
    static let warn = adaptive(0xFBEAEA, dark: 0x3D2222)
    static let warnLabel = adaptive(0xAA3333, dark: 0xFF8F87)
    static let paper = adaptive(0xFFFFFF, dark: 0x1A1816)
    /// The drawn screens' grouped background, cells and separators.
    static let mockBackground = adaptive(0xF2F1F6, dark: 0x101012)
    static let mockCell = adaptive(0xFFFFFF, dark: 0x2C2C2E)
    static let mockSeparator = adaptive(0xD8D8DC, dark: 0x3A3A3C)
    static let mockFrame = adaptive(0x1B1714, dark: 0x5A5550)

    /// The body face: Iowan Old Style for Latin scripts, scaling with Dynamic Type. CJK text falls
    /// back to the system's own serif faces.
    static func serif(_ style: Font.TextStyle) -> Font {
        .custom("Iowan Old Style", size: size(of: style), relativeTo: style)
    }

    private static func size(of style: Font.TextStyle) -> CGFloat {
        switch style {
        case .largeTitle: 34
        case .title: 28
        case .title2: 22
        case .title3: 20
        case .headline, .body: 17
        case .callout: 16
        case .subheadline: 15
        case .footnote: 13
        case .caption: 12
        case .caption2: 11
        @unknown default: 17
        }
    }

    private static func adaptive(_ light: UInt32, dark: UInt32) -> Color {
        let light = PlatformColor(hex: light), dark = PlatformColor(hex: dark)
        // Sendable, so the system may resolve it off the main thread.
        #if os(iOS)
        return Color(UIColor { @Sendable traits in traits.userInterfaceStyle == .dark ? dark : light })
        #else
        return Color(NSColor(name: nil) { @Sendable appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
        })
        #endif
    }
}

extension Color {
    init(hex: UInt32) { self.init(PlatformColor(hex: hex)) }
}

// MARK: - Inline runs

enum GuideRuns {
    /// A paragraph's runs as one attributed string: bold, app controls as tinted chips, keys and
    /// code in a fixed-width face, fine print, and links that open in the browser.
    static func attributed(_ runs: [UserGuide.Run]) -> AttributedString {
        var result = AttributedString()
        for run in runs {
            if run.lineBreak {
                result += AttributedString("\n")
                continue
            }
            var text = run.text
            if run.ui || run.kbd {
                // A control's name stays on one line, with a little room inside its tint.
                text = "\u{202F}" + text.replacingOccurrences(of: " ", with: "\u{00A0}") + "\u{202F}"
            }
            var piece = AttributedString(text)
            if run.ui {
                piece.font = .system(.callout, weight: .semibold)
                piece.foregroundColor = GuideStyle.accent
                piece.backgroundColor = GuideStyle.accentSoft
            } else if run.kbd {
                piece.font = .system(.callout, design: .monospaced, weight: .semibold)
                piece.backgroundColor = GuideStyle.panel
            } else if run.code {
                piece.font = .system(.callout, design: .monospaced)
            } else if run.small {
                piece.font = .footnote
                piece.foregroundColor = .secondary
            }
            if run.bold {
                piece.inlinePresentationIntent = .stronglyEmphasized
                if run.ui { piece.font = .system(.callout, weight: .bold) }
            }
            if let link = run.link, let url = URL(string: link), ["https", "http", "mailto"].contains(url.scheme ?? "") {
                piece.link = url
                piece.foregroundColor = GuideStyle.accent
                piece.underlineStyle = Text.LineStyle(pattern: .solid, color: GuideStyle.accent.opacity(0.35))
            }
            result += piece
        }
        return result
    }
}

private struct RunsText: View {
    let runs: [UserGuide.Run]
    var font: Font = GuideStyle.serif(.body)

    var body: some View {
        Text(GuideRuns.attributed(runs))
            .font(font)
            .lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
    }
}

// MARK: - Blocks

struct GuideBlocks: View {
    let blocks: [UserGuide.Block]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                GuideBlock(block: block)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct GuideBlock: View {
    let block: UserGuide.Block

    var body: some View {
        switch block {
        case .paragraph(let runs, let fine):
            if fine {
                RunsText(runs: runs, font: GuideStyle.serif(.footnote))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            } else {
                RunsText(runs: runs)
            }
        case .heading(let runs):
            Text(GuideRuns.attributed(runs))
                .font(.title3.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)
                .accessibilityAddTraits(.isHeader)
                .accessibilityHeading(.h2)
        case .list(let items):
            GuideList(items: items)
        case .steps(let items):
            GuideSteps(items: items)
        case .table(let header, let rows):
            GuideTable(header: header, rows: rows)
        case .callout(let style, let label, let blocks):
            GuideCallout(style: style, label: label, blocks: blocks)
        case .figure(let figure):
            GuideFigure(figure: figure)
                .frame(maxWidth: .infinity)
        case .feature(let figure, let mock, let blocks, let flip):
            GuideFeature(figure: figure, mock: mock, blocks: blocks, flip: flip)
        case .mock(let mock):
            GuideMock(mock: mock)
                .frame(maxWidth: .infinity)
        case .unknown:
            EmptyView()
        }
    }
}

private struct GuideList: View {
    let items: [[UserGuide.Run]]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text("•")
                        .font(GuideStyle.serif(.body).weight(.bold))
                        .foregroundStyle(GuideStyle.accent)
                        .accessibilityHidden(true)
                    RunsText(runs: item)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }
}

private struct GuideSteps: View {
    let items: [[UserGuide.Block]]
    @ScaledMetric(relativeTo: .subheadline) private var badge: CGFloat = 26

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(Array(items.enumerated()), id: \.offset) { offset, blocks in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(verbatim: "\(offset + 1)")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(GuideStyle.onAccent)
                        .frame(width: badge, height: badge)
                        .background(Circle().fill(GuideStyle.accent))
                        .accessibilityLabel(Text(verbatim: "\(offset + 1)."))
                    GuideBlocks(blocks: blocks)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

private struct GuideCallout: View {
    let style: UserGuide.CalloutStyle
    let label: String
    let blocks: [UserGuide.Block]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !label.isEmpty {
                Text(label)
                    .font(.caption.weight(.bold))
                    .textCase(.uppercase)
                    .tracking(1.2)
                    .foregroundStyle(style == .warn ? GuideStyle.warnLabel : GuideStyle.accent)
                    .accessibilityAddTraits(.isHeader)
            }
            GuideBlocks(blocks: blocks)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(fill))
        .padding(.vertical, 4)
    }

    private var fill: Color {
        switch style {
        case .tip: GuideStyle.accentSoft
        case .note: GuideStyle.panel
        case .warn: GuideStyle.warn
        }
    }
}

// MARK: Tables

/// A grid on a wide column; on a narrow one each row becomes a card, so nothing truncates.
private struct GuideTable: View {
    let header: [[UserGuide.Run]]
    let rows: [[[UserGuide.Run]]]
    @Environment(\.guideWide) private var wide

    var body: some View {
        if wide {
            grid
        } else {
            cards
        }
    }

    private var grid: some View {
        Grid(alignment: .topLeading, horizontalSpacing: 16, verticalSpacing: 10) {
            if !header.isEmpty {
                GridRow {
                    ForEach(Array(header.enumerated()), id: \.offset) { _, cell in
                        Text(GuideRuns.attributed(cell))
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }
                Divider().overlay(GuideStyle.rule)
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                GridRow {
                    ForEach(Array(row.enumerated()), id: \.offset) { column, cell in
                        RunsText(runs: cell, font: column == 0 ? .body.weight(.medium) : GuideStyle.serif(.body))
                    }
                }
                Divider().overlay(GuideStyle.rule)
            }
        }
        .padding(.vertical, 4)
    }

    private var cards: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { offset, row in
                VStack(alignment: .leading, spacing: 6) {
                    if let first = row.first {
                        RunsText(runs: first, font: .body.weight(.semibold))
                    }
                    ForEach(Array(row.dropFirst().enumerated()), id: \.offset) { index, cell in
                        VStack(alignment: .leading, spacing: 1) {
                            // With three or more columns, each value says which column it is.
                            if row.count > 2, header.indices.contains(index + 1) {
                                Text(GuideRuns.attributed(header[index + 1]))
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                    .textCase(.uppercase)
                            }
                            RunsText(runs: cell)
                        }
                    }
                }
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                if offset < rows.count - 1 { Divider().overlay(GuideStyle.rule) }
            }
        }
        .padding(.horizontal, 14)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GuideStyle.panel))
        .padding(.vertical, 4)
    }
}

// MARK: Figures

private struct GuideFigure: View {
    let figure: UserGuide.Figure
    @Environment(UserGuideStore.self) private var store

    var body: some View {
        VStack(spacing: 8) {
            if let image = store.image(named: figure.image) {
                image
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: figure.device == .watch ? 150 : 240)
            }
            if !figure.caption.isEmpty {
                Text(figure.caption)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 240)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(figure.caption)
        .accessibilityAddTraits(.isImage)
    }
}

/// Words beside a screenshot or a drawn screen: side by side when there's room, the picture
/// below the words when there isn't.
private struct GuideFeature: View {
    let figure: UserGuide.Figure?
    let mock: UserGuide.Mock?
    let blocks: [UserGuide.Block]
    let flip: Bool
    @Environment(\.guideWide) private var wide

    var body: some View {
        if figure == nil && mock == nil {
            GuideBlocks(blocks: blocks)
        } else if wide {
            HStack(alignment: .top, spacing: 28) {
                if flip { picture }
                GuideBlocks(blocks: blocks)
                if !flip { picture }
            }
            .padding(.vertical, 6)
        } else {
            VStack(alignment: .leading, spacing: 16) {
                GuideBlocks(blocks: blocks)
                picture.frame(maxWidth: .infinity)
            }
            .padding(.vertical, 6)
        }
    }

    @ViewBuilder private var picture: some View {
        if let figure {
            GuideFigure(figure: figure)
                .frame(width: wide ? (figure.device == .watch ? 160 : 220) : nil)
        } else if let mock {
            GuideMock(mock: mock)
                .frame(width: wide ? 280 : nil)
        }
    }
}

// MARK: Drawn screens

/// A grouped list drawn natively inside a device-like frame, so a setup screen reads in any
/// language and at any text size.
private struct GuideMock: View {
    let mock: UserGuide.Mock

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            bar
            ForEach(Array(mock.sections.enumerated()), id: \.offset) { _, section in
                VStack(alignment: .leading, spacing: 5) {
                    if let header = section.header, !header.isEmpty {
                        Text(header)
                            .font(.caption2)
                            .textCase(.uppercase)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 10)
                    }
                    VStack(spacing: 0) {
                        ForEach(Array(section.rows.enumerated()), id: \.offset) { index, row in
                            MockRow(row: row)
                            if index < section.rows.count - 1 {
                                Rectangle().fill(GuideStyle.mockSeparator).frame(height: 0.5)
                                    .padding(.leading, 12)
                            }
                        }
                    }
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GuideStyle.mockCell))
                    if let footer = section.footer, !footer.isEmpty {
                        Text(footer)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 10)
                    }
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.top, 14)
        .padding(.bottom, 16)
        .frame(maxWidth: 300)
        .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(GuideStyle.mockBackground))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(GuideStyle.mockFrame, lineWidth: 3))
        .accessibilityElement(children: .contain)
    }

    private var bar: some View {
        HStack(spacing: 6) {
            Text(mock.bar.leading)
                .foregroundStyle(GuideStyle.accent)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(mock.bar.title)
                .font(.footnote.weight(.semibold))
                .layoutPriority(1)
            Text(mock.bar.trailing)
                .fontWeight(.semibold)
                .foregroundStyle(GuideStyle.accent)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .font(.footnote)
        .lineLimit(1)
        .minimumScaleFactor(0.8)
        .padding(.horizontal, 6)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

private struct MockRow: View {
    let row: UserGuide.Mock.Row

    var body: some View {
        HStack(spacing: 8) {
            if let icon = row.icon, !icon.isEmpty {
                Text(icon)
                    .frame(minWidth: 16)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(row.text)
                    .foregroundStyle(color)
                if let detail = row.detail, !detail.isEmpty {
                    Text(detail)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            if row.checked == true {
                Image(systemName: "checkmark")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(GuideStyle.accent)
                    .accessibilityHidden(true)
            }
        }
        .font(.footnote)
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background {
            if row.highlight == true {
                RoundedRectangle(cornerRadius: 8, style: .continuous).fill(GuideStyle.accentSoft)
            }
        }
        .overlay {
            if row.highlight == true {
                RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(GuideStyle.accent, lineWidth: 2)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(row.checked == true ? .isSelected : [])
    }

    private var color: Color {
        switch row.style {
        case .plain: .primary
        case .link: GuideStyle.accent
        case .field: Color.secondary.opacity(0.7)
        case .destructive: .red
        }
    }
}
