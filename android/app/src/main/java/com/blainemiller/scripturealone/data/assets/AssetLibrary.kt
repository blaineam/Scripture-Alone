package com.blainemiller.scripturealone.data.assets

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import android.content.pm.ApplicationInfo
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.BundledDatabase
import com.blainemiller.scripturealone.text.AppText
import com.google.android.gms.tasks.Task
import com.google.android.play.core.assetpacks.AssetPackException
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where one pack stands, for the banner and the Study download screens — `AssetLibrary.State`. */
sealed interface AssetState {
    data object Absent : AssetState
    data class Downloading(val fraction: Float) : AssetState
    /**
     * Play is holding the download for Wi-Fi, or wants the reader's go-ahead first. Not a failure:
     * [AssetLibrary.confirm] shows Play's own dialog.
     */
    data class NeedsConfirmation(val fraction: Float) : AssetState
    data object Ready : AssetState
    data class Failed(val message: String) : AssetState
}

/**
 * Finds, fetches and keeps the databases that ship as asset packs — `AssetLibrary.swift`.
 *
 * **Where a database comes from**, first match wins:
 * 1. A copy already taken out of its pack, in no-backup storage (`packs/`). Once there, it is ordinary
 *    app data: it survives every app update and is never fetched twice.
 * 2. The app's own assets. An install-time pack (ASV, BSB) is merged into the assets by Play, and a
 *    debug APK carries every pack's file there (see `localPacks` in the build script), so a developer
 *    build runs with nothing to download. [BundledDatabase] copies it out, version-stamped, as it
 *    always has.
 * 3. An on-demand pack Play has already delivered but the app hasn't copied yet (the process died
 *    between the two): copied now.
 *
 * Otherwise the pack is absent, and [ensure] asks Google Play for it — Play's own hosting, free, no
 * server — reporting progress through [states].
 *
 * **Why the files are copied out, then the pack removed**, as iOS does: SQLite needs a path that stays
 * put, and a pack's location is Play's to move; and keeping the pack as well would store the database
 * twice. Once removed, an on-demand pack is never fetched again unless asked for, so an app update
 * doesn't re-download what the reader already has — nor, as on iOS, does it refresh a copied
 * database. A changed database therefore ships under a new file name.
 */
object AssetLibrary {

    private const val TAG = "AssetLibrary"

    private lateinit var app: Context
    /** Debuggable builds log each pack's progress; release builds log nothing. */
    private val debug: Boolean get() = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = mutableMapOf<AssetPack, Deferred<Boolean>>()
    private val manager: AssetPackManager by lazy { AssetPackManagerFactory.getInstance(app) }

    private val _states = MutableStateFlow<Map<AssetPack, AssetState>>(emptyMap())
    val states: StateFlow<Map<AssetPack, AssetState>> = _states

    /**
     * Registered by the activity, so Play's "download over mobile data?" dialog can be shown when Play
     * asks for it. Null while no activity is up; the banner then waits.
     */
    var confirmationLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    /** Called once from `Application.onCreate`. */
    fun attach(context: Context) {
        app = context.applicationContext
        _states.value = AssetPack.entries.associateWith { if (isOnDevice(it)) AssetState.Ready else AssetState.Absent }
    }

    val isAttached: Boolean get() = ::app.isInitialized

    fun state(pack: AssetPack): AssetState = _states.value[pack] ?: AssetState.Absent

    private fun set(pack: AssetPack, state: AssetState) = _states.update { it + (pack to state) }

    /**
     * Whether [pack]'s database can be opened without a download. Cheap — no copying — so the reader
     * can ask it on the main thread before deciding whether choosing a translation starts a download.
     */
    fun isOnDevice(pack: AssetPack): Boolean =
        installedCopy(pack).exists() || BundledDatabase.hasAsset(app, pack.file) || deliveredFile(pack) != null

    /**
     * Whether the reader has downloaded [pack] — delivered by Play or already copied out. Unlike
     * [isOnDevice] this ignores a debug build's own assets, which carry every pack: it answers what
     * the reader chose, for the translation menu.
     */
    fun isDownloaded(pack: AssetPack): Boolean = installedCopy(pack).exists() || deliveredFile(pack) != null

