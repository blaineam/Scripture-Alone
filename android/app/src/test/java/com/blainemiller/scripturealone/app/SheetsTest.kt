package com.blainemiller.scripturealone.app

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The reader's sheets on a phone, against the real bundled data: Go To (books, chapters, a typed
 * reference, word search, a life topic), Study (cross references, the context tab), Translations
 * (switching, Manage, Compare), Notes, and Appearance.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SheetsTest : AppTest() {
    @get:Rule override val rule = createEmptyComposeRule()

    private fun type(text: String) {
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput(text)
        settle()
    }

    @Test fun goToABookAndChapter() {
        launch()
        tapDesc("Go to passage")
        tapDesc("Genesis", substring = false)
        assertShown("Genesis's chapters", desc("Genesis chapter 50"))
        tapDesc("Genesis chapter 3")
        assertShown("Genesis 3", desc("currently Genesis 3"))
        assertShown("its heading", text("The Serpent’s Deception"))
    }

    @Test fun goToATypedReference() {
        launch()
        tapDesc("Go to passage")
        type("Ps 23")
        waitFor("the typed reference to be offered") { text("Psalm 23").exists() || text("Psalms 23").exists() }
        tapText(if (text("Psalm 23").exists()) "Psalm 23" else "Psalms 23")
        waitFor("Psalm 23 to open") { desc("currently Psalm").exists() }
    }

    @Test fun goToSearchesWords() {
        launch()
        tapDesc("Go to passage")
        type("love one another")
        waitFor("search results") { text("John 13:34").exists() || text("John 15:12").exists() || text("1 John").exists() }
    }

    @Test fun aLifeTopicListsItsPassagesAndOpensOne() {
        launch()
        tapDesc("Go to passage")
        tapText("Anxiety & Worry")
        assertShown("the topic's promise", text("tomorrow feels heavy"))
        tapText("Philippians 4:6–7")
        waitFor("Philippians 4 to open") { desc("currently Philippians 4").exists() }
    }

    @Test fun studyShowsCrossReferencesAndContext() {
        launch()
        tapDesc("Study", substring = false)
        assertShown("Study opens on John 1:1", text("John 1:1"))
        tapText("References")
        waitFor("cross references ranked by readers") { text("references, ranked").exists() }
        tapText("Context")
        assertShown("the context tabs", text("Overview"))
        tapText("Map")
        tapText("Timeline")
        tapText("Charts")
        tapText("Commentary")
        tapText("Original")
        tapDesc("About Study Resources")
        back()
        tapDesc("Close Study")
        assertFalse("Study closed", desc("Close Study").exists())
    }

    @Test fun translationsSwitchManageAndCompare() {
        launch()
        tapDesc("Translation,")
        tapText("KJV", substring = false)
        waitFor("the KJV to open") { desc("Translation, KJV").exists() }
        tapDesc("Translation,")
        tapText("Manage Translations")
        assertShown("the included Bibles", text("INCLUDED"))
        assertShown("adding one", text("Browse Free Translations…"))
        tapText("Done", substring = false)
        tapDesc("Translation,")
        tapText("Compare Translations")
        waitFor("two translations side by side") { desc("Compare KJV with").exists() || desc("Compare").exists() }
        tapText("Close", substring = false)
    }

    @Test fun notesListsItsScopes() {
        launch()
        tapDesc("Notes", substring = false)
        assertShown("an empty library", text("No Notes Yet"))
        tapText("This Book")
        tapText("This Chapter")
        tapText("Highlights")
        tapText("Favorites")
        tapText("All Books")
    }

    @Test fun appearanceChangesTheReader() {
        launch()
        tapDesc("Appearance", substring = false)
        tapDesc("Sepia theme")
        tapDesc("Dark theme")
        tapDesc("Sea accent")
        tapDesc("Larger")
        tapDesc("Smaller")
        tapText("Verse by Verse")
        tapText("Paragraphs")
        tapText("Inter")
        tapText("Words of Christ in Red")
        tapText("Verse Numbers")
        tapText("Section Headings")
        tapText("Footnotes")
        assertShown("About This Translation", text("American Standard Version"))
        back()
        assertShown("the reader, still John 1", desc("currently John 1"))
    }
}
