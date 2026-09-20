import AVFoundation
import Foundation
import MediaPlayer
import Observation
import ScriptureAloneCore
#if os(iOS)
import UIKit
#endif

/// Reads the Bible aloud. One per app: there is one speaker, so whichever window starts
/// listening owns it, and only that window marks the spoken verse.
///
/// System voices speak verse by verse through `AVSpeechSynthesizer`, all of the chapter queued at
/// once so there are no gaps; the synthesizer's "started utterance" callback moves the marker.
/// Mi Speaks Studio voices (iOS) arrive as one rendered clip per chapter — see `MiSpeaksClient`.
@Observable
final class ListenController {
    static let shared = ListenController()

    enum Phase: Equatable {
        case idle
        case preparing(String)
        case playing
        case paused
    }

    /// One thing to say: a verse, or the chapter announcement (`key == 0`).
    struct Item: Equatable {
        let key: Int
        let text: String
    }

    private enum Scope: Equatable {
        /// From a verse to the end of the chapter, then (optionally) on into the next.
        case chapter(ChapterRef)
        /// Just these verses, then stop.
        case selection
    }

    private(set) var phase: Phase = .idle
    /// The verse being read, while listening.
    private(set) var speakingVerse: Int?
    /// A short note for the bar — a fallback, a failure, a sleep timer that ended.
    var notice: String?
    /// True while the bar should show.
    private(set) var isPresented = false
    private(set) var sleepTimer: SleepTimer = .off

    var speed: Double {
        didSet {
            defaults.set(speed, forKey: SettingsKey.listenSpeed)
            speedChanged()
        }
    }
    var voiceID: String? {
        didSet {
            defaults.set(voiceID, forKey: SettingsKey.listenVoice)
            if engine == .system { restartFromCurrent() }
        }
    }
    var studioVoiceID: String? {
        didSet { defaults.set(studioVoiceID, forKey: SettingsKey.listenStudioVoice) }
    }
    var engine: ListenEngine {
        didSet {
            defaults.set(engine.rawValue, forKey: SettingsKey.listenEngine)
            if oldValue != engine { restartFromCurrent() }
        }
    }
    var continueChapters: Bool {
        didSet { defaults.set(continueChapters, forKey: SettingsKey.listenContinue) }
    }

    @ObservationIgnored private weak var reader: ReaderModel?
    @ObservationIgnored private var scope: Scope = .selection
    @ObservationIgnored private var translation = ""
    /// The text being read, so the Studio hand-off can check its licence.
    @ObservationIgnored private var translationInfo: TranslationInfo?
    @ObservationIgnored private var items: [Item] = []
    @ObservationIgnored private var current = 0
    @ObservationIgnored private let defaults = UserDefaults.standard

    // System voice
    @ObservationIgnored private let synthesizer = AVSpeechSynthesizer()
    @ObservationIgnored private let speechDelegate = SpeechDelegate()
    /// Utterance token → index into `items`. Tokens are never reused, so a late callback from a
    /// cancelled queue can't be mistaken for the new one.
    @ObservationIgnored private var tokens: [Int: Int] = [:]
    @ObservationIgnored private var nextToken = 0
    @ObservationIgnored private var resolvedVoice: (id: String?, voice: AVSpeechSynthesisVoice?)?

    // Studio voice (rendered clip)
    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private let playerDelegate = PlayerDelegate()
    @ObservationIgnored private var clipURL: URL?
    @ObservationIgnored private var verseStarts: [TimeInterval] = []
    @ObservationIgnored private var renderedSpeed = 1.0
    @ObservationIgnored private var renderTask: Task<Void, Never>?
    @ObservationIgnored private var progressTask: Task<Void, Never>?

    @ObservationIgnored private var sleepTask: Task<Void, Never>?
    @ObservationIgnored private var remoteCommandsInstalled = false
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    private init() {
        let stored = defaults.double(forKey: SettingsKey.listenSpeed)
        speed = stored > 0 ? stored : 1
        voiceID = defaults.string(forKey: SettingsKey.listenVoice)
        studioVoiceID = defaults.string(forKey: SettingsKey.listenStudioVoice)
        engine = defaults.string(forKey: SettingsKey.listenEngine).flatMap(ListenEngine.init(rawValue:)) ?? .system
        continueChapters = defaults.object(forKey: SettingsKey.listenContinue) as? Bool ?? true

        synthesizer.delegate = speechDelegate
        speechDelegate.onEvent = { [weak self] event in
            Task { @MainActor in self?.handle(event) }
        }
        playerDelegate.onFinish = { [weak self] in
            Task { @MainActor in self?.passFinished() }
        }
        #if os(iOS)
        MiSpeaksClient.sweepStaleJobs()
        observeAudioSession()
        #endif
    }

