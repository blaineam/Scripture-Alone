package com.blainemiller.scripturealone.data.rights

import com.blainemiller.scripturealone.data.TranslationInfo
import com.blainemiller.scripturealone.data.canon.BookID
import java.text.Normalizer

/**
 * A publisher's own published terms for quoting its translation without asking — what the app holds a
 * quotation to, and the notice that must travel with it. A port of
 * `ScriptureAloneCore/PublisherTerms.swift`; keep the two tables identical.
 *
 * Every figure here is copied from the publisher's permissions page ([source]), checked on the date in
 * [checked]. Rules that depend on the work a quotation ends up in ("no more than 25% of the total text
 * of the work") can't be measured from inside the app and are left to the reader; the rules that can
 * be — how many verses, a whole book, how much of one book — are enforced.
 *
 * A translation that matches nothing here keeps [TranslationRights.LICENSED_DEFAULT]: the lowest limit
 * any of these publishers sets, with the file's own copyright line as the notice.
 */
data class PublisherTerms(
    /** The abbreviation it is quoted under, and the one the notice uses. */
    val abbreviation: String,
    /** Other abbreviations a file might carry for the same text ("NASB95"). */
    val aliases: List<String>,
    /** Words that identify the translation in a file's name or copyright page, most specific first. */
    val markers: List<String>,
    /** Most verses in one quotation, or null for no count limit. */
    val maxVerses: Int?,
    val allowsCompleteBook: Boolean,
    /** Largest share of any one book a quotation may be, when the publisher sets one. */
    val maxShareOfBook: Double?,
    /** Text may be shared out of the app at all — social posts, messages, the share sheet. */
    val allowsSharing: Boolean,
    /** Verses may be set into an image. Several publishers license stand-alone verse art separately. */
    val allowsVerseImages: Boolean,
    /** The notice that must accompany a quotation. */
    val notice: String,
    val source: String,
    val checked: String,
    /** The abbreviated notice a publisher accepts in non-salable media — a verse image, a post. */
    val shortNotice: String? = null,
) {
    /** What the app's gates ask, under these terms. */
    val rights: TranslationRights
        get() = TranslationRights(
            allowCopy = true, allowShare = allowsSharing, allowVerseImages = allowsSharing && allowsVerseImages,
            allowNotesExport = true, allowExternalHandoff = false, allowOfflineStorage = true,
            maxQuotationVerses = maxVerses?.toLong() ?: TranslationRights.UNLIMITED_QUOTATION,
        )

    companion object {
        /**
         * The terms for a translation, from its abbreviation first and then the words of its name and
         * copyright line. Null when the translation is none of these.
         */
        fun matching(abbreviation: String, name: String, copyright: String): PublisherTerms? {
            val key = key(abbreviation)
            all.firstOrNull { terms -> key(terms.abbreviation) == key || terms.aliases.any { key(it) == key } }?.let { return it }
            return matching(name + "\n" + copyright)
        }

        /** The terms whose translation a passage of text names — a file's copyright page. */
        fun matching(text: String): PublisherTerms? {
            val folded = fold(text)
            // `all` lists the more specific names first: "New Revised Standard" before "Revised
            // Standard", "Holman Christian Standard" before "Christian Standard".
            return all.firstOrNull { terms -> terms.markers.any { folded.contains(it.lowercase()) } }
        }

        private val marks = Regex("""\p{Mn}+""")

        /** Case- and diacritic-insensitive, as Foundation's `folding(options:)` is. */
        private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase()

        private fun key(abbreviation: String): String = abbreviation.uppercase().filter { it.isLetterOrDigit() }

        // MARK: - The table

        val all: List<PublisherTerms> = listOf(
            PublisherTerms(
                abbreviation = "ESV", aliases = emptyList(), markers = listOf("English Standard Version"),
                maxVerses = 500, allowsCompleteBook = false, maxShareOfBook = 0.5,
                allowsSharing = true, allowsVerseImages = true,
                notice = "Scripture quotations are from the ESV® Bible (The Holy Bible, English Standard Version®), © 2001 by Crossway, a publishing ministry of Good News Publishers. Used by permission. All rights reserved.",
                source = "https://www.crossway.org/permissions/", checked = "2026-09-23", shortNotice = "(ESV)",
            ),
            PublisherTerms(
                abbreviation = "HCSB", aliases = emptyList(), markers = listOf("Holman Christian Standard"),
                maxVerses = 250, allowsCompleteBook = false, maxShareOfBook = null,
                allowsSharing = true, allowsVerseImages = true,
                notice = "Scripture quotations marked HCSB are taken from the Holman Christian Standard Bible®, Copyright © 1999, 2000, 2002, 2003 by Holman Bible Publishers. Used by permission. Holman Christian Standard Bible®, Holman CSB®, and HCSB® are federally registered trademarks of Holman Bible Publishers.",
                source = "https://ssl.bhpublishinggroup.com/hcsb/Holman_CSB_copyright_policy.pdf", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "CSB", aliases = emptyList(), markers = listOf("Christian Standard Bible"),
                maxVerses = 1000, allowsCompleteBook = false, maxShareOfBook = null,
                allowsSharing = true, allowsVerseImages = true,
                notice = "Scripture quotations marked CSB have been taken from the Christian Standard Bible®, Copyright © 2017 by Holman Bible Publishers. Used by permission. Christian Standard Bible® and CSB® are federally registered trademarks of Holman Bible Publishers.",
                source = "https://csbible.com/about-the-csb/permissions/", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "NIV", aliases = emptyList(), markers = listOf("New International Version"),
                maxVerses = 500, allowsCompleteBook = false, maxShareOfBook = 0.5,
                // Stand-alone verse art needs Zondervan's permission.
                allowsSharing = true, allowsVerseImages = false,
                notice = "Scripture quotations taken from The Holy Bible, New International Version® NIV®. Copyright © 1973, 1978, 1984, 2011 by Biblica, Inc.™ Used by permission. All rights reserved worldwide.",
                source = "https://www.harpercollinschristian.com/permissions/", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "NKJV", aliases = emptyList(), markers = listOf("New King James"),
                maxVerses = 500, allowsCompleteBook = false, maxShareOfBook = 0.5,
                // Stand-alone verse art needs Thomas Nelson's permission.
                allowsSharing = true, allowsVerseImages = false,
                notice = "Scripture taken from the New King James Version®. Copyright © 1982 by Thomas Nelson. Used by permission. All rights reserved.",
                source = "https://www.harpercollinschristian.com/permissions/", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "NASB", aliases = listOf("NASB2020", "NASB20"),
                markers = listOf("New American Standard Bible, Copyright © 1960, 1971, 1977, 1995, 2020", "2020 by The Lockman"),
                maxVerses = 1000, allowsCompleteBook = false, maxShareOfBook = null,
                allowsSharing = true, allowsVerseImages = true,
                notice = "Scripture quotations taken from the (NASB®) New American Standard Bible®, Copyright © 1960, 1971, 1977, 1995, 2020 by The Lockman Foundation. Used by permission. All rights reserved. www.Lockman.org",
                source = "https://www.lockman.org/permission-to-quote-copyright-trademark-information/", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "NASB1995", aliases = listOf("NASB95", "NAS"), markers = listOf("New American Standard"),
                maxVerses = 1000, allowsCompleteBook = false, maxShareOfBook = null,
                allowsSharing = true, allowsVerseImages = true,
                notice = "Scripture quotations taken from the (NASB®) New American Standard Bible®, Copyright © 1960, 1971, 1977, 1995 by The Lockman Foundation. Used by permission. All rights reserved. www.Lockman.org",
                source = "https://www.lockman.org/permission-to-quote-copyright-trademark-information/", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "AMP", aliases = emptyList(), markers = listOf("Amplified"),
                maxVerses = 1000, allowsCompleteBook = false, maxShareOfBook = null,
                allowsSharing = true, allowsVerseImages = true,
                notice = "Scripture quotations taken from the Amplified® Bible (AMP), Copyright © 2015 by The Lockman Foundation. Used by permission. www.Lockman.org",
                source = "https://www.lockman.org/permission-to-quote-copyright-trademark-information/", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "LSB", aliases = emptyList(), markers = listOf("Legacy Standard"),
                maxVerses = 1000, allowsCompleteBook = false, maxShareOfBook = null,
                allowsSharing = true, allowsVerseImages = true,
                notice = "Scripture quotations taken from the (LSB®) Legacy Standard Bible®, Copyright © 2021 by The Lockman Foundation. Used by permission. All rights reserved. Managed in partnership with Three Sixteen Publishing Inc. LSBible.org and 316publishing.com.",
                source = "https://lsbible.org/permission-to-quote-the-lsb/", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "NLT", aliases = emptyList(), markers = listOf("New Living Translation"),
                maxVerses = 500, allowsCompleteBook = false, maxShareOfBook = null,
                // Artwork needs Tyndale's permission.
                allowsSharing = true, allowsVerseImages = false,
                notice = "Scripture quotations are taken from the Holy Bible, New Living Translation, copyright ©1996, 2004, 2015 by Tyndale House Foundation. Used by permission of Tyndale House Publishers, Carol Stream, Illinois 60188. All rights reserved.",
                source = "https://www.tyndale.com/permissions", checked = "2026-09-23", shortNotice = "(NLT)",
            ),
            PublisherTerms(
                abbreviation = "MSG", aliases = emptyList(), markers = listOf("The Message", "Eugene H. Peterson"),
                maxVerses = 500, allowsCompleteBook = false, maxShareOfBook = null,
                // Free use covers print and eBooks only; anything online or audio needs NavPress's permission.
                allowsSharing = false, allowsVerseImages = false,
                notice = "All Scripture quotations are taken from The Message, copyright © 1993, 2002, 2018 by Eugene H. Peterson. Used by permission of NavPress. All rights reserved. Represented by Tyndale House Publishers.",
                source = "https://www.navpress.com/permissions", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "NRSVUE", aliases = listOf("NRSVue"), markers = listOf("New Revised Standard Version Updated", "NRSVue"),
                maxVerses = 499, allowsCompleteBook = false, maxShareOfBook = null,
                // The free-use guidelines "do not apply to Phone Applications, or other New Media Platforms".
                allowsSharing = false, allowsVerseImages = false,
                notice = "Scripture quotations are taken from the New Revised Standard Version Updated Edition. Copyright © 2021 National Council of Churches of Christ in the United States of America. Used by permission. All rights reserved worldwide.",
                source = "https://www.friendshippress.org/pages/nrsvue-quick-faq", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "NRSV", aliases = emptyList(), markers = listOf("New Revised Standard"),
                maxVerses = 499, allowsCompleteBook = false, maxShareOfBook = null,
                allowsSharing = false, allowsVerseImages = false,
                notice = "New Revised Standard Version Bible, copyright © 1989 National Council of the Churches of Christ in the United States of America. Used by permission. All rights reserved worldwide.",
                source = "https://www.friendshippress.org/pages/nrsvue-quick-faq", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "RSV", aliases = emptyList(), markers = listOf("Revised Standard Version"),
                maxVerses = 499, allowsCompleteBook = false, maxShareOfBook = null,
                allowsSharing = false, allowsVerseImages = false,
                notice = "Revised Standard Version of the Bible, copyright © 1946, 1952, and 1971 National Council of the Churches of Christ in the United States of America. Used by permission. All rights reserved worldwide.",
                source = "https://www.friendshippress.org/pages/nrsvue-quick-faq", checked = "2026-09-23",
            ),
            PublisherTerms(
                abbreviation = "NET", aliases = emptyList(), markers = listOf("NET Bible", "Biblical Studies Press"),
                maxVerses = null, allowsCompleteBook = true, maxShareOfBook = null,
                allowsSharing = true, allowsVerseImages = true,
                notice = "Scripture quoted by permission. Quotations designated (NET) are from the NET Bible® copyright ©1996, 2019 by Biblical Studies Press, L.L.C. http://netbible.com All rights reserved.",
                source = "https://netbible.com/copyright/", checked = "2026-09-23", shortNotice = "(NET)",
            ),
            PublisherTerms(
                abbreviation = "CEB", aliases = emptyList(), markers = listOf("Common English Bible"),
                maxVerses = null, allowsCompleteBook = false, maxShareOfBook = null,
                allowsSharing = true, allowsVerseImages = true,
                notice = "Scripture quotations from the COMMON ENGLISH BIBLE. © Copyright 2011 COMMON ENGLISH BIBLE. All rights reserved. Used by permission. (www.CommonEnglishBible.com).",
                source = "https://www.commonenglishbible.com/permissions", checked = "2026-09-23",
            ),
        )
    }
}

