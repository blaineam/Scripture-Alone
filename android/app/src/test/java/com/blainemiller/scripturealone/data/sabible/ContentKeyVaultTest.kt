package com.blainemiller.scripturealone.data.sabible

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The vault's rules — `ContentKeyVaultTests.swift`, ported. On iOS the Secure Enclave half can't run
 * in a test binary; here the Android Keystore can't run on the JVM, so the device key is a software
 * AES-GCM key behind the same [KeyWrapper] seam. What the Keystore itself does is checked on the
 * emulator (see the parity ledger).
 */
class ContentKeyVaultTest {

    @get:Rule val temp = TemporaryFolder()

    /** A software stand-in for the Keystore key: AES-256-GCM, `iv ‖ ciphertext ‖ tag`, as the real one. */
    private class SoftwareWrapper : KeyWrapper {
        var key: SecretKey? = null
        var wraps = 0

        private fun key(): SecretKey = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }

        override fun wrap(plaintext: ByteArray): ByteArray {
            wraps += 1
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plaintext)
        }

        override fun unwrap(sealed: ByteArray): ByteArray {
            val key = key ?: throw GeneralSecurityException("the device key is gone")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }

        override fun erase() {
            key = null
        }
    }

    private val seed = ByteArray(32) { 0x5C }

    private fun vault(wrapper: KeyWrapper = SoftwareWrapper(), directory: File = temp.newFolder(), id: String = "ASV") =
        ContentKeyVault(id, directory, wrapper)

    @Test
    fun theSeedIsNotTheKey() {
        val key = ContentKey.derive(ByteArray(32) { 0xAB.toByte() }, "ESV")
        assertEquals(32, key.size)
        assertFalse(key.contentEquals(ByteArray(32) { 0xAB.toByte() }))
    }

    /** One seed can serve several publishers without any of them sharing a key. */
    @Test
    fun differentTranslationsDeriveDifferentKeys() {
        val seed = ByteArray(32) { 0x11 }
        assertFalse(ContentKey.derive(seed, "ESV").contentEquals(ContentKey.derive(seed, "CSB")))
        assertArrayEquals(ContentKey.derive(seed, "ESV"), ContentKey.derive(seed, "ESV"))
    }

    /** The bundled ASV's key is what the shipped package's header names — the derivation the vault seals. */
    @Test
    fun theBundledSeedDerivesTheKeyTheShippedPackageNames() {
        val key = ContentKey.derive(ContentKey.BUNDLED_SEED, "ASV")
        val resources = File(System.getProperty("scripturealone.resources") ?: error("run through Gradle"))
        val bytes = File(resources, "Packages/ASV.sabible").readBytes()
        assertTrue(String(bytes, Charsets.UTF_8).contains("\"keyID\":\"${SabibleKeys.contentKeyId(key)}\""))
    }

    @Test
    fun bootstrapStoresOnceAndUnwrapsToTheDerivedKey() {
        val wrapper = SoftwareWrapper()
        val vault = vault(wrapper)
        assertFalse(vault.hasStoredKey)
        assertTrue(vault.bootstrapIfNeeded(seed))
        assertFalse("the seed is consumed once", vault.bootstrapIfNeeded(seed))
        assertEquals(1, wrapper.wraps)
        assertArrayEquals(ContentKey.derive(seed, "ASV"), vault.contentKey())
    }

    /** "Bootstrap once, then read" across launches: a new vault over the same storage and device key. */
    @Test
    fun aRelaunchReadsWhatTheFirstLaunchSealed() {
        val wrapper = SoftwareWrapper()
        val directory = temp.newFolder()
        assertTrue(vault(wrapper, directory).bootstrapIfNeeded(seed))
        val relaunched = vault(wrapper, directory)
        assertTrue(relaunched.hasStoredKey)
        assertFalse(relaunched.bootstrapIfNeeded(seed))
        assertArrayEquals(ContentKey.derive(seed, "ASV"), relaunched.contentKey())
    }

    /** Only the wrapped key reaches the disk — never the key itself. */
    @Test
    fun theKeyIsNeverOnDiskInTheClear() {
        val directory = temp.newFolder()
        vault(directory = directory).bootstrapIfNeeded(seed)
        val stored = directory.listFiles().orEmpty().filter { it.isFile }
        assertEquals(1, stored.size)
        val bytes = stored.single().readBytes()
        val key = ContentKey.derive(seed, "ASV")
        assertFalse(bytes.asList().windowed(key.size).any { it.toByteArray().contentEquals(key) })
        assertNotEquals(key.size, bytes.size)
    }

    @Test
    fun aVaultWithNothingStoredRefuses() {
        assertFailure<ContentKeyVault.Failure.NoKeyStored> { vault().contentKey() }
    }

    /** Sealed by a device key that no longer exists — the blob is ciphertext nobody can open. */
    @Test
    fun aLostDeviceKeyReadsAsCorrupted() {
        val wrapper = SoftwareWrapper()
        val vault = vault(wrapper)
        vault.bootstrapIfNeeded(seed)
        wrapper.key = null
        assertFailure<ContentKeyVault.Failure.Corrupted> { vault.contentKey() }
    }

    /** Another device's vault (another device key) can't open this one's blob. */
    @Test
    fun aCopiedBlobIsUselessOnAnotherDevice() {
        val directory = temp.newFolder()
        vault(SoftwareWrapper(), directory).bootstrapIfNeeded(seed)
        val elsewhere = SoftwareWrapper().also { it.wrap(ByteArray(1)) } // a different device key exists
        assertFailure<ContentKeyVault.Failure.Corrupted> { vault(elsewhere, directory).contentKey() }
    }

    @Test
    fun anAlteredBlobReadsAsCorrupted() {
        val directory = temp.newFolder()
        val wrapper = SoftwareWrapper()
        vault(wrapper, directory).bootstrapIfNeeded(seed)
        val file = directory.listFiles()!!.single()
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertFailure<ContentKeyVault.Failure.Corrupted> { vault(wrapper, directory).contentKey() }
    }

    @Test
    fun eraseForgetsTheKey() {
        val wrapper = SoftwareWrapper()
        val vault = vault(wrapper)
        vault.bootstrapIfNeeded(seed)
        assertTrue(vault.hasStoredKey)
        vault.erase()
        assertFalse(vault.hasStoredKey)
        assertEquals(null, wrapper.key)
        assertFailure<ContentKeyVault.Failure.NoKeyStored> { vault.contentKey() }
        // …and a later bootstrap seals afresh.
        assertTrue(vault.bootstrapIfNeeded(seed))
        assertArrayEquals(ContentKey.derive(seed, "ASV"), vault.contentKey())
    }

    /** A Keystore that refuses to seal is reported as such, and leaves nothing half-stored. */
    @Test
    fun aKeystoreThatWontSealIsReportedAndStoresNothing() {
        val refusing = object : KeyWrapper {
            override fun wrap(plaintext: ByteArray): ByteArray = throw GeneralSecurityException("no keystore")
            override fun unwrap(sealed: ByteArray): ByteArray = throw GeneralSecurityException("no keystore")
            override fun erase() = Unit
        }
        val vault = vault(refusing)
        assertFailure<ContentKeyVault.Failure.KeystoreUnavailable> { vault.bootstrapIfNeeded(seed) }
        assertFalse(vault.hasStoredKey)
    }

    /** Two translations' keys are two blobs, and neither opens as the other's. */
    @Test
    fun eachTranslationHasItsOwnBlob() {
        val directory = temp.newFolder()
        val wrapper = SoftwareWrapper()
        vault(wrapper, directory, "ASV").bootstrapIfNeeded(seed)
        vault(wrapper, directory, "ESV").bootstrapIfNeeded(seed)
        assertEquals(2, directory.listFiles()!!.size)
        assertFalse(vault(wrapper, directory, "ASV").contentKey().contentEquals(vault(wrapper, directory, "ESV").contentKey()))
    }

    private inline fun <reified E : ContentKeyVault.Failure> assertFailure(block: () -> Unit) {
        try {
            block()
        } catch (e: ContentKeyVault.Failure) {
            if (e !is E) fail("expected ${E::class.simpleName}, got ${e::class.simpleName}")
            return
        }
        fail("expected ${E::class.simpleName}")
    }
}
