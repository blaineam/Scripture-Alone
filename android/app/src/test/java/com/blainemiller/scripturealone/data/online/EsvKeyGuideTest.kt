package com.blainemiller.scripturealone.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The guided ESV setup's logic with no web view in it. The test credentials here are invented; no
 * Crossway service is reached.
 */
class EsvKeyGuideTest {

    private val creds = """{"event":"creds","email":"reader@example.com","password":"pa\"ss\\word"}"""

    // MARK: What the page is given

    @Test fun `the page gets the words, a shim to the bridge, then the script`() {
        val source = EsvKeyGuideScript.source(mapOf("skip" to "Skip guide"), "/*script*/")
        assertTrue(source.startsWith("window.__saT = {\"skip\":\"Skip guide\"}"))
        assertTrue("saBridge.postMessage(JSON.stringify(m))" in source)
        assertTrue(source.trimEnd().endsWith("/*script*/"))
        assertFalse("__saDev" in source)
    }

    @Test fun `a debug build can mark the page as a stand-in`() {
        assertTrue("window.__saDev = true;" in EsvKeyGuideScript.source(emptyMap(), "", dev = true))
    }

    @Test fun `quotes and newlines in the words cannot break out of the script`() {
        val source = EsvKeyGuideScript.source(mapOf("a" to "x\"; alert(1); //\n"), "")
        assertTrue("\\\"; alert(1)" in source)
        assertFalse("\"; alert(1)" in source.replace("\\\"; alert(1)", ""))
    }

    @Test fun `words use the script's own placeholder and keep the application's details in English`() {
        val words = EsvKeyGuideScript.words { if (it == "fillBody") "Complete “%1\$s”." else it }
        assertEquals("Complete “%@”.", words["fillBody"])
        assertEquals("Scripture Alone (personal use)", words["appName"])
        assertEquals("https://api.esv.org/account/create-application/", words["createURL"])
        assertEquals("https://www.esv.org/account/register/", words["registerURL"])
    }

    // MARK: Messages

    @Test fun `a found key is reported`() {
        val event = EsvGuideSession().onMessage("""{"event":"key","value":"abc123"}""")
        assertEquals(EsvGuideEvent.Key("abc123"), event)
    }

    @Test fun `junk, empty keys and unknown events are ignored`() {
        val session = EsvGuideSession()
        assertNull(session.onMessage("not json"))
        assertNull(session.onMessage("""{"event":"key","value":""}"""))
        assertNull(session.onMessage("""{"event":"error","message":"x"}"""))
    }

    @Test fun `credentials are held but never returned`() {
        val session = EsvGuideSession()
        assertNull(session.onMessage(creds))
        assertTrue(session.holdsCredentials)
    }

    @Test fun `half a set of credentials is not kept`() {
        val session = EsvGuideSession()
        session.onMessage("""{"event":"creds","email":"reader@example.com","password":""}""")
        assertFalse(session.holdsCredentials)
    }

    @Test fun `skipping forgets the credentials`() {
        val session = EsvGuideSession()
        session.onMessage(creds)
        assertEquals(EsvGuideEvent.Skip, session.onMessage("""{"event":"skip"}"""))
        assertFalse(session.holdsCredentials)
    }

    // MARK: After a page loads

    @Test fun `nothing happens without credentials`() {
        val session = EsvGuideSession()
        assertEquals(EsvGuideAction.None, session.onPageFinished("https://www.esv.org/Matthew+1/"))
        assertEquals(EsvGuideAction.None, session.onPageFinished("https://api.esv.org/login/"))
    }

    @Test fun `a registration that failed validation stays put`() {
        val session = EsvGuideSession()
        session.onMessage(creds)
        assertEquals(EsvGuideAction.None, session.onPageFinished("https://www.esv.org/account/register/"))
        assertEquals(EsvGuideAction.None, session.onPageFinished("https://www.esv.org/account/register"))
        assertTrue(session.holdsCredentials)
    }

    @Test fun `leaving the register page carries the reader to the developer site`() {
        val session = EsvGuideSession()
        session.onMessage(creds)
        assertEquals(
            EsvGuideAction.Load("https://api.esv.org/login/?next=/account/create-application/"),
            session.onPageFinished("https://www.esv.org/Matthew+1/"),
        )
        assertTrue(session.holdsCredentials)
    }

    @Test fun `the developer-site login is filled once and the credentials are dropped`() {
        val session = EsvGuideSession()
        session.onMessage(creds)
        val action = session.onPageFinished("https://api.esv.org/login/?next=/account/create-application/")
        assertEquals(EsvGuideAction.FillLogin("reader@example.com", "pa\"ss\\word"), action)
        assertFalse(session.holdsCredentials)
        // A wrong password returns to the login page: it must not be filled again.
        assertEquals(EsvGuideAction.None, session.onPageFinished("https://api.esv.org/login/"))
    }

    @Test fun `other hosts are never given the credentials`() {
        val session = EsvGuideSession()
        session.onMessage(creds)
        assertEquals(EsvGuideAction.None, session.onPageFinished("https://evil.example.com/login/"))
        assertEquals(EsvGuideAction.None, session.onPageFinished("https://api.esv.org.evil.example/login/"))
        assertTrue(session.holdsCredentials)
    }

    @Test fun `clearing forgets the credentials`() {
        val session = EsvGuideSession()
        session.onMessage(creds)
        session.clear()
        assertFalse(session.holdsCredentials)
    }

    @Test fun `the fill script escapes the values as JSON strings`() {
        val script = EsvGuideSession.fillLoginScript("a@b.c", "p\"w\\x</script>")
        assertEquals(
            "window.__saGuide && window.__saGuide.fillLogin(\"a@b.c\", \"p\\\"w\\\\x</script>\")",
            script,
        )
    }

    // MARK: Checking a key

    private fun transport(status: Int) = HttpTransport { _, _ -> HttpResponse(status, ByteArray(0)) }

    @Test fun `Crossway accepting a key means it works`() {
        assertEquals(true, EsvKeyCheck.validate("k", transport(200)))
    }

    @Test fun `Crossway rejecting a key means it does not`() {
        assertEquals(false, EsvKeyCheck.validate("k", transport(401)))
        assertEquals(false, EsvKeyCheck.validate("k", transport(403)))
    }

    @Test fun `trouble reaching Crossway is not a verdict`() {
        assertNull(EsvKeyCheck.validate("k", transport(500)))
        assertNull(EsvKeyCheck.validate("k", transport(429)))
        assertNull(EsvKeyCheck.validate("k") { _, _ -> throw IOException("offline") })
    }

    @Test fun `the key travels only in the authorization header`() {
        var url = ""
        var headers = emptyMap<String, String>()
        EsvKeyCheck.validate("secret-key") { u, h -> url = u; headers = h; HttpResponse(200, ByteArray(0)) }
        assertEquals("Token secret-key", headers["Authorization"])
        assertFalse("secret-key" in url)
        assertTrue(url.startsWith("https://api.esv.org/"))
    }
}
