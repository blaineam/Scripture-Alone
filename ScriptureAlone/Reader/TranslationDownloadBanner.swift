import SwiftUI
import ScriptureAloneCore

/// "Downloading the Berean Standard Bible…", above the text while a translation the reader chose is
/// on its way.
///
/// The BSB and KJV are on-demand asset packs. Choosing one keeps the current translation on screen
/// and fetches the other; this says what is happening and how far along it is, and — if the
/// download fails, most often for want of a connection — says so and offers to try again, rather
/// than leaving the reader wondering why nothing changed.
struct TranslationDownloadBanner: View {
    @Environment(ReaderModel.self) private var model

    var body: some View {
        if let pack = model.downloadingPack ?? failedPack {
            HStack(spacing: 12) {
                icon(for: pack)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title(for: pack))
                        .font(.system(.subheadline, design: .serif).weight(.semibold))
                    detail(for: pack)
                }
                Spacer(minLength: 8)
                if case .failed = AssetLibrary.shared.state(of: pack) {
                    Button("Try Again") { model.selectTranslation(translationID(for: pack)) }
                        .font(.footnote.weight(.semibold))
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: 620)
            .glassEffect(.regular, in: .rect(cornerRadius: 20))
            .padding(.horizontal)
            .padding(.top, 4)
            .accessibilityElement(children: .combine)
        }
    }

    /// A translation download that failed stays visible until retried or another is chosen.
    private var failedPack: AssetPack? {
        AssetPack.translations.first {
            if case .failed = AssetLibrary.shared.state(of: $0) { return true }
            return false
        }
    }

    @ViewBuilder private func icon(for pack: AssetPack) -> some View {
        if case .failed = AssetLibrary.shared.state(of: pack) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange).accessibilityHidden(true)
        } else {
            Image(systemName: "arrow.down.circle.fill").font(.title3).foregroundStyle(.tint).accessibilityHidden(true)
        }
    }

    private func title(for pack: AssetPack) -> String {
        if case .failed = AssetLibrary.shared.state(of: pack) { return "Couldn’t download the \(pack.title)" }
        return "Downloading the \(pack.title)…"
    }

    @ViewBuilder private func detail(for pack: AssetPack) -> some View {
        switch AssetLibrary.shared.state(of: pack) {
        case .downloading(let fraction):
            ProgressView(value: fraction)
                .frame(maxWidth: 220)
                .accessibilityValue(Text(fraction, format: .percent.precision(.fractionLength(0))))
        case .failed(let message):
            Text(message).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
        default:
            Text(pack.explanation).font(.caption2).foregroundStyle(.secondary)
        }
    }

    private func translationID(for pack: AssetPack) -> String {
        pack.translationID ?? ReaderModel.defaultTranslation
    }
}
