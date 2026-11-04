package com.blainemiller.scripturealone.data.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * The guided ESV key setup — `ESVKeyGuideScript.swift` and `ESVKeyGuideView.swift`.
 *
 * The coach layer itself is one script, `ScriptureAlone/Shared/ESVKeyGuide.js`, copied into this
 * app's assets at build time so iOS and Android never drift. It dims Crossway's own page, points at
 * the next thing to do, fills in the application, and tells the app when the key appears. This file
 * is everything around it that has no UI in it, so it is proven on the JVM: the words the script
 * shows, the one-line shim that lets it talk to Android, what to do after each page loads, and the
 * check that a key really works.
 */
object EsvKeyGuideScript {
    const val CREATE_APPLICATION_URL = "https://api.esv.org/account/create-application/"
    const val REGISTER_URL = "https://www.esv.org/account/register/"
    const val LOGIN_URL = "https://api.esv.org/login/?next=/account/create-application/"

    /** The name the page's messages are posted to. Only Crossway's own origins may reach it. */
    const val BRIDGE = "saBridge"

    /** The origins allowed to post to [BRIDGE]. */
    val ORIGINS = setOf("https://api.esv.org", "https://www.esv.org", "https://esv.org")

    /**
     * What the page runs: the card text, the shim that routes the script's messages to [BRIDGE],
     * then the script. [dev] is for a debug build's local stand-in page only.
     */
    fun source(words: Map<String, String>, sharedScript: String, dev: Boolean = false): String {
        val json = JsonObject(words.mapValues { JsonPrimitive(it.value) }).toString()
        return buildString {
            append("window.__saT = ").append(json).append(";\n")
            if (dev) append("window.__saDev = true;\n")
            append("window.webkit = { messageHandlers: { guide: { postMessage: function (m) { ")
            append(BRIDGE).append(".postMessage(JSON.stringify(m)); } } } };\n")
            append(sharedScript)
        }
    }

    /**
     * The script's text from the resource strings. Android formats with `%1$s`; the script fills
     * its own `%@`, as the iOS strings do.
     */
    fun words(get: (String) -> String): Map<String, String> {
        val keys = listOf(
            "skip", "emailTitle", "emailBody", "passTitle", "passBody", "signInTitle", "tapThis",
            "needAccount", "fillTitle", "fillBody", "tosTitle", "tosBody", "captchaTitle", "captchaBody",
            "submitTitle", "submitBody", "verifyTitle", "verifyBody", "continue", "signingTitle",
            "signingBody", "keyTitle", "keyBody", "badKeyTitle", "badKeyBody", "retry",
        )
        val out = keys.associateWith { get(it).replace("%1\$s", "%@") }.toMutableMap()
        // These three go to Crossway as the application's own details, so they stay in English.
        out["appName"] = "Scripture Alone (personal use)"
        out["appURL"] = "https://wemiller.com/apps/scripture-alone/"
        out["appDescription"] = "Personal Bible reading in Scripture Alone, a free, non-commercial, " +
            "open-source Bible app. The key is mine alone and stays on my devices."
        out["createURL"] = CREATE_APPLICATION_URL
        out["registerURL"] = REGISTER_URL
        return out
    }
}

/** A message the page posted to [EsvKeyGuideScript.BRIDGE]. */
sealed interface EsvGuideEvent {
    data class Key(val value: String) : EsvGuideEvent
    data object Skip : EsvGuideEvent
}

/** What the screen should do after a page finishes loading. */
sealed interface EsvGuideAction {
    data object None : EsvGuideAction
    data class Load(val url: String) : EsvGuideAction

    /** Type the credentials into the developer-site login and submit it. */
    data class FillLogin(val email: String, val password: String) : EsvGuideAction
}

/**
 * The part of the guide that outlives a page — the iOS `ESVGuideController`'s logic with the web
 * view taken out.
 *
 * It holds the email and password the reader chose for their new Crossway account, in memory only,
 * until the developer-site login has been filled in once. They are never persisted, logged, or sent
 * anywhere but back to Crossway's own sign-in, and a wrong password is tried once, never in a loop.
 *
 * [isApi] and [isWeb] tell Crossway's two sites apart; a debug build points them at a local page.
 */
class EsvGuideSession(
    private val loginUrl: String = EsvKeyGuideScript.LOGIN_URL,
    private val isApi: (host: String, path: String) -> Boolean = { host, _ -> host == "api.esv.org" },
    private val isWeb: (host: String, path: String) -> Boolean = { host, _ -> host == "www.esv.org" || host == "esv.org" },
) {
    private var pending: Pair<String, String>? = null

    /** True while a registration's credentials are waiting to be used. */
    val holdsCredentials: Boolean get() = pending != null

    /** Handles one message from the page. Credentials are kept here and never returned. */
    fun onMessage(json: String): EsvGuideEvent? {
        val body = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        return when (body["event"]?.jsonPrimitive?.contentOrNull) {
            "key" -> body["value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { EsvGuideEvent.Key(it) }
            "creds" -> {
                val email = body["email"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val password = body["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (email.isNotEmpty() && password.isNotEmpty()) pending = email to password
                null
            }
            "skip" -> {
                pending = null
                EsvGuideEvent.Skip
            }
            else -> null
        }
    }

    /**
     * After the reader registers, carry them to the developer site and sign them in, so one account
     * works in both places without typing it twice.
     */
    fun onPageFinished(url: String): EsvGuideAction {
        val creds = pending ?: return EsvGuideAction.None
        val uri = runCatching { URI(url) }.getOrNull() ?: return EsvGuideAction.None
        val host = uri.host ?: return EsvGuideAction.None
        val path = uri.path.orEmpty()
        return when {
            // A registration that failed validation re-shows the form; leaving it means it worked.
            isWeb(host, path) && path != "/account/register/" && path != "/account/register" ->
                EsvGuideAction.Load(loginUrl)
            isApi(host, path) && path.startsWith("/login") -> {
                // One attempt only: a wrong password must not loop.
                pending = null
                EsvGuideAction.FillLogin(creds.first, creds.second)
            }
            else -> EsvGuideAction.None
        }
    }

    /** Forgets anything held, when the guide closes. */
    fun clear() { pending = null }

    companion object {
        /** The JavaScript that fills the login, with the values escaped as JSON string literals. */
        fun fillLoginScript(email: String, password: String): String =
            "window.__saGuide && window.__saGuide.fillLogin(" +
                JsonPrimitive(email) + ", " + JsonPrimitive(password) + ")"
    }
}

/** Asks Crossway once whether a key works, so a mis-scraped string is never saved. */
object EsvKeyCheck {
    /** `true` accepted, `false` rejected, `null` could not tell (offline, server trouble). */
    fun validate(key: String, transport: HttpTransport = UrlConnectionTransport): Boolean? {
        val url = "https://api.esv.org/v3/passage/text/?q=John+1%3A1" +
            "&include-passage-references=false&include-footnotes=false&include-headings=false"
        val response = runCatching { transport.get(url, mapOf("Authorization" to "Token $key")) }.getOrNull()
            ?: return null
        return when (response.status) {
            in 200 until 300 -> true
            401, 403 -> false
            else -> null
        }
    }
}
