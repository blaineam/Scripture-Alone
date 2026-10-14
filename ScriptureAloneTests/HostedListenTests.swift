#if os(iOS)
import SwiftUI
import SwiftData
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// Listen's transport and its player bar, with the speaker turned silent (`speaksSilentlyForTesting`):
/// the same path a spoken verse takes — the marker, the bar, Now Playing, the end of a pass — with no
/// voice lookup and no sound. iPhone and iPad only: starting a session claims the system's Now Playing
/// and remote commands, which a unit test must not do to a Mac.
@MainActor
@Suite(.serialized)
struct HostedListenTests {
    let container: ModelContainer
    let model: ReaderModel
    let listen = ListenController.shared

    init() throws {
        container = try ViewHost.container()
        model = ViewHost.readerModel()
        listen.stop()
        listen.speaksSilentlyForTesting = true
    }

    func finish() {
        listen.stop()
        listen.speaksSilentlyForTesting = false
    }

    func draw<V: View>(_ view: V, dark: Bool = false) async -> HostedView {
        await ViewHost.render(ViewHost.inReader(view, model: model, container: container), size: CGSize(width: 390, height: 844), dark: dark)
    }

    @Test func aChapterPlaysPausesSkipsAndStops() async {
        defer { finish() }
        model.show(ChapterRef(.john, 3))
        listen.playChapter(in: model)
        #expect(listen.isListening(in: model))
        #expect(listen.isPlaying)
        #expect(listen.speakingVerse(in: model) == VerseRef(.john, 3, 1).key)

        listen.nextVerse()
        let second = listen.speakingVerse(in: model)
        listen.nextVerse()
        #expect(listen.speakingVerse(in: model) != second)
        listen.previousVerse()
        #expect(listen.speakingVerse(in: model) == second)
        #expect(listen.nowPlayingTitle.hasPrefix("John 3"))

        listen.togglePlayPause()
        #expect(!listen.isPlaying)
        listen.togglePlayPause()
        #expect(listen.isPlaying)
        listen.pause()
        listen.resume()
        listen.toolbarAction(in: model)
        #expect(!listen.isPlaying, "the toolbar button pauses a session that is up")

        listen.stop()
        #expect(!listen.isListening(in: model))
        #expect(listen.speakingVerse(in: model) == nil)
        listen.togglePlayPause()
        #expect(!listen.isPlaying, "nothing to toggle once stopped")
    }

    @Test func thePlayerBarDrawsInEveryStateAndInTheReader() async {
        defer { finish() }
        listen.playChapter(in: model)
        #expect(await draw(NowPlayingBar()).isDrawn)
        #expect(await draw(NowPlayingBar(), dark: true).isDrawn)
        #expect(await draw(ReaderView()).isDrawn, "the bar floats over the page")
        listen.minimize()
        #expect(listen.isMinimized)
        #expect(await draw(ReaderView()).isDrawn, "minimized: no bar, the toolbar shows the session")
        listen.expand()
        #expect(!listen.isMinimized)
        listen.pause()
        #expect(await draw(NowPlayingBar()).isDrawn)
        listen.setSleepTimer(.minutes15)
        #expect(listen.sleepTimer == .minutes15)
        #expect(await draw(NowPlayingBar()).isDrawn)
        listen.setSleepTimer(.endOfChapter)
        #expect(listen.sleepTimer == .endOfChapter)
        listen.setSleepTimer(.off)
        listen.notice = "Sleep timer ended."
        #expect(await draw(NowPlayingBar()).isDrawn)
    }

    @Test func aSelectionIsReadAndThenTheSessionEnds() async {
        defer { finish() }
        model.show(ChapterRef(.john, 3))
        model.selection = [VerseRef(.john, 3, 16).key, VerseRef(.john, 3, 17).key]
        listen.playSelection(in: model)
        #expect(listen.isListening(in: model))
        #expect(listen.speakingVerse(in: model) == VerseRef(.john, 3, 16).key)
        #expect(await draw(NowPlayingBar()).isDrawn)
        model.selection = []
    }

    @Test func speedAndVoiceChangesAreRemembered() {
        let defaults = UserDefaults.standard
        let speed = listen.speed, voice = listen.voiceID
        defer {
            listen.speed = speed
            listen.voiceID = voice
            finish()
        }
        listen.playChapter(in: model)
        listen.speed = 1.25
        #expect(defaults.double(forKey: SettingsKey.listenSpeed) == 1.25)
        listen.voiceID = "com.apple.voice.test"
        #expect(defaults.string(forKey: SettingsKey.listenVoice) == "com.apple.voice.test")
        listen.voiceID = nil
        #expect(listen.isListening(in: model))
    }
}
#endif
