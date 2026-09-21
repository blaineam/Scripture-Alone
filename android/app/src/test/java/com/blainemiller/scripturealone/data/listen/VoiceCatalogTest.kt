package com.blainemiller.scripturealone.data.listen

import com.blainemiller.scripturealone.data.listen.VoiceCatalog.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class VoiceCatalogTest {

    private val us = VoiceInfo("en-us-x-iob-local", "en-US", 400, requiresNetwork = false)
    private val usNet = VoiceInfo("en-us-x-iob-network", "en-US", 500, requiresNetwork = true)
    private val usLow = VoiceInfo("en-us-x-sfg-local", "en-US", 300, requiresNetwork = false)
    private val gb = VoiceInfo("en-gb-x-gba-local", "en-GB", 500, requiresNetwork = false)
    private val fr = VoiceInfo("fr-fr-x-frc-local", "fr-FR", 500, requiresNetwork = false)
    private val missing = VoiceInfo("en-au-x-aua-local", "en-AU", 500, requiresNetwork = false, installed = false)
    private val all = listOf(fr, usLow, gb, usNet, missing, us)

    @Test
    fun onlyInstalledVoicesForTheTextsLanguageHomeRegionFirstThenBest() {
        assertEquals(
            listOf(us, usLow, usNet, gb).map { it.id },
            VoiceCatalog.options(all, "en", "US", allowNetwork = true).map { it.id },
        )
        assertEquals(listOf(gb, us, usLow, usNet).map { it.id }, VoiceCatalog.options(all, "en", "GB", allowNetwork = true).map { it.id })
    }

    @Test
    fun aTranslationThatForbidsHandOffGetsOnlyOnDeviceVoices() {
        assertEquals(listOf(us, usLow, gb).map { it.id }, VoiceCatalog.options(all, "en", "US", allowNetwork = false).map { it.id })
        assertEquals(true, VoiceCatalog.allowsNetworkVoices(externalHandoffPermitted = true))
        assertEquals(false, VoiceCatalog.allowsNetworkVoices(externalHandoffPermitted = false))
    }

    @Test
    fun theSavedVoiceIsUsedWhileItIsInstalledAndAllowed() {
        assertEquals(Resolution.Use(gb), VoiceCatalog.resolve(gb.id, all, "en", "US", allowNetwork = true))
        // Gone, or never set: the best voice for the language.
        assertEquals(Resolution.Use(us), VoiceCatalog.resolve("en-in-x-gone-local", all, "en", "US", allowNetwork = true))
        assertEquals(Resolution.Use(us), VoiceCatalog.resolve(null, all, "en", "US", allowNetwork = true))
        assertEquals(Resolution.Use(us), VoiceCatalog.resolve(missing.id, all, "en", "US", allowNetwork = true))
        // Nothing installed for the language: the engine's default.
        assertEquals(Resolution.Use(null), VoiceCatalog.resolve(null, listOf(fr), "en", "US", allowNetwork = true))
    }

    @Test
    fun aSavedNetworkVoiceIsRefusedForALicensedTextWithAnOnDeviceFallback() {
        assertEquals(Resolution.Refused(us), VoiceCatalog.resolve(usNet.id, all, "en", "US", allowNetwork = false))
        assertEquals(Resolution.Use(usNet), VoiceCatalog.resolve(usNet.id, all, "en", "US", allowNetwork = true))
    }

    @Test
    fun titlesReadLikeIosWithTheEnginesCodeForAName() {
        assertEquals("Voice IOB (Enhanced) · United States", us.title(Locale.US))
        assertEquals("Voice IOB (Premium, Online) · United States", usNet.title(Locale.US))
        assertEquals("Voice SFG · United States", usLow.title(Locale.US))
        assertEquals("Voice GBA (Premium) · United Kingdom", gb.title(Locale.US))
        assertEquals("iob", us.shortName)
        assertNull(VoiceInfo("en-us-language", "en-US", 400, false).shortName)
        assertEquals("Standard Voice (Enhanced) · United States", VoiceInfo("en-us-language", "en-US", 400, false).title(Locale.US))
        assertNull(usLow.badge)
    }
}
