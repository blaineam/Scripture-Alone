package com.blainemiller.scripturealone.text

import android.os.LocaleList
import java.util.Locale

/**
 * The language the app is running in, as the Study content and the Verse of the Day theme follow it —
 * `Bundle.main.preferredLocalizations.first` on iOS (`StudyLanguage`, `ContextStore.appLanguage`).
 *
 * The app speaks English and the big 8 (docs/localization.md). Like the resource system, this walks
 * the reader's languages in order (the per-app language first, when one is set) and takes the first
 * the app has: English, or one of [SUPPORTED]. A language the app lacks is passed over, and a reader
 * with none of them gets English. Traditional Chinese is not Simplified: it gets English.
 */
object AppLanguage {
    /** The big 8, as the context `translations` table and `DailyVerses.json` key them. */
    val SUPPORTED = listOf("zh-Hans", "ja", "de", "fr", "es", "ko", "pt-BR", "it")

    /** The app's language tag: "en" or one of [SUPPORTED]. */
    val current: String
        get() = runCatching {
            val locales = AppText.installedContext?.resources?.configuration?.locales ?: LocaleList.getDefault()
            resolve((0 until locales.size()).map { locales[it] })
        }.getOrDefault("en") // the plain JVM (unit tests) has no locale list: English

    /** Whether the app is running in English — the language the English-only study content is in. */
    val isEnglish: Boolean get() = current == "en"

    /** The Study translation language: null in English (nothing to translate), else the tag. */
    val studyLanguage: String? get() = current.takeIf { it != "en" }

    /** The first of [preferred] the app speaks, as a tag; English when none. */
    fun resolve(preferred: List<Locale>): String {
        for (locale in preferred) match(locale)?.let { return it }
        return "en"
    }

    /** "en", one of [SUPPORTED], or null when the app doesn't speak [locale]'s language. */
    fun match(locale: Locale): String? = when (locale.language) {
        "en" -> "en"
        "zh" -> {
            val traditional = locale.script == "Hant" ||
                (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
            if (traditional) null else "zh-Hans"
        }
        "pt" -> "pt-BR"
        else -> SUPPORTED.firstOrNull { it == locale.language }
    }
}
