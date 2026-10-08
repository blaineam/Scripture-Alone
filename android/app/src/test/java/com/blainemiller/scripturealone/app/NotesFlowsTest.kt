package com.blainemiller.scripturealone.app

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Notes, export, keepsakes, bringing notes from another app, and the Topics directory — on a phone,
 * end to end against the app's real database. Nothing here sends or downloads anything.
 */
@RunWith(AndroidJUnit4::class)
// A tall phone window, so the long lazy lists (Go To's books under its topics) compose whole.
@Config(qualifiers = "w411dp-h2400dp-xxhdpi")
class NotesFlowsTest : AppTest() {
    @get:Rule override val rule = createEmptyComposeRule()

    private val verse1 = "Verse 1. In the beginning was the Word"

    private fun typeInto(index: Int, text: String) {
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[index].performTextInput(text)
        settle()
    }

    private fun newNote(title: String) {
        tapDesc(verse1)
        tapDesc("Add Note")
        assertShown("the editor's passages", text("Passages"))
        assertShown("John 1:1 attached", text("John 1:1"))
        typeInto(0, title)
        // The editor keeps what is typed as it goes; Back closes it.
        tapDesc("Back", substring = false)
    }

    /** The editor closes back into the Notes panel it opened in; from the reader, the toolbar's Notes. */
    private fun openNotes() {
        if (desc("Notes", substring = false).exists()) tapDesc("Notes", substring = false)
    }

    @Test fun aNoteOnAPassageIsListedOpenedAndDeleted() {
        launch()
        newNote("In the beginning")
        openNotes()
        waitFor("the note in the list") { text("In the beginning", substring = false).exists() }
        tapText("This Chapter")
        assertShown("in this chapter's notes", text("In the beginning", substring = false))
        tapText("In the beginning", substring = false)
        tapDesc("More")
        tapText("Delete Note")
        assertShown("deleting asks first", text("Delete this note?"))
        // The menu's Delete Note, then the dialog's.
        val confirm = text("Delete Note", substring = false)
        confirm[confirm.fetchSemanticsNodes().size - 1].activate()
        waitFor("the note to leave the list") { !text("In the beginning", substring = false).exists() }
    }

    @Test fun notesExportPreparesAFile() {
        launch()
        newNote("Word")
        openNotes()
        tapDesc("Export")
        tapText("Export All Notes…")
        assertShown("the export sheet", text("Include Verse Text"))
        // Plain text: Robolectric's PdfDocument can't write pages ("document is closed!"); the PDF is
        // proven on the emulator (NotesPdfRendererTest) and its layout on the JVM (NotesPdfDocumentTest).
        tapText("Plain Text")
        tapText("Prepare Export")
        waitFor("the export to be ready") { text("Share…").exists() || text("Ready").exists() }
        assertShown("Save to Files", text("Save to Files…"))
    }

    @Test fun keepsakeAndExportOffersAKeepsake() {
        launch()
        tapDesc("Appearance", substring = false)
        tapText("Keepsake & Export")
        assertShown("the keepsake settings", text("Your Keepsake Bible"))
        tapText("Create a Keepsake")
        assertShown("what a keepsake is", text("A Keepsake Bible is a copy"))
        tapText("Protect with a Passphrase")
        assertShown("the passphrase", text("Passphrase"))
    }

    @Test fun notesPastedFromAnotherAppArePreviewedThenAdded() {
        launch()
        tapDesc("Appearance", substring = false)
        tapText("Keepsake & Export")
        tapText("Bring Notes From Another App…")
        assertShown("the import sheet", text("Bring Your Notes"))
        tapText("Paste Notes From Any App…")
        typeInto(0, "John 3:16 — the whole gospel in one verse\n\nRomans 8:28 — all things work together")
        tapText("Read", substring = false)
        waitFor("the preview") { text("Found in this file").exists() || text("Add 2 Items").exists() }
        tapText("Add 2 Items")
        waitFor("the notes brought across") { text("Brought across").exists() }
    }

    @Test fun theTopicsDirectoryAndACrisisSearch() {
        launch()
        tapDesc("Go to passage")
        tapText("See All")
        waitFor("the Topics directory") { desc("Search topics").exists() }
        assertShown("the life topics, by kind", text("WORRY & HARD FEELINGS"))
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[fields.fetchSemanticsNodes().size - 1].performTextInput("Abraham")
        settle()
        val result = hasText("Abraham", substring = false) and !hasSetTextAction()
        waitFor("Nave's topic Abraham") { rule.onAllNodes(result).fetchSemanticsNodes().isNotEmpty() }
        assertShown("under Nave's", text("NAVE’S TOPICAL BIBLE"))
        rule.onAllNodes(result).onFirst().activate()
        waitFor("Abraham's passages") { text("Genesis").exists() || text("Gen ").exists() }
        back()
        back()
        back()
        tapDesc("Go to passage")
        typeInto(0, "I want to die")
        waitFor("help first") { text("You’re Not Alone").exists() }
        assertTrue("a helpline to call or text", text("Call ").exists() || text("Text ").exists() || text("Find a Helpline").exists())
        assertFalse("no verse results above it", text("No Results").exists())
    }
}
