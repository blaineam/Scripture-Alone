import Foundation

/// A search that sounds like someone thinking of ending their life gets a crisis line before any
/// verse: a person to talk to, now, in their own country.
///
/// Matching is deliberately broad — "suicide" typed to study what Scripture says about it still
/// shows the card, which costs a glance; missing someone who needed it costs far more. Phrases are
/// kept in the nine app languages and compared after `TopicSearch.normalize`, so accents, case
/// and width don't matter.
///
/// Numbers are checked against findahelpline.com (the international directory run with the
/// International Association for Suicide Prevention), which is also the fallback for every
/// country not listed here.
public enum CrisisSupport {
    public struct Helpline: Hashable, Sendable {
        /// The service's own name, in its own language — never translated.
        public let name: String
        /// As it is printed in that country.
        public let display: String
        /// Digits only, for `tel:` and `sms:`.
        public let dial: String
        /// The line also answers text messages.
        public let texts: Bool

        public var callURL: URL? { URL(string: "tel:\(dial)") }
        public var textURL: URL? { texts ? URL(string: "sms:\(dial)") : nil }
    }

    /// Every other country's lines, searchable by country.
    public static let directoryURL = URL(string: "https://findahelpline.com")!

    /// Whether a search reads as someone in crisis.
    public static func isCrisis(_ query: String) -> Bool {
        let text = TopicSearch.normalize(query)
        guard !text.isEmpty else { return false }
        return normalizedPhrases.contains { text.contains($0) }
    }

    /// The line for a region (ISO 3166 code, as `Locale.Region.identifier` gives it), or nil where
    /// the directory is the better answer.
    public static func helpline(forRegion region: String?) -> Helpline? {
        guard let region else { return nil }
        return helplines[region.uppercased()]
    }

    static let helplines: [String: Helpline] = {
        let us = Helpline(name: "988 Suicide & Crisis Lifeline", display: "988", dial: "988", texts: true)
        let samaritans = Helpline(name: "Samaritans", display: "116 123", dial: "116123", texts: false)
        let seelsorge = Helpline(name: "TelefonSeelsorge", display: "0800 111 0 111", dial: "08001110111", texts: false)
        return [
            "US": us,
            "CA": Helpline(name: "9-8-8 Suicide Crisis Helpline", display: "988", dial: "988", texts: true),
            "GB": samaritans,
            "IE": samaritans,
            "AU": Helpline(name: "Lifeline", display: "13 11 14", dial: "131114", texts: false),
            "NZ": Helpline(name: "Need to talk? 1737", display: "1737", dial: "1737", texts: true),
            "DE": seelsorge,
            "AT": Helpline(name: "TelefonSeelsorge", display: "142", dial: "142", texts: false),
            "CH": Helpline(name: "Die Dargebotene Hand", display: "143", dial: "143", texts: false),
            "FR": Helpline(name: "3114", display: "3114", dial: "3114", texts: false),
            "ES": Helpline(name: "Línea 024", display: "024", dial: "024", texts: false),
            "MX": Helpline(name: "Línea de la Vida", display: "800 911 2000", dial: "8009112000", texts: false),
            "BR": Helpline(name: "CVV", display: "188", dial: "188", texts: false),
            "IT": Helpline(name: "Telefono Amico", display: "02 2327 2327", dial: "0223272327", texts: false),
            "JP": Helpline(name: "よりそいホットライン", display: "0120-279-338", dial: "0120279338", texts: false),
            "KR": Helpline(name: "자살예방상담전화", display: "109", dial: "109", texts: false),
            "CN": Helpline(name: "希望24热线", display: "400-161-9995", dial: "4001619995", texts: false),
            "TW": Helpline(name: "安心專線", display: "1925", dial: "1925", texts: false),
        ]
    }()

    /// What people type — in the app's nine languages.
    static let phrases: [String] = [
        // English
        "suicide", "suicidal", "kill myself", "killing myself", "want to die", "wanna die",
        "wish i was dead", "wish i were dead", "end my life", "ending my life", "take my own life",
        "end it all", "dont want to live", "dont want to be alive", "no reason to live",
        "self harm", "selfharm", "hurt myself", "hurting myself", "cutting myself",
        // Deutsch
        "suizid", "selbstmord", "selbsttotung", "umbringen", "will sterben", "sterben will",
        "nicht mehr leben", "leben beenden", "selbstverletzung", "ritzen",
        // Español
        "suicidio", "suicida", "suicidarme", "matarme", "quiero morir", "quitarme la vida",
        "no quiero vivir", "autolesion", "hacerme dano",
        // Français
        "suicidaire", "me suicider", "me tuer", "envie de mourir", "veux mourir",
        "mettre fin a mes jours", "en finir", "automutilation", "me faire du mal",
        // Italiano
        "suicidarmi", "uccidermi", "voglio morire", "togliermi la vita", "farla finita",
        "autolesionismo", "farmi del male",
        // Português
        "suicidio", "me matar", "quero morrer", "tirar minha vida", "tirar a minha vida",
        "acabar com minha vida", "nao quero viver", "automutilacao", "me machucar",
        // 日本語
        "自殺", "自死", "死にたい", "消えたい", "自傷", "リストカット", "生きていたくない", "命を絶",
        // 한국어
        "자살", "죽고 싶", "죽고싶", "자해", "극단적 선택", "살고 싶지 않", "목숨을 끊",
        // 中文
        "自杀", "想死", "轻生", "自残", "不想活", "结束生命", "结束自己",
    ]

    private static let normalizedPhrases: [String] = phrases.map(TopicSearch.normalize).filter { !$0.isEmpty }
}
