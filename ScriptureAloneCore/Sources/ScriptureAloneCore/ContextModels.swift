import Foundation

// Value types for Study mode's context data (places, eras, events, charts). Built by
// Tools/build_context.py; see docs/context-sources.md for where every value comes from.

public enum PlaceKind: String, Sendable, Codable {
    case settlement, region, island, water, mountain, site
}

/// A biblical place, geocoded by OpenBible.info.
public struct Place: Hashable, Sendable, Identifiable {
    public let id: Int
    /// OpenBible.info's ancient-place id (e.g. "a15257a" for Jerusalem).
    public let openBibleID: String
    public let name: String
    /// The modern site it is identified with, when that differs from the name ("Konya" for Iconium).
    public let modernName: String
    public let kind: PlaceKind
    /// OpenBible's type ("settlement", "river", "people group"…).
    public let type: String
    public let longitude: Double
    public let latitude: Double
    /// Current scholarly confidence in the identification, 0–1000 (OpenBible's `time_total`).
    public let confidence: Int
    /// "point", "representative point", "center" or "settlement".
    public let precision: String
    /// How many other identifications have been proposed.
    public let alternatives: Int
    /// Verses that mention this place.
    public let mentions: Int
    /// Coordinates derived from OpenStreetMap (attribution required, ODbL).
    public let fromOpenStreetMap: Bool

    public init(id: Int, openBibleID: String, name: String, modernName: String, kind: PlaceKind, type: String,
                longitude: Double, latitude: Double, confidence: Int, precision: String, alternatives: Int,
                mentions: Int, fromOpenStreetMap: Bool) {
        self.id = id
        self.openBibleID = openBibleID
        self.name = name
        self.modernName = modernName
        self.kind = kind
        self.type = type
        self.longitude = longitude
        self.latitude = latitude
        self.confidence = confidence
        self.precision = precision
        self.alternatives = alternatives
        self.mentions = mentions
        self.fromOpenStreetMap = fromOpenStreetMap
    }

    public enum Confidence: Sendable, Comparable {
        case uncertain, likely, identified

        public var title: String {
            switch self {
            case .identified: String(localized: "Identified", bundle: .module, comment: "How confidently a biblical place's location is known")
            case .likely: String(localized: "Likely location", bundle: .module, comment: "How confidently a biblical place's location is known")
            case .uncertain: String(localized: "Uncertain location", bundle: .module, comment: "How confidently a biblical place's location is known")
            }
        }
    }

    public var confidenceLevel: Confidence {
        switch confidence {
        case 900...: .identified
        case 500..<900: .likely
        default: .uncertain
        }
    }

    /// Regions, and places OpenBible can only pin to an area rather than a site.
    public var isArea: Bool {
        kind == .region || precision == "representative point" || precision == "center"
    }

    /// "OpenBible.info/geo" page for this place.
    public var sourceURL: URL? { URL(string: "https://www.openbible.info/geo/ancient/\(openBibleID)") }
}

/// A place and the verses of one chapter that mention it.
public struct PlaceMention: Hashable, Sendable, Identifiable {
    public let place: Place
    public let verses: [Int]
    public var id: Int { place.id }
}

public struct Era: Hashable, Sendable, Identifiable {
    public let id: String
    public let order: Int
    public let name: String
    public let shortName: String
    /// Negative years are BC. Nil for the undated primeval era.
    public let start: Int?
    public let end: Int?
    public let dates: String
    /// "#RRGGBB"
    public let color: String
    public let summary: String
    /// Where the dates are approximate or disputed.
    public let debate: String
}

/// When a chapter sits in the Bible's story.
public struct ChapterTime: Hashable, Sendable {
    public enum Basis: String, Sendable {
        /// The year is when the chapter's events took place.
        case events
        /// The year is roughly when the book (or psalm, or letter) was written.
        case written
    }

    /// Primary era first.
    public let eras: [Era]
    public let year: Int?
    public let basis: Basis
    public let note: String?

    public var era: Era { eras[0] }

    public var yearLabel: String? { year.map { ContextYear.label($0) } }
}

public enum ContextYear {
    /// -1446 -> "c. 1446 BC", 30 -> "c. AD 30".
    public static func label(_ year: Int, approximate: Bool = true) -> String {
        let bc = -year
        switch (year < 0, approximate) {
        case (true, true): return String(localized: "c. \(bc) BC", bundle: .module, comment: "An approximate year before Christ. %lld is the year.")
        case (true, false): return String(localized: "\(bc) BC", bundle: .module, comment: "A year before Christ. %lld is the year.")
        case (false, true): return String(localized: "c. AD \(year)", bundle: .module, comment: "An approximate year after Christ. %lld is the year.")
        case (false, false): return String(localized: "AD \(year)", bundle: .module, comment: "A year after Christ. %lld is the year.")
        }
    }
}

