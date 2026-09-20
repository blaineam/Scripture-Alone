//
//  MiSpeaksClient.swift
//  Scripture Alone
//
//  Borrowing Mi Speaks's Studio voices, the same way Ari does.
//
//  Mi Speaks (com.blaineam.Mi-Speaks, same team) carries a neural TTS engine and its voices.
//  Scripture Alone ships none of that; it asks. The protocol is Mi Speaks's existing
//  `AriNarrationBridge` — this is a second client of it, mirroring Ari's `MiSpeaksBridge`, and
//  Mi Speaks is not modified. The round trip:
//
//   1. Both apps are in the App Group `group.com.blaineam.Mi-Speaks`. Mi Speaks publishes its
//      installed Studio voices to `<group>/Ari/voices.json` ([{id, name, language}]) at launch and
//      before every job, and its entitlement flag `hasPremium` lives in the group's UserDefaults.
//   2. We write `<group>/Ari/jobs/<id>.json` = {id, text, voiceID, rate}. `rate` is in
//      AVSpeechUtterance units: Mi Speaks doubles it and clamps to 0.5…2, so speed ÷ 2.
//      Our ids are prefixed `scripture-alone-` so cleanup never touches Ari's jobs.
//   3. We open `mispeaks://render?job=<id>`. On iOS that brings Mi Speaks to the front; it checks
//      its own premium gate (never trusts ours), renders sentence by sentence and joins them.
//   4. It writes the audio to `<group>/Ari/out/<id>.caf` (a joined multi-sentence render is
//      AAC-in-M4A data under that name — AVAudioPlayer sniffs the content) and the answer to
//      `<group>/Ari/jobs/<id>.done.json` — the same job with `resultFilename` (relative to the
//      group container) or `failure` (a user-facing message, e.g. the subscription gate).
//   5. We poll for the answer, copy the audio into our own temporary directory, delete the
//      request, the answer and the shared audio, and play the copy with AVAudioPlayer.
//
//  Why one job per chapter rather than per verse: iOS brings Mi Speaks to the foreground for
//  every URL it opens, and an app in the background cannot open URLs at all — so each job costs
//  the listener an app switch, and Mi Speaks can only render while it is in front (iOS kills
//  background GPU work). A job per verse would mean a switch per verse. So the chapter (from the
//  starting verse) is one job, rendered in one visit, which also makes the chapter gapless.
//  Verse highlighting then follows an estimate — each verse's share of the characters — because
//  Mi Speaks returns one clip with no timings.
//
//  What was and wasn't verified (iOS 27 Simulator, 2026-09-18): with Mi Speaks absent, the
//  "isn't installed" fallback + App Store link; with a Mi Speaks debug build installed and the
//  group's `hasPremium` set, the job JSON landed in `<group>/Ari/jobs/`, `mispeaks://render`
//  brought Mi Speaks forward, and Stop deleted the job. Mi Speaks never answered in the Simulator
//  (its neural engine can't run there), so the answer → playback half — audio copy, verse
//  timing, cleanup of `Ari/out` — is written to Mi Speaks's source, not observed. Needs a device.
//

#if os(iOS)
import Foundation
import UIKit
import ScriptureAloneCore

@MainActor
enum MiSpeaksClient {
    static let appGroup = "group.com.blaineam.Mi-Speaks"
    static let scheme = "mispeaks"
    static let appStoreURL = URL(string: "https://apps.apple.com/app/mi-speaks/id6451395651")!
    private static let jobPrefix = "scripture-alone-"

    enum Availability: Equatable {
        case ready
        case notInstalled
        case noSharedContainer
        case notSubscribed
        /// The text itself may not be handed to another app — see `TranslationInfo`.
        case translationNotPermitted

        nonisolated var explanation: String {
            switch self {
            case .ready: "Mi Speaks will read in its Studio voices."
            case .notInstalled: "Studio voices come from Mi Speaks, which isn’t installed."
            case .noSharedContainer: "This build can’t reach Mi Speaks’s shared folder."
            case .notSubscribed: "Studio voices need Mi Speaks Premium."
            case .translationNotPermitted:
                "Studio voices send the text to Mi Speaks to record it, which this translation’s "
                    + "licence doesn’t allow. The voices on this device read it as usual."
            }
        }
    }

    /// One of Mi Speaks's installed Studio voices, as it publishes them.
    struct Voice: Identifiable, Codable, Hashable {
        let id: String
        let name: String
        var language: String?
    }

    /// The request, and the same file with Mi Speaks's answer written into it.
    struct Job: Codable {
        var id: String
        var text: String
        var voiceID: String?
        var rate: Double
        var resultFilename: String?
        var failure: String?
    }

    enum Failure: LocalizedError {
        case unavailable(Availability)
        case refused(String)
        case timedOut

