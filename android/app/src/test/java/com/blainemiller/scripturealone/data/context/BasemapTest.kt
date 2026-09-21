package com.blainemiller.scripturealone.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * A port of `basemapDecodes` in `ContextStoreTests.swift`, against the very `Study/Basemap.bin` the iOS
 * app ships.
 *
 * The Swift test's last line — that `ContextStore.labels()` includes the Dead Sea — belongs to the
 * context store, which is not part of this port, and is left to that store's own tests.
 */
class BasemapTest {

    private val file: File by lazy {
        val root = System.getProperty("scripturealone.resources") ?: error("scripturealone.resources is not set")
        File(root, "Study/Basemap.bin").also { require(it.exists()) { "missing $it" } }
    }

    @Test fun basemapDecodes() {
        val map = Basemap(file.readBytes())
        assertEquals(6, map.layers.size)
        val land = requireNotNull(map.layer(Basemap.Kind.LAND, Basemap.Detail.FINE))
        assertTrue("land rings: ${land.rings.size}", land.rings.size > 50)
        val points = land.rings.flatMap { it.points }
        assertTrue(points.all { it.x >= map.minLongitude - 0.001 && it.x <= map.maxLongitude + 0.001 })
        // Jerusalem is on land: some land ring's box covers it.
        assertTrue(land.rings.any { ring ->
            val xs = ring.points.map { it.x }
            val ys = ring.points.map { it.y }
            35.23 in xs.min()..xs.max() && 31.78 in ys.min()..ys.max()
        })
        try {
            Basemap("nope".toByteArray())
            fail("expected a DecodeError")
        } catch (_: Basemap.DecodeError) {
        }
    }
}