public struct TimelineEvent: Hashable, Sendable, Identifiable {
    public let id: Int
    public let eraID: String
    public let order: Int
    public let name: String
    public let year: Int?
    /// Display date ("c. 1446 BC (or c. 1260 BC)"); nil when undated.
    public let date: String?
    public let debated: Bool
    public let range: VerseRange?
}

public enum ChartKind: String, Sendable, Codable {
    case kings, journeys, tribes, feasts
}

public struct ChartInfo: Hashable, Sendable, Identifiable {
    public let id: String
    public let order: Int
    public let kind: ChartKind
    public let title: String
    public let subtitle: String
    /// How the chart was compiled and what it cites.
    public let sources: String
    /// Books where the chart is suggested.
    public let scope: [BookID]
    let body: Data

    public func decode<T: Decodable>(_ type: T.Type) throws -> T {
        try JSONDecoder().decode(type, from: body)
    }
}

/// An authored water or landscape label on the base map.
public struct MapLabel: Hashable, Sendable {
    public enum Kind: String, Sendable { case sea, lake, river, land }
    public let text: String
    public let subtitle: String?
    public let longitude: Double
    public let latitude: Double
    /// Hidden below this map scale (points per degree of latitude).
    public let minimumScale: Double
    public let kind: Kind
    /// Degrees, counter-clockwise.
    public let angle: Double
}

// MARK: - Chart bodies

/// References are stored as [startKey, endKey].
public struct ChartReference: Hashable, Sendable, Codable {
    public let range: VerseRange

    public init(from decoder: Decoder) throws {
        let keys = try decoder.singleValueContainer().decode([Int].self)
        guard keys.count == 2, let start = VerseRef(key: keys[0]), let end = VerseRef(key: keys[1]) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "bad reference"))
        }
        range = VerseRange(start, end)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode([range.start.key, range.end.key])
    }
}

public struct KingsChart: Hashable, Sendable, Codable {
    public struct King: Hashable, Sendable, Codable, Identifiable {
        public enum Verdict: String, Sendable, Codable { case good, evil, mixed }
        public let name: String
        public let reign: String
        public let years: String
        public let verdict: Verdict
        public let ref: ChartReference
        public let note: String?
        public let prophets: [String]?
        public var id: String { name + reign }
    }
    public let united: [King]
    public let israel: [King]
    public let judah: [King]
}

public struct JourneysChart: Hashable, Sendable, Codable {
    public struct Stop: Hashable, Sendable, Codable {
        public let id: Int
        public let name: String
        public let lon: Double
        public let lat: Double
        public let kind: PlaceKind
        public let ref: ChartReference
        public let note: String?
    }
    public struct Journey: Hashable, Sendable, Codable, Identifiable {
        public let id: String
        public let name: String
        public let dates: String
        public let refs: ChartReference
        public let color: String
        public let stops: [Stop]
    }
    public let journeys: [Journey]
}

public struct TribesChart: Hashable, Sendable, Codable {
    public struct Tribe: Hashable, Sendable, Codable, Identifiable {
        public let name: String
        public let order: Int
        public let mother: String
        public let birth: ChartReference
        public let jacob: ChartReference
        public let moses: ChartReference?
        public let allotment: ChartReference
        public let note: String?
        public let lon: Double?
        public let lat: Double?
        public let lon2: Double?
        public let lat2: Double?
        public var id: String { name }
    }
    public let tribes: [Tribe]
}

public struct FeastsChart: Hashable, Sendable, Codable {
    public struct Feast: Hashable, Sendable, Codable, Identifiable {
        public let name: String
        public let hebrew: String
        public let date: String
        public let season: String
        public let refs: ChartReference
        public let also: ChartReference?
        public let meaning: String
        public let nt: ChartReference?
        public let ntText: String?
        public let pilgrim: Bool?
        public let interpretive: Bool?
        public let later: Bool?
        /// "spring" or "autumn" for the feasts the chart groups by season, whatever language the
        /// season text is in (`ContextStore.localizedBody`).
        public let seasonGroup: String?
        public var id: String { name }
    }
    public let feasts: [Feast]
}
