import SwiftUI
import ScriptureAloneCore

/// "Where": a map of the chapter's places and a list of them.
struct ContextWhereSection: View {
    let chapter: ChapterRef
    let verse: Int?
    var openMap: () -> Void
    @Binding var selectedPlace: Place?

    var body: some View {
        let mentions = ContextLibrary.shared.places(in: chapter)
        let pins = pins(for: mentions)
        VStack(alignment: .leading, spacing: 12) {
            if mentions.isEmpty {
                Text("No places are named in \(chapter.display).")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("Open the Map", systemImage: "map", action: openMap)
                    .font(.subheadline)
            } else {
                BibleMapView(content: MapContent(pins: pins, selectedID: selectedPlace?.id,
                                                 fitRect: MapContent.fitRect(for: pins.filter { !$0.isArea || pins.count < 3 })),
                             fitToken: chapter) { id in
                    selectedPlace = mentions.first { $0.place.id == id }?.place
                        ?? ((try? ContextLibrary.shared.store?.place(id: id)) ?? nil)
                }
                .frame(height: 260)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    Button(action: openMap) {
                        Image(systemName: "arrow.up.left.and.arrow.down.right").frame(width: 18, height: 18)
                    }
                    .buttonStyle(.glass)
                    .controlSize(.small)
                    .padding(10)
                    .accessibilityLabel("Open Large Map")
                }

                VStack(spacing: 0) {
                    ForEach(mentions) { mention in
                        Button { selectedPlace = mention.place } label: {
                            PlaceRow(place: mention.place, verses: mention.verses, highlightVerse: verse)
                        }
                        .buttonStyle(.plain)
                        if mention.id != mentions.last?.id { Divider() }
                    }
                }
            }
        }
    }

    /// Chapter places, the ones in the reader's verse listed first so they win label space.
    private func pins(for mentions: [PlaceMention]) -> [MapPin] {
        let sorted = mentions.sorted { a, b in
            let aHere = verse.map { v in a.verses.contains { $0 % 1000 == v } } ?? false
            let bHere = verse.map { v in b.verses.contains { $0 % 1000 == v } } ?? false
            return aHere && !bHere
        }
        return sorted.map { MapPin(place: $0.place) }
    }
}

struct PlaceRow: View {
    let place: Place
    let verses: [Int]
    var highlightVerse: Int?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .font(.body)
                .foregroundStyle(.tint)
                .frame(width: 22)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(place.name).font(.body.weight(.semibold))
                    if !place.modernName.isEmpty {
                        Text(place.modernName).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Text(versesText).font(.caption).foregroundStyle(.secondary)
                if place.confidenceLevel != .identified {
                    ConfidenceBadge(place: place)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("Shows every verse that mentions \(place.name).")
    }

    private var symbol: String {
        switch place.kind {
        case .settlement: "building.columns"
        case .region: "square.dashed"
        case .island: "circle.dashed"
        case .water: "drop"
        case .mountain: "mountain.2"
        case .site: "mappin"
        }
    }

    private var versesText: String {
        let numbers = verses.map { $0 % 1000 }
        let list = numbers.map(String.init).joined(separator: ", ")
        return numbers.count == 1 ? "Verse \(list)" : "Verses \(list)"
    }
}

struct ConfidenceBadge: View {
    let place: Place

    var body: some View {
        Label(text, systemImage: place.confidenceLevel == .uncertain ? "questionmark.diamond" : "scope")
            .font(.caption2.weight(.semibold))
            .foregroundStyle(place.confidenceLevel == .uncertain ? .orange : .secondary)
    }

    private var text: String {
        var parts = [place.confidenceLevel.title]
        if place.isArea && place.kind != .region { parts.append("approximate area") }
        return parts.joined(separator: " · ")
    }
}

/// Every verse that mentions a place, with a small map. Tap a verse to read it.
struct PlaceDetailView: View {
    let place: Place
    @Environment(\.dismiss) private var dismiss
    @State private var verses: [Int] = []

    var body: some View {
        NavigationStack {
            List {
                Section {
                    BibleMapView(content: MapContent(pins: [MapPin(place: place)], selectedID: place.id,
                                                     fitRect: MapProjection.fit([MapPin(place: place).point], minSpan: 3.2)),
                                 fitToken: place.id, showsControls: false)
                        .frame(height: 180)
                        .listRowInsets(EdgeInsets())
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 6) {
                        if !place.modernName.isEmpty {
                            LabeledContent("Identified with", value: place.modernName)
                        }
                        LabeledContent("Type", value: place.type.capitalized)
                        LabeledContent("Confidence") { ConfidenceBadge(place: place) }
                        if place.alternatives > 0 {
                            Text(place.alternatives == 1
                                 ? "One other location has been proposed."
                                 : "\(place.alternatives) other locations have been proposed.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        if let url = place.sourceURL {
                            Link("Evidence at OpenBible.info", destination: url).font(.footnote)
                        }
                    }
                    .font(.subheadline)
                }
                ForEach(groupedVerses, id: \.0) { book, keys in
                    Section("\(book.name) (\(keys.count))") {
                        ForEach(keys, id: \.self) { key in
                            VerseMentionRow(key: key) {
                                if let ref = VerseRef(key: key) {
                                    ReadingFocus.shared.jump(to: ref)
                                    dismiss()
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(place.name)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .task(id: place.id) {
                verses = (try? ContextLibrary.shared.store?.verses(mentioning: place.id)) ?? []
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 520)
        #endif
    }

    private var groupedVerses: [(BookID, [Int])] {
        var groups: [(BookID, [Int])] = []
        for key in verses {
            guard let ref = VerseRef(key: key) else { continue }
            if groups.last?.0 == ref.book { groups[groups.count - 1].1.append(key) } else { groups.append((ref.book, [key])) }
        }
        return groups
    }
}

private struct VerseMentionRow: View {
    let key: Int
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 3) {
                Text(VerseRef(key: key)?.display ?? "").font(.subheadline.weight(.semibold))
                if let text = VersePreview.shared.text(for: key) {
                    Text(text).font(.subheadline).foregroundStyle(.secondary).lineLimit(3)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens this verse in the reader.")
    }
}
