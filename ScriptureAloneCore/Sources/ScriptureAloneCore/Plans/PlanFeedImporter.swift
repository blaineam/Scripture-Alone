import Foundation

/// A source of web requests. The app hands in `URLSession`; tests hand in a fixed table.
public protocol PlanFetching: Sendable {
    func fetch(_ url: URL) async throws -> (Data, HTTPURLResponse)
}

extension URLSession: PlanFetching {
    public func fetch(_ url: URL) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.setValue("application/json, application/rss+xml, application/xml;q=0.9", forHTTPHeaderField: "Accept")
        let (data, response) = try await data(for: request)
        guard let http = response as? HTTPURLResponse else { throw PlanFeedError.badResponse(0) }
        return (data, http)
    }
}

public enum PlanFeedError: Error, Equatable, Sendable {
    /// The request never reached the server: no network, a timeout, a dead host.
    case offline
    case badResponse(Int)
    /// Reached a server that answered with neither a WordPress API nor a feed.
    case notAFeed
    /// A feed with no title that names a passage.
    case noReadings
    case badURL
}

/// One post of a feed, reduced to what a plan needs.
public struct FeedEntry: Equatable, Sendable {
    public let title: String
    /// "yyyy-MM-dd"
    public let date: String
    public let link: URL?
    public init(title: String, date: String, link: URL?) {
        self.title = title
        self.date = date
        self.link = link
    }
}

public struct FeedCategory: Equatable, Sendable, Identifiable {
    public let id: Int
    public let name: String
    public let count: Int
}

/// A post's commentary, fetched on demand and shown with a link back. Never bundled; the app may keep
/// the last one it fetched on the device, for reading offline (`StudyCache`).
public struct StudyPost: Codable, Equatable, Sendable {
    public let title: String
    public let date: String
    public let link: URL?
    public let text: String

    public init(title: String, date: String, link: URL?, text: String) {
        self.title = title
        self.date = date
        self.link = link
        self.text = text
    }
}

/// Reads a reading-plan schedule from a WordPress site or an RSS feed: the *schedule* only. Each
/// post's title names the passage for its date; the passage text is read from the reader's own
/// translation, so nothing a site republishes is copied.
public struct PlanFeedImporter: Sendable {
    public let fetcher: any PlanFetching

    public init(fetcher: any PlanFetching) {
        self.fetcher = fetcher
    }

    /// Disciples Church's Bible reading plan, the first source: category 2 is the daily reading,
    /// category 7 the Saturday Study.
    public static let disciplesChurch = Preset(
        name: "Disciples Church Bible Reading Plan",
        site: URL(string: "https://discipleschurch.com/bible")!,
        scheduleCategory: 2, studyCategory: 7)

    public struct Preset: Sendable {
        public let name: String
        public let site: URL
        public let scheduleCategory: Int
        public let studyCategory: Int?
    }

    // MARK: Pasted addresses

    /// Where a pasted address leads: a site root to try the WordPress API on, or a feed.
    public enum Endpoint: Equatable, Sendable {
        case wordPress(site: URL, category: Int?)
        case rss(URL)
    }

