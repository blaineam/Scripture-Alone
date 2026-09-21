package com.blainemiller.scripturealone.data.sabible

import com.google.crypto.tink.subtle.Hkdf

/**
 * Derives the content key for a sealed translation. Mirrors `ContentKeyVault.deriveContentKey` in
 * `ScriptureAloneCore/Package/ContentKeyVault.swift` and the `bundle` command of
 * `Tools/package_translation.py`, which built the shipped package with the same derivation.
 *
 * HKDF-SHA256 over the seed, salt `scripture-alone-content-key-v1`, info = the translation ID,
 * 32 bytes. HKDF rather than the seed itself, so the bytes in the binary are not the content key and
 * one seed can serve more than one translation.
 *
 * TODO(android-keystore): the iOS app never holds this key in memory for long — `ContentKeyVault`
 *   seals it once to a Secure Enclave key and unwraps it per use. The Android equivalent is an
 *   Android Keystore (StrongBox where present) AES key wrapping the derived key, per the parity
 *   ledger. Until that lands, callers derive the key from the seed and hold it in memory. Do not
 *   treat the absence of wrapping as the design.
 */
object ContentKey {
    /**
     * The seed the bundled ASV was sealed with — `SealedTranslations.seed` on iOS. Published on
     * purpose: it protects a public-domain text, so secrecy would be theatre.
     */
    val BUNDLED_SEED: ByteArray = "SCRIPTURE-ALONE-BUNDLED-SEED-v1".toByteArray(Charsets.UTF_8)

    private val SALT: ByteArray = "scripture-alone-content-key-v1".toByteArray(Charsets.UTF_8)

    /** The 32-byte AES-256 key for [translationId], derived from [seed]. */
    fun derive(seed: ByteArray, translationId: String): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", seed, SALT, translationId.toByteArray(Charsets.UTF_8), 32)
}
