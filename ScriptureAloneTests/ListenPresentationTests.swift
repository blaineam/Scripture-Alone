import Testing
@testable import Scripture_Alone

/// How the Listen player shows: hidden, the full bar, or the minimized pill — and Silent mode's mute,
/// which the pill also shows. (Mirrors Android's ListenPresentationTest and ListenMuteTest.)
struct ListenPresentationTests {
    @Test func startsHiddenAndASessionOpensTheFullBar() {
        var player = ListenPresentation()
        #expect(player.mode == .hidden)
        #expect(!player.isPresented)
        player.sessionStarted()
        #expect(player.mode == .expanded)
        #expect(player.isPresented)
        #expect(!player.isMinimized)
    }

    @Test func minimizeKeepsThePlayerUpAndExpandBringsTheBarBack() {
        var player = ListenPresentation()
        player.sessionStarted()
        player.minimize()
        #expect(player.mode == .minimized)
        #expect(player.isPresented, "a minimized player is still up: reading goes on")
        player.expand()
        #expect(player.mode == .expanded)
    }

    @Test func minimizeAndExpandDoNothingWithNoSession() {
        var player = ListenPresentation()
        player.minimize()
        #expect(player.mode == .hidden)
        player.expand()
        #expect(player.mode == .hidden)
    }

    @Test func everyNewSessionOpensExpanded() {
        var player = ListenPresentation()
        player.sessionStarted()
        player.minimize()
        // Listen again (a selection, the toolbar after Stop, an intent) while the pill is up.
        player.sessionStarted()
        #expect(player.mode == .expanded)
        player.minimize()
        player.sessionEnded()
        player.sessionStarted()
        #expect(player.mode == .expanded, "minimizing is never remembered past a session")
    }

    @Test func stopHidesTheBarAndThePill() {
        var player = ListenPresentation()
        player.sessionStarted()
        player.sessionEnded()
        #expect(player.mode == .hidden)
        player.sessionStarted()
        player.minimize()
        player.sessionEnded()
        #expect(player.mode == .hidden)
    }

    @Test func theEndOfReadingClosesThePillButLeavesTheBar() {
        var player = ListenPresentation()
        player.sessionStarted()
        let barCloses = player.passEndedCloses(withNotice: false)
        #expect(!barCloses, "the full bar stays up, paused, to read again")
        #expect(player.mode == .expanded)
        player.minimize()
        let pillCloses = player.passEndedCloses(withNotice: false)
        #expect(pillCloses, "the pill goes away when reading ends")
    }

    @Test func aNoticeOpensThePill() {
        var player = ListenPresentation()
        player.sessionStarted()
        player.minimize()
        let closesWithNotice = player.passEndedCloses(withNotice: true)
        #expect(!closesWithNotice, "a notice at the end is shown, not dropped")
        #expect(player.mode == .expanded)
        player.minimize()
        player.noticeShown()
        #expect(player.mode == .expanded)
        player.sessionEnded()
        player.noticeShown()
        #expect(player.mode == .hidden, "a notice never brings a closed player back")
    }

    // MARK: Silent mode

    @Test func silentModeMutesUntilUnmuteForTheSession() {
        var mute = ListenMute()
        mute.begin(switchSilenced: true)
        #expect(mute.muted)
        mute.unmute()
        #expect(!mute.muted)
        // The switch read again without moving: Unmute holds.
        mute.switchRead(silenced: true)
        #expect(!mute.muted)
        mute.end()
        mute.begin(switchSilenced: true)
        #expect(mute.muted, "a new session reads the switch afresh")
    }

    @Test func aSwitchThatMovesMidSessionApplies() {
        var mute = ListenMute()
        mute.begin(switchSilenced: false)
        mute.switchRead(silenced: true)
        #expect(mute.muted)
        mute.switchRead(silenced: false)
        #expect(!mute.muted)
    }
}
