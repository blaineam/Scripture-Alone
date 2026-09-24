package com.blainemiller.scripturealone.data.topics

/**
 * A search that sounds like someone thinking of ending their life gets a crisis line before any
 * verse: a person to talk to, now, in their own country — `CrisisSupport.swift`, with the same
 * phrases, the same lines and the same fallback.
 *
 * Matching is deliberately broad — "suicide" typed to study what Scripture says about it still
 * shows the card, which costs a glance; missing someone who needed it costs far more. Phrases are
 * kept in the nine app languages and compared after [TopicSearch.normalize], so accents, case and
 * width don't matter.
 *
 * Numbers are checked against findahelpline.com (the international directory run with the
 * International Association for Suicide Prevention), which is also the fallback for every country
 * not listed here.
 */
object CrisisSupport {
    data class Helpline(
        /** The service's own name, in its own language — never translated. */
        val name: String,
        /** As it is printed in that country. */
        val display: String,
        /** Digits only, for `tel:` and `sms:`. */
        val dial: String,
        /** The line also answers text messages. */
        val texts: Boolean,
    ) {
        val callUri: String get() = "tel:$dial"
        val textUri: String? get() = if (texts) "sms:$dial" else null
    }

    /** Every other country's lines, searchable by country. */
    const val DIRECTORY_URL = "https://findahelpline.com"

    /** Whether a search reads as someone in crisis. */
    fun isCrisis(query: String): Boolean {
        val text = TopicSearch.normalize(query)
        if (text.isEmpty()) return false
        return normalizedPhrases.any { text.contains(it) }
    }

    /**
     * The line for a region (ISO 3166 code, as `Locale.getDefault().country` gives it), or null where
     * the directory is the better answer.
     */
    fun helpline(forRegion: String?): Helpline? {
        if (forRegion.isNullOrEmpty()) return null
        return helplines[forRegion.uppercase()]
    }

    internal val helplines: Map<String, Helpline> = run {
        val us = Helpline("988 Suicide & Crisis Lifeline", "988", "988", texts = true)
        val samaritans = Helpline("Samaritans", "116 123", "116123", texts = false)
        val seelsorge = Helpline("TelefonSeelsorge", "0800 111 0 111", "08001110111", texts = false)
        mapOf(
            "US" to us,
            "CA" to Helpline("9-8-8 Suicide Crisis Helpline", "988", "988", texts = true),
            "GB" to samaritans,
            "IE" to samaritans,
            "AU" to Helpline("Lifeline", "13 11 14", "131114", texts = false),
            "NZ" to Helpline("Need to talk? 1737", "1737", "1737", texts = true),
            "DE" to seelsorge,
            "AT" to Helpline("TelefonSeelsorge", "142", "142", texts = false),
            "CH" to Helpline("Die Dargebotene Hand", "143", "143", texts = false),
            "FR" to Helpline("3114", "3114", "3114", texts = false),
            "ES" to Helpline("Línea 024", "024", "024", texts = false),
            "MX" to Helpline("Línea de la Vida", "800 911 2000", "8009112000", texts = false),
            "BR" to Helpline("CVV", "188", "188", texts = false),
            "IT" to Helpline("Telefono Amico", "02 2327 2327", "0223272327", texts = false),
            "JP" to Helpline("よりそいホットライン", "0120-279-338", "0120279338", texts = false),
            "KR" to Helpline("자살예방상담전화", "109", "109", texts = false),
            "CN" to Helpline("希望24热线", "400-161-9995", "4001619995", texts = false),
            "TW" to Helpline("安心專線", "1925", "1925", texts = false),
        )
    }

    /** What people type — in the app's nine languages. */
    internal val phrases: List<String> = listOf(
        // English
        "suicide", "suicidal", "kill myself", "killing myself", "want to die", "wanna die",
        "wish i was dead", "wish i were dead", "end my life", "ending my life", "take my own life",
        "end it all", "dont want to live", "dont want to be alive", "no reason to live",
        "self harm", "selfharm", "hurt myself", "hurting myself", "cutting myself",
        // Deutsch
        "suizid", "selbstmord", "selbsttotung", "umbringen", "will sterben", "sterben will",
        "nicht mehr leben", "leben beenden", "selbstverletzung", "ritzen",
        // Español
        "suicidio", "suicida", "suicidarme", "matarme", "quiero morir", "quitarme la vida",
        "no quiero vivir", "autolesion", "hacerme dano",
        // Français
        "suicidaire", "me suicider", "me tuer", "envie de mourir", "veux mourir",
        "mettre fin a mes jours", "en finir", "automutilation", "me faire du mal",
        // Italiano
        "suicidarmi", "uccidermi", "voglio morire", "togliermi la vita", "farla finita",
        "autolesionismo", "farmi del male",
        // Português
        "suicidio", "me matar", "quero morrer", "tirar minha vida", "tirar a minha vida",
        "acabar com minha vida", "nao quero viver", "automutilacao", "me machucar",
        // 日本語
        "自殺", "自死", "死にたい", "消えたい", "自傷", "リストカット", "生きていたくない", "命を絶",
        // 한국어
        "자살", "죽고 싶", "죽고싶", "자해", "극단적 선택", "살고 싶지 않", "목숨을 끊",
        // 中文
        "自杀", "想死", "轻生", "自残", "不想活", "结束生命", "结束自己",
    )

    private val normalizedPhrases: List<String> = phrases.map(TopicSearch::normalize).filter { it.isNotEmpty() }
}