    // MARK: Queries for the reader

    /// The spoken verse, if this window is the one listening.
    /// (The observed property is read first on purpose: `reader` isn't observed, and short-circuiting
    /// on it would leave the view untracked — the bar then never appeared on first tap.)
    func speakingVerse(in model: ReaderModel) -> Int? {
        let verse = speakingVerse
        return reader === model ? verse : nil
    }

    func isListening(in model: ReaderModel) -> Bool {
        let presented = isPresented
        return presented && reader === model
    }

    var isPlaying: Bool { phase == .playing }

    var nowPlayingTitle: String {
        guard let key = speakingVerse ?? items.first(where: { $0.key > 0 })?.key, let ref = VerseRef(key: key) else {
            return reader?.location.display ?? ""
        }
        return ref.display
    }

    /// The Mi Speaks engine is only offered where Mi Speaks runs.
    static var studioSupported: Bool {
        #if os(iOS)
        true
        #else
        false
        #endif
    }

    // MARK: Starting

    /// Plays the current chapter from the verse at the top of the screen.
    func playChapter(in model: ReaderModel) {
        let chapter = model.location
        let top = model.topVerse.flatMap(VerseRef.init(key:)).flatMap { $0.chapterKey == chapter ? $0.verse : nil } ?? 1
        start(in: model, scope: .chapter(chapter), items: chapterItems(model: model, chapter: chapter, from: top))
    }

    /// Plays the selected verses, then stops.
    func playSelection(in model: ReaderModel) {
        guard let store = model.store else { return }
        let verses = model.selectedRanges.flatMap { (try? store.verses(in: $0)) ?? [] }
        start(in: model, scope: .selection, items: verses.map { Item(key: $0.ref.key, text: $0.text) })
    }

    /// The toolbar button: start if idle, otherwise play/pause.
    func toolbarAction(in model: ReaderModel) {
        if reader === model, phase != .idle {
            togglePlayPause()
        } else {
            playChapter(in: model)
        }
    }

    private func chapterItems(model: ReaderModel, chapter: ChapterRef, from verse: Int) -> [Item] {
        guard let store = model.store else { return [] }
        let count = store.verseCount(chapter)
        guard count > 0 else { return [] }
        let first = min(max(1, verse), count)
        let verses = (try? store.verses(in: VerseRange(VerseRef(chapter.book, chapter.chapter, first),
                                                         VerseRef(chapter.book, chapter.chapter, count)))) ?? []
        var result: [Item] = []
        if first == 1 {
            let title = chapter.book.isSingleChapter ? chapter.book.name : "\(chapter.book.name), chapter \(chapter.chapter)."
            result.append(Item(key: 0, text: title))
        }
        result += verses.map { Item(key: $0.ref.key, text: $0.text) }
        return result
    }

    private func start(in model: ReaderModel, scope: Scope, items: [Item]) {
        stopOutput()
        guard !items.isEmpty else { return }
        reader = model
        translation = model.translationID
        translationInfo = model.store?.info
        self.scope = scope
        self.items = items
        current = 0
        notice = nil
        isPresented = true
        activateSession()
        installRemoteCommands()
        begin(at: 0)
    }

    private func begin(at index: Int) {
        stopOutput()
        current = max(0, min(index, items.count - 1))
        speakingVerse = items[current].key > 0 ? items[current].key : items.first(where: { $0.key > 0 })?.key
        #if os(iOS)
        if engine == .miSpeaks {
            beginStudio(at: current)
            return
        }
        #endif
        beginSystem(at: current)
    }

    // MARK: Transport

    func togglePlayPause() {
        switch phase {
        case .playing: pause()
        case .paused: resume()
        case .idle: if let reader { playChapter(in: reader) }
        case .preparing: break
        }
    }

