package com.blainemiller.scripturealone.ui.share

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.share.AppLink
import com.blainemiller.scripturealone.data.share.SharePassageText
import com.blainemiller.scripturealone.data.share.ShareVerse
import com.blainemiller.scripturealone.ui.reader.ReaderFontFamily
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.blainemiller.scripturealone.data.prefs.ReaderKeys
import com.blainemiller.scripturealone.data.prefs.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The designer's values and rules against `ShareStyle.swift`, `ShareCard.swift` and `docs/share-links.md`. */
class ShareStyleTest {

    @Test fun templatesAreTheLinkNamesAndGroundsOfTheSameName() {
        assertEquals(
            listOf("parchment", "ink", "dawn", "night", "linen", "stone", "olive", "minimal"),
            ShareTemplate.entries.map { it.raw },
        )
        for (template in ShareTemplate.entries) {
            assertEquals(template.raw, template.background.raw)
            assertEquals(template, template.background.linkTemplate)
        }
        assertNull(ShareTemplate.fromRaw("sepia"))
    }

    /** The grounds and their colours, in the Swift order (`ShareBackground`). */
    @Test fun groundsAreTheSwiftOnesInOrder() {
        assertEquals(
            listOf(
                "parchment", "watercolor", "glow", "linen", "night", "bokeh", "dawn", "lattice", "mist", "grain", "contour", "dusk",
                "canvas", "sage", "tide", "ink", "stone", "sand", "blush", "olive", "minimal",
            ),
            ShareBackground.entries.map { it.raw },
        )
        assertEquals(listOf(0xF7D9C4L, 0xEFB4A8L, 0xA893CCL), ShareBackground.DAWN.colors)
        assertEquals(0x3B2F20L, ShareBackground.PARCHMENT.ink)
        assertEquals(0xC9A45CL, ShareBackground.INK.accent)
        assertEquals(0xFFA48AL, ShareBackground.OLIVE.red)
        assertEquals(setOf(ShareBackground.PARCHMENT, ShareBackground.LINEN, ShareBackground.CANVAS), ShareBackground.entries.filter { it.hasFrame }.toSet())
        assertEquals(9, ShareBackground.TEXTURED.size)
        assertEquals(ShareTemplate.LINEN, ShareBackground.WATERCOLOR.linkTemplate)
        assertEquals(ShareTemplate.NIGHT, ShareBackground.BOKEH.linkTemplate)
    }

    // Contrast — `ShareStyleTests.swift`.

    @Test fun contrastRatiosFollowWCAG() {
        assertEquals(21.0, ShareContrast.ratio(0x000000, 0xFFFFFF), 0.01)
        assertEquals(1.0, ShareContrast.ratio(0x777777, 0x777777), 0.0001)
        assertTrue(ShareContrast.ratio(0x767676, 0xFFFFFF) > 4.5)
        assertTrue(ShareContrast.ratio(0x777777, 0xFFFFFF) < 4.5)
    }

    /** Every ready-made style reads as it is on its ground as drawn (`referenceStats`), unadjusted. */
    @Test fun everyStyleIsLegibleWithoutAdjustment() {
        for (background in ShareBackground.entries) {
            val stats = background.referenceStats
            val colors = ShareStyle().applying(background).colors(stats)
            val check = ShareContrast.check(colors.ink, stats)
            assertTrue("$background text ${check.mean}", check.mean >= ShareContrast.TARGET)
            assertTrue("$background worst ${check.worst}", check.worst >= ShareContrast.MINIMUM || background.presetShadow != ShareShadow.NONE)
            assertFalse("$background needed adjusting", colors.adjusted)
            assertEquals(background.ink, colors.ink)
            assertTrue("$background accent", ShareContrast.ratio(colors.accent, stats.mean) >= ShareContrast.MINIMUM)
            assertTrue("$background red", ShareContrast.ratio(colors.red, stats.mean) >= ShareContrast.MINIMUM)
            for (swatch in background.palette) {
                assertTrue("${swatch.hex} on $background", ShareContrast.ratio(swatch.hex, stats.mean) >= ShareContrast.MINIMUM)
            }
        }
    }

    @Test fun aPickedColorTooFaintIsNudgedUntilItReads() {
        val stats = ShareBackground.PARCHMENT.referenceStats
        val colors = ShareStyle().applying(ShareBackground.PARCHMENT).copy(ink = 0xE8D9B8).colors(stats)
        assertTrue(colors.adjusted)
        assertTrue(ShareContrast.ratio(colors.ink, stats.mean) >= ShareContrast.TARGET)
        assertTrue(ShareContrast.luminance(colors.ink) < ShareContrast.luminance(0xE8D9B8))
    }

