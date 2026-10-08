package com.blainemiller.scripturealone.data.sabible

import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.VerseSnapshot.Kind
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.google.crypto.tink.subtle.Ed25519Sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant

/**
 * The `wearables` term — `WearableLicenceTests.swift`: parsed from the signed header, impossible to flip
 * or strip, refused by a watch, swept off one, and never sent by the phone with any of its text.
 *
 * The packages here are built in the test: a real header signed with a key made for it, over a body the
 * reader never decrypts (opening checks the header, the signature and the terms, not the chapters).
 */
class WearableLicenceTest {
    @get:Rule val folder = TemporaryFolder()

    private val contentKey = ByteArray(32) { (it * 7).toByte() }
    private val signer = Ed25519Sign.KeyPair.newKeyPair()
    private val keyring = PublisherKeyring(listOf(signer.publicKey))

    private fun header(policy: String): String =
        """{"chapters":[{"book":43,"chapter":3,"length":64,"offset":0,"verses":1}],""" +
            """"createdAt":"2026-10-08T00:00:00Z","crypto":{"aad":"${SabibleFormat.ASSOCIATED_DATA_VERSION}",""" +
            """"cipher":"${SabibleFormat.CIPHER}","keyID":"${SabibleKeys.contentKeyId(contentKey)}",""" +
            """"publisherKeyID":"${SabibleKeys.publisherKeyId(signer.publicKey)}","signature":"${SabibleFormat.SIGNATURE_ALGORITHM}"},""" +
            """"format":${SabibleFormat.VERSION},"packageID":"TEST","policy":$policy,""" +
            """"translation":{"abbreviation":"T","copyright":"c","id":"T","license":"l","name":"Test","publisher":"p"}}"""

    /** A package file: magic, version, header, signature, body — the layout of `docs/encrypted-translations.md`. */
    private fun assemble(header: ByteArray, signature: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(SabibleFormat.MAGIC)
        write(ByteBuffer.allocate(2).putShort(SabibleFormat.VERSION.toShort()).array())
        write(ByteBuffer.allocate(4).putInt(header.size).array())
        write(header)
        write(ByteBuffer.allocate(2).putShort(signature.size.toShort()).array())
        write(signature)
        write(ByteArray(64))
    }.toByteArray()

    private fun pkg(policy: String, name: String = "T.sabible"): File {
        val bytes = header(policy).toByteArray()
        return File(folder.root, name).apply { writeBytes(assemble(bytes, Ed25519Sign(signer.privateKey).sign(bytes))) }
    }

    private fun open(file: File, forWearable: Boolean) =
        TranslationPackage.open(file, keyring, contentKey, Instant.parse("2026-10-08T00:00:00Z"), forWearable = forWearable)

    private inline fun <reified T : Throwable> assertRefused(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
    }

    private val prohibited = """{"allowCopy":true,"wearables":"prohibited"}"""

    @Test fun theTermIsReadAsAllowedProhibitedOrAbsent() {
        fun allows(policy: String) = PackageHeaderParser.parse(header(policy).toByteArray()).policy.allowsWearables
        assertTrue(allows("""{"allowCopy":true}"""))
        assertTrue(allows("""{"wearables":null}"""))
        assertTrue(allows("""{"wearables":"allowed"}"""))
        assertFalse(allows(prohibited))
        // Anything but the two words fails closed.
        assertFalse(allows("""{"wearables":"Allowed"}"""))
        assertFalse(allows("""{"wearables":""}"""))
        // The wrong type is a damaged header, as in Swift.
        assertRefused<TranslationPackageException.DamagedHeader> { allows("""{"wearables":true}""") }
    }

    @Test fun aWatchRefusesAProhibitedPackageAndAPhoneDoesNot() {
        val file = pkg(prohibited)
        assertRefused<TranslationPackageException.NotForWearables> { open(file, forWearable = true) }
        open(file, forWearable = false).use { phone -> assertFalse(phone.policy.allowsWearables) }
        assertTrue(TranslationPackageException.NotForWearables().message!!.isNotEmpty())
    }

    @Test fun aWatchOpensAllowedAndLegacyPackages() {
        for ((index, policy) in listOf("""{"allowCopy":true}""", """{"wearables":"allowed"}""").withIndex()) {
            open(pkg(policy, "ok$index.sabible"), forWearable = true).use { assertTrue(it.policy.allowsWearables) }
        }
    }

