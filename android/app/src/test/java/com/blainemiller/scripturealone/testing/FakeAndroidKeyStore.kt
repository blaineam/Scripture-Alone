package com.blainemiller.scripturealone.testing

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * An in-memory stand-in for the Android Keystore on the JVM, so Robolectric tests can open the sealed
 * ASV through the app's own `AndroidKeystoreKeyWrapper` (data/sabible/SealedTranslationKeys.kt): a
 * `KeyStore` and an AES `KeyGenerator` registered as the "AndroidKeyStore" provider. Keys are plain
 * AES keys the JVM's own AES/GCM cipher uses; nothing leaves the process.
 */
object FakeAndroidKeyStore {
    private val keys: MutableMap<String, SecretKey> = Collections.synchronizedMap(LinkedHashMap())

    fun install() {
        if (Security.getProvider(NAME) == null) Security.insertProviderAt(FakeProvider(), 1)
    }

    /** Forgets every key, as a cleared credential store does. */
    fun clear() = keys.clear()

    private const val NAME = "AndroidKeyStore"


    private class FakeProvider : Provider(NAME, 1.0, "In-memory Android Keystore for JVM tests") {
        init {
            put("KeyStore.$NAME", FakeKeyStore::class.java.name)
            put("KeyGenerator.AES", FakeAesKeyGenerator::class.java.name)
        }
    }

    class FakeKeyStore : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = if (alias in keys) Date(0) else null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String) = alias in keys
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = alias in keys
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    class FakeAesKeyGenerator : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null
        private val random = SecureRandom()

        override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException("needs a KeyGenParameterSpec")
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            spec = params as? KeyGenParameterSpec ?: throw IllegalArgumentException("not a KeyGenParameterSpec")
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException("needs a KeyGenParameterSpec")
        override fun engineGenerateKey(): SecretKey {
            val spec = spec ?: throw IllegalStateException("not initialised")
            val bytes = ByteArray(spec.keySize.coerceAtLeast(128) / 8).also(random::nextBytes)
            return SecretKeySpec(bytes, "AES").also { keys[spec.keystoreAlias] = it }
        }
    }
}
