package com.blainemiller.scripturealone.data.sabible

import com.google.crypto.tink.subtle.Hkdf
import java.security.MessageDigest

/**
 * The content-key arithmetic the phone and the Wear OS app share — `ContentKeyVault.deriveContentKey`
 * in `ScriptureAloneCore` and the `bundle` command of `Tools/package_translation.py`.
 *
 * HKDF-SHA256 over the seed, salt `scripture-alone-content-key-v1`, info = the translation ID,
 * 32 bytes. HKDF rather than the seed itself, so the bytes in the binary are not the content key and
 * one seed can serve more than one translation.
 */
object SealedKeys {
    /**
     * The seed the ASV was sealed with — `SealedTranslations.seed` on iOS. Published on purpose: it
     * protects a public-domain text, so secrecy would be theatre.
     */
    val BUNDLED_SEED: ByteArray get() = "SCRIPTURE-ALONE-BUNDLED-SEED-v1".toByteArray(Charsets.UTF_8)

    private val SALT: ByteArray = "scripture-alone-content-key-v1".toByteArray(Charsets.UTF_8)

    /** The 32-byte AES-256 key for [translationId], derived from [seed]. */
    fun derive(seed: ByteArray, translationId: String): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", seed, SALT, translationId.toByteArray(Charsets.UTF_8), 32)

    /**
     * A build's secret seed from its masked hex (`contentKeySeedMasked` in the Gradle builds), or null
     * when the build carries none. The mask is obfuscation, not protection.
     */
    fun unmask(hex: String): ByteArray? {
        if (hex.isEmpty()) return null
        val pad = MessageDigest.getInstance("SHA-256").digest("scripture-alone-seed-pad-v1".toByteArray(Charsets.UTF_8))
        return ByteArray(hex.length / 2) { i ->
            (hex.substring(2 * i, 2 * i + 2).toInt(16) xor (pad[i % pad.size].toInt() and 0xff)).toByte()
        }
    }
}
