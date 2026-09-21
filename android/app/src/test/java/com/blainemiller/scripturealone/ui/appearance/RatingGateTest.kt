package com.blainemiller.scripturealone.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** MillerKit's `RatingManager` gates, as the Android review prompt applies them. */
class RatingGateTest {

    private class MapStore : RatingGate.Store {
        val values = mutableMapOf<String, Any>()
        override fun long(key: String) = values[key] as Long?
        override fun putLong(key: String, value: Long) { values[key] = value }
        override fun string(key: String) = values[key] as String?
        override fun putString(key: String, value: String) { values[key] = value }
    }

    private var clock = 1_000_000_000_000L
    private val store = MapStore()
    private fun gate(version: String = "1.0.0") = RatingGate(store, version, now = { clock })
    private fun days(n: Int) = TimeUnit.DAYS.toMillis(n.toLong())

    /** Everything met: ten launches over a week, three things done. */
    private fun earn(gate: RatingGate) {
        repeat(10) { gate.recordLaunch() }
        clock += days(7)
        repeat(3) { gate.recordSignificantAction() }
    }

    @Test
    fun nothingIsAskedOnAFreshInstall() {
        val gate = gate()
        gate.recordLaunch()
        gate.recordSignificantAction()
        assertFalse(gate.shouldRequestReview)
    }

    @Test
    fun launchesDaysAndActionsAreAllRequired() {
        val gate = gate()
        repeat(10) { gate.recordLaunch() }
        repeat(3) { gate.recordSignificantAction() }
        assertFalse("a week hasn't passed", gate.shouldRequestReview)
        clock += days(7)
        assertTrue(gate.shouldRequestReview)

        val few = RatingGate(MapStore(), "1.0.0", now = { clock })
        repeat(9) { few.recordLaunch() }
        clock += days(30)
        repeat(3) { few.recordSignificantAction() }
        assertFalse("nine launches", few.shouldRequestReview)
    }

    @Test
    fun anAttemptStartsTheCooldownAndIsNeverRepeatedForTheSameVersion() {
        val gate = gate()
        earn(gate)
        assertTrue(gate.shouldRequestReview)
        gate.recordAttempt()
        assertEquals(1, gate.attempts)
        assertFalse(gate.shouldRequestReview)
        clock += days(200)
        assertFalse("same version", gate.shouldRequestReview)
        assertTrue("a new version, past the cooldown", gate("1.1.0").shouldRequestReview)
    }

    @Test
    fun aNewVersionStillWaitsOutTheCooldown() {
        earn(gate())
        gate().recordAttempt()
        clock += days(119)
        assertFalse(gate("1.1.0").shouldRequestReview)
        clock += days(1)
        assertTrue(gate("1.1.0").shouldRequestReview)
    }

    @Test
    fun theFirstLaunchDateIsKeptFromTheFirstLaunch() {
        val gate = gate()
        gate.recordLaunch()
        val first = store.values["millerkit.rating.firstLaunchDate"]
        clock += days(3)
        gate.recordLaunch()
        assertEquals(first, store.values["millerkit.rating.firstLaunchDate"])
        assertEquals(2L, gate.launches)
    }
}