    /**
     * The database's file, copying it out of the assets or a delivered pack first if need be, or null
     * when the pack isn't on the device. Blocking; call off the main thread.
     */
    fun file(context: Context, pack: AssetPack): File? {
        if (!isAttached) attach(context)
        return synchronized(lock(pack)) { resolve(pack) }
    }

    private fun resolve(pack: AssetPack): File? {
        val copy = installedCopy(pack)
        if (copy.exists()) return copy
        if (BundledDatabase.hasAsset(app, pack.file)) return BundledDatabase.copyFromAssets(app, pack.file)
        val delivered = deliveredFile(pack) ?: return null
        // Beside the destination and then moved into place, so an interrupted copy never leaves a
        // truncated database to be opened next launch.
        val partial = File(copy.parentFile, ".${pack.file}.partial")
        delivered.inputStream().use { input -> partial.outputStream().use { input.copyTo(it, 1 shl 20) } }
        if (!partial.renameTo(copy)) {
            copy.delete()
            check(partial.renameTo(copy)) { "Could not move ${pack.file} into place" }
        }
        // The file is ours now; keeping the pack as well would store it twice.
        runCatching { manager.removePack(pack.packName) }
        if (debug) Log.d(TAG, "${pack.packName}: copied out of its pack (${copy.length()} bytes)")
        return copy
    }

    private val locks = AssetPack.entries.associateWith { Any() }
    private fun lock(pack: AssetPack): Any = locks.getValue(pack)

    private fun installedCopy(pack: AssetPack): File =
        File(File(app.noBackupFilesDir, "packs").apply { mkdirs() }, pack.file)

    /** The file inside a pack Play has delivered, or null. Install-time packs are read as assets instead. */
    private fun deliveredFile(pack: AssetPack): File? {
        if (pack.delivery == AssetPack.Delivery.INSTALL_TIME) return null
        val path = runCatching { manager.getPackLocation(pack.packName)?.assetsPath() }.getOrNull() ?: return null
        return File(path, pack.file).takeIf { it.isFile }
    }

    /**
     * Fetches the pack if its database isn't already here, and copies it out. Safe to call every time
     * a feature is opened: an installed database returns at once, and a second call while one is in
     * flight waits for the same download.
     */
    suspend fun ensure(pack: AssetPack): Boolean {
        val job = synchronized(inflight) {
            inflight[pack]?.takeIf { it.isActive } ?: scope.async { fetch(pack) }.also { inflight[pack] = it }
        }
        return job.await()
    }

    private suspend fun fetch(pack: AssetPack): Boolean {
        if (file(app, pack) != null) {
            set(pack, AssetState.Ready)
            return true
        }
        if (pack.delivery == AssetPack.Delivery.INSTALL_TIME) {
            // Arrives with the install from Play; a copy without it was installed some other way.
            set(pack, AssetState.Failed(AssetPackProgress.notInThisCopy(pack)))
            return false
        }
        set(pack, AssetState.Downloading(0f))
        val finished = CompletableDeferred<AssetPackState>()
        val listener = AssetPackStateUpdateListener { update ->
            if (update.name() != pack.packName) return@AssetPackStateUpdateListener
            if (debug) {
                Log.d(TAG, "${pack.packName}: status ${update.status()} ${update.bytesDownloaded()}/${update.totalBytesToDownload()}")
            }
            AssetPackProgress.state(update.status(), update.bytesDownloaded(), update.totalBytesToDownload())?.let { set(pack, it) }
            if (AssetPackProgress.isTerminal(update.status())) finished.complete(update)
        }
        manager.registerListener(listener)
        try {
            val states = manager.fetch(listOf(pack.packName)).await()
            // Already delivered, or failed at once: the listener may never hear about it.
            states.packStates()[pack.packName]?.let { listener.onStateUpdate(it) }
            val final = finished.await()
            if (final.status() != AssetPackStatus.COMPLETED) {
                set(pack, AssetState.Failed(AssetPackProgress.message(final.errorCode(), final.status(), pack)))
                return false
            }
            val copied = withContext(Dispatchers.IO) { runCatching { file(app, pack) }.getOrNull() }
            return if (copied != null) {
                set(pack, AssetState.Ready)
                true
            } else {
                set(pack, AssetState.Failed(AssetPackProgress.message(AssetPackErrorCode.INTERNAL_ERROR, AssetPackStatus.FAILED, pack)))
                false
            }
        } catch (e: AssetPackException) {
            if (debug) Log.d(TAG, "${pack.packName}: fetch refused, error ${e.errorCode}")
            set(pack, AssetState.Failed(AssetPackProgress.message(e.errorCode, AssetPackStatus.FAILED, pack)))
            return false
        } catch (e: Exception) {
            if (debug) Log.d(TAG, "${pack.packName}: fetch failed", e)
            set(pack, AssetState.Failed(AssetPackProgress.message(AssetPackErrorCode.INTERNAL_ERROR, AssetPackStatus.FAILED, pack)))
            return false
        } finally {
            manager.unregisterListener(listener)
        }
    }

