import AVFoundation
import Foundation

/// Which synthesizer reads aloud.
enum ListenEngine: String, CaseIterable, Identifiable {
    /// `AVSpeechSynthesizer` with the voices installed on this device.
    case system
    /// Mi Speaks's Studio voices, rendered by Mi Speaks and played here (iOS only).
    case miSpeaks

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: String(localized: "System Voice", comment: "Read-aloud engine using the device's built-in voices")
        case .miSpeaks: String(localized: "Mi Speaks Studio", comment: "Read-aloud engine. “Mi Speaks” is an app name; do not translate it.")
        }
    }
}

/// Stops listening after a while — for falling asleep to the Psalms.
enum SleepTimer: Hashable, CaseIterable, Identifiable {
    case off, minutes15, minutes30, minutes60, endOfChapter

    var id: Self { self }

    var title: String {
        switch self {
        case .off: String(localized: "Off", comment: "Sleep timer option")
        case .minutes15: String(localized: "15 Minutes", comment: "Sleep timer option")
        case .minutes30: String(localized: "30 Minutes", comment: "Sleep timer option")
        case .minutes60: String(localized: "1 Hour", comment: "Sleep timer option")
        case .endOfChapter: String(localized: "End of Chapter", comment: "Sleep timer option")
        }
    }

    var duration: Duration? {
        switch self {
        case .minutes15: .seconds(15 * 60)
        case .minutes30: .seconds(30 * 60)
        case .minutes60: .seconds(60 * 60)
        case .off, .endOfChapter: nil
        }
    }
}

/// One installed system voice, as the picker shows it.
nonisolated struct VoiceOption: Identifiable, Hashable, Sendable {
    enum Kind: Int, Comparable, Sendable {
        case personal, premium, enhanced, standard
        static func < (lhs: Kind, rhs: Kind) -> Bool { lhs.rawValue < rhs.rawValue }
    }

    let id: String
    let name: String
    let language: String
    let kind: Kind

    var badge: String? {
        switch kind {
        case .personal: String(localized: "Personal Voice", comment: "Kind of system voice (Apple's Personal Voice feature)")
        case .premium: String(localized: "Premium", comment: "Quality tier of a system voice")
        case .enhanced: String(localized: "Enhanced", comment: "Quality tier of a system voice")
        case .standard: nil
        }
    }

    /// "Ava (Premium) · United States"
    var title: String {
        let region = Locale.current.localizedString(forIdentifier: language) ?? language
        if let badge {
            return String(localized: "\(name) (\(badge)) · \(region)", comment: "A voice in the picker. %1$@ voice name, %2$@ quality (e.g. “Premium”), %3$@ language and region.")
        }
        return String(localized: "\(name) · \(region)", comment: "A voice in the picker. %1$@ voice name, %2$@ language and region.")
    }
}

/// First caller wins; resumes a continuation exactly once from two racing threads.
private nonisolated final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

