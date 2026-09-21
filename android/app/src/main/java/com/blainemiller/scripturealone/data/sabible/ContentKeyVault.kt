package com.blainemiller.scripturealone.data.sabible

import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * Seals and opens a few bytes with a key that never leaves this device. On Android that is an Android
 * Keystore AES key ([AndroidKeystoreKeyWrapper]); the seam exists so the vault's rules are tested on
 * the JVM with a software key, since the Keystore only runs on a device.
 */
interface KeyWrapper {
    /** @throws GeneralSecurityException when the device key can't be made or used. */
    fun wrap(plaintext: ByteArray): ByteArray

    /** @throws GeneralSecurityException when [sealed] wasn't sealed by this device's key, or was altered. */
    fun unwrap(sealed: ByteArray): ByteArray

    /** Deletes the device key. Everything it sealed becomes unreadable. */
    fun erase()
}

/**
 * Holds the content key of a sealed translation on this device, wrapped by a device key —
 * `ContentKeyVault.swift`, where the Secure Enclave does the wrapping. The same semantics: bootstrap
 * once, then read.
 *
 * **What this does and does not buy**, as the Swift file says too. The seed ships in the app, so it is
 * in every copy and a rooted device can recover it; nothing on a general-purpose computer changes
 * that. What changes is every *other* way a key gets loose:
 *
 * - The key is never written to disk in the clear. What is stored is the key sealed by an AES-256-GCM
 *   Keystore key that is non-exportable, hardware-backed where the device has secure hardware
 *   (StrongBox when present, otherwise the TEE).
 * - The stored blob is useless on another device: the Keystore key never leaves this one, and the
 *   blob sits in no-backup storage, so a backup or a copied data directory carries only ciphertext
 *   nobody can open.
 * - The derived key is zeroed as soon as it is sealed, so the seed is used once per device and the
 *   unwrapped key exists only while a package is being opened. (The seed itself is a constant in the
 *   binary and can't be erased from memory — the Swift vault's caveat about the binary applies.)
 *
 * Pure JVM apart from [KeyWrapper], so the bootstrap-once, refuse-when-empty and corrupted-blob rules
 * are unit-tested.
 */
class ContentKeyVault(
    private val translationId: String,
    directory: File,
    private val wrapper: KeyWrapper,
) {
    /** Why the key couldn't be read — `ContentKeyVault.Failure`. */
    sealed class Failure(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class NoKeyStored : Failure("No content key has been set up on this device.")
        class KeystoreUnavailable(why: String, cause: Throwable? = null) :
            Failure("The Android Keystore isn’t available: $why", cause)
        class Corrupted(cause: Throwable? = null) : Failure("The stored content key couldn’t be read.", cause)
    }

    /** One sealed blob per translation, named by letters and digits only. */
    private val file = File(directory, "${translationId.filter { it.isLetterOrDigit() }.ifEmpty { "KEY" }}.contentkey")

    val hasStoredKey: Boolean get() = file.isFile && file.length() > 0

    /**
     * Seals the content key to this device, once. Safe to call on every launch: it returns false at
     * once when the key is already sealed, so the seed is used exactly once per device.
     *
     * @return true when this call sealed the key.
     */
    fun bootstrapIfNeeded(seed: ByteArray): Boolean {
        if (hasStoredKey) return false
        val key = ContentKey.derive(seed, translationId)
        try {
            val sealed = try {
                wrapper.wrap(key)
            } catch (e: GeneralSecurityException) {
                throw Failure.KeystoreUnavailable(e.message ?: "sealing failed", e)
            } catch (e: ProviderException) {
                throw Failure.KeystoreUnavailable(e.message ?: "sealing failed", e)
            }
            file.parentFile?.mkdirs()
            // Beside the destination and moved into place: a half-written blob would read as
            // corrupted on every launch after.
            val partial = File(file.parentFile, "${file.name}.partial")
            partial.writeBytes(sealed)
            if (!partial.renameTo(file)) {
                file.delete()
                check(partial.renameTo(file)) { "Could not store the sealed key" }
            }
        } finally {
            key.fill(0)
        }
        return true
    }

    /**
     * The content key, unwrapped, for as long as the caller holds it. The caller zeroes it when done.
     *
     * @throws Failure.NoKeyStored before [bootstrapIfNeeded]; [Failure.Corrupted] when the blob won't
     *   open — altered, or sealed by a device key that no longer exists.
     */
    fun contentKey(): ByteArray {
        if (!hasStoredKey) throw Failure.NoKeyStored()
        val sealed = file.readBytes()
        val plain = try {
            wrapper.unwrap(sealed)
        } catch (e: GeneralSecurityException) {
            throw Failure.Corrupted(e)
        } catch (e: ProviderException) {
            throw Failure.Corrupted(e)
        }
        if (plain.size != 32) {
            plain.fill(0)
            throw Failure.Corrupted()
        }
        return plain
    }

    /** Forgets the key and the device key that sealed it — when a licence lapses or a translation is removed. */
    fun erase() {
        file.delete()
        runCatching { wrapper.erase() }
    }
}
