package com.blainemiller.scripturealone.data.online

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.Properties

/**
 * The reader's own API keys, encrypted at rest with a key the Android Keystore holds —
 * `OnlineTranslationKeys` on iOS, where the keychain does the same job.
 *
 * A key is a credential. It is sealed with AES-256-GCM under a non-exportable Keystore key (StrongBox
 * where the device has one) and only the ciphertext reaches app storage, so a backup or a copied
 * data directory carries nothing usable. It is **never logged**: nothing in this class, or in any
 * caller, writes a key to Logcat, to an exception message or to a URL — the clients send it only as a
 * request header, over HTTPS.
 *
 * Unlike iOS there is no cross-device sync yet; iCloud Keychain's counterpart (Block Store) is a
 * later step. The ciphertext lives in no-backup storage deliberately: restored onto a new device it
 * could not be decrypted anyway, because the Keystore key never leaves this one.
 */
class OnlineKeyStore(context: Context) {

    /** Sealed values by provider, as a tiny properties file in no-backup storage. */
    private val file = File(context.applicationContext.noBackupFilesDir, FILE)

    @Synchronized
    private fun readAll(): Properties = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    @Synchronized
    private fun update(change: (Properties) -> Unit) {
        val all = readAll()
        change(all)
        val partial = File(file.parentFile, "$FILE.partial")
        partial.outputStream().use { all.store(it, null) }
        if (!partial.renameTo(file)) {
            file.delete()
            partial.renameTo(file)
        }
    }

    fun key(provider: OnlineProvider): String? {
        val sealed = readAll().getProperty(provider.raw) ?: return null
        return try {
            open(Base64.decode(sealed, Base64.NO_WRAP))
        } catch (_: GeneralSecurityException) {
            // The Keystore key is gone (a device restore, a cleared credential store): the sealed
            // value can never be read again, so it is dropped and the reader asked for the key anew.
            remove(provider)
            null
        } catch (_: IllegalArgumentException) {
            remove(provider)
            null
        }
    }

    fun hasKey(provider: OnlineProvider): Boolean = key(provider) != null

    /** Stores [key], trimmed; an empty key removes the provider's. */
    fun set(key: String, provider: OnlineProvider) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            remove(provider)
            return
        }
        val sealed = Base64.encodeToString(seal(trimmed), Base64.NO_WRAP)
        update { it.setProperty(provider.raw, sealed) }
    }

    fun remove(provider: OnlineProvider) {
        update { it.remove(provider.raw) }
    }

    private fun seal(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    private fun open(sealed: ByteArray): String {
        if (sealed.size <= IV_BYTES) throw GeneralSecurityException("sealed key is too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        }
        return try {
            generate(strongBox = true)
        } catch (_: java.security.ProviderException) {
            // No StrongBox on this device (the emulator, many phones) — StrongBoxUnavailableException
            // is a ProviderException: the TEE-backed Keystore instead.
            generate(strongBox = false)
        } catch (_: GeneralSecurityException) {
            generate(strongBox = false)
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "scripturealone.online-keys"
        const val FILE = "online-keys.properties"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
