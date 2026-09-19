import SwiftUI
import ScriptureAloneCore

/// A compact band of the Bible's eras with the chapter's place marked. Eras get equal widths
/// (the span from Abraham to the apostles is too uneven to draw to scale at this size).
struct EraBand: View {
    let eras: [Era]
    let time: ChapterTime?

    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width / CGFloat(max(eras.count, 1))
            ZStack(alignment: .topLeading) {
                HStack(spacing: 2) {
                    ForEach(eras) { era in
                        let current = time?.eras.contains(era) == true
                        let primary = time?.era == era
                        RoundedRectangle(cornerRadius: 3, style: .continuous)
                            .fill(Color(contextHex: era.color).opacity(primary ? 1 : current ? 0.6 : 0.22))
                            .frame(height: primary ? 18 : 12)
                            .frame(maxHeight: 18, alignment: .center)
                    }
                }
                if let x = markerPosition(segmentWidth: width) {
                    Image(systemName: "arrowtriangle.down.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(.primary)
                        .position(x: x, y: -6)
                }
            }
        }
        .frame(height: 18)
        .padding(.top, 10)
    }

    private func markerPosition(segmentWidth: CGFloat) -> CGFloat? {
        guard let time, let index = eras.firstIndex(of: time.era) else { return nil }
        var fraction = 0.5
        if let year = time.year, let start = time.era.start, let end = time.era.end, end > start {
            fraction = min(max(Double(year - start) / Double(end - start), 0.08), 0.92)
        }
        return segmentWidth * (CGFloat(index) + fraction)
    }
}

/// "When": the chapter's era on a compact timeline, its date and events.
struct ContextWhenSection: View {
    let chapter: ChapterRef
    var openTimeline: () -> Void

    private var library: ContextLibrary { .shared }

    var body: some View {
        let time = library.time(for: chapter)
        let events = library.events(in: chapter)
        VStack(alignment: .leading, spacing: 12) {
            Button(action: openTimeline) {
                VStack(alignment: .leading, spacing: 8) {
                    EraBand(eras: library.eras, time: time)
                    if let time {
                        HStack(alignment: .firstTextBaseline) {
                            Text(time.era.name).font(.headline)
                            Spacer()
                            Text(time.era.dates).font(.subheadline).foregroundStyle(.secondary)
                        }
                        if let year = time.yearLabel {
                            Label(time.basis == .events ? "\(chapter.display): \(year)" : "Written \(year)",
                                  systemImage: time.basis == .events ? "clock" : "pencil.and.scribble")
                                .font(.subheadline)
                        }
                        if time.eras.count > 1 {
                            Text("Also spans: " + time.eras.dropFirst().map(\.name).joined(separator: ", "))
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        if let note = time.note {
                            Text(note).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityLabel(time))
            .accessibilityHint("Opens the full timeline.")
            .accessibilityAddTraits(.isButton)

            if !events.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(events) { event in
                        EventRow(event: event, compact: true)
                    }
                }
            }
        }
    }

    private func accessibilityLabel(_ time: ChapterTime?) -> String {
        guard let time else { return "Timeline" }
        var parts = ["Timeline. \(time.era.name), \(time.era.dates)."]
        if let year = time.yearLabel {
            parts.append(time.basis == .events ? "This chapter: \(year)." : "Written \(year).")
        }
        return parts.joined(separator: " ")
    }
}

/// One event, tappable to read its passage.
struct EventRow: View {
    let event: TimelineEvent
    var compact = false

    var body: some View {
        if let range = event.range {
            Button { ReadingFocus.shared.jump(to: range.start) } label: { content }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityHint("Opens the passage in the reader.")
        } else {
            content.accessibilityElement(children: .combine)
        }
    }

    private var content: some View {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(event.date ?? "—")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(minWidth: compact ? 70 : 92, alignment: .leading)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        Text(event.name).font(compact ? .subheadline : .body)
                        if event.debated {
                            Image(systemName: "questionmark.circle")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .accessibilityLabel("Date debated")
                        }
                    }
                    if let range = event.range {
                        Text(range.display).font(.caption).foregroundStyle(.tint)
                    }
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
    }
}

/// The whole story, era by era, with the reader's chapter marked.
struct FullTimelineView: View {
    let chapter: ChapterRef

    private var library: ContextLibrary { .shared }
    @State private var expandedDebates: Set<String> = []

    var body: some View {
        let time = library.time(for: chapter)
        let chapterEvents = Set(library.events(in: chapter).map(\.id))
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Section {
                        ForEach(library.eras) { era in
                            eraCard(era, time: time, chapterEvents: chapterEvents)
                                .id(era.id)
                        }
                        Text("Dates before the divided kingdom are approximate; ? marks dates that scholars dispute. Sources are listed under Sources & Credits.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.top, 4)
                    } header: {
                        EraBand(eras: library.eras, time: time)
                            .padding(.vertical, 8)
                            .background(.background)
                            .accessibilityHidden(true)
                    }
                }
                .padding()
            }
            .onAppear {
                if let id = time?.era.id { proxy.scrollTo(id, anchor: .top) }
            }
        }
    }

    private func eraCard(_ era: Era, time: ChapterTime?, chapterEvents: Set<Int>) -> some View {
        let current = time?.era == era
        let events = library.events.filter { $0.eraID == era.id }
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(era.name).font(.title3.weight(.semibold))
                Spacer()
                Text(era.dates).font(.subheadline).foregroundStyle(.secondary)
            }
            if current, let time {
                Label(time.yearLabel.map { "\(chapter.display) — \($0)" } ?? "\(chapter.display) is set here",
                      systemImage: "bookmark.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color(contextHex: era.color))
            }
            Text(era.summary).font(.subheadline)
            ForEach(events) { event in
                EventRow(event: event)
                    .padding(.vertical, 2)
                    .background(chapterEvents.contains(event.id)
                                ? Color(contextHex: era.color).opacity(0.14) : .clear,
                                in: RoundedRectangle(cornerRadius: 6))
            }
            if !era.debate.isEmpty {
                DisclosureGroup(isExpanded: Binding(
                    get: { expandedDebates.contains(era.id) },
                    set: { if $0 { expandedDebates.insert(era.id) } else { expandedDebates.remove(era.id) } })) {
                    Text(era.debate).font(.footnote).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } label: {
                    Text("About these dates").font(.footnote.weight(.semibold))
                }
            }
        }
        .padding(14)
        .background(.quaternary.opacity(current ? 0.9 : 0.4), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(alignment: .leading) {
            UnevenRoundedRectangle(topLeadingRadius: 14, bottomLeadingRadius: 14, style: .continuous)
                .fill(Color(contextHex: era.color))
                .frame(width: 5)
        }
        .overlay {
            if current {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(Color(contextHex: era.color), lineWidth: 1.5)
            }
        }
    }
}
