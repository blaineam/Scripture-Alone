import SwiftUI
import MillerKit

/// Reading preferences, with the chapter visible behind the sheet as a live preview.
struct AppearanceView: View {
    @Environment(ReaderModel.self) private var model
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
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Form {
            Section {
                HStack(spacing: 10) {
                    ForEach(ReaderTheme.allCases) { option in
                        themeSwatch(option)
                    }
                }
                .padding(.vertical, 4)
            }

            Section {
                HStack(spacing: 12) {
                    ForEach(ReaderAccent.allCases) { option in
                        accentSwatch(option)
                    }
                }
                .padding(.vertical, 2)
            } header: {
                Text("Accent")
            } footer: {
                Text("Colours verse numbers, links and the app’s controls.")
            }

            Section("Text") {
                HStack {
                    Button { fontSize = max(12, fontSize - 1) } label: { Image(systemName: "textformat.size.smaller") }
                        .accessibilityLabel("Smaller")
                    Slider(value: $fontSize, in: 12...40, step: 1)
                        .accessibilityLabel("Text Size")
                        .accessibilityValue("\(Int(fontSize)) points")
                    Button { fontSize = min(40, fontSize + 1) } label: { Image(systemName: "textformat.size.larger") }
                        .accessibilityLabel("Larger")
                }
                .buttonStyle(.borderless)
                HStack {
                    Image(systemName: "line.3.horizontal").foregroundStyle(.secondary)
                    Slider(value: $lineSpacing, in: 1.0...2.0)
                        .accessibilityLabel("Line Spacing")
                    Image(systemName: "line.3.horizontal.decrease").rotationEffect(.degrees(180)).foregroundStyle(.secondary)
                }
                Picker("Layout", selection: $layout) {
                    ForEach(ReadingLayout.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
            }

            Section("Font") {
                ForEach(FontFamily.allCases) { option in
                    Button { family = option } label: {
                        HStack {
                            Text(option.title).font(option.swiftUIFont(size: 18))
                            Spacer()
                            if option == family { Image(systemName: "checkmark").foregroundStyle(.tint) }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }

            Section("Show") {
                Toggle("Words of Christ in Red", isOn: $redLetters)
                Toggle("Verse Numbers", isOn: $verseNumbers)
                Toggle("Section Headings", isOn: $headings)
                Toggle("Footnotes", isOn: $footnotes)
            }

            Section { LegacyAndExportRow() }

            SupportSection(app: .scriptureAlone,
                           extraContext: ["Translation": model.source?.info.id ?? "—"])
            LoveThisAppSection(app: .scriptureAlone)
            AboutSection(app: .scriptureAlone)

            if let info = model.source?.info {
                Section("About This Translation") {
                    Text(info.name).font(.headline)
                    Text(info.copyright).font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .formStyle(.grouped)
    }

    private func accentSwatch(_ option: ReaderAccent) -> some View {
        let chosen = option == accent
        return Button { accent = option } label: {
            Circle()
                .fill(option.swatch)
                .frame(width: 30, height: 30)
                .overlay(
                    Circle().strokeBorder(.primary.opacity(chosen ? 0.55 : 0.12),
                                          lineWidth: chosen ? 2.5 : 1))
                .overlay {
                    if chosen {
                        Image(systemName: "checkmark")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .shadow(radius: 1)
                    }
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(option.title) accent")
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }

    private func themeSwatch(_ option: ReaderTheme) -> some View {
        let palette = option.palette(for: colorScheme)
        return Button { theme = option } label: {
            VStack(spacing: 6) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color(palette.page))
                    Text("Aa").font(.system(size: 17, design: .serif)).foregroundStyle(Color(palette.ink))
                }
                .frame(height: 48)
                .overlay(RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(option == theme ? Color.accentColor : .primary.opacity(0.15), lineWidth: option == theme ? 2.5 : 1))
                Text(option.title).font(.caption)
            }
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
        .accessibilityLabel("\(option.title) theme")
        .accessibilityAddTraits(option == theme ? .isSelected : [])
    }
}
