import SwiftUI
import ScriptureAloneCore

/// Card linking to a chart.
struct ChartCard: View {
    let chart: ChartInfo
    /// Standalone cards draw their own background and chevron; list rows already have both.
    var framed = true

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: ChartView.symbol(chart.kind))
                .font(.title3)
                .foregroundStyle(.tint)
                .frame(width: 36, height: 36)
                .background(.tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(chart.title).font(.subheadline.weight(.semibold))
                Text(chart.subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if framed {
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .padding(framed ? 10 : 0)
        .background(framed ? AnyShapeStyle(.quaternary.opacity(0.5)) : AnyShapeStyle(.clear),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// Any chart, by kind.
struct ChartView: View {
    let chart: ChartInfo
    let chapter: ChapterRef

    static func symbol(_ kind: ChartKind) -> String {
        switch kind {
        case .kings: "crown"
        case .journeys: "sailboat"
        case .tribes: "person.3"
        case .feasts: "calendar"
        }
    }

    var body: some View {
        Group {
            switch chart.kind {
            case .kings:
                if let body = try? chart.decode(KingsChart.self) { KingsChartView(chart: body, chapter: chapter) }
            case .journeys:
                if let body = try? chart.decode(JourneysChart.self) { JourneysChartView(chart: body, chapter: chapter) }
            case .tribes:
                if let body = try? chart.decode(TribesChart.self) { TribesChartView(chart: body) }
            case .feasts:
                if let body = try? chart.decode(FeastsChart.self) { FeastsChartView(chart: body) }
            }
        }
        .safeAreaInset(edge: .bottom) {
            Text(chart.sources)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal)
                .padding(.vertical, 8)
                .background(.bar)
        }
        .navigationTitle(chart.title)
    }
}

/// A tappable reference that opens the passage in the reader.
struct ReferenceButton: View {
    let range: VerseRange
    var label: String?

    var body: some View {
        Button(label ?? range.display) { ReadingFocus.shared.jump(to: range.start) }
            .font(.caption.weight(.medium))
            .buttonStyle(.borderless)
            .accessibilityLabel("Read \(range.display)")
    }
}

// MARK: - Kings

struct KingsChartView: View {
    let chart: KingsChart
    let chapter: ChapterRef
    @State private var kingdom = Kingdom.israel
    @State private var width: CGFloat = 0
    /// Israel and Judah side by side when there is room.
    private var wide: Bool { width >= 640 }

    enum Kingdom: String, CaseIterable, Identifiable {
        case united = "United", israel = "Israel (north)", judah = "Judah (south)"
        var id: String { rawValue }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                legend
                column(.united)
                if wide {
                    HStack(alignment: .top, spacing: 16) {
                        column(.israel)
                        column(.judah)
                    }
                } else {
                    Picker("Kingdom", selection: $kingdom) {
                        Text("Israel").tag(Kingdom.israel)
                        Text("Judah").tag(Kingdom.judah)
                    }
                    .pickerStyle(.segmented)
                    column(kingdom)
                }
            }
            .padding()
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .onAppear {
            if chapter.book == .secondChronicles || current(in: chart.judah) != nil && current(in: chart.israel) == nil {
                kingdom = .judah
            }
        }
    }

    private var legend: some View {
        HStack(spacing: 14) {
            ForEach([KingsChart.King.Verdict.good, .mixed, .evil], id: \.self) { verdict in
                Label(Self.title(verdict), systemImage: Self.symbol(verdict))
                    .foregroundStyle(Self.color(verdict))
            }
        }
        .font(.caption.weight(.semibold))
        .accessibilityElement(children: .combine)
    }

    private func column(_ kingdom: Kingdom) -> some View {
        let kings = switch kingdom {
        case .united: chart.united
        case .israel: chart.israel
        case .judah: chart.judah
        }
        let here = current(in: kings)
        return VStack(alignment: .leading, spacing: 8) {
            Text(kingdom == .united ? "United Kingdom" : kingdom.rawValue)
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            ForEach(kings) { king in
                KingRow(king: king, kingdom: kingdom == .united ? "Israel" : kingdom == .israel ? "Israel" : "Judah",
                        isCurrent: king.id == here?.id)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// While reading 1–2 Kings, the king whose account the chapter falls in.
    private func current(in kings: [KingsChart.King]) -> KingsChart.King? {
        guard [.firstKings, .secondKings].contains(chapter.book) else { return nil }
        // Solomon's account ends with 1 Kings 11; after that only the divided kingdoms apply.
        if kings == chart.united && ChapterRef(.firstKings, 11) < chapter { return nil }
        let end = chapter.keyRange.upperBound
        return kings.filter { [.firstKings, .secondKings].contains($0.ref.range.start.book) && $0.ref.range.start.key <= end }
            .max { $0.ref.range.start < $1.ref.range.start }
    }

    static func title(_ verdict: KingsChart.King.Verdict) -> String {
        switch verdict {
        case .good: "Did right"
        case .evil: "Did evil"
        case .mixed: "Mixed"
        }
    }

    static func symbol(_ verdict: KingsChart.King.Verdict) -> String {
        switch verdict {
        case .good: "checkmark.circle.fill"
        case .evil: "xmark.circle.fill"
        case .mixed: "circle.lefthalf.filled"
        }
    }

    static func color(_ verdict: KingsChart.King.Verdict) -> Color {
        switch verdict {
        case .good: Color(contextHex: "#2E8B57")
        case .evil: Color(contextHex: "#C0392B")
        case .mixed: Color(contextHex: "#C98A1B")
        }
    }
}

private struct KingRow: View {
    let king: KingsChart.King
    let kingdom: String
    let isCurrent: Bool

    var body: some View {
        Button { ReadingFocus.shared.jump(to: king.ref.range.start) } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: KingsChartView.symbol(king.verdict))
                    .foregroundStyle(KingsChartView.color(king.verdict))
                    .font(.body)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(king.name).font(.subheadline.weight(.semibold))
                        Spacer(minLength: 4)
                        Text(king.reign).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    Text(yearsText + " · " + king.ref.range.start.display)
                        .font(.caption).foregroundStyle(.secondary)
                    if let prophets = king.prophets, !prophets.isEmpty {
                        Label(prophets.joined(separator: ", "), systemImage: "megaphone")
                            .font(.caption)
                            .foregroundStyle(.tint)
                    }
                    if let note = king.note {
                        Text(note).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(isCurrent ? AnyShapeStyle(.tint.opacity(0.14)) : AnyShapeStyle(.quaternary.opacity(0.45)),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay {
                if isCurrent {
                    RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(.tint, lineWidth: 1.5)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityHint("Opens \(king.ref.range.start.display).")
    }

    private var yearsText: String {
        Int(king.years) != nil ? "\(king.years) years" : king.years
    }

    private var accessibilityText: String {
        var parts = ["\(king.name), \(kingdom), \(king.reign), \(yearsText). \(KingsChartView.title(king.verdict))."]
        if let prophets = king.prophets, !prophets.isEmpty { parts.append("Prophets: \(prophets.joined(separator: ", ")).") }
        if let note = king.note { parts.append(note) }
        if isCurrent { parts.append("The chapter you are reading.") }
        return parts.joined(separator: " ")
    }
}

// MARK: - Journeys

struct JourneysChartView: View {
    let chart: JourneysChart
    let chapter: ChapterRef
    @State private var selected: String = "first"

    var body: some View {
        let journey = chart.journeys.first { $0.id == selected } ?? chart.journeys[0]
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Picker("Journey", selection: $selected) {
                    ForEach(chart.journeys) { Text(short($0)).tag($0.id) }
                }
                .pickerStyle(.segmented)

                BibleMapView(content: content(for: journey), fitToken: selected)
                    .frame(height: 340)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

                HStack(alignment: .firstTextBaseline) {
                    Text(journey.name).font(.headline)
                    Spacer()
                    Text(journey.dates).font(.subheadline).foregroundStyle(.secondary)
                }
                ReferenceButton(range: journey.refs.range)

                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(journey.stops.enumerated()), id: \.offset) { index, stop in
                        Button { ReadingFocus.shared.jump(to: stop.ref.range.start) } label: {
                            HStack(alignment: .top, spacing: 10) {
                                Text("\(index + 1)")
                                    .font(.caption.weight(.bold).monospacedDigit())
                                    .foregroundStyle(.white)
                                    .frame(width: 22, height: 22)
                                    .background(Color(contextHex: journey.color), in: Circle())
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(stop.name).font(.subheadline.weight(.semibold))
                                    if let note = stop.note { Text(note).font(.caption).foregroundStyle(.secondary) }
                                }
                                Spacer()
                                Text(stop.ref.range.start.display).font(.caption).foregroundStyle(.tint)
                            }
                            .padding(.vertical, 6)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityElement(children: .combine)
                        .accessibilityHint("Opens the passage in the reader.")
                    }
                }
            }
            .padding()
        }
        .onAppear { selected = initialJourney() }
    }

    private func short(_ journey: JourneysChart.Journey) -> String {
        switch journey.id {
        case "first": "1st"
        case "second": "2nd"
        case "third": "3rd"
        default: "Rome"
        }
    }

    private func initialJourney() -> String {
        guard chapter.book == .acts else { return chart.journeys[0].id }
        return chart.journeys.first { $0.refs.range.overlaps(chapter) }?.id
            ?? (chapter.chapter > 21 ? "rome" : chart.journeys[0].id)
    }

    private func content(for journey: JourneysChart.Journey) -> MapContent {
        let color = Color(contextHex: journey.color)
        let points = journey.stops.map { MapProjection.point(lon: $0.lon, lat: $0.lat) }
        let pins = journey.stops.map { stop in
            MapPin(id: stop.id, name: stop.name, lon: stop.lon, lat: stop.lat, kind: stop.kind,
                   isArea: stop.kind == .region, tint: color)
        }
        return MapContent(pins: pins, routes: [MapRoute(id: journey.id, name: journey.name, points: points, color: color)],
                          showsBackgroundPlaces: false, fitRect: MapProjection.fit(points))
    }
}

// MARK: - Tribes

struct TribesChartView: View {
    let chart: TribesChart

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                BibleMapView(content: mapContent, fitToken: 0)
                    .frame(height: 420)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                Text("Label positions mark the approximate center of each allotment (Joshua 13–19).")
                    .font(.caption).foregroundStyle(.secondary)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: 12)], spacing: 12) {
                    ForEach(chart.tribes) { tribe in TribeCard(tribe: tribe) }
                }
            }
            .padding()
        }
    }

    private var mapContent: MapContent {
        var tags: [MapTag] = []
        for tribe in chart.tribes {
            let name = tribe.name.replacingOccurrences(of: "Joseph: ", with: "")
            if let lon = tribe.lon, let lat = tribe.lat { tags.append(MapTag(text: name, point: MapProjection.point(lon: lon, lat: lat))) }
            if let lon = tribe.lon2, let lat = tribe.lat2 { tags.append(MapTag(text: name, point: MapProjection.point(lon: lon, lat: lat))) }
        }
        let fit = MapProjection.rect(minLon: 34.6, minLat: 31.0, maxLon: 36.2, maxLat: 33.3)
        return MapContent(tags: tags, showsBackgroundPlaces: false, fitRect: fit)
    }
}

