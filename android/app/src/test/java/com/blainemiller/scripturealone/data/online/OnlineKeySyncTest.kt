package com.blainemiller.scripturealone.data.online

import com.blainemiller.scripturealone.data.sabible.KeyWrapper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The reader's keys across devices — iCloud Keychain's behaviour on iOS, Block Store's here. Block Store
 * and the Keystore only run on a device, so both are stand-ins behind their seams: a software AES-GCM
 * key for the Keystore, a map for Block Store (which can be made unreachable). Each "device" is its own
 * directory and its own device key; the backup is what travels between them.
 */
class OnlineKeySyncTest {

    @get:Rule val temp = TemporaryFolder()

    private class SoftwareWrapper : KeyWrapper {
        var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun wrap(plaintext: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key ?: throw GeneralSecurityException("gone"), GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plaintext)
        }

        override fun unwrap(sealed: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key ?: throw GeneralSecurityException("gone"), GCMParameterSpec(128, sealed, 0, 12))
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }

        override fun erase() {
            key = null
        }
    }

    /** Block Store: the reader's Google account's copy. */
    private class FakeBackup : KeyBackup {
        val entries = mutableMapOf<String, ByteArray>()
        var reachable = true

        private fun check() {
            if (!reachable) throw IOException("Play services unavailable")
        }

        override suspend fun retrieve(names: List<String>): Map<String, ByteArray> {
            check()
            return entries.filterKeys { it in names }
        }

        override suspend fun store(name: String, bytes: ByteArray) {
            check()
            entries[name] = bytes
        }

        override suspend fun delete(names: List<String>) {
            check()
            names.forEach { entries.remove(it) }
        }
    }

    private inner class Device(val wrapper: SoftwareWrapper = SoftwareWrapper(), val dir: File = temp.newFolder()) {
        val store get() = OnlineKeyStore(dir, wrapper)
    }

    private val esv = OnlineProvider.CROSSWAY
    private val apiBible = OnlineProvider.API_BIBLE

    @Test
    fun aKeyEnteredOnOnePhoneIsRestoredOnTheNext() = runTest {
        val backup = FakeBackup()
        val old = Device()
        old.store.set("  esv-key-123 ", esv)
        val first = OnlineKeySync.sync(old.store, backup)
        assertEquals(setOf(esv), first.backedUp)

        val new = Device()
        assertNull(new.store.key(esv))
        val result = OnlineKeySync.sync(new.store, backup)
        assertEquals(setOf(esv), result.restored)
        assertEquals("esv-key-123", new.store.key(esv))
        assertNull(new.store.key(apiBible))
    }

    @Test
    fun whatTravelsIsTheVersionedKeyNotTheDeviceCiphertext() = runTest {
        val backup = FakeBackup()
        val phone = Device()
        phone.store.set("abc", apiBible)
        OnlineKeySync.sync(phone.store, backup)
        assertArrayEquals("v1\nabc".toByteArray(), backup.entries[OnlineKeySync.entryName(apiBible)])
        // …while on disk only this device's ciphertext sits.
        val onDisk = File(phone.dir, OnlineKeyStore.FILE).readText()
        assertFalse(onDisk.contains("abc"))
    }

    @Test
    fun aKeyRemovedHereIsRemovedFromTheBackupAndNotRestored() = runTest {
        val backup = FakeBackup()
        val phone = Device()
        phone.store.set("k", esv)
        OnlineKeySync.sync(phone.store, backup)
        phone.store.remove(esv)
        val result = OnlineKeySync.sync(phone.store, backup)
        assertEquals(setOf(esv), result.deleted)
        assertTrue(backup.entries.isEmpty())
        assertNull(phone.store.key(esv))
        assertFalse(phone.store.removed(esv))
        // And a new phone restored afterwards gets nothing back.
        assertTrue(OnlineKeySync.sync(Device().store, backup).restored.isEmpty())
    }

    @Test
    fun aRemovalMadeOfflineIsNotUndoneByTheBackupsOldCopy() = runTest {
        val backup = FakeBackup()
        val phone = Device()
        phone.store.set("k", esv)
        OnlineKeySync.sync(phone.store, backup)
        backup.reachable = false
        phone.store.remove(esv)
        OnlineKeySync.sync(phone.store, backup)
        assertTrue(phone.store.removed(esv))

        backup.reachable = true
        val result = OnlineKeySync.sync(phone.store, backup)
        assertTrue(result.restored.isEmpty())
        assertEquals(setOf(esv), result.deleted)
        assertNull(phone.store.key(esv))
        assertTrue(backup.entries.isEmpty())
    }

    @Test
    fun aKeyChangedHereReplacesTheBackupsAndIsNeverOverwrittenByIt() = runTest {
        val backup = FakeBackup()
        backup.entries[OnlineKeySync.entryName(esv)] = OnlineKeySync.encode("old")
        val phone = Device()
        phone.store.set("new", esv)
        val result = OnlineKeySync.sync(phone.store, backup)
        assertTrue(result.restored.isEmpty())
        assertEquals("new", phone.store.key(esv))
        assertEquals("new", OnlineKeySync.decode(backup.entries.getValue(OnlineKeySync.entryName(esv))))
    }

    @Test
    fun aKeyEnteredOfflineReachesTheBackupOnTheNextPass() = runTest {
        val backup = FakeBackup().apply { reachable = false }
        val phone = Device()
        phone.store.set("k", apiBible)
        assertTrue(OnlineKeySync.sync(phone.store, backup).backedUp.isEmpty())
        backup.reachable = true
        assertEquals(setOf(apiBible), OnlineKeySync.sync(phone.store, backup).backedUp)
        assertEquals("k", OnlineKeySync.decode(backup.entries.getValue(OnlineKeySync.entryName(apiBible))))
    }

    @Test
    fun aLostDeviceKeyIsRecoveredFromTheBackup() = runTest {
        val backup = FakeBackup()
        val phone = Device()
        phone.store.set("k", esv)
        OnlineKeySync.sync(phone.store, backup)
        // The Keystore key is gone (a cleared credential store): the sealed value is unreadable.
        phone.wrapper.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(phone.store.key(esv))
        assertFalse(phone.store.removed(esv))
        assertEquals(setOf(esv), OnlineKeySync.sync(phone.store, backup).restored)
        assertEquals("k", phone.store.key(esv))
    }

    @Test
    fun anEmptyFieldSavedBeforeTheRestoreArrivesDoesNotWipeTheBackup() = runTest {
        val backup = FakeBackup()
        backup.entries[OnlineKeySync.entryName(esv)] = OnlineKeySync.encode("k")
        val phone = Device()
        phone.store.set("", esv) // Done on the keys page with nothing typed
        assertFalse(phone.store.removed(esv))
        assertEquals(setOf(esv), OnlineKeySync.sync(phone.store, backup).restored)
    }

    @Test
    fun anUnreachableBackupChangesNothingHere() = runTest {
        val backup = FakeBackup().apply { reachable = false }
        val phone = Device()
        val result = OnlineKeySync.sync(phone.store, backup)
        assertEquals(OnlineKeySync.Result(emptySet(), emptySet(), emptySet()), result)
        assertNull(phone.store.key(esv))
    }

    @Test
    fun entriesThisVersionDidNotWriteAreIgnored() = runTest {
        assertNull(OnlineKeySync.decode("esv-key".toByteArray()))
        assertNull(OnlineKeySync.decode("v2\nesv-key".toByteArray()))
        assertNull(OnlineKeySync.decode("v1\n   ".toByteArray()))
        assertEquals("abc", OnlineKeySync.decode(OnlineKeySync.encode("abc")))
        val backup = FakeBackup()
        backup.entries[OnlineKeySync.entryName(esv)] = "garbage".toByteArray()
        val phone = Device()
        assertTrue(OnlineKeySync.sync(phone.store, backup).restored.isEmpty())
        assertNull(phone.store.key(esv))
    }

    @Test
    fun settingTheSameKeyAgainKeepsTheSealedValue() {
        val phone = Device()
        phone.store.set("k", esv)
        val before = File(phone.dir, OnlineKeyStore.FILE).readText().lines().first { it.startsWith("crossway=") }
        phone.store.set("k", esv)
        val after = File(phone.dir, OnlineKeyStore.FILE).readText().lines().first { it.startsWith("crossway=") }
        assertEquals(before, after)
    }

    @Test
    fun theEntryNamesAreStable() {
        // Changing these would strand every key already in a reader's Block Store.
        assertEquals("online-key.crossway", OnlineKeySync.entryName(esv))
        assertEquals("online-key.apiBible", OnlineKeySync.entryName(apiBible))
    }
}