    /** Flipped to the same length — JSON whitespace pads "allowed" — or removed: the signature fails. */
    @Test fun aFlippedOrStrippedTermFailsTheSignature() {
        val original = header(prohibited).toByteArray()
        val signature = Ed25519Sign(signer.privateKey).sign(original)
        val flipped = String(original).replace(""""wearables":"prohibited"""", """"wearables":"allowed"   """).toByteArray()
        assertEquals(original.size, flipped.size)
        assertNotEquals(String(original), String(flipped))
        val flippedFile = File(folder.root, "flipped.sabible").apply { writeBytes(assemble(flipped, signature)) }
        // The unverified read believes the edit — the signed open, on any device, does not.
        assertTrue(WearableLicence.allowsWearables(flippedFile))
        for (wearable in listOf(true, false)) {
            assertRefused<TranslationPackageException.SignatureInvalid> { open(flippedFile, wearable) }
        }
        val stripped = String(original).replace(""","wearables":"prohibited"""", "").toByteArray()
        val strippedFile = File(folder.root, "stripped.sabible").apply { writeBytes(assemble(stripped, signature)) }
        assertTrue(WearableLicence.allowsWearables(strippedFile))
        assertRefused<TranslationPackageException.SignatureInvalid> { open(strippedFile, forWearable = true) }
    }

    /** What the phone asks before it sends a file to the watch. */
    @Test fun thePhoneSendsOnlyPackagesTheirTermsLetGo() {
        val allowed = pkg("""{"allowCopy":true}""", "ASV.sabible")
        val restricted = pkg(prohibited, "NASB1995.sabible")
        val junk = File(folder.root, "junk.sabible").apply { writeText("not a package") }
        val edition = File(folder.root, "BSB-Watch.sqlite").apply { writeText("a compact edition") }
        assertTrue(WearableLicence.maySendToWatch(allowed, sealed = true))
        assertFalse(WearableLicence.maySendToWatch(restricted, sealed = true))
        // Can't tell is never "send it"; a compact edition carries no such term.
        assertFalse(WearableLicence.maySendToWatch(junk, sealed = true))
        assertFalse(WearableLicence.allowsWearables(File(folder.root, "missing.sabible")))
        assertTrue(WearableLicence.maySendToWatch(edition, sealed = false))
    }

    /** The phone names a package to the watch as the reader sees it, from the same unverified read. */
    @Test fun theHeaderGivesThePhoneTheNameItShows() {
        val restricted = pkg(prohibited, "NASB1995.sabible")
        val header = WearableLicence.unverifiedHeader(restricted)
        assertEquals("T", header?.translation?.abbreviation)
        assertEquals(false, header?.policy?.allowsWearables)
        assertNull(WearableLicence.unverifiedHeader(File(folder.root, "junk.sabible").apply { writeText("not a package") }))
    }

    @Test fun aWatchDeletesAProhibitedPackageItFinds() {
        val dir = folder.newFolder("editions")
        pkg(prohibited, "NASB1995.sabible").copyTo(File(dir, "NASB1995.sabible"))
        pkg("""{"allowCopy":true}""", "ASV.sabible").copyTo(File(dir, "ASV.sabible"))
        File(dir, "BSB-Watch.sqlite").writeText("edition")
        val removed = WearableLicence.removeProhibitedPackages(dir)
        assertEquals(listOf("NASB1995.sabible"), removed.map { it.name })
        assertEquals(listOf("ASV.sabible", "BSB-Watch.sqlite"), dir.list()!!.sorted())
    }

    /** The favorites/highlights/notes snapshot the phone sends: references only when kept off watches. */
    @Test fun aSnapshotInAProhibitedTranslationTravelsAsReferencesOnly() {
        val range = VerseRange(VerseRef(43, 3, 16), VerseRef(43, 3, 16))
        val at = Instant.parse("2026-10-08T00:00:00Z")
        val snapshot = VerseSnapshot(
            generatedAt = at, translation = "NASB1995",
            items = listOf(
                VerseSnapshot.Item.of(Kind.FAVORITE, range, "licensed words", at),
                VerseSnapshot.Item.of(Kind.NOTE, range, "licensed words", at, noteTitle = "Mine", noteBody = "my own words"),
            ),
            daily = mapOf("43003016-43003016" to VerseSnapshot.DailyText("licensed words", emptyList())),
        )
        assertEquals(snapshot, snapshot.forWatch(translationKeptOffWatch = false))
        val sent = snapshot.forWatch(translationKeptOffWatch = true)
        assertTrue(sent.items.all { it.text.isEmpty() })
        assertNull(sent.daily)
        assertFalse(sent.encoded().contains("licensed words"))
        assertEquals(snapshot.items.map { it.range to it.reference }, sent.items.map { it.range to it.reference })
        assertEquals("my own words", sent.items[1].noteBody)
        assertEquals("Mine", sent.items[1].noteTitle)
    }
}