    @Test fun aPickedColorThatReadsIsKeptExactly() {
        val colors = ShareStyle().applying(ShareBackground.NIGHT).copy(ink = 0xE9C77F).colors(ShareBackground.NIGHT.referenceStats)
        assertEquals(0xE9C77FL, colors.ink)
        assertFalse(colors.adjusted)
    }

    @Test fun aBusyGroundGetsAShadowWhenThereIsNone() {
        val stats = ShareBackdropStats(0x202020, 0xD0D0D0, 0x101010)
        val colors = ShareContrast.resolve(ShareBackground.INK, 0xFFFFFF, ShareShadow.NONE, stats)
        assertEquals(0xFFFFFFL, colors.ink)
        assertEquals(ShareShadow.SOFT, colors.shadow)
        assertTrue(colors.adjusted)
        assertEquals(0x000000L, colors.shadowColor)
        assertEquals(ShareShadow.STRONG, ShareContrast.resolve(ShareBackground.INK, 0xFFFFFF, ShareShadow.STRONG, stats).shadow)
    }

    @Test fun theContrastPickerChoosesLightOnDarkAndDarkOnLight() {
        val candidates = listOf(0x111111L, 0xFFFFFFL, 0x1F2F4AL, 0xF5EBD7L)
        assertTrue(ShareContrast.luminance(ShareContrast.bestInk(ShareBackground.NIGHT.referenceStats, candidates)) > 0.7)
        assertEquals(0x111111L, ShareContrast.bestInk(ShareBackground.MINIMAL.referenceStats, candidates))
    }

    @Test fun theTextColorFollowsTheGroundUntilOneIsPicked() {
        var style = ShareStyle().applying(ShareBackground.PARCHMENT).copy(background = ShareBackground.NIGHT)
        assertEquals(ShareBackground.NIGHT.ink, style.colors(ShareBackground.NIGHT.referenceStats).ink)
        style = style.copy(ink = 0xE9C77F, background = ShareBackground.INK)
        assertEquals(0xE9C77FL, style.colors(ShareBackground.INK.referenceStats).ink)
        style = style.applying(ShareBackground.WATERCOLOR)
        assertNull(style.ink)
        assertEquals(ShareShadow.SOFT, style.shadow)
        assertEquals(ReaderFontFamily.PALATINO, style.family)
    }

    // Remembering.

    @Test fun theDesignIsRememberedUnderTheIosKeys() {
        val style = ShareStyle().applying(ShareBackground.BOKEH).copy(ink = 0xCFE0F7, aspect = ShareAspect.STORY, redLetters = false)
        val prefs = mutablePreferencesOf()
        style.save(prefs)
        assertEquals("bokeh", prefs[ReaderKeys.SHARE_TEMPLATE])
        assertEquals("CFE0F7", prefs[ReaderKeys.SHARE_INK])
        assertEquals("strong", prefs[ReaderKeys.SHARE_SHADOW])
        assertEquals(style, ShareStyle.from(ReaderSettings.from(prefs)))
        style.copy(ink = null).save(prefs)
        assertNull(ShareStyle.from(ReaderSettings.from(prefs)).ink)
        // A fresh install, and a template saved before these styles.
        assertEquals(ShareStyle(), ShareStyle.from(ReaderSettings.from(emptyPreferences())))
        val legacy = ShareStyle.from(ReaderSettings.from(mutablePreferencesOf(ReaderKeys.SHARE_TEMPLATE to "night")))
        assertEquals(ShareBackground.NIGHT, legacy.background)
        assertEquals(ShareShadow.NONE, legacy.shadow)
    }

    /** Values printed by `ShareBackdrop.swift` (`ShareStyleTests.noiseMatchesTheAndroidPort`). */
    @Test fun noiseMatchesTheSwift() {
        assertEquals(0x02BCEC5F, ShareNoise.hash(1, 2, 3))
        assertEquals(0x20F89503, ShareNoise.hash(12, -7, 99))
        assertEquals(0.4618003f, ShareNoise.fbm(3.7f, 11.2f, 4, 42), 1e-5f)
        assertEquals(-2_023_758_861, ShareNoise.seed("watercolor"))
    }