/**
 * Whether these verses (keys) may leave the app together under this translation's terms, and if not,
 * why not — `TranslationInfo.quotationRefusal` in `PublisherTerms.swift`.
 *
 * [bookVerses] is how many verses a book has in this translation, for the whole-book and
 * share-of-a-book rules; a book whose size can't be learned (0) is held to the verse limit alone.
 */
fun TranslationInfo.quotationRefusal(verseKeys: Collection<Int>, bookVerses: (BookID) -> Int): QuotationRefusal? {
    val rights = rights
    if (rights.hasExpired() || rights.maxQuotationVerses <= 0) return QuotationRefusal.NotPermitted
    val unique = verseKeys.toSet()
    if (unique.size > rights.maxQuotationVerses) return QuotationRefusal.TooManyVerses(rights.maxQuotationVerses)
    val terms = publisherTerms ?: return null
    if (terms.allowsCompleteBook && terms.maxShareOfBook == null) return null
    for ((number, selected) in unique.groupBy { it / 1_000_000 }.toSortedMap()) {
        val book = BookID.of(number) ?: continue
        val total = bookVerses(book)
        if (total <= 0) continue
        if (!terms.allowsCompleteBook && selected.size >= total) return QuotationRefusal.WholeBook(book)
        val share = terms.maxShareOfBook
        if (share != null && selected.size > total * share) return QuotationRefusal.TooMuchOfBook(book, Math.round(share * 100).toInt())
    }
    return null
}

/** Why a selection may not leave the app as a quotation. */
sealed class QuotationRefusal {
    /** The translation's terms allow no quotation of this kind at all. */
    data object NotPermitted : QuotationRefusal()
    data class TooManyVerses(val limit: Long) : QuotationRefusal()
    data class WholeBook(val book: BookID) : QuotationRefusal()

    /** More of one book than the terms allow; [percent] is the most they do. */
    data class TooMuchOfBook(val book: BookID, val percent: Int) : QuotationRefusal()
}
