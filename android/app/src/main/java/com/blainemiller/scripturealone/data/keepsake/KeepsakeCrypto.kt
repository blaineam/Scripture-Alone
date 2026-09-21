package com.blainemiller.scripturealone.data.keepsake

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional passphrase protection for a keepsake, ported from `Keepsake/KeepsakeCrypto.swift`.
 *
 * - Key: PBKDF2-HMAC-SHA256 over the passphrase (Unicode NFC, UTF-8) with a random 16-byte salt and
 *   600,000 iterations, giving 32 bytes.
 * - Cipher: AES-256-GCM with a random 12-byte nonce. The stored payload is nonce ‖ ciphertext ‖
 *   16-byte tag — CryptoKit's "combined" form.
 * - Associated data: the exact bytes of the outer `manifest.json`.
 *
 * PBKDF2 is computed here over explicit UTF-8 bytes rather than through `SecretKeyFactory`, whose
 * char-to-byte conversion has differed between Android providers; the passphrase must derive the
 * same key the iPhone derives, byte for byte.
 */
object KeepsakeCrypto {
    const val ALGORITHM = "AES-256-GCM"
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val DEFAULT_ITERATIONS = 600_000
    const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12
    private const val TAG_BITS = 128
    private const val PROGRESS_STEP = 5_000

    private val random = SecureRandom()

    fun randomSalt(): ByteArray = ByteArray(SALT_LENGTH).also(random::nextBytes)

    /**
     * The passphrase's key. Deliberately slow (600,000 rounds — seconds on a phone), so callers stay
     * off the main thread; [progress] hears 0…1 as the rounds run, for a progress bar.
     */
    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int, progress: ((Float) -> Unit)? = null): ByteArray {
        if (iterations !in 1..50_000_000 || salt.isEmpty()) throw KeepsakeException.Damaged("encryption settings")
        val password = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        if (password.isEmpty()) throw KeepsakeException.PassphraseRequired()
        return pbkdf2Sha256(password, salt, iterations, 32, progress)
    }

    /** RFC 8018 PBKDF2 with HMAC-SHA256. */
    internal fun pbkdf2Sha256(
        password: ByteArray, salt: ByteArray, iterations: Int, length: Int, progress: ((Float) -> Unit)? = null,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val hLen = mac.macLength
        val out = ByteArray(length)
        var block = 1
        var offset = 0
        val u = ByteArray(hLen)
        val total = ((length + hLen - 1) / hLen).toLong() * iterations
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            val t = u.copyOf()
            for (i in 1 until iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                if (progress != null && i % PROGRESS_STEP == 0) progress(((block - 1L) * iterations + i).toFloat() / total)
            }
            val n = minOf(hLen, length - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            block++
        }
        progress?.invoke(1f)
        return out
    }

    internal fun seal(plaintext: ByteArray, key: ByteArray, associatedData: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return nonce + cipher.doFinal(plaintext)
    }

    internal fun open(combined: ByteArray, key: ByteArray, associatedData: ByteArray): ByteArray {
        if (combined.size < NONCE_LENGTH + TAG_BITS / 8) throw KeepsakeException.Damaged("encrypted payload")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, combined, 0, NONCE_LENGTH))
        cipher.updateAAD(associatedData)
        return try {
            cipher.doFinal(combined, NONCE_LENGTH, combined.size - NONCE_LENGTH)
        } catch (_: AEADBadTagException) {
            // GCM can't tell a wrong key from tampering; a wrong passphrase is by far the likelier.
            throw KeepsakeException.WrongPassphrase()
        } catch (_: GeneralSecurityException) {
            throw KeepsakeException.WrongPassphrase()
        }
    }
}