    func pause() {
        guard phase == .playing else { return }
        if let player {
            player.pause()
        } else {
            synthesizer.pauseSpeaking(at: .word)
        }
        phase = .paused
        updateNowPlaying()
    }

    func resume() {
        guard phase == .paused else { return }
        activateSession()
        if let player {
            player.play()
            phase = .playing
        } else if synthesizer.isPaused {
            synthesizer.continueSpeaking()
            phase = .playing
        } else {
            begin(at: current)
            return
        }
        updateNowPlaying()
    }

    func nextVerse() { skip(by: 1) }
    func previousVerse() { skip(by: -1) }

    private func skip(by delta: Int) {
        guard phase != .idle, !items.isEmpty else { return }
        var target = current + delta
        // Step over the chapter announcement.
        while target >= 0, target < items.count, items[target].key == 0 { target += delta > 0 ? 1 : -1 }
        if target < 0 { target = items.firstIndex { $0.key > 0 } ?? 0 }
        guard target < items.count else {
            passFinished()
            return
        }
        seek(to: target)
    }

    private func seek(to index: Int) {
        if let player, index < verseStarts.count {
            current = index
            player.currentTime = verseStarts[index]
            speakingVerse = items[index].key > 0 ? items[index].key : speakingVerse
            updateNowPlaying()
            return
        }
        if phase == .paused {
            holdPaused(at: index)
        } else {
            begin(at: index)
        }
    }

    /// Moves to a verse without speaking; play starts from there.
    private func holdPaused(at index: Int) {
        stopOutput()
        current = max(0, min(index, items.count - 1))
        if items[current].key > 0 { speakingVerse = items[current].key }
        phase = .paused
        updateNowPlaying()
    }

    /// Stops and hides the bar.
    func stop() {
        stopOutput()
        isPresented = false
        speakingVerse = nil
        items = []
        notice = nil
        setSleepTimer(.off)
        deactivateSession()
        clearNowPlaying()
    }

    private func stopOutput() {
        renderTask?.cancel()
        renderTask = nil
        tokens.removeAll()
        if synthesizer.isSpeaking || synthesizer.isPaused { synthesizer.stopSpeaking(at: .immediate) }
        progressTask?.cancel()
        progressTask = nil
        player?.stop()
        player = nil
        if let clipURL { try? FileManager.default.removeItem(at: clipURL) }
        clipURL = nil
        verseStarts = []
        phase = .idle
    }

    /// Voice or engine changed mid-read: carry on from the same verse.
    private func restartFromCurrent() {
        guard phase == .playing || phase == .paused, !items.isEmpty else { return }
        if phase == .paused {
            holdPaused(at: current)
        } else {
            begin(at: current)
        }
    }

    private func speedChanged() {
        if let player {
            player.rate = Float(min(2, max(0.5, speed / renderedSpeed)))
        } else if phase == .playing || phase == .paused {
            restartFromCurrent()
        }
    }

    // MARK: Sleep timer

    func setSleepTimer(_ timer: SleepTimer) {
        sleepTask?.cancel()
        sleepTask = nil
        sleepTimer = timer
        guard let duration = timer.duration else { return }
        sleepTask = Task { [weak self] in
            try? await Task.sleep(for: duration)
            guard !Task.isCancelled, let self else { return }
            self.pause()
            self.sleepTimer = .off
            self.notice = "Sleep timer ended."
        }
    }

    // MARK: End of a pass

    private func passFinished() {
        guard phase != .idle else { return }
        if case .chapter(let chapter) = scope, continueChapters, sleepTimer != .endOfChapter,
           let next = chapter.next, let reader {
            #if os(iOS)
            // A Studio render needs Mi Speaks in front, which can't happen from the background.
            if engine == .miSpeaks, UIApplication.shared.applicationState != .active {
                endPass(notice: "Finished \(chapter.display). Open Scripture Alone to continue with \(next.display).")
                return
            }
            #endif
            reader.show(next)
            let items = chapterItems(model: reader, chapter: next, from: 1)
            guard !items.isEmpty else { return endPass(notice: nil) }
            stopOutput()
            scope = .chapter(next)
            self.items = items
            begin(at: 0)
            return
        }
        if sleepTimer == .endOfChapter { setSleepTimer(.off) }
        endPass(notice: nil)
    }

