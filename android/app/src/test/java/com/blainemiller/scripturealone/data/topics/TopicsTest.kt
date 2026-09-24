package com.blainemiller.scripturealone.data.topics

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.study.JdbcSqlSource
import com.blainemiller.scripturealone.data.study.resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Topics directory — a port of `TopicsTests.swift`, against the very `LifeThemes.json` and
 * `Topics.sqlite` the iOS app ships, and the generated `life_themes.xml` arrays in every locale.
 */
class TopicsTest {

    private val catalog by lazy {
        val root = System.getProperty("scripturealone.resources") ?: error("scripturealone.resources is not set")
        LifeThemeCatalog.parse(File(root, "../../ScriptureAloneCore/Sources/ScriptureAloneCore/Resources/LifeThemes.json").readText())
    }

    /** The string arrays of one resource folder's `life_themes.xml`, by name. */
    private fun arrays(folder: String): Map<String, List<String>> {
        val file = File("src/main/res/$folder/life_themes.xml")
        require(file.exists()) { "missing $file — run Tools/build_topics.py" }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string-array")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            val items = node.childNodes.let { children ->
                (0 until children.length).map(children::item).filter { it.nodeName == "item" }.map { it.textContent.replace("\\'", "'") }
            }
            node.attributes.getNamedItem("name").nodeValue to items
        }
    }

    private fun localized(folder: String): LifeThemeCatalog {
        val a = arrays(folder)
        return catalog.localized(a.getValue("life_theme_groups"), a.getValue("life_theme_names"),
                                 a.getValue("life_theme_descriptions"), a.getValue("life_theme_synonyms"))
    }

    @Test fun catalogIsCuratedAndWellFormed() {
        assertTrue(catalog.themes.size in 60..80)
        assertEquals(catalog.themes.size, catalog.themes.map { it.id }.toSet().size)
        for (theme in catalog.themes) {
            assertTrue("${theme.id} has ${theme.passages.size} passages", theme.passages.size in 8..20)
            assertTrue("${theme.id} has no group", catalog.groups.any { it.id == theme.group })
        }
        assertEquals(VerseRange(VerseRef(50, 4, 6), VerseRef(50, 4, 7)), catalog.theme("anxiety")?.passages?.first())
    }

    /** Every locale's arrays line up with the catalog, so no theme is shown with another's words. */
    @Test fun everyLocaleHasEveryTheme() {
        for (folder in listOf("values", "values-de", "values-es", "values-fr", "values-it", "values-ja", "values-ko", "values-b+pt+BR", "values-zh-rCN")) {
            val a = arrays(folder)
            assertEquals(folder, catalog.groups.size, a.getValue("life_theme_groups").size)
            for (name in listOf("life_theme_names", "life_theme_descriptions", "life_theme_synonyms")) {
                assertEquals("$folder $name", catalog.themes.size, a.getValue(name).size)
                assertTrue("$folder $name", a.getValue(name).all { it.isNotBlank() })
            }
        }
        assertEquals(catalog.themes.map { it.name }, arrays("values").getValue("life_theme_names"))
    }

    @Test fun normalizesForSearch() {
        assertEquals("gods love", TopicSearch.normalize("God’s  Love!"))
        assertEquals("ansiedad y preocupacion", TopicSearch.normalize("Ansiedad y PREOCUPACIÓN"))
        assertEquals("self worth", TopicSearch.normalize("self-worth"))
        // Voiced kana and Korean syllables stay whole letters.
        assertEquals("がんばる", TopicSearch.normalize("がんばる"))
        assertEquals("외로움", TopicSearch.normalize("외로움"))
    }

    @Test fun scoresAsIosDoes() {
        assertEquals(100, TopicSearch.score("anxious", "anxious"))
        assertEquals(80, TopicSearch.score("anxiety", "anxi"))
        assertEquals(70, TopicSearch.score("alone", "i feel so alone"))
        assertEquals(60, TopicSearch.score("mental health", "health"))
        assertEquals(0, TopicSearch.score("sad", "crusade"))
        assertEquals(70, TopicSearch.score("不安", "とても不安です"))
    }

    @Test fun searchFindsThemesByWhatPeopleType() {
        assertEquals("anxiety", catalog.search("anxious").firstOrNull()?.id)
        assertEquals("grief", catalog.search("grief").firstOrNull()?.id)
        assertTrue(catalog.search("I feel so alone").any { it.id == "loneliness" })
        assertEquals("weariness", catalog.search("burned out").firstOrNull()?.id)
        assertTrue(catalog.search("xyzzy").isEmpty())
        assertTrue(catalog.search("an").isEmpty())
    }

    @Test fun searchUsesTheAppLanguagesWords() {
        val german = localized("values-de")
        assertEquals("loneliness", german.search("einsam").firstOrNull()?.id)
        assertEquals("Einsamkeit", german.theme("loneliness")?.localizedName)
        val japanese = localized("values-ja")
        assertTrue(japanese.search("不安").any { it.id == "anxiety" })
    }

    @Test fun naveIndexReadsTopicsLinesAndLinks() {
        JdbcSqlSource(resource("Study/Topics.sqlite")).use { db ->
            val index = TopicalIndex(db)
            assertTrue(index.topics.size > 5_000)
            assertEquals("Public domain", index.license)
            val anxiety = index.topic("anxiety")
            assertNotNull(anxiety)
            assertEquals(listOf("Care"), index.entries(anxiety!!).flatMap { it.seeAlso }.map { it.name })
            val prayer = index.topic("Prayer")!!
            val entries = index.entries(prayer)
            assertTrue(entries.any { e -> e.passages.any { it.contains(40_006_009) } })
            assertTrue(entries.any { it.level == 1 })
            assertEquals("Prayer", index.search("prayer").firstOrNull()?.name)
            assertEquals("Abraham", index.search("abra").firstOrNull()?.name)
            for (theme in catalog.themes) for (name in theme.naveTopics) {
                assertNotNull("${theme.id}: $name", index.topic(name))
            }
        }
    }

    @Test fun decodesCompactEntries() {
        val entries = TopicalIndex.decodeEntries("""[["Of Saul",0,[9010027,9010027],[]],["",1,[],[7]],["bad"]]""") { IndexTopic(it, "Topic $it") }
        assertEquals(2, entries.size)
        assertEquals(listOf(VerseRange(VerseRef(9, 10, 27), VerseRef(9, 10, 27))), entries[0].passages)
        assertEquals(listOf(IndexTopic(7, "Topic 7")), entries[1].seeAlso)
    }
}
