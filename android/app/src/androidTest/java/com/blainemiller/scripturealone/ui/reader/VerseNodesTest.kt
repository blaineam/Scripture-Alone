package com.blainemiller.scripturealone.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Block
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Footnote
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Fragment
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Kind
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The verse nodes TalkBack reads, on a device: one per verse, selectable by TalkBack's double-tap, the
 * footnote a custom action.
 */
@RunWith(AndroidJUnit4::class)
class VerseNodesTest {

    @get:Rule val rule = createComposeRule()

    private val paragraph = ChapterRenderer(ReaderStyle(palette = ReaderPalette.Light), ReaderFonts(body = FontFamily.Serif)).render(
        ChapterRef(43, 3),
        ChapterLayout(
            listOf(
                Block(
                    Kind.PARAGRAPH,
                    fragments = listOf(
                        Fragment(16, true, "For God so loved the world.", footnotes = listOf(Footnote(3, "Or thus"))),
                        Fragment(17, true, "For God sent not the Son."),
                    ),
                ),
            ),
        ),
        "Public domain.",
    ).paragraphs.first { it.verseSpans.isNotEmpty() }

    @Test
    fun eachVerseIsANodeThatSelectsAndCarriesItsFootnote() {
        val tapped = mutableListOf<Int>()
        val footnotes = mutableListOf<String>()
        rule.setContent {
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Box {
                Text(paragraph.text, onTextLayout = { layout = it }, modifier = Modifier.clearAndSetSemantics {})
                VerseNodes(
                    paragraph, layout, topInset = 0f,
                    marks = VerseMarks(highlights = mapOf(43_003_017 to "blue"), selection = setOf(43_003_016)),
                    selectable = true,
                    onTap = { tapped += it }, onLongPress = {},
                    onFootnote = { footnotes += it.text }, onNotes = { _, _ -> },
                )
            }
        }
        val sixteen = rule.onNodeWithContentDescription("Verse 16. For God so loved the world.")
        sixteen.assertIsSelected()
        rule.onNodeWithContentDescription("Verse 17. For God sent not the Son.")
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Highlighted blue"))
        sixteen.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(43_003_016), tapped)
        val action = rule.onNodeWithContentDescription("Verse 16. For God so loved the world.")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        assertEquals("Footnote a", action.label)
        action.action()
        assertEquals(listOf("Or thus"), footnotes)
        // The words are read once — by the verse nodes, not again by the paragraph.
        assertEquals(1, rule.onAllNodesWithContentDescription("Verse 16", substring = true).fetchSemanticsNodes().size)
    }
}
