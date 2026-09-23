package com.blainemiller.scripturealone.text

import android.content.Context
import android.icu.text.PluralRules
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.blainemiller.scripturealone.R
import java.io.File
import java.util.Locale

/**
 * The app's words outside Compose — the data layer's errors, Listen's notices, the notification's
 * lines — from the `res/values…` `strings.xml` files, as `String(localized:)` reads the catalog on iOS.
 *
 * [install] hands it the application context (in `ScriptureAloneApplication`), whose resources
 * follow the reader's language, per-app language included. The JVM unit tests have no Android
 * runtime: there it reads the English straight from `src/main/res/values/strings.xml`, so a test
 * sees the same words the app shows in English.
 *
 * Counts: Levi fills `strings.xml` only (not `<plurals>`), so a counted phrase is a pair of strings,
 * `…_one` and `…_other`, and [plural] picks one by the language's own plural rules — in French 0 and
 * 1 take "one"; in Chinese, Japanese and Korean everything is "other".
 */
object AppText {
    @Volatile private var context: Context? = null

    fun install(context: Context) {
        this.context = context.applicationContext
    }

    /** The installed context, when there is one (never in the JVM tests). */
    val installedContext: Context? get() = context

    fun get(@StringRes id: Int, vararg args: Any): String {
        context?.let { return if (args.isEmpty()) it.getString(id) else it.getString(id, *args) }
        val raw = JvmStrings.value(id)
        return if (args.isEmpty()) raw else String.format(Locale.ROOT, raw, *args)
    }

    /** `…_one` or `…_other` for [count], by the current language's plural rules. */
    fun plural(@StringRes one: Int, @StringRes other: Int, count: Int, vararg args: Any): String =
        get(if (isOne(count, context?.resources?.configuration?.locales?.get(0))) one else other, *args)

    internal fun isOne(count: Int, locale: Locale?): Boolean =
        if (locale == null) count == 1 else runCatching { PluralRules.forLocale(locale).select(count.toDouble()) == "one" }
            .getOrDefault(count == 1)

    /**
     * English from the base `strings.xml`, by resource name, for code running on the plain JVM.
     */
    private object JvmStrings {
        private val names: Map<Int, String> by lazy {
            R.string::class.java.fields.mapNotNull { f -> runCatching { f.getInt(null) to f.name }.getOrNull() }.toMap()
        }
        private val values: Map<String, String> by lazy {
            val file = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
                .map(::File).firstOrNull { it.exists() } ?: return@lazy emptyMap()
            Regex("""<string\s+name="([^"]+)"[^>]*>([\s\S]*?)</string>""").findAll(file.readText())
                .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        }

        fun value(id: Int): String {
            val name = names[id] ?: error("no string resource $id")
            return values[name] ?: error("no English for R.string.$name")
        }

        /** aapt's reading of a value: entities, backslash escapes, surrounding quotes. */
        fun unescape(raw: String): String {
            var s = raw.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&")
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        else -> out.append(n)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}

/** [AppText.plural] in a composable: `…_one` or `…_other` for [count], by the language's rules. */
@Composable
fun countedString(@StringRes one: Int, @StringRes other: Int, count: Int, vararg args: Any): String {
    val locale = LocalConfiguration.current.locales[0]
    return if (args.isEmpty()) stringResource(if (AppText.isOne(count, locale)) one else other)
    else stringResource(if (AppText.isOne(count, locale)) one else other, *args)
}