    /** Shows Play's own dialog for a download held for Wi-Fi or awaiting the reader's go-ahead. */
    fun confirm(): Boolean {
        val launcher = confirmationLauncher ?: return false
        return runCatching { manager.showConfirmationDialog(launcher) }.getOrDefault(false)
    }

    /**
     * Forgets a failed translation download once the reader has moved on to another, so its "couldn't
     * download" banner doesn't outlive the choice it was about.
     */
    fun clearFailedTranslations(except: AssetPack?) {
        for (other in AssetPack.entries.filter { it.isTranslation && it != except }) {
            if (state(other) is AssetState.Failed) set(other, AssetState.Absent)
        }
    }

    /** Removes a downloaded database, for a reader reclaiming space. Install-time packs stay. */
    fun remove(pack: AssetPack) {
        synchronized(lock(pack)) { installedCopy(pack).delete() }
        set(pack, if (isOnDevice(pack)) AssetState.Ready else AssetState.Absent)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
    }
}

/**
 * Play's pack statuses and error codes, in the reader's terms — kept apart from [AssetLibrary] so the
 * mapping is unit-tested without a device.
 */
object AssetPackProgress {

    /** The state to show for a status update, or null for one that changes nothing on screen. */
    fun state(status: Int, downloaded: Long, total: Long): AssetState? {
        val fraction = if (total > 0) (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
        return when (status) {
            AssetPackStatus.PENDING -> AssetState.Downloading(0f)
            AssetPackStatus.DOWNLOADING -> AssetState.Downloading(fraction)
            // Downloaded, now being unpacked on the device.
            AssetPackStatus.TRANSFERRING -> AssetState.Downloading(1f)
            AssetPackStatus.WAITING_FOR_WIFI, AssetPackStatus.REQUIRES_USER_CONFIRMATION -> AssetState.NeedsConfirmation(fraction)
            else -> null
        }
    }

    fun isTerminal(status: Int): Boolean = status == AssetPackStatus.COMPLETED ||
        status == AssetPackStatus.FAILED || status == AssetPackStatus.CANCELED

    /** What a failed download says. iOS's sentences where iOS has one. */
    fun message(errorCode: Int, status: Int, pack: AssetPack): String = when {
        status == AssetPackStatus.CANCELED -> AppText.get(R.string.data_pack_download_cancelled, pack.title)
        errorCode == AssetPackErrorCode.NETWORK_ERROR -> AppText.get(R.string.data_pack_needs_connection, pack.title)
        errorCode == AssetPackErrorCode.INSUFFICIENT_STORAGE -> AppText.get(R.string.data_pack_insufficient_storage, pack.title)
        errorCode in UNAVAILABLE -> notInThisCopy(pack)
        else -> AppText.get(R.string.data_pack_download_failed, pack.title, errorCode)
    }

    /**
     * The one failure a reader can do nothing about from inside the app: this copy wasn't installed
     * from Google Play (a sideloaded or debug-built release), so Play won't deliver its packs. Said
     * plainly rather than offering a retry that cannot succeed.
     */
    fun notInThisCopy(pack: AssetPack): String =
        AppText.get(R.string.data_pack_not_from_play, pack.title)

    private val UNAVAILABLE = setOf(
        AssetPackErrorCode.APP_UNAVAILABLE,
        AssetPackErrorCode.PACK_UNAVAILABLE,
        AssetPackErrorCode.API_NOT_AVAILABLE,
        AssetPackErrorCode.APP_NOT_OWNED,
        AssetPackErrorCode.UNRECOGNIZED_INSTALLATION,
    )
}
