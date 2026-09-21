package com.blainemiller.scripturealone.data.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against the real Interlinear.sqlite, glossed with the real BSB text. */
class InterlinearStoreTest {

    private fun bsbText(db: JdbcSqlSource, key: Int): String =
        db.query("SELECT text FROM verses WHERE id = ?", key) { it.text(0) }.firstOrNull() ?: ""

    /**
     * The proof: every verse's word ranges slice correctly out of the BSB's own text. The build
     * recorded how many verses align exactly; the port must reproduce that count, and must never
     * throw a text mismatch on the BSB.
     */
    @Test fun everyVerseAlignsToTheBsbAsTheBuildRecorded() {
        JdbcSqlSource(resource("Study/Interlinear.sqlite")).use { idb ->
            JdbcSqlSource(resource("Bibles/BSB.sqlite")).use { bsb ->
                val store = InterlinearStore(idb)
                val texts = bsb.query("SELECT id, text FROM verses") { it.long(0).toInt() to it.text(1) }.toMap()
                val keys = idb.query("SELECT verse_key FROM verses WHERE count > 0 ORDER BY verse_key") {
                    it.long(0).toInt()
                }
                var words = 0
                var sliced = 0
                for (key in keys) {
                    val text = texts[key] ?: ""
                    for (w in store.words(key, text)) {
                        words++
                        val r = w.range ?: continue
                        assertEquals("slice mismatch at $key", w.english, text.substring(r))
                        sliced++
                    }
                }
                println("interlinear: ${keys.size} verses, $words words, $sliced with a BSB range; " +
                            "build says ${store.statistics.words} words, ${store.statistics.versesAligned}/${store.statistics.verses} aligned")
                assertEquals(store.statistics.words, words)
                // Cross-checked against an independent decode of the same file in Python (zlib, raw
                // DEFLATE): 385,561 of 443,625 words carry a BSB range. The rest are legitimate —
                // words the BSB renders with nothing, and Psalm titles kept out of the verse text.
                assertEquals(385_561, sliced)
            }
        }
    }

    @Test fun john1v1IsGreekInOriginalOrder() {
        JdbcSqlSource(resource("Study/Interlinear.sqlite")).use { idb ->
            JdbcSqlSource(resource("Bibles/BSB.sqlite")).use { bsb ->
                val store = InterlinearStore(idb)
                val words = store.words(43_001_001, bsbText(bsb, 43_001_001))
                assertTrue(words.isNotEmpty())
                assertTrue(words.all { it.language == InterlinearLanguage.GREEK })
                // Original order is a permutation of 1…n.
                assertEquals((1..words.size).toList(), words.map { it.originalOrder }.sorted())
                assertTrue(words.any { it.strongs == "G3056" }) // λόγος
            }
        }
    }

    @Test fun psalmTitlesComeFirstAndHaveNoRange() {
        JdbcSqlSource(resource("Study/Interlinear.sqlite")).use { idb ->
            JdbcSqlSource(resource("Bibles/BSB.sqlite")).use { bsb ->
                val words = InterlinearStore(idb).words(19_023_001, bsbText(bsb, 19_023_001))
                val supers = words.takeWhile { it.isSuperscription }
                assertTrue("Psalm 23:1 has a title", supers.isNotEmpty())
                assertTrue(supers.all { it.range == null })
                assertFalse(words.drop(supers.size).any { it.isSuperscription })
                assertTrue(words.all { it.language == InterlinearLanguage.HEBREW })
            }
        }
    }

    @Test fun nehemiah7v68HasNoWords() {
        JdbcSqlSource(resource("Study/Interlinear.sqlite")).use { idb ->
            val store = InterlinearStore(idb)
            assertFalse(store.hasWords(16_007_068))
            assertEquals(emptyList<InterlinearWord>(), store.words(16_007_068, "anything"))
        }
    }

    @Test fun anotherTranslationsTextIsRefusedNotMisHighlighted() {
        JdbcSqlSource(resource("Study/Interlinear.sqlite")).use { idb ->
            val store = InterlinearStore(idb)
            assertTrue(runCatching { store.words(43_001_001, "In") }.exceptionOrNull() is InterlinearTextMismatch)
        }
    }

    @Test fun lexiconAcceptsTheWaysPeopleWriteStrongs() {
        JdbcSqlSource(resource("Study/Interlinear.sqlite")).use { idb ->
            val store = InterlinearStore(idb)
            val god = store.entry("H0430")!!
            assertEquals(god, store.entry("H430"))
            assertEquals(god, store.entry("h430"))
            assertEquals(god, store.entry("H0430G"))
            assertTrue(god.gloss.contains("God", ignoreCase = true))
            assertTrue(store.entry("G3056")!!.gloss.contains("word", ignoreCase = true))
            assertNull(store.entry("X12"))
            assertNull(InterlinearStore.normalize("H123456"))
            assertEquals("G0026", InterlinearStore.normalize(" g26 "))
            assertTrue(store.attribution.requiredLines.all { it.isNotBlank() })
        }
    }
}
