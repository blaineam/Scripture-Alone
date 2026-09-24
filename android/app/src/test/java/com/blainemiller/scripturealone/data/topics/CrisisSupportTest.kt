package com.blainemiller.scripturealone.data.topics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A port of `CrisisSupportTests.swift`. */
class CrisisSupportTest {

    @Test
    fun crisisSearchesAreRecognisedInEveryLanguage() {
        for (query in listOf(
            "suicide", "I want to die", "i don't want to live anymore", "Kill myself",
            "Selbstmord", "quiero morir", "je veux mourir", "voglio morire", "quero morrer",
            "死にたい", "자살", "想死", "SUICIDIO", "suicídio",
        )) {
            assertTrue(query, CrisisSupport.isCrisis(query))
        }
    }

    @Test
    fun ordinarySearchesAreNot() {
        for (query in listOf(
            "", "John 3:16", "love", "peace that passes understanding", "die to self",
            "worried", "kill", "Lazarus died", "hope",
        )) {
            assertFalse(query, CrisisSupport.isCrisis(query))
        }
    }

    @Test
    fun helplinesFollowTheRegionAndFallBackToTheDirectory() {
        assertEquals("988", CrisisSupport.helpline("US")?.dial)
        assertEquals("sms:988", CrisisSupport.helpline("us")?.textUri)
        assertEquals("tel:116123", CrisisSupport.helpline("GB")?.callUri)
        assertNull(CrisisSupport.helpline("DE")?.textUri)
        assertNull(CrisisSupport.helpline("ZZ"))
        assertNull(CrisisSupport.helpline(null))
        assertNull(CrisisSupport.helpline(""))
        for (line in CrisisSupport.helplines.values) {
            assertTrue(line.name, line.dial.isNotEmpty() && line.dial.all { it.isDigit() })
        }
        assertEquals("https://findahelpline.com", CrisisSupport.DIRECTORY_URL)
    }
}
