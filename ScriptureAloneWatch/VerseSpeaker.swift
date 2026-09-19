import AVFoundation
import Observation

/// Reads a passage aloud on the watch.
///
/// Live `AVSpeechSynthesizer.speak()` on Apple Watch plays only to Bluetooth (or nothing) on
/// Series 4 and later, and synthesizer buffers fed to AVAudioEngine have come out silent. What
/// reliably reaches the built-in speaker is rendering the speech to a file and playing that
/// file with AVAudioPlayer on a `.playback` session with the default route policy.
@Observable
final class VerseSpeaker {
    private(set) var isSpeaking = false
    @ObservationIgnored private let synthesizer = AVSpeechSynthesizer()
    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var generation = 0

    func toggle(_ text: String) {
        if isSpeaking { stop() } else { speak(text) }
    }

    func speak(_ text: String) {
        stop()
        guard !text.isEmpty else { return }
        generation += 1
        let current = generation
        isSpeaking = true

        let url = FileManager.default.temporaryDirectory.appending(path: "verse-\(current).caf")
        try? FileManager.default.removeItem(at: url)
        let writer = SpeechFileWriter(url: url)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.95

        let rendered: @MainActor @Sendable (Bool) -> Void = { [weak self] ready in
            self?.play(url, ready: ready, generation: current)
        }
        // The callback arrives on a synthesizer queue: keep it @Sendable and hop back to the
        // main actor only to start playback.
        synthesizer.write(utterance) { @Sendable buffer in
            guard let pcm = buffer as? AVAudioPCMBuffer else { return }
            if pcm.frameLength == 0 {
                let finished = writer.finish()
                Task { @MainActor in rendered(finished) }
            } else {
                writer.append(pcm)
            }
        }
    }

    func stop() {
        generation += 1
        synthesizer.stopSpeaking(at: .immediate)
        player?.stop()
        player = nil
        isSpeaking = false
    }

    private func play(_ url: URL, ready: Bool, generation: Int) {
        guard generation == self.generation, ready else {
            if generation == self.generation { isSpeaking = false }
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, policy: .default, options: [])
            try session.setActive(true)
            let player = try AVAudioPlayer(contentsOf: url)
            self.player = player
            player.play()
            let duration = player.duration
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(duration + 0.2))
                guard let self, generation == self.generation else { return }
                self.isSpeaking = false
            }
        } catch {
            isSpeaking = false
        }
    }
}

/// Collects synthesized PCM into a file off the main actor.
private nonisolated final class SpeechFileWriter: @unchecked Sendable {
    private let url: URL
    private let lock = NSLock()
    private var file: AVAudioFile?
    private var wrote = false

    init(url: URL) { self.url = url }

    func append(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        defer { lock.unlock() }
        if file == nil {
            file = try? AVAudioFile(forWriting: url, settings: buffer.format.settings,
                                    commonFormat: buffer.format.commonFormat, interleaved: buffer.format.isInterleaved)
        }
        if (try? file?.write(from: buffer)) != nil { wrote = true }
    }

    /// Closes the file; true if any audio was written.
    func finish() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        file = nil
        return wrote
    }
}