    @Test fun aspectsAreLaidOutAt1080OnTheLongSide() {
        assertEquals(listOf("square", "story", "wide"), ShareAspect.entries.map { it.raw })
        assertEquals(608f, ShareAspect.STORY.width)
        assertEquals(1080f, ShareAspect.STORY.height)
        assertEquals(608f, ShareAspect.WIDE.height)
    }

    /** `FontFamily` raw values (the stored setting) and `shareToken` (the link's `f`). */
    @Test fun typefacesCarryTheSwiftRawValuesAndShareTokens() {
        assertEquals(
            listOf("newYork", "sanFrancisco", "charter", "iowan", "georgia", "palatino", "avenir"),
            ReaderFontFamily.entries.map { it.raw },
        )
        assertEquals(
            listOf("serif", "sans", "charter", "iowan", "georgia", "palatino", "avenir"),
            ReaderFontFamily.entries.map { it.shareToken },
        )
        assertEquals(ReaderFontFamily.PALATINO, ReaderFontFamily.fromShareToken("palatino"))
        assertEquals(ReaderFontFamily.NEW_YORK, ReaderFontFamily.fromRaw("newYork"))
        assertNull(ReaderFontFamily.fromShareToken("comic"))
    }

    @Test fun variableFacesGetWeightAndOnlySourceSerifAnOpticalSize() {
        assertEquals("'wght' 400, 'opsz' 19.0", ReaderFontFamily.NEW_YORK.variationSettings(19f))
        assertEquals("'wght' 700, 'opsz' 60.0", ReaderFontFamily.NEW_YORK.variationSettings(90f, bold = true))
        assertEquals("'wght' 400", ReaderFontFamily.SAN_FRANCISCO.variationSettings(19f))
    }

    /** The numbers in `docs/share-links.md`'s layout section. */
    @Test fun metricsMatchTheWebContract() {
        val square = ShareCardMetrics(ShareAspect.SQUARE)
        assertEquals(97.2f, square.horizontalPadding, 0.001f)
        assertEquals(1080 * 0.034f, square.referenceSize, 0.001f)
        assertEquals(1080 * 0.024f, square.wordmarkSize, 0.001f)
        assertEquals(1080 * 0.066f, square.maxFontSize, 0.01f)
        assertEquals(23.76f, square.minFontSize, 0.001f)
        val story = ShareCardMetrics(ShareAspect.STORY)
        assertEquals(608 * 0.034f, story.referenceSize, 0.001f)
        assertEquals(1080 - 2 * 97.2f - 3.6f * story.referenceSize - 2.4f * story.wordmarkSize, story.textHeight(footer = true), 0.01f)
        assertEquals(1080 - 2 * 97.2f - 3.6f * story.referenceSize, story.textHeight(footer = false), 0.01f)
    }

    @Test fun theLargestSizeThatFitsIsFoundAndFloored() {
        val metrics = ShareCardMetrics(ShareAspect.SQUARE)
        // A passage whose height is 10 × its size: fits up to 0.95 × height / 10.
        val height = 600f
        val size = ShareCardFitter.largestFittingSize(metrics, height) { it * 10f }!!
        assertTrue("floored, never past the fit: $size", size == 56f || size == 57f)
        // Everything fits → the largest allowed.
        assertEquals(metrics.maxFontSize, ShareCardFitter.largestFittingSize(metrics, height) { 1f })
        // Nothing fits → null, so the fitter trims verses.
        assertNull(ShareCardFitter.largestFittingSize(metrics, height) { 10_000f })
    }

    private fun verse(v: Int, text: String = "Verse $v text.") = ShareVerse(VerseRef(43, 3, v), text)

    private fun source(verses: List<ShareVerse>, notice: String? = null, rights: TranslationRights = TranslationRights.PUBLIC_DOMAIN) =
        ShareSource(
            ranges = listOf(VerseRange(verses.first().ref, verses.last().ref)), verses = verses, translation = "BSB",
            notice = notice, rights = rights, verseCount = { 36 },
        )

    @Test fun aPassageTooLongForOneCardIsTrimmedToWholeVerses() {
        val verses = (1..20).map { verse(it) }
        // Each verse measures 100 at the smallest size; the square card's text height holds a handful.
        val result = ShareCardFitter.fit(
            verses, "BSB", null, ShareStyle(), rangesOf = source(verses)::rangesOf,
        ) { passage, size -> passage.text.count { it == '.' } * 100f * size / 23.76f }
        assertTrue(result.trimmed)
        assertTrue(result.shownVerses in 1..ShareCardFitter.MAX_VERSES)
        assertEquals("John 3:1–${result.shownVerses}", result.content.reference)
        assertEquals(20, result.totalVerses)
        assertNotNull(ShareCardFitter.trimNote(result))
    }

