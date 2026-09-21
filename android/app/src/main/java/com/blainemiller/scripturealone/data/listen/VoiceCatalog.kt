package com.blainemiller.scripturealone.data.listen

import java.util.Locale

/**
 * One installed text-to-speech voice, as the picker shows it — `VoiceOption` in `SpeechVoices.swift`.
 * A plain copy of what `android.speech.tts.Voice` reports, so the choosing rules run on the JVM.
 */
data class VoiceInfo(
    /** The engine's name for the voice ("en-us-x-iol-local"); persisted as `listen.voice`. */
    val id: String,
    /** BCP 47 tag of the voice's locale ("en-US"). */
    val languageTag: String,
    /** `Voice.QUALITY_*`: 100 very low … 500 very high. */
    val quality: Int,
    /** True when the voice speaks by sending the text to the engine's server. */
    val requiresNetwork: Boolean,
    /** False when the engine lists the voice but its data isn't on the device yet. */
    val installed: Boolean = true,
) {
    val language: String get() = Locale.forLanguageTag(languageTag).language
    val region: String get() = Locale.forLanguageTag(languageTag).country

    /** iOS's badge, by the engine's own quality grade. */
    val badge: String?
        get() = when {
            quality >= QUALITY_VERY_HIGH -> "Premium"
            quality >= QUALITY_HIGH -> "Enhanced"
            else -> null
        }

    /**
     * "Voice IOL (Enhanced) · United States". Android voices have no human names — the engine calls
     * them by a code — so the code stands in for iOS's "Ava".
     */
    fun title(displayLocale: Locale = Locale.getDefault()): String {
        val region = Locale.forLanguageTag(languageTag).let { l ->
            l.getDisplayCountry(displayLocale).ifEmpty { l.getDisplayLanguage(displayLocale) }
        }.ifEmpty { languageTag }
        val tags = listOfNotNull(badge, if (requiresNetwork) "Online" else null)
        val suffix = if (tags.isEmpty()) "" else " (${tags.joinToString(", ")})"
        return "$name$suffix · $region"
    }

    /** The part of the engine's name that tells voices apart: "iol" in "en-us-x-iol-local". */
    val shortName: String?
        get() {
            val parts = id.split('-')
            val x = parts.indexOf("x")
            return if (x >= 0 && x + 1 < parts.size) parts[x + 1] else null
        }

    /**
     * "Voice IOL"; the engine's plain per-language voice ("en-us-language", no variant code) is its
     * "Standard Voice".
     */
    val name: String get() = shortName?.let { "Voice ${it.uppercase(Locale.ROOT)}" } ?: "Standard Voice"

    companion object {
        const val QUALITY_HIGH = 400
        const val QUALITY_VERY_HIGH = 500
    }
}

/** The voices Listen offers, and the one it reads with. */
object VoiceCatalog {

    /**
     * The installed voices for the text's [language], home region first, then by quality, region and
     * name — the order of `SpeechVoices.enumerate`. A voice that speaks over the network is left out
     * when [allowNetwork] is false: sending the text to a server is a hand-off the translation's
     * licence may not allow (see [allowsNetworkVoices]).
     */
    fun options(voices: List<VoiceInfo>, language: String, homeRegion: String?, allowNetwork: Boolean): List<VoiceInfo> {
        val home = homeRegion?.uppercase(Locale.ROOT)
        return voices
            .filter { it.installed && it.language.equals(language, ignoreCase = true) && (allowNetwork || !it.requiresNetwork) }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<VoiceInfo> { it.region.equals(home, ignoreCase = true) }
                    .thenBy { it.requiresNetwork }
                    .thenByDescending { it.quality }
                    .thenBy { it.languageTag }
                    .thenBy { it.id },
            )
    }

    /** What happens to the reader's saved voice for this text. */
    sealed interface Resolution {
        /** Read with this voice. Null means the engine's default voice for the language. */
        data class Use(val voice: VoiceInfo?) : Resolution
        /**
         * The saved voice reads over the network, which this translation's licence doesn't allow; the
         * voices on the device read it instead. The bar says so, as iOS does for Studio voices.
         */
        data class Refused(val fallback: VoiceInfo?) : Resolution
    }

    /**
     * The saved voice if it is still installed and allowed; otherwise the best voice for the language
     * (null lets the engine pick) — `SpeechVoices.voice(for:)`.
     */
    fun resolve(saved: String?, voices: List<VoiceInfo>, language: String, homeRegion: String?, allowNetwork: Boolean): Resolution {
        val allowed = options(voices, language, homeRegion, allowNetwork)
        if (saved != null) {
            allowed.firstOrNull { it.id == saved }?.let { return Resolution.Use(it) }
            val refused = voices.firstOrNull { it.id == saved && it.installed && it.requiresNetwork && !allowNetwork }
            if (refused != null) return Resolution.Refused(allowed.firstOrNull())
        }
        return Resolution.Use(allowed.firstOrNull())
    }

    /**
     * On-device voices read any text, as iOS's system voices do: listening is local and always allowed.
     * A network voice sends the text to the speech engine's server — a copy into software the
     * publisher never licensed, the same line `TranslationRights.allowExternalHandoff` draws for
     * handing text to Mi Speaks on iOS.
     */
    fun allowsNetworkVoices(externalHandoffPermitted: Boolean): Boolean = externalHandoffPermitted

    /** The notice for [Resolution.Refused] — worded as iOS's `translationNotPermitted` explanation. */
    const val NETWORK_VOICE_REFUSED =
        "That voice reads over the internet, which this translation’s licence doesn’t allow. " +
            "A voice on this device reads it instead."
}
