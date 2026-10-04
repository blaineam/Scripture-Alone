package com.blainemiller.scripturealone.ui.guide

import com.blainemiller.scripturealone.text.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Which bundled guide opens — `UserGuide.url` on iOS: the app language's, else English's. */
class UserGuideTest {
    private val all = (listOf("en") + AppLanguage.SUPPORTED).map(UserGuide::fileName)

    @Test fun theAppLanguagesGuide() {
        assertEquals("UserGuide-en.pdf", UserGuide.pick("en", all))
        assertEquals("UserGuide-zh-Hans.pdf", UserGuide.pick("zh-Hans", all))
        assertEquals("UserGuide-pt-BR.pdf", UserGuide.pick("pt-BR", all))
        assertEquals("UserGuide-de.pdf", UserGuide.pick("de", all))
    }

    @Test fun englishWhenThatLanguageHasNone() {
        assertEquals("UserGuide-en.pdf", UserGuide.pick("ko", listOf("UserGuide-en.pdf", "UserGuide-de.pdf")))
        assertNull(UserGuide.pick("ko", listOf("UserGuide-de.pdf")))
        assertNull(UserGuide.pick("en", emptyList()))
    }

    /** The guides are copied from the iOS resources at build time; English, the fallback, must be there. */
    @Test fun theEnglishGuideShips() {
        val resources = System.getProperty("scripturealone.resources") ?: return
        val manual = File(resources, "Manual")
        assertTrue("UserGuide-en.pdf in $manual", File(manual, UserGuide.fileName("en")).isFile)
        // Every guide that ships is for a language the app runs in, so none is unreachable.
        val reachable = all.toSet()
        manual.listFiles { file -> file.name.startsWith("UserGuide-") && file.name.endsWith(".pdf") }.orEmpty()
            .forEach { assertTrue("${it.name} is for a language the app speaks", it.name in reachable) }
    }
}
