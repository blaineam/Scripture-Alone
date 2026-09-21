package com.blainemiller.scripturealone.ui.reader

import android.view.KeyEvent

/** What a hardware-keyboard shortcut asks the reader to do. */
enum class ReaderCommand(val title: String) {
    GO_TO("Go to Passage"),
    SEARCH("Search"),
    PREVIOUS_CHAPTER("Previous Chapter"),
    NEXT_CHAPTER("Next Chapter"),
    NOTES("Notes"),
    STUDY("Study"),
    STUDY_BACK("Back in Study"),
    MAPS("Maps & Timeline"),
    FAVORITE("Add to or Remove from Favorites"),
    LARGER_TEXT("Larger Text"),
    SMALLER_TEXT("Smaller Text"),
}

/**
 * The reader's keyboard shortcuts — the iPad and Mac app's `keyboardShortcut`s, with Ctrl for ⌘ and
 * Alt for ⌥, as Android and ChromeOS spell them:
 *
 * | iOS | Android | |
 * |---|---|---|
 * | ⌘L | Ctrl+L | Go to passage (the passage title) |
 * | — | Ctrl+F | Search — the Go To sheet, whose field searches; iOS has no ⌘F |
 * | ⌘[ / ⌘] | Ctrl+[ / Ctrl+] | Previous / next chapter |
 * | ⇧⌘N | Ctrl+Shift+N | Notes |
 * | ⌥⌘S | Ctrl+Alt+S | Study |
 * | ⌥⌘[ | Ctrl+Alt+[ | Study's back trail |
 * | ⇧⌘M | Ctrl+Shift+M | Maps & Timeline (Study's Context tab) |
 * | ⌘D | Ctrl+D | Favorite the selection |
 * | ⌘+ / ⌘− | Ctrl+= (or +) / Ctrl+− | Text size, a point at a time |
 * | Esc | Esc | Clear the selection — Android turns an unhandled Esc into Back, which the reader handles |
 *
 * Share Designer's ⌘S (Save Image…) is Mac-only on iOS and isn't carried over. Pure apart from
 * `KeyEvent`'s constants, so matching is unit-tested.
 */
object ReaderShortcuts {

    data class Shortcut(val command: ReaderCommand, val keyCode: Int, val ctrl: Boolean = true, val shift: Boolean = false, val alt: Boolean = false) {
        /** For `KeyboardShortcutInfo`: the modifier flags. */
        val modifiers: Int
            get() = (if (ctrl) KeyEvent.META_CTRL_ON else 0) or (if (shift) KeyEvent.META_SHIFT_ON else 0) or
                (if (alt) KeyEvent.META_ALT_ON else 0)
    }

    /** Every shortcut, in the order the keyboard shortcuts helper lists them. */
    val all: List<Shortcut> = listOf(
        Shortcut(ReaderCommand.GO_TO, KeyEvent.KEYCODE_L),
        Shortcut(ReaderCommand.SEARCH, KeyEvent.KEYCODE_F),
        Shortcut(ReaderCommand.PREVIOUS_CHAPTER, KeyEvent.KEYCODE_LEFT_BRACKET),
        Shortcut(ReaderCommand.NEXT_CHAPTER, KeyEvent.KEYCODE_RIGHT_BRACKET),
        Shortcut(ReaderCommand.NOTES, KeyEvent.KEYCODE_N, shift = true),
        Shortcut(ReaderCommand.STUDY, KeyEvent.KEYCODE_S, alt = true),
        Shortcut(ReaderCommand.STUDY_BACK, KeyEvent.KEYCODE_LEFT_BRACKET, alt = true),
        Shortcut(ReaderCommand.MAPS, KeyEvent.KEYCODE_M, shift = true),
        Shortcut(ReaderCommand.FAVORITE, KeyEvent.KEYCODE_D),
        Shortcut(ReaderCommand.LARGER_TEXT, KeyEvent.KEYCODE_EQUALS),
        Shortcut(ReaderCommand.SMALLER_TEXT, KeyEvent.KEYCODE_MINUS),
    )

    /** Keys that mean the same as one in [all] but aren't listed twice: + typed with Shift, the keypad. */
    private val aliases: List<Shortcut> = listOf(
        Shortcut(ReaderCommand.LARGER_TEXT, KeyEvent.KEYCODE_EQUALS, shift = true),
        Shortcut(ReaderCommand.LARGER_TEXT, KeyEvent.KEYCODE_PLUS),
        Shortcut(ReaderCommand.LARGER_TEXT, KeyEvent.KEYCODE_PLUS, shift = true),
        Shortcut(ReaderCommand.LARGER_TEXT, KeyEvent.KEYCODE_NUMPAD_ADD),
        Shortcut(ReaderCommand.SMALLER_TEXT, KeyEvent.KEYCODE_NUMPAD_SUBTRACT),
    )

    /**
     * The command for a key press, or null. Only Ctrl, Shift and Alt count — Caps Lock, Num Lock and
     * the like are ignored — and they must match exactly, so Ctrl+Shift+L is not Ctrl+L. A press
     * with Meta (the launcher's key) is never the reader's.
     */
    fun match(keyCode: Int, metaState: Int): ReaderCommand? {
        if (metaState and KeyEvent.META_META_ON != 0) return null
        val ctrl = metaState and KeyEvent.META_CTRL_ON != 0
        val shift = metaState and KeyEvent.META_SHIFT_ON != 0
        val alt = metaState and KeyEvent.META_ALT_ON != 0
        return (all + aliases).firstOrNull { it.keyCode == keyCode && it.ctrl == ctrl && it.shift == shift && it.alt == alt }?.command
    }

    /** Commands that make sense held down, repeating: paging and text size. */
    fun repeats(command: ReaderCommand): Boolean = command in setOf(
        ReaderCommand.PREVIOUS_CHAPTER, ReaderCommand.NEXT_CHAPTER, ReaderCommand.LARGER_TEXT, ReaderCommand.SMALLER_TEXT,
    )
}
