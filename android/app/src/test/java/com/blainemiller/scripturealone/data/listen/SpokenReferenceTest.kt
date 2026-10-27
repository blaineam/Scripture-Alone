package com.blainemiller.scripturealone.data.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenReferenceTest {
    @Test fun spokenFormsBecomeReferences() {
        val cases = listOf(
            "John 3 16" to "john 3:16", "John 3 colon 16" to "john 3:16", "john chapter 3 verse 16" to "john 3:16",
            "1 Corinthians 7 4" to "1 corinthians 7:4", "John 3 16 through 21" to "john 3:16-21", "John 3 16 21" to "john 3:16-21",
            "Romans 8:28 to 30" to "romans 8:28-30", "John 3.16" to "john 3:16", "Psalm 23" to "psalm 23",
            "Romans 8" to "romans 8", "John 3-5" to "john 3-5", "1 John 1 9" to "1 john 1:9",
        )
        for ((heard, expected) in cases) assertEquals(heard, expected, SpokenReference.normalize(heard))
    }

    @Test fun todayIsAskedForByName() {
        assertTrue(SpokenReference.asksForToday("today's reading"))
        assertTrue(SpokenReference.asksForToday("my reading plan"))
        assertFalse(SpokenReference.asksForToday("Psalm 23"))
    }
}
