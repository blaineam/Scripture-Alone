package com.blainemiller.scripturealone.data.sabible

import com.blainemiller.scripturealone.BuildConfig
import com.google.crypto.tink.subtle.Hkdf
import java.security.MessageDigest

/**
 * Derives the content key for a sealed translation. Mirrors `ContentKeyVault.deriveContentKey` in
 * `ScriptureAloneCore/Package/ContentKeyVault.swift` and the `bundle` command of
 * `Tools/package_translation.py`, which built the shipped package with the same derivation.
 *
 * HKDF-SHA256 over the seed, salt `scripture-alone-content-key-v1`, info = the translation ID,
 * 32 bytes. HKDF rather than the seed itself, so the bytes in the binary are not the content key and
 * one seed can serve more than one translation.
 *
 * The app derives it once per device: [ContentKeyVault] seals it with an Android Keystore key and the
 * reader unwraps it from there ([SealedTranslationKeys]), as `ContentKeyVault` does with the Secure
 * Enclave on iOS. Tests derive it directly.
 */
object ContentKey {
    /**
     * The seed the bundled ASV was sealed with — `SealedTranslations.seed` on iOS. Published on
     * purpose: it protects a public-domain text, so secrecy would be theatre.
     */
    val BUNDLED_SEED: ByteArray = "SCRIPTURE-ALONE-BUNDLED-SEED-v1".toByteArray(Charsets.UTF_8)

    /**
     * The build's secret seed for licensed translations — `ContentKeySeed.data` on iOS — unmasked, or
     * null when this build carries none. Never in the repository: the release build reads it from the
     * SA_CONTENT_KEY_SEED secret (see `contentKeySeedMasked` in build.gradle.kts).
     */
    fun licensedSeed(): ByteArray? {
        val hex = BuildConfig.CONTENT_KEY_SEED_MASKED
        if (hex.isEmpty()) return null
        val pad = MessageDigest.getInstance("SHA-256").digest("scripture-alone-seed-pad-v1".toByteArray(Charsets.UTF_8))
        return ByteArray(hex.length / 2) { i ->
            (hex.substring(2 * i, 2 * i + 2).toInt(16) xor (pad[i % pad.size].toInt() and 0xff)).toByte()
        }
    }

    private val SALT: ByteArray = "scripture-alone-content-key-v1".toByteArray(Charsets.UTF_8)

    /** The 32-byte AES-256 key for [translationId], derived from [seed]. */
    fun derive(seed: ByteArray, translationId: String): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", seed, SALT, translationId.toByteArray(Charsets.UTF_8), 32)
}
