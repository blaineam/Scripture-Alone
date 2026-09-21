package com.blainemiller.scripturealone.data.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scalar → UTF-16 conversion every layout offset passes through. English text can't catch a
 * mistake here — every character of it is one unit either way — so these strings put characters
 * outside the Basic Multilingual Plane before, inside and after the ranges.
 */
class ScalarOffsetsTest {

    @Test
    fun bmpTextIsTheIdentity() {
        val s = "For God so loved the world"
        for (i in 0..s.length) assertEquals(i, s.utf16Offset(i))
    }

    @Test
    fun aNonBmpCharacterIsOneScalarButTwoUnits() {
        // 𝔸 is U+1D538: one scalar, a surrogate pair in UTF-16.
        val s = "𝔸 LORD"
        assertEquals(7, s.length)
        assertEquals(0, s.utf16Offset(0))
        assertEquals(2, s.utf16Offset(1))   // after 𝔸
        assertEquals(3, s.utf16Offset(2))   // after the space
        assertEquals("LORD", s.substring(s.utf16Offset(2), s.utf16Offset(6)))
    }

    @Test
    fun rangesAfterSeveralAstralCharactersLandOnTheRightWords() {
        // Scalars: 🙏(0) ␠(1) 𝔸(2) 𝔹(3) ␠(4) J(5)e(6)s(7)u(8)s(9) ␠(10) w(11)e(12)p(13)t(14)
        val s = "🙏 𝔸𝔹 Jesus wept"
        val jesus = s.utf16Range(scalarStart = 5, scalarLength = 5)
        assertEquals("Jesus", s.substring(jesus.first, jesus.last + 1))
        val wept = s.utf16Range(scalarStart = 11, scalarLength = 4)
        assertEquals("wept", s.substring(wept.first, wept.last + 1))
        assertEquals(8, jesus.first)
        // Treating the scalar offsets as UTF-16 — the bug this guards against — lands three units
        // early and colours the wrong words.
        assertEquals("𝔹 Je", s.substring(5, 10))
    }

    @Test
    fun aRangeCoveringTheAstralCharacterKeepsThePairWhole() {
        val s = "a𝔸b"
        val r = s.utf16Range(scalarStart = 1, scalarLength = 1)
        assertEquals(1..2, r)
        assertEquals("𝔸", s.substring(r.first, r.last + 1))
    }

    @Test
    fun offsetsPastTheEndClampToTheLengthAsSwiftDoes() {
        val s = "ab𝔸"
        assertEquals(4, s.utf16Offset(3))
        assertEquals(4, s.utf16Offset(99))
        assertEquals(0, s.utf16Offset(-1))
        assertEquals(IntRange(4, 3), s.utf16Range(3, 5))   // empty, at the end
    }

    @Test
    fun aLoneSurrogateCountsAsOneScalar() {
        val s = "a\uD835b"   // a high surrogate with no partner
        assertEquals(2, s.utf16Offset(2))
        assertEquals(3, s.utf16Offset(3))
    }
}
