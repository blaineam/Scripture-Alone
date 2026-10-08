package com.blainemiller.scripturealone.data.listen

import com.blainemiller.scripturealone.data.listen.ListenPresentation.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Listen player's full bar or minimized into the Listen button — mirrors iOS's ListenPresentationTests. */
class ListenPresentationTest {

    @Test
    fun startsHiddenAndASessionOpensTheFullBar() {
        val player = ListenPresentation()
        assertEquals(Mode.HIDDEN, player.mode)
        assertFalse(player.isPresented)
        player.sessionStarted()
        assertEquals(Mode.EXPANDED, player.mode)
        assertTrue(player.isPresented)
        assertFalse(player.isMinimized)
    }

    @Test
    fun minimizeKeepsThePlayerUpAndExpandBringsTheBarBack() {
        val player = ListenPresentation()
        player.sessionStarted()
        player.minimize()
        assertEquals(Mode.MINIMIZED, player.mode)
        assertTrue("a minimized player is still up: reading goes on", player.isPresented)
        player.expand()
        assertEquals(Mode.EXPANDED, player.mode)
    }

    @Test
    fun minimizeAndExpandDoNothingWithNoSession() {
        val player = ListenPresentation()
        player.minimize()
        assertEquals(Mode.HIDDEN, player.mode)
        player.expand()
        assertEquals(Mode.HIDDEN, player.mode)
    }

    @Test
    fun everyNewSessionOpensExpanded() {
        val player = ListenPresentation()
        player.sessionStarted()
        player.minimize()
        player.sessionStarted()
        assertEquals(Mode.EXPANDED, player.mode)
        player.minimize()
        player.sessionEnded()
        player.sessionStarted()
        assertEquals("minimizing is never remembered past a session", Mode.EXPANDED, player.mode)
    }

    @Test
    fun stopEndsTheBarAndTheMinimizedPlayer() {
        val player = ListenPresentation()
        player.sessionStarted()
        player.minimize()
        player.sessionEnded()
        assertEquals(Mode.HIDDEN, player.mode)
    }

    @Test
    fun theEndOfReadingClosesAMinimizedPlayerButLeavesTheBar() {
        val player = ListenPresentation()
        player.sessionStarted()
        assertFalse("the full bar stays up, paused, to read again", player.passEndedCloses(withNotice = false))
        assertEquals(Mode.EXPANDED, player.mode)
        player.minimize()
        assertTrue("a minimized session ends when reading ends", player.passEndedCloses(withNotice = false))
    }

    @Test
    fun aNoticeExpandsAMinimizedPlayer() {
        val player = ListenPresentation()
        player.sessionStarted()
        player.minimize()
        assertFalse(player.passEndedCloses(withNotice = true))
        assertEquals(Mode.EXPANDED, player.mode)
        player.minimize()
        player.noticeShown()
        assertEquals(Mode.EXPANDED, player.mode)
        player.sessionEnded()
        player.noticeShown()
        assertEquals("a notice never brings a closed player back", Mode.HIDDEN, player.mode)
    }
}