    /// Reads whatever the reader pasted. An address inside `/wp-json/` or with a `?cat=`/`categories=`
    /// names its category; one ending in `/feed` is RSS; anything else is a site to try both on.
    public static func endpoints(for pasted: String) -> [Endpoint] {
        var text = pasted.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return [] }
        if !text.contains("://") { text = "https://" + text }
        guard let url = URL(string: text), let host = url.host, host.contains("."),
              url.scheme == "https" || url.scheme == "http" else { return [] }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let category = items.first { ["categories", "cat", "categories[0]"].contains($0.name) }
            .flatMap { $0.value.flatMap { Int($0.split(separator: ",")[0]) } }
        var base = url.absoluteString
        if let range = base.range(of: "/wp-json") { base = String(base[..<range.lowerBound]) }
        base = base.components(separatedBy: "?")[0]
        while base.hasSuffix("/") { base.removeLast() }
        let path = url.path.lowercased()
        if path.contains("/wp-json") {
            return URL(string: base).map { [.wordPress(site: $0, category: category)] } ?? []
        }
        if path.hasSuffix("/feed") || path.hasSuffix("/feed/") || path.hasSuffix(".xml") || path.hasSuffix("/rss") {
            return [.rss(url)]
        }
        guard let site = URL(string: base) else { return [] }
        let feed = URL(string: base + "/feed/")
        return [.wordPress(site: site, category: category)] + (feed.map { [.rss($0)] } ?? [])
    }

    // MARK: Fetching

    private func restURL(_ site: URL, _ route: String, _ query: [String: String]) -> URL? {
        var components = URLComponents(url: site.appendingPathComponent("wp-json/wp/v2/\(route)"), resolvingAgainstBaseURL: false)
        components?.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        return components?.url
    }

    private func get(_ url: URL) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await fetcher.fetch(url)
            guard (200..<300).contains(response.statusCode) else { throw PlanFeedError.badResponse(response.statusCode) }
            return (data, response)
        } catch let error as PlanFeedError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw PlanFeedError.offline
        }
    }

    /// The site's categories that hold posts, most posts first — for choosing which one is the plan.
    public func categories(site: URL) async throws -> [FeedCategory] {
        guard let url = restURL(site, "categories", ["per_page": "100", "hide_empty": "true", "_fields": "id,name,count"]) else {
            throw PlanFeedError.badURL
        }
        let (data, _) = try await get(url)
        guard let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { throw PlanFeedError.notAFeed }
        return rows.compactMap { row in
            guard let id = row["id"] as? Int, let name = row["name"] as? String else { return nil }
            return FeedCategory(id: id, name: Self.plainText(name), count: row["count"] as? Int ?? 0)
        }
        .sorted { $0.count > $1.count }
    }

    /// Posts oldest first, from `after` on, at most `limit`. A WordPress site is paged 100 at a time.
    public func entries(_ endpoint: Endpoint, after: Date? = nil, limit: Int = 400) async throws -> [FeedEntry] {
        switch endpoint {
        case .rss(let url):
            let (data, _) = try await get(url)
            return try Self.parseRSS(data).sorted { $0.date < $1.date }
        case .wordPress(let site, let category):
            var result: [FeedEntry] = []
            var page = 1
            var pages = 1
            while page <= pages, result.count < limit {
                var query = ["per_page": "100", "page": String(page), "orderby": "date", "order": "asc",
                             "_fields": "id,date,title,link"]
                if let category { query["categories"] = String(category) }
                if let after { query["after"] = Self.isoDateTime(after) }
                guard let url = restURL(site, "posts", query) else { throw PlanFeedError.badURL }
                let data: Data
                let response: HTTPURLResponse
                do {
                    (data, response) = try await get(url)
                } catch PlanFeedError.badResponse(400) where page > 1 {
                    break // WordPress answers 400 for a page past the last.
                }
                pages = Int(response.value(forHTTPHeaderField: "X-WP-TotalPages") ?? "") ?? pages
                guard let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { throw PlanFeedError.notAFeed }
                for row in rows {
                    guard let date = row["date"] as? String, date.count >= 10,
                          let title = (row["title"] as? [String: Any])?["rendered"] as? String else { continue }
                    result.append(FeedEntry(title: Self.plainText(title), date: String(date.prefix(10)),
                                            link: (row["link"] as? String).flatMap(URL.init(string:))))
                }
                page += 1
            }
            return Array(result.prefix(limit))
        }
    }

    /// Tries each endpoint a pasted address could mean, in order, until one answers with posts.
    public func entries(forPasted pasted: String, after: Date? = nil, limit: Int = 400) async throws -> (Endpoint, [FeedEntry]) {
        let candidates = Self.endpoints(for: pasted)
        guard !candidates.isEmpty else { throw PlanFeedError.badURL }
        var failure: Error = PlanFeedError.notAFeed
        for endpoint in candidates {
            do {
                let found = try await entries(endpoint, after: after, limit: limit)
                if !found.isEmpty { return (endpoint, found) }
            } catch {
                // Offline outranks "not a feed": the next candidate would fail the same way.
                if case PlanFeedError.offline = error { throw error }
                failure = error
            }
        }
        throw failure
    }

    // MARK: Making a plan

    public struct Import: Sendable {
        public let plan: ReadingPlan
        /// Titles that named no passage (an "About" page, a banner post), for the reader to see.
        public let skipped: [String]
    }

    /// Turns posts into a plan: one day per date, each holding the passages its titles name.
    public static func plan(from entries: [FeedEntry], title: String, source: PlanSource) throws -> Import {
        var byDate: [String: (label: String?, passages: [PlanPassage])] = [:]
        var skipped: [String] = []
        for entry in entries.sorted(by: { $0.date < $1.date }) {
            guard let parsed = parseTitle(entry.title) else {
                skipped.append(entry.title)
                continue
            }
            var day = byDate[entry.date] ?? (parsed.label, [])
            for passage in parsed.passages where !day.passages.contains(passage) { day.passages.append(passage) }
            byDate[entry.date] = day
        }
        guard !byDate.isEmpty else { throw PlanFeedError.noReadings }
        let dates = byDate.keys.sorted()
        let days = dates.enumerated().map { offset, date in
            PlanDay(index: offset, date: date, label: byDate[date]?.label, passages: byDate[date]?.passages ?? [])
        }
        // Weekdays with no reading anywhere are the source's rest days (needs a few weeks to tell).
        var skip: Set<Int> = []
        if days.count >= 14 {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
            var seen: Set<Int> = []
            for date in dates {
                let parts = date.split(separator: "-").compactMap { Int($0) }
                if parts.count == 3, let d = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])) {
                    seen.insert(calendar.component(.weekday, from: d))
                }
            }
            skip = Set(1...7).subtracting(seen)
        }
        let id = "import-" + String(stableHash(source.siteURL.absoluteString + title), radix: 36)
        let plan = ReadingPlan(id: id, kind: .imported, title: title,
                               summary: "Schedule from \(source.name). The text is read in your translation.",
                               days: days, skipWeekdays: skip, source: source)
        return Import(plan: plan, skipped: skipped)
    }

    /// "Epistle- 1 Corinthians 5" → label "Epistle", passage 1 Corinthians 5. A title that is itself a
    /// reference ("Psalms 23-24", "Genesis 1:1 - 2:3") has no label. Nil when no reference is found.
    public static func parseTitle(_ title: String) -> (label: String?, passages: [PlanPassage])? {
        let text = plainText(title)
        if let whole = ReferenceParser.parseAnyLanguage(text) { return (nil, [PlanPassage(whole)]) }
        // Try the text after each "label separator" in turn: "Epistle- 1 Corinthians 5", "Day 4: John 3".
        let separators = try? NSRegularExpression(pattern: #"\s*[-–—:|]\s+"#)
        let ns = text as NSString
        let matches = separators?.matches(in: text, range: NSRange(location: 0, length: ns.length)) ?? []
        for match in matches {
            let label = ns.substring(to: match.range.location).trimmingCharacters(in: .whitespaces)
            let tail = ns.substring(from: match.range.location + match.range.length)
            if let one = ReferenceParser.parseAnyLanguage(tail) {
                return (label.isEmpty ? nil : label, [PlanPassage(one)])
            }
            let list = ReferenceParser.parseList(tail, language: "en").filter { $0.book.chapterCount >= $0.startChapter }
            if !list.isEmpty, list.count == 1 || ReferenceParser.parseAnyLanguage(String(tail.split(separator: ";").first ?? "")) != nil {
                return (label.isEmpty ? nil : label, list.map(PlanPassage.init))
            }
        }
        return nil
    }

    // MARK: Weekly study

    /// The most recent commentary post published on or before `date`, with its text as plain
    /// paragraphs. Fetched each time it is wanted; the caller shows it with `link` as the credit.
    public func latestStudy(source: PlanSource, onOrBefore date: String) async throws -> StudyPost? {
        guard let root = source.apiRoot, let category = source.studyCategory else { return nil }
        let site = root.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        guard let url = restURL(site, "posts", ["categories": String(category), "per_page": "1", "orderby": "date",
                                                "order": "desc", "before": date + "T23:59:59",
                                                "_fields": "id,date,title,link,content"]) else { throw PlanFeedError.badURL }
        let (data, _) = try await get(url)
        guard let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { throw PlanFeedError.notAFeed }
        guard let row = rows.first, let title = (row["title"] as? [String: Any])?["rendered"] as? String,
              let html = (row["content"] as? [String: Any])?["rendered"] as? String else { return nil }
        return StudyPost(title: Self.plainText(title), date: String((row["date"] as? String ?? "").prefix(10)),
                         link: (row["link"] as? String).flatMap(URL.init(string:)), text: Self.paragraphs(fromHTML: html))
    }

    /// The REST root of a WordPress site.
    public static func apiRoot(of site: URL) -> URL { site.appendingPathComponent("wp-json/wp/v2") }

    // MARK: Text

    static func isoDateTime(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return f.string(from: date)
    }

    /// Tags dropped, entities decoded, whitespace collapsed.
    static func plainText(_ html: String) -> String {
        decodeEntities(html.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression))
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Post HTML as plain text, one blank line between paragraphs.
    static func paragraphs(fromHTML html: String) -> String {
        var s = html
        s = s.replacingOccurrences(of: #"(?is)<(script|style)[^>]*>.*?</\1>"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"(?i)</(p|div|h[1-6]|li|blockquote)>|<br\s*/?>"#, with: "\n\n", options: .regularExpression)
        s = s.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        s = decodeEntities(s)
        let blocks = s.components(separatedBy: "\n").map {
            $0.replacingOccurrences(of: #"[ \t]+"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        }
        return blocks.filter { !$0.isEmpty }.joined(separator: "\n\n")
    }

    static func decodeEntities(_ s: String) -> String {
        guard s.contains("&") else { return s }
        let named = ["amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ", "ndash": "–", "mdash": "—",
                     "lsquo": "‘", "rsquo": "’", "ldquo": "“", "rdquo": "”", "hellip": "…"]
        var out = ""
        var rest = Substring(s)
        while let amp = rest.firstIndex(of: "&") {
            out += rest[..<amp]
            let after = rest[rest.index(after: amp)...]
            if let semi = after.prefix(10).firstIndex(of: ";") {
                let name = String(after[..<semi])
                var decoded: String?
                if name.hasPrefix("#x") || name.hasPrefix("#X") {
                    decoded = UInt32(name.dropFirst(2), radix: 16).flatMap(Unicode.Scalar.init).map(String.init)
                } else if name.hasPrefix("#") {
                    decoded = UInt32(name.dropFirst()).flatMap(Unicode.Scalar.init).map(String.init)
                } else {
                    decoded = named[name]
                }
                if let decoded {
                    out += decoded
                    rest = after[after.index(after: semi)...]
                    continue
                }
            }
            out.append("&")
            rest = after
        }
        return out + rest
    }

    // MARK: RSS

    static func parseRSS(_ data: Data) throws -> [FeedEntry] {
        let delegate = RSSDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        guard parser.parse(), !delegate.entries.isEmpty else { throw PlanFeedError.notAFeed }
        return delegate.entries
    }
}

extension PlanFeedImporter {
    /// A hash that is the same on every launch (`Hasher` is seeded per process): FNV-1a.
    static func stableHash(_ string: String) -> UInt64 {
        var h: UInt64 = 0xcbf29ce484222325
        for byte in string.utf8 { h = (h ^ UInt64(byte)) &* 0x100000001b3 }
        return h
    }
}

private final class RSSDelegate: NSObject, XMLParserDelegate {
    var entries: [FeedEntry] = []
    private var inItem = false
    private var element = ""
    private var title = ""
    private var link = ""
    private var pubDate = ""

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?,
                attributes: [String: String] = [:]) {
        element = name
        if name == "item" {
            inItem = true
            title = ""
            link = ""
            pubDate = ""
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard inItem else { return }
        switch element {
        case "title": title += string
        case "link": link += string
        case "pubDate": pubDate += string
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCDATA data: Data) {
        guard inItem, let string = String(data: data, encoding: .utf8) else { return }
        self.parser(parser, foundCharacters: string)
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        element = ""
        guard name == "item" else { return }
        inItem = false
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss Z"
        guard let date = f.date(from: pubDate.trimmingCharacters(in: .whitespacesAndNewlines)) else { return }
        f.dateFormat = "yyyy-MM-dd"
        entries.append(FeedEntry(title: PlanFeedImporter.plainText(title), date: f.string(from: date),
                                 link: URL(string: link.trimmingCharacters(in: .whitespacesAndNewlines))))
    }
}
