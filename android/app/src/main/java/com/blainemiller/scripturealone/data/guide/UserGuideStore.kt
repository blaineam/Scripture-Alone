package com.blainemiller.scripturealone.data.guide

import android.content.Context
import com.blainemiller.scripturealone.text.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The guide copies kept on the device, as the iOS app keeps them: one per language in
 * `filesDir/user_guide/<code>/` with the package's SHA-256 beside it.
 *
 * Nothing is fetched until the reader opens the guide. Opening shows a held copy at once ([held]) and
 * then [refresh]es it in the background: the index is fetched, and only when its SHA-256 for this
 * language differs from the one held is the package downloaded, checked against it, unpacked into a
 * staging directory and swapped in. The requests are plain GETs for public files; nothing about the
 * device or the reader is sent.
 */
class UserGuideStore(private val root: File, private val transport: Transport = HttpTransport) {

    /** A GET for [url]'s body, refusing more than [maxBytes]. Blocking. */
    fun interface Transport {
        @Throws(IOException::class)
        fun get(url: String, maxBytes: Long): ByteArray
    }

    /** A guide on the device, and the directory its images are in. */
    data class Copy(val language: String, val guide: UserGuide, val directory: File) {
        fun image(name: String): File = File(directory, "images/$name")
    }

    fun directory(language: String) = File(root, language)

    /** The copy held for [language], if there is a readable one. Blocking (a small file read). */
    fun held(language: String): Copy? {
        val dir = directory(language)
        return UserGuidePackage.load(dir)?.let { Copy(language, it, dir) }
    }

    /**
     * Brings [language]'s copy up to date: the new copy when one was downloaded, null when the held
     * copy is already current. Throws when the network or the package fails — the held copy, if any,
     * is untouched. Blocking; call off the main thread.
     */
    @Throws(IOException::class, GuideFormatException::class)
    fun refreshBlocking(language: String): Copy? {
        val index = UserGuidePackage.parseIndex(
            transport.get(UserGuidePackage.INDEX_URL, 1L * 1024 * 1024).toString(Charsets.UTF_8),
        )
        val entry = index[language] ?: throw GuideFormatException("the index has no \"$language\" guide")
        val dir = directory(language)
        if (UserGuidePackage.recordedSha(dir) == entry.sha256 && UserGuidePackage.load(dir) != null) return null
        val data = transport.get(UserGuidePackage.packageUrl(language), UserGuidePackage.MAX_PACKAGE_BYTES)
        if (!UserGuidePackage.verify(data, entry.sha256)) throw GuideFormatException("the download doesn't match the index")
        val guide = UserGuidePackage.unpack(data, dir, entry.sha256)
        return Copy(language, guide, dir)
    }

    /** [refreshBlocking] on the IO dispatcher, one at a time per process. */
    suspend fun refresh(language: String): Copy? = withContext(Dispatchers.IO) {
        lock.withLock { refreshBlocking(language) }
    }

    suspend fun heldAsync(language: String): Copy? = withContext(Dispatchers.IO) { held(language) }

    companion object {
        private val lock = Mutex()

        /** The guide language the app is running in: zh → zh-Hans, pt → pt-BR, else English. */
        val currentLanguage: String get() = UserGuidePackage.language(listOf(AppLanguage.current))

        fun forContext(context: Context) = UserGuideStore(File(context.applicationContext.filesDir, "user_guide"))
    }
}

/** [UserGuideStore.Transport] over `HttpURLConnection`, as the eBible catalogue downloads. */
object HttpTransport : UserGuideStore.Transport {
    override fun get(url: String, maxBytes: Long): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            // GitHub answers a release download with a redirect to its file host, both HTTPS.
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            val status = connection.responseCode
            if (status !in 200 until 300) throw IOException("HTTP $status for $url")
            if (connection.contentLengthLong > maxBytes) throw IOException("$url is too large")
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("$url is too large")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
