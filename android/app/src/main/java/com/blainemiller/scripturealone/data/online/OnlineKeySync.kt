package com.blainemiller.scripturealone.data.online

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Where a copy of the reader's keys is kept off this device. On Android that is Google Block Store
 * ([BlockStoreKeyBackup]); the seam exists so the sync rules are tested on the JVM.
 */
interface KeyBackup {
    /** Every entry the backup holds among [names], by name. Throws when the backup can't be reached. */
    suspend fun retrieve(names: List<String>): Map<String, ByteArray>

    suspend fun store(name: String, bytes: ByteArray)

    suspend fun delete(names: List<String>)
}

/**
 * Keeps the reader's ESV and API.Bible keys in Block Store as iOS keeps them in iCloud Keychain, and
 * restores them onto a phone set up from another.
 *
 * One pass ([sync]), run at launch and after the keys are edited:
 * - a key held here is handed to Block Store (idempotent — the same bytes again);
 * - a key removed here is deleted from Block Store, and the removal mark cleared once it is;
 * - an empty slot the reader hasn't cleared takes Block Store's copy — the restore.
 *
 * Nothing here is ever logged, and a failure only means the next pass tries again.
 */
object OnlineKeySync {

    /** The Block Store entry for a provider's key. */
    fun entryName(provider: OnlineProvider): String = "online-key.${provider.raw}"

    private const val VERSION = "v1\n"

    /** What is stored: a version line, then the key in UTF-8 — so a later format can be told apart. */
    fun encode(key: String): ByteArray = (VERSION + key).toByteArray(Charsets.UTF_8)

    /** The key in [bytes], or null for anything this version didn't write. */
    fun decode(bytes: ByteArray): String? {
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        if (!text.startsWith(VERSION)) return null
        return text.removePrefix(VERSION).trim().takeIf { it.isNotEmpty() }
    }

    /** What a pass did, for tests and the debug log (never the keys themselves). */
    data class Result(val restored: Set<OnlineProvider>, val backedUp: Set<OnlineProvider>, val deleted: Set<OnlineProvider>)

    private val lock = Mutex()

    suspend fun sync(store: OnlineKeyStore, backup: KeyBackup): Result = lock.withLock {
        val restored = mutableSetOf<OnlineProvider>()
        val backedUp = mutableSetOf<OnlineProvider>()
        val deleted = mutableSetOf<OnlineProvider>()

        val gone = OnlineProvider.entries.filter { store.removed(it) }
        if (gone.isNotEmpty()) {
            runCatching { backup.delete(gone.map(::entryName)) }.onSuccess {
                gone.forEach { store.markRemovalBackedUp(it); deleted += it }
            }
        }

        val held = OnlineProvider.entries.associateWith { store.key(it) }
        for ((provider, key) in held) {
            if (key == null) continue
            runCatching { backup.store(entryName(provider), encode(key)) }.onSuccess { backedUp += provider }
        }

        val missing = OnlineProvider.entries.filter { held[it] == null && !store.removed(it) }
        if (missing.isNotEmpty()) {
            val found = runCatching { backup.retrieve(missing.map(::entryName)) }.getOrDefault(emptyMap())
            for (provider in missing) {
                val key = found[entryName(provider)]?.let(::decode) ?: continue
                if (store.restore(key, provider)) restored += provider
            }
        }
        Result(restored, backedUp, deleted)
    }
}

/**
 * [KeyBackup] over Google Block Store (`play-services-auth-blockstore`): free, no server, the data
 * end-to-end encrypted with the device's screen lock when the reader's Google account backs up
 * (`isEndToEndEncryptionAvailable`), and otherwise kept for a device-to-device transfer only. Block
 * Store restores during a new phone's setup (from the cloud backup or a cable/tap transfer), after which
 * the app finds the entries here — it is not a live sync between two phones in use at once, which is
 * where it differs from iCloud Keychain.
 */
class BlockStoreKeyBackup(context: Context) : KeyBackup {
    private val client = Blockstore.getClient(context.applicationContext)

    override suspend fun retrieve(names: List<String>): Map<String, ByteArray> {
        val response = client.retrieveBytes(RetrieveBytesRequest.Builder().setKeys(names).build()).await()
        return response.blockstoreDataMap.mapValues { it.value.bytes }
    }

    override suspend fun store(name: String, bytes: ByteArray) {
        // Block Store backs an entry up to the cloud only when it can encrypt it end to end (a screen
        // lock is set); otherwise it keeps it for device-to-device transfer alone.
        client.storeBytes(
            StoreBytesData.Builder().setKey(name).setBytes(bytes).setShouldBackupToCloud(true).build(),
        ).await()
    }

    override suspend fun delete(names: List<String>) {
        client.deleteBytes(DeleteBytesRequest.Builder().setKeys(names).build()).await()
    }
}

/** Awaits a Play services task without another dependency. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