private struct TribeCard: View {
    let tribe: TribesChart.Tribe

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(tribe.name).font(.headline)
                Spacer()
                Text("Son \(tribe.order) · \(tribe.mother)").font(.caption).foregroundStyle(.secondary)
            }
            if let note = tribe.note { Text(note).font(.caption).foregroundStyle(.secondary) }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) { links }
                VStack(alignment: .leading, spacing: 4) { links }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder private var links: some View {
        ReferenceButton(range: tribe.birth.range, label: "Birth")
        ReferenceButton(range: tribe.jacob.range, label: "Jacob’s blessing")
        if let moses = tribe.moses { ReferenceButton(range: moses.range, label: "Moses’ blessing") }
        ReferenceButton(range: tribe.allotment.range, label: tribe.name == "Levi" ? "Cities" : "Land")
    }
}

// MARK: - Feasts

struct FeastsChartView: View {
    let chart: FeastsChart

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                seasonStrip
                ForEach(chart.feasts) { feast in FeastCard(feast: feast) }
            }
            .padding()
        }
    }

    /// The seven appointed times of Leviticus 23 laid out across the year.
    private var seasonStrip: some View {
        let spring = chart.feasts.filter { $0.later != true && ($0.season.hasPrefix("March") || $0.season.hasPrefix("May")) }
        let autumn = chart.feasts.filter { $0.later != true && $0.season.hasPrefix("September") }
        return HStack(alignment: .top, spacing: 12) {
            seasonColumn("Spring", feasts: spring, color: Color(contextHex: "#3F8F6B"))
            seasonColumn("Autumn", feasts: autumn, color: Color(contextHex: "#C9862B"))
        }
    }

    private func seasonColumn(_ title: String, feasts: [FeastsChart.Feast], color: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption.weight(.bold)).foregroundStyle(color)
            ForEach(feasts) { feast in
                HStack(spacing: 6) {
                    Circle().fill(color).frame(width: 7, height: 7)
                    Text(feast.name).font(.caption)
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color.opacity(0.1), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

private struct FeastCard: View {
    let feast: FeastsChart.Feast

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(feast.name).font(.headline)
                Text(feast.hebrew).font(.subheadline).italic().foregroundStyle(.secondary)
                Spacer()
            }
            HStack(spacing: 6) {
                Label(feast.date, systemImage: "calendar").font(.caption)
                Text("· \(feast.season)").font(.caption).foregroundStyle(.secondary)
            }
            HStack(spacing: 6) {
                if feast.pilgrim == true { Tag(text: "Pilgrim feast") }
                if feast.later == true { Tag(text: "Later feast") }
            }
            Text(feast.meaning).font(.subheadline)
            HStack(spacing: 10) {
                ReferenceButton(range: feast.refs.range)
                if let also = feast.also { ReferenceButton(range: also.range) }
            }
            if let nt = feast.nt {
                VStack(alignment: .leading, spacing: 2) {
                    Text(feast.interpretive == true ? "Often connected with" : "In the New Testament")
                        .font(.caption2.weight(.bold)).foregroundStyle(.secondary).textCase(.uppercase)
                    if let text = feast.ntText { Text(text).font(.caption) }
                    ReferenceButton(range: nt.range)
                }
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.tint.opacity(0.08), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
        }
        .padding(12)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .contain)
    }
}

private struct Tag: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(.tint.opacity(0.14), in: Capsule())
    }
}