    /// Leaves the bar up, paused at the start of what was read, so play reads it again.
    private func endPass(notice: String?) {
        stopOutput()
        current = items.first?.key == 0 ? 1 : 0
        phase = items.isEmpty ? .idle : .paused
        speakingVerse = nil
        self.notice = notice
        updateNowPlaying()
    }

    // MARK: System voice

    private func beginSystem(at index: Int) {
        if let cached = resolvedVoice, cached.id == voiceID {
            speakSystem(from: index, voice: cached.voice)
            return
        }
        // Voice lookup has to happen off the main thread (see SpeechVoices).
        phase = .preparing("Loading voice…")
        let wanted = voiceID
        renderTask = Task { [weak self] in
            let resolved = await SpeechVoices.voice(for: wanted)
            guard let self, !Task.isCancelled else { return }
            self.renderTask = nil
            switch resolved {
            case .voice(let voice):
                self.resolvedVoice = (wanted, voice)
                self.speakSystem(from: index, voice: voice)
            case .timedOut:
                // Not cached, so the next start asks again.
                self.speakSystem(from: index, voice: nil)
            }
        }
    }

    private func speakSystem(from index: Int, voice: AVSpeechSynthesisVoice?) {
        tokens.removeAll()
        let rate = SpeechVoices.utteranceRate(forSpeed: speed)
        for i in index..<items.count {
            nextToken += 1
            let utterance = TokenUtterance(string: items[i].text, token: nextToken)
            utterance.voice = voice
            utterance.rate = rate
            // A breath between verses; a longer one after the chapter announcement.
            utterance.postUtteranceDelay = items[i].key == 0 ? 0.5 : 0.12
            tokens[nextToken] = i
            synthesizer.speak(utterance)
        }
        phase = .playing
        updateNowPlaying()
    }

    private func handle(_ event: SpeechDelegate.Event) {
        switch event {
        case .started(let token):
            guard let index = tokens[token] else { return }
            current = index
            if items[index].key > 0 { speakingVerse = items[index].key }
            updateNowPlaying()
        case .finished(let token):
            guard let index = tokens[token] else { return }
            tokens[token] = nil
            if index == items.count - 1 { passFinished() }
        }
    }

    // MARK: Studio voice (Mi Speaks)

    #if os(iOS)
    private func beginStudio(at index: Int) {
        let availability = MiSpeaksClient.availability(for: translationInfo)
        guard availability == .ready else {
            fallBackToSystem(at: index, because: availability.explanation)
            return
        }
        let slice = Array(items[index...])
        let text = slice.map(\.text).joined(separator: " ")
        let speed = self.speed
        let voice = studioVoiceID
        phase = .preparing("Rendering in Mi Speaks…")
        notice = "Mi Speaks opens to record this chapter. Come back when it’s done."
        renderTask = Task { [weak self] in
            do {
                let url = try await MiSpeaksClient.render(text, voiceID: voice, speed: speed)
                guard let self, !Task.isCancelled else {
                    try? FileManager.default.removeItem(at: url)
                    return
                }
                self.playClip(url, slice: slice, offset: index, renderedAt: speed)
            } catch is CancellationError {
                return
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.fallBackToSystem(at: index, because: error.localizedDescription)
            }
        }
    }

    private func fallBackToSystem(at index: Int, because reason: String) {
        notice = "\(reason) Reading with the system voice."
        beginSystem(at: index)
    }

    private func playClip(_ url: URL, slice: [Item], offset: Int, renderedAt speed: Double) {
        renderTask = nil
        do {
            let player = try AVAudioPlayer(contentsOf: url)
            player.delegate = playerDelegate
            player.enableRate = true
            renderedSpeed = speed
            player.rate = Float(min(2, max(0.5, self.speed / speed)))
            // Mi Speaks returns one clip without timings; place each verse by its share of the text.
            let weights = slice.map { Double($0.text.count + 1) }
            let total = weights.reduce(0, +)
            var starts = Array(repeating: 0.0, count: offset)
            var running = 0.0
            for weight in weights {
                starts.append(player.duration * running / max(total, 1))
                running += weight
            }
            verseStarts = starts
            clipURL = url
            self.player = player
            activateSession()
            player.play()
            phase = .playing
            notice = nil
            updateNowPlaying()
            progressTask = Task { [weak self] in
                while !Task.isCancelled {
                    self?.trackClipProgress()
                    try? await Task.sleep(for: .milliseconds(250))
                }
            }
        } catch {
            try? FileManager.default.removeItem(at: url)
            fallBackToSystem(at: offset, because: "Couldn’t play the Mi Speaks audio.")
        }
    }

