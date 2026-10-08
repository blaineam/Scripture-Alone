package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.companion.WearFavorites
import com.blainemiller.scripturealone.companion.WearFavorites.Action
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** A heart tapped on the watch, as the phone's `WatchRequestsService` writes it into the real store. */
class WatchFavoriteTest {
    private lateinit var dir: File
    private lateinit var db: JdbcUserDatabase
    private lateinit var store: UserDataStore

    @Before fun setUp() {
        dir = Files.createTempDirectory("watchfav").toFile()
        db = JdbcUserDatabase(File(dir, "userdata.sqlite"))
        store = UserDataStore(db)
    }

    @After fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun range(a: Int, b: Int = a) = VerseRange.of(VerseRef.fromKey(a), VerseRef.fromKey(b))
    private val john316 = range(43003016)

    @Test fun anAddFromTheWatchBecomesAFavoriteOnce() {
        assertEquals(Action.ADD, store.applyWatchFavorite(WearFavorites.Request(john316, true, 1_000)))
        assertEquals(listOf(john316), store.favorites().map { it.range })
        assertEquals(Instant.ofEpochMilli(1_000), store.favorites().single().createdAt)
        // Delivered twice (the item wasn't cleared): nothing changes the second time.
        assertEquals(Action.NONE, store.applyWatchFavorite(WearFavorites.Request(john316, true, 1_000)))
        assertEquals(1, store.favorites().size)
    }

    @Test fun aRemovalTakesOnlyThatPassage() {
        val rom = range(45008038, 45008039)
        store.add(Favorite(range = john316, createdAt = Instant.ofEpochMilli(100)))
        store.add(Favorite(range = rom, createdAt = Instant.ofEpochMilli(100)))
        assertEquals(Action.REMOVE, store.applyWatchFavorite(WearFavorites.Request(john316, false, 1_000)))
        assertEquals(listOf(rom), store.favorites().map { it.range })
    }

    @Test fun aStaleRemovalLeavesANewerPhoneFavorite() {
        store.add(Favorite(range = john316, createdAt = Instant.ofEpochMilli(5_000)))
        assertEquals(Action.NONE, store.applyWatchFavorite(WearFavorites.Request(john316, false, 1_000)))
        assertTrue(store.favorites().any { it.range == john316 })
    }
}
