package com.blainemiller.scripturealone.ui.reader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iPad and Mac shortcuts, with Ctrl for ⌘ and Alt for ⌥. */
class ReaderShortcutsTest {

    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    @Test
    fun theIosShortcutsMatch() {
        assertEquals(ReaderCommand.GO_TO, ReaderShortcuts.match(KeyEvent.KEYCODE_L, ctrl))
        assertEquals(ReaderCommand.SEARCH, ReaderShortcuts.match(KeyEvent.KEYCODE_F, ctrl))
        assertEquals(ReaderCommand.PREVIOUS_CHAPTER, ReaderShortcuts.match(KeyEvent.KEYCODE_LEFT_BRACKET, ctrl))
        assertEquals(ReaderCommand.NEXT_CHAPTER, ReaderShortcuts.match(KeyEvent.KEYCODE_RIGHT_BRACKET, ctrl))
        assertEquals(ReaderCommand.NOTES, ReaderShortcuts.match(KeyEvent.KEYCODE_N, ctrl or shift))
        assertEquals(ReaderCommand.STUDY, ReaderShortcuts.match(KeyEvent.KEYCODE_S, ctrl or alt))
        assertEquals(ReaderCommand.STUDY_BACK, ReaderShortcuts.match(KeyEvent.KEYCODE_LEFT_BRACKET, ctrl or alt))
        assertEquals(ReaderCommand.MAPS, ReaderShortcuts.match(KeyEvent.KEYCODE_M, ctrl or shift))
        assertEquals(ReaderCommand.FAVORITE, ReaderShortcuts.match(KeyEvent.KEYCODE_D, ctrl))
        assertEquals(ReaderCommand.LARGER_TEXT, ReaderShortcuts.match(KeyEvent.KEYCODE_EQUALS, ctrl))
        assertEquals(ReaderCommand.SMALLER_TEXT, ReaderShortcuts.match(KeyEvent.KEYCODE_MINUS, ctrl))
    }

    @Test
    fun plusCanBeTypedAnyWay() {
        for ((key, meta) in listOf(KeyEvent.KEYCODE_EQUALS to (ctrl or shift), KeyEvent.KEYCODE_PLUS to ctrl, KeyEvent.KEYCODE_NUMPAD_ADD to ctrl)) {
            assertEquals(ReaderCommand.LARGER_TEXT, ReaderShortcuts.match(key, meta))
        }
        assertEquals(ReaderCommand.SMALLER_TEXT, ReaderShortcuts.match(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, ctrl))
    }

    @Test
    fun modifiersMustMatchExactly() {
        assertNull("no Ctrl", ReaderShortcuts.match(KeyEvent.KEYCODE_L, 0))
        assertNull("typing a bracket", ReaderShortcuts.match(KeyEvent.KEYCODE_RIGHT_BRACKET, 0))
        assertNull("Ctrl+Shift+L isn't Ctrl+L", ReaderShortcuts.match(KeyEvent.KEYCODE_L, ctrl or shift))
        assertNull("Ctrl+N is not Notes", ReaderShortcuts.match(KeyEvent.KEYCODE_N, ctrl))
        assertNull("Meta belongs to the system", ReaderShortcuts.match(KeyEvent.KEYCODE_L, ctrl or KeyEvent.META_META_ON))
    }

    @Test
    fun lockKeysDoNotGetInTheWay() {
        val locks = KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON
        assertEquals(ReaderCommand.GO_TO, ReaderShortcuts.match(KeyEvent.KEYCODE_L, ctrl or locks))
    }

    @Test
    fun everyCommandIsListedOnceForTheHelper() {
        val listed = ReaderShortcuts.all.map { it.command }
        assertEquals(ReaderCommand.entries.toSet(), listed.toSet())
        assertEquals(listed.size, listed.toSet().size)
        // No two listed shortcuts share keys.
        assertEquals(ReaderShortcuts.all.size, ReaderShortcuts.all.map { it.keyCode to it.modifiers }.toSet().size)
        assertTrue(ReaderShortcuts.all.all { it.modifiers and KeyEvent.META_CTRL_ON != 0 })
    }
}