    private func trackClipProgress() {
        guard let player, player.isPlaying else { return }
        let time = player.currentTime
        guard let index = verseStarts.lastIndex(where: { $0 <= time }), index != current else { return }
        current = index
        if items[index].key > 0 { speakingVerse = items[index].key }
        updateNowPlaying()
    }
    #endif

    // MARK: Audio session (iOS)

    private func activateSession() {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio)
        try? session.setActive(true)
        #endif
    }

    private func deactivateSession() {
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    #if os(iOS)
    private func observeAudioSession() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt)
                .map(AVAudioSession.InterruptionOptions.init(rawValue:)) ?? []
            MainActor.assumeIsolated {
                let controller = ListenController.shared
                if type == .began {
                    controller.pause()
                } else if type == .ended, options.contains(.shouldResume) {
                    controller.resume()
                }
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                .flatMap(AVAudioSession.RouteChangeReason.init)
            // Headphones pulled out: pause rather than read aloud to the room.
            guard reason == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { ListenController.shared.pause() }
        })
    }
    #endif

    // MARK: Now Playing and remote commands

    private func installRemoteCommands() {
        guard !remoteCommandsInstalled else { return }
        remoteCommandsInstalled = true
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { @Sendable _ in
            Task { @MainActor in ListenController.shared.resume() }
            return .success
        }
        center.pauseCommand.addTarget { @Sendable _ in
            Task { @MainActor in ListenController.shared.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { @Sendable _ in
            Task { @MainActor in ListenController.shared.togglePlayPause() }
            return .success
        }
        center.nextTrackCommand.addTarget { @Sendable _ in
            Task { @MainActor in ListenController.shared.nextVerse() }
            return .success
        }
        center.previousTrackCommand.addTarget { @Sendable _ in
            Task { @MainActor in ListenController.shared.previousVerse() }
            return .success
        }
    }

    private func updateNowPlaying() {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: nowPlayingTitle,
            MPMediaItemPropertyArtist: "Scripture Alone",
            MPMediaItemPropertyAlbumTitle: "\(reader?.location.display ?? "") · \(translation)",
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyPlaybackRate: phase == .playing ? 1.0 : 0.0,
        ]
        if let player {
            info[MPMediaItemPropertyPlaybackDuration] = player.duration
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = player.currentTime
            info[MPNowPlayingInfoPropertyPlaybackRate] = phase == .playing ? Double(player.rate) : 0.0
        }
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = info
        #if os(macOS)
        center.playbackState = phase == .playing ? .playing : .paused
        #endif
    }

    private func clearNowPlaying() {
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = nil
        #if os(macOS)
        center.playbackState = .stopped
        #endif
    }
}

/// Carries a token the delegate can read without touching main-actor state.
private nonisolated final class TokenUtterance:AVSpeechUtterance, @unchecked Sendable {
    let token: Int

    init(string: String, token: Int) {
        self.token = token
        super.init(string: string)
    }

    required init?(coder: NSCoder) {
        token = 0
        super.init(coder: coder)
    }
}

/// AVSpeechSynthesizer calls its delegate off the main thread; this forwards plain tokens.
private nonisolated final class SpeechDelegate: NSObject, AVSpeechSynthesizerDelegate, @unchecked Sendable {
    enum Event: Sendable {
        case started(Int)
        case finished(Int)
    }

    /// Set once, before the synthesizer speaks.
    var onEvent: (@Sendable (Event) -> Void)?

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        guard let token = (utterance as? TokenUtterance)?.token else { return }
        onEvent?(.started(token))
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        guard let token = (utterance as? TokenUtterance)?.token else { return }
        onEvent?(.finished(token))
    }
}

private nonisolated final class PlayerDelegate: NSObject, AVAudioPlayerDelegate, @unchecked Sendable {
    /// Set once, before playback.
    var onFinish: (@Sendable () -> Void)?

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        onFinish?()
    }
}
