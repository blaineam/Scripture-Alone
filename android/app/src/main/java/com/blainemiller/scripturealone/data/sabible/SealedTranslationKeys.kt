package com.blainemiller.scripturealone.data.sabible

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * [KeyWrapper] over an Android Keystore AES-256-GCM key — the Secure Enclave's part in iOS's vault.
 * Non-exportable; StrongBox where the device has it, otherwise the TEE; a software keystore only on a
 * device with neither (the emulator reports which — see [securityLevel]).
 *
 * Usable after the first unlock with no further authentication, as iOS's item is
 * `AfterFirstUnlockThisDeviceOnly`: the reader, widgets and Listen open the ASV with the screen locked.
 */
class AndroidKeystoreKeyWrapper(private val alias: String) : KeyWrapper {

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun unwrap(sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES + TAG_BYTES) throw GeneralSecurityException("sealed key is too short")
        val key = key(create = false) ?: throw GeneralSecurityException("the device key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    override fun erase() {
        keyStore().deleteEntry(alias)
    }

    /** Where the key lives — "StrongBox", "TEE", "software" — for the debug log. */
    fun securityLevel(): String {
        val key = key(create = false) ?: return "none"
        return runCatching {
            val info = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                    else -> "unknown (${info.securityLevel})"
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) "secure hardware" else "software"
            }
        }.getOrDefault("unknown")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    @Synchronized
    private fun key(create: Boolean): SecretKey? {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        }
        return try {
            generate(strongBox = true)
        } catch (_: ProviderException) {
            // StrongBoxUnavailableException is a ProviderException: no StrongBox here — the TEE instead.
            generate(strongBox = false)
        } catch (_: GeneralSecurityException) {
            generate(strongBox = false)
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/**
 * The content keys of the translations the app ships sealed — the key half of `SealedTranslations`
 * on iOS. The ASV's key is derived from the published seed once, sealed to this device by the Android
 * Keystore, and unwrapped from then on.
 */
object SealedTranslationKeys {
    private const val TAG = "ContentKeyVault"

    fun vault(context: Context, translationId: String): ContentKeyVault = ContentKeyVault(
        translationId,
        File(context.applicationContext.noBackupFilesDir, "vault"),
        AndroidKeystoreKeyWrapper("scripturealone.content-key.$translationId"),
    )

    /**
     * The content key for [translationId], bootstrapping the vault on first use. The caller zeroes it.
     *
     * One difference from iOS, deliberate: a blob that won't open — the Keystore key was lost, which
     * happens on Android (a cleared credential store, some restores) where the Secure Enclave key
     * doesn't — is sealed afresh from the seed rather than leaving the translation unreadable. The
     * seed is in the binary either way, so re-deriving costs nothing the first bootstrap didn't.
     */
    fun contentKey(context: Context, translationId: String, seed: ByteArray): ByteArray {
        val vault = vault(context, translationId)
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (vault.bootstrapIfNeeded(seed) && debug) Log.d(TAG, "$translationId: content key sealed to this device")
        return try {
            vault.contentKey().also {
                if (debug) Log.d(TAG, "$translationId: content key unwrapped from the vault (device key: ${securityLevel(translationId)})")
            }
        } catch (e: ContentKeyVault.Failure.Corrupted) {
            if (debug) Log.d(TAG, "$translationId: sealed key unreadable; sealing afresh", e)
            vault.erase()
            vault.bootstrapIfNeeded(seed)
            vault.contentKey()
        }
    }

    /** Where the ASV's device key lives, for the debug log and the emulator check. */
    fun securityLevel(translationId: String): String =
        AndroidKeystoreKeyWrapper("scripturealone.content-key.$translationId").securityLevel()
}
