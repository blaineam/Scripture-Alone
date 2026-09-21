package com.blainemiller.scripturealone.data.online

import android.content.Context
import com.blainemiller.scripturealone.data.sabible.AndroidKeystoreKeyWrapper
import com.blainemiller.scripturealone.data.sabible.KeyWrapper
import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import java.util.Properties

/**
 * The reader's own API keys, encrypted at rest with a key the Android Keystore holds —
 * `OnlineTranslationKeys` on iOS, where the keychain does the same job.
 *
 * A key is a credential. It is sealed with AES-256-GCM under a non-exportable Keystore key (StrongBox
 * where the device has one — [AndroidKeystoreKeyWrapper]) and only the ciphertext reaches app storage,
 * so an app-data backup or a copied data directory carries nothing usable. It is **never logged**:
 * nothing in this class, or in any caller, writes a key to Logcat, to an exception message or to a
 * URL — the clients send it only as a request header, over HTTPS.
 *
 * **Across the reader's devices**, iOS's item is `kSecAttrSynchronizable` and rides iCloud Keychain.
 * Here the same job falls to Google Block Store ([OnlineKeySync], [BlockStoreKeyBackup]), which keeps a
 * copy end-to-end encrypted in the reader's Google account and hands it to a new phone set up from
 * this one. Every sync hands Block Store the keys this store holds, so there is nothing to track for
 * those; what this store does remember is a key removed here that Block Store may still hold
 * ([removed]), so a removal is never undone by the next restore, even one made while Play services
 * couldn't be reached.
 *
 * The file lives in no-backup storage deliberately: restored onto another device by Android's own
 * backup it could not be decrypted anyway, because the Keystore key never leaves this one.
 *
 * Pure JVM apart from [KeyWrapper], so its rules are unit-tested with a software key.
 */
class OnlineKeyStore(directory: File, private val wrapper: KeyWrapper) {

    constructor(context: Context) : this(context.applicationContext.noBackupFilesDir, AndroidKeystoreKeyWrapper(ALIAS))

    /** Sealed values by provider, as a tiny properties file. */
    private val file = File(directory, FILE)

    @Synchronized
    private fun readAll(): Properties = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    @Synchronized
    private fun update(change: (Properties) -> Unit) {
        val all = readAll()
        change(all)
        file.parentFile?.mkdirs()
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
            String(wrapper.unwrap(Base64.getDecoder().decode(sealed)), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            forgetUnreadable(provider)
        } catch (_: ProviderException) {
            forgetUnreadable(provider)
        } catch (_: IllegalArgumentException) {
            forgetUnreadable(provider)
        }
    }

    /**
     * The Keystore key is gone (a cleared credential store, a device restore): the sealed value can
     * never be read again. It is dropped *without* a removal mark, so Block Store's copy — the same
     * key, sealed by Google rather than this device — can put it back on the next sync.
     */
    private fun forgetUnreadable(provider: OnlineProvider): String? {
        update { it.remove(provider.raw) }
        return null
    }

    fun hasKey(provider: OnlineProvider): Boolean = key(provider) != null

    /**
     * Stores [key], trimmed; an empty key removes the provider's. Setting the key already stored
     * changes nothing (the Translations screen saves every field on Done).
     */
    fun set(key: String, provider: OnlineProvider) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            remove(provider)
            return
        }
        if (key(provider) == trimmed) return
        val sealed = Base64.getEncoder().encodeToString(wrapper.wrap(trimmed.toByteArray(Charsets.UTF_8)))
        update {
            it.setProperty(provider.raw, sealed)
            it.remove(provider.removedKey)
        }
    }

    /**
     * Removes the provider's key, and remembers to remove Block Store's copy too. With no key stored
     * here there is nothing the reader could have meant to remove — and a key Block Store is about to
     * restore mustn't be wiped by an empty field saved before it arrived.
     */
    fun remove(provider: OnlineProvider) {
        if (readAll().getProperty(provider.raw) == null) return
        update {
            it.remove(provider.raw)
            it.setProperty(provider.removedKey, "1")
        }
    }

    // ---- What Block Store still has to hear -------------------------------------------------------

    /** A key removed on this device that Block Store may still hold. */
    fun removed(provider: OnlineProvider): Boolean = readAll().getProperty(provider.removedKey) != null

    /** Block Store no longer holds [provider]'s key — unless one was entered again meanwhile. */
    fun markRemovalBackedUp(provider: OnlineProvider) {
        if (readAll().getProperty(provider.raw) == null) update { it.remove(provider.removedKey) }
    }

    /**
     * Puts back a key Block Store restored. Only into an empty slot the reader hasn't cleared — a key
     * set here, or removed here, is never overwritten by an older copy.
     *
     * @return true when the key was restored.
     */
    fun restore(key: String, provider: OnlineProvider): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || removed(provider) || key(provider) != null) return false
        val sealed = Base64.getEncoder().encodeToString(wrapper.wrap(trimmed.toByteArray(Charsets.UTF_8)))
        update { it.setProperty(provider.raw, sealed) }
        return true
    }

    private val OnlineProvider.removedKey: String get() = "$raw.removed"

    companion object {
        const val ALIAS = "scripturealone.online-keys"
        const val FILE = "online-keys.properties"
    }
}