/// Voice lookups.`AVSpeechSynthesisVoice.speechVoices()` (and the identifier lookup) must never
/// run on the main thread: on iOS 27 it blocks in an accessibility sync call that waits on the
/// main thread — observed as a permanent hang the first time Listen was tapped in the Simulator.
nonisolated enum SpeechVoices {
    /// The voice language for a text language tag: "fr" for "fr", "zh" for "zh-Hans", "pt" for
    /// "pt-BR". The voices offered and chosen are the text's — an English voice reading Louis
    /// Segond helps no one — with the device's own region first.
    static func voiceLanguage(for textLanguage: String) -> String {
        String(textLanguage.split(separator: "-").first ?? "en")
    }

    /// The installed voices for a text language, enumerated off the main thread. Empty if the
    /// lookup doesn't answer.
    static func available(for textLanguage: String = "en") async -> [VoiceOption] {
        let language = voiceLanguage(for: textLanguage)
        return await withTimeout { enumerate(language) } ?? []
    }

    enum Resolved: Sendable {
        case voice(AVSpeechSynthesisVoice?)
        /// The voice service didn't answer; speak with the system default (`voice = nil`).
        case timedOut
    }

    /// The saved voice if it's still installed, else the best voice for the device's region —
    /// resolved off the main thread.
    /// A saved voice in another language (an English voice picked while reading the KJV, now
    /// reading Segond) is passed over for this text, not forgotten.
    static func voice(for identifier: String?, textLanguage: String = "en") async -> Resolved {
        let language = voiceLanguage(for: textLanguage)
        let found: AVSpeechSynthesisVoice?? = await withTimeout {
            if let identifier, let voice = AVSpeechSynthesisVoice(identifier: identifier),
               voice.language.hasPrefix(language) { return voice }
            if let best = enumerate(language).first(where: { $0.kind != .personal }),
               let voice = AVSpeechSynthesisVoice(identifier: best.id) { return voice }
            return AVSpeechSynthesisVoice(language: language == "en" ? "en-US" : language)
        }
        guard let found else { return .timedOut }
        return .voice(found)
    }

    /// Runs a blocking lookup on a background thread and gives up after `timeout`. The voice
    /// service can stall indefinitely (seen in a fresh iOS 27 Simulator: `speechVoices()` never
    /// returned, even off the main thread), and Listen must still start.
    private static func withTimeout<T: Sendable>(_ timeout: Duration = .seconds(2),
                                                 _ work: @escaping @Sendable () -> T) async -> T? {
        await withCheckedContinuation { (continuation: CheckedContinuation<T?, Never>) in
            let once = Once()
            Thread.detachNewThread {
                let value = work()
                if once.claim() { continuation.resume(returning: value) }
            }
            Task.detached {
                try? await Task.sleep(for: timeout)
                if once.claim() { continuation.resume(returning: nil) }
            }
        }
    }

    private static func enumerate(_ language: String) -> [VoiceOption] {
        let region = Locale.current.region?.identifier
        // Mainland Mandarin for the simplified-script 和合本, whatever region the device is in.
        let preferred = language == "zh" ? "zh-CN" : region.map { "\(language)-\($0)" }
        return AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.hasPrefix(language) && !$0.voiceTraits.contains(.isNoveltyVoice) }
            .map { voice in
                let kind: VoiceOption.Kind = voice.voiceTraits.contains(.isPersonalVoice) ? .personal
                    : voice.quality == .premium ? .premium
                    : voice.quality == .enhanced ? .enhanced : .standard
                return VoiceOption(id: voice.identifier, name: voice.name, language: voice.language, kind: kind)
            }
            .sorted { a, b in
                let aHome = a.language == preferred, bHome = b.language == preferred
                if aHome != bHome { return aHome }
                if a.kind != b.kind { return a.kind < b.kind }
                if a.language != b.language { return a.language < b.language }
                return a.name < b.name
            }
    }

    /// 0.5×–2× mapped onto `AVSpeechUtterance.rate`. The rate scale isn't linear in words per
    /// minute: above the default it speeds up quickly, so the top half is compressed (2× → 0.75).
    static func utteranceRate(forSpeed speed: Double) -> Float {
        let base = Double(AVSpeechUtteranceDefaultSpeechRate)
        let rate = speed <= 1 ? base * speed : base + (speed - 1) * (Double(AVSpeechUtteranceMaximumSpeechRate) - base) * 0.5
        return Float(min(Double(AVSpeechUtteranceMaximumSpeechRate), max(Double(AVSpeechUtteranceMinimumSpeechRate), rate)))
    }

    static var personalVoiceAuthorized: Bool {
        AVSpeechSynthesizer.personalVoiceAuthorizationStatus == .authorized
    }

    /// Asked only when someone picks "Personal Voice" — never up front.
    static func requestPersonalVoice() async -> Bool {
        await withCheckedContinuation { continuation in
            AVSpeechSynthesizer.requestPersonalVoiceAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }
    }
}