    @Test fun noMoreThanTwelveVersesEverReachACard() {
        val verses = (1..30).map { verse(it) }
        val result = ShareCardFitter.fit(verses, "BSB", null, ShareStyle(), source(verses)::rangesOf) { _, _ -> 1f }
        assertEquals(12, result.shownVerses)
        assertEquals("John 3:1–12", result.content.reference)
    }

    @Test fun linksCarryTheDesignersTemplateTypefaceAndAspect() {
        val verses = listOf(verse(16, "For God so loved the world").copy(red = listOf(0..3)), verse(17))
        val style = ShareStyle(background = ShareBackground.NIGHT, aspect = ShareAspect.STORY, family = ReaderFontFamily.GEORGIA)
        val link = source(verses).link(style)!!
        val payload = (AppLink.parse(link) as AppLink.Share).payload
        assertEquals("night", payload.template)
        assertEquals("georgia", payload.font)
        assertEquals("story", payload.aspect)
        assertEquals("John 3:16–17", payload.reference)
        assertTrue(payload.red.isNotEmpty())
        // Red letters off: the link carries none.
        val plain = (AppLink.parse(source(verses).link(style.copy(redLetters = false))!!) as AppLink.Share).payload
        assertTrue(plain.red.isEmpty())
    }

    @Test fun linksAreOnlyForTextsThatNeedNoNotice() {
        val verses = listOf(verse(16))
        assertNull(source(verses, notice = "© Publisher", rights = TranslationRights.LICENSED_DEFAULT).link(ShareStyle()))
        assertFalse(source(verses, notice = "© Publisher", rights = TranslationRights.LICENSED_DEFAULT).linksAllowed)
    }

    /** `permits(VERSE_IMAGES)` and `mayQuote`, as the iOS gates ask. */
    @Test fun imagesAskTheTranslationsTerms() {
        val verses = (1..3).map { verse(it) }
        assertTrue(source(verses).imagesAllowed)
        val noImages = TranslationRights.LICENSED_DEFAULT.copy(allowVerseImages = false)
        assertFalse(source(verses, "©", noImages).imagesAllowed)
        val twoVerses = TranslationRights.LICENSED_DEFAULT.copy(maxQuotationVerses = 2)
        assertFalse(source(verses, "©", twoVerses).imagesAllowed)
        assertTrue(source(verses.take(2), "©", twoVerses).imagesAllowed)
    }

    @Test fun filenamesSwapOutColonsAndDashes() {
        val content = ShareCardContent(SharePassageText("x"), "John 3:16–17", "ASV", null, 40f)
        assertEquals("John 3.16-17 ASV.png", source(listOf(verse(16))).filename(content))
    }

    // Verse numbers in a link's text, found from `k` as the web page finds them.

    @Test fun numbersAreFoundInALinksText() {
        val passage = SharePassageText.of(listOf(verse(16, "For God so loved."), verse(17, "For God sent not.")))
        val found = ShareLinkNumbers.find(passage.text, listOf(VerseRange(VerseRef(43, 3, 16), VerseRef(43, 3, 17))))
        assertEquals(passage.numbers, found)
    }

    @Test fun numbersFollowAChapterBreakAndASecondRange() {
        val verses = listOf(
            ShareVerse(VerseRef(1, 1, 31), "It was very good."),
            ShareVerse(VerseRef(1, 2, 1), "Thus the heavens were finished."),
            ShareVerse(VerseRef(1, 2, 4), "These are the generations of 12 things."),
        )
        val passage = SharePassageText.of(verses)
        val ranges = listOf(VerseRange(VerseRef(1, 1, 31), VerseRef(1, 2, 1)), VerseRange(VerseRef(1, 2, 4), VerseRef(1, 2, 4)))
        val found = ShareLinkNumbers.find(passage.text, ranges)
        assertEquals(listOf("31", "2:1", "4"), found.map { passage.text.substring(it.first, it.last + 1) })
    }

    @Test fun aSingleVerseOrUnnumberedTextHasNoNumbers() {
        assertTrue(ShareLinkNumbers.find("For God so loved", listOf(VerseRange(VerseRef(43, 3, 16), VerseRef(43, 3, 16)))).isEmpty())
        assertTrue(ShareLinkNumbers.find("For God so loved", listOf(VerseRange(VerseRef(43, 3, 16), VerseRef(43, 3, 17)))).isEmpty())
    }
}