        var errorDescription: String? {
            switch self {
            case .unavailable(let why): why.explanation
            case .refused(let why): why
            case .timedOut: "Mi Speaks didn’t finish in time."
            }
        }
    }

    static var isInstalled: Bool {
        UIApplication.shared.canOpenURL(URL(string: "\(scheme)://")!)
    }

    /// Nil unless this build carries the App Group entitlement.
    static var container: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
    }

    /// `translation` is the text about to be read. Handing it to another app is a copy the
    /// publisher never licensed, so a licensed translation stays with the on-device voices.
    static func availability(for translation: TranslationInfo?) -> Availability {
        if let translation, !translation.mayHandOffToOtherApps { return .translationNotPermitted }
        return availability
    }

    static var availability: Availability {
        guard isInstalled else { return .notInstalled }
        guard container != nil else { return .noSharedContainer }
        // Advisory only: Mi Speaks checks the gate itself and answers with `failure` if it's shut.
        // Asking here just saves an app switch to be told no.
        guard UserDefaults(suiteName: appGroup)?.bool(forKey: "hasPremium") == true else { return .notSubscribed }
        return .ready
    }

    static func publishedVoices() -> [Voice] {
        guard let url = container?.appendingPathComponent("Ari/voices.json"),
              let data = try? Data(contentsOf: url),
              let voices = try? JSONDecoder().decode([Voice].self, from: data) else { return [] }
        return voices
    }

    private static var inbox: URL? { container?.appendingPathComponent("Ari/jobs", isDirectory: true) }

    /// Writes the job file and returns its id. Separate from `render` so the file format can be
    /// exercised without opening Mi Speaks.
    static func writeJob(text: String, voiceID: String?, speed: Double) throws -> Job {
        guard let inbox else { throw Failure.unavailable(.noSharedContainer) }
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let job = Job(id: jobPrefix + UUID().uuidString, text: text, voiceID: voiceID, rate: min(1, max(0.25, speed / 2)))
        try JSONEncoder().encode(job).write(to: inbox.appendingPathComponent("\(job.id).json"), options: .atomic)
        return job
    }

    /// Asks Mi Speaks to read `text`, waits for it, and returns a local copy of the audio that the
    /// caller owns (and deletes). Cancelling the calling task abandons the job and cleans it up.
    static func render(_ text: String, voiceID: String?, speed: Double, timeout: Duration = .seconds(15 * 60)) async throws -> URL {
        let availability = availability
        guard availability == .ready, let container, let inbox else { throw Failure.unavailable(availability) }

        let job = try writeJob(text: text, voiceID: voiceID, speed: speed)
        let request = inbox.appendingPathComponent("\(job.id).json")
        let answer = inbox.appendingPathComponent("\(job.id).done.json")
        defer {
            try? FileManager.default.removeItem(at: request)
            try? FileManager.default.removeItem(at: answer)
        }

        guard await UIApplication.shared.open(URL(string: "\(scheme)://render?job=\(job.id)")!) else {
            throw Failure.unavailable(.notInstalled)
        }

        // While Mi Speaks is in front this app is suspended and the loop simply pauses; it
        // picks the answer up as soon as the listener comes back.
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            try Task.checkCancellation()
            if let data = try? Data(contentsOf: answer), let finished = try? JSONDecoder().decode(Job.self, from: data) {
                if let failure = finished.failure { throw Failure.refused(failure) }
                guard let name = finished.resultFilename else { throw Failure.refused("Mi Speaks returned no audio.") }
                let shared = container.appendingPathComponent(name)
                defer { try? FileManager.default.removeItem(at: shared) }
                let local = FileManager.default.temporaryDirectory.appendingPathComponent("\(job.id).caf")
                try? FileManager.default.removeItem(at: local)
                try FileManager.default.copyItem(at: shared, to: local)
                return local
            }
            try await Task.sleep(for: .milliseconds(400))
        }
        throw Failure.timedOut
    }

    /// Removes any of our job files left behind by a render that was abandoned mid-way
    /// (the app was killed while Mi Speaks was rendering).
    static func sweepStaleJobs() {
        guard let container else { return }
        let fm = FileManager.default
        for folder in ["Ari/jobs", "Ari/out"] {
            let dir = container.appendingPathComponent(folder, isDirectory: true)
            for name in (try? fm.contentsOfDirectory(atPath: dir.path)) ?? [] where name.hasPrefix(jobPrefix) {
                try? fm.removeItem(at: dir.appendingPathComponent(name))
            }
        }
        let tmp = fm.temporaryDirectory
        for name in (try? fm.contentsOfDirectory(atPath: tmp.path)) ?? [] where name.hasPrefix(jobPrefix) {
            try? fm.removeItem(at: tmp.appendingPathComponent(name))
        }
    }
}
#endif
