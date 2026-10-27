package com.blainemiller.scripturealone.data.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRouteTest {
    @Test fun theCarsSearchPhraseSurvivesItsIdAndReadsAsAPassage() {
        val id = CarAction.spokenId(listOf("John chapter 3"))
        val action = CarAction.parse(id) as CarAction.Spoken
        val request = SpokenReference.plan(VoiceQuery.decode(action.query))
        assertTrue("was $request (id=$id, query=${action.query})", request is SpokenReference.Request.Read)
        assertEquals(3, (request as SpokenReference.Request.Read).passages.single().startChapter)
    }
}
