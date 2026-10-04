package com.blainemiller.scripturealone.data.guide

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Where the guide packages live, and how one is checked and unpacked — `UserGuidePackage` in
 * `UserGuide.swift`. Plain JVM, so every rule here is unit-tested.
 */
object UserGuidePackage {
    /** The release the CI workflow (`.github/workflows/user-guide.yml`) publishes to. */
    const val RELEASE_BASE = "https://github.com/blaineam/Scripture-Alone/releases/download/user-guide/"
    const val INDEX_URL = RELEASE_BASE + "UserGuide-index.json"

    /** The guide languages, as the packages are named. */
    val LANGUAGES = listOf("en", "zh-Hans", "ja", "de", "fr", "es", "ko", "pt-BR", "it")

    /** The file in an unpacked guide's directory that records the package's SHA-256. */
    const val SHA_FILE = "package.sha256"

    /** A package larger than this is refused unread — the real ones are about 1 MB. */
    const val MAX_PACKAGE_BYTES = 32L * 1024 * 1024

    /**
     * The package for a list of preferred languages (the app's, best first): the first one with a
     * guide, English failing that. zh → zh-Hans, pt → pt-BR, as the app's own languages are.
     */
    fun language(preferred: List<String>): String {
        for (raw in preferred) {
            val tag = raw.replace('_', '-')
            if (tag.startsWith("zh")) return "zh-Hans"
            if (tag.startsWith("pt")) return "pt-BR"
            val base = tag.substringBefore('-')
            if (base in LANGUAGES) return base
        }
        return "en"
    }

    fun packageUrl(language: String) = "${RELEASE_BASE}UserGuide-$language.zip"

    /** The printable PDF of the same guide — what Share hands on. */
    fun pdfUrl(language: String) = "${RELEASE_BASE}UserGuide-$language.pdf"

    /** One package's line in `UserGuide-index.json`. */
    data class Entry(val sha256: String, val size: Long)

    /**
     * `UserGuide-index.json` — `{"schema":1,"packages":{"<code>":{"sha256","size"}}}` — as a map. An
     * index of a newer schema, or one that isn't this shape, fails.
     */
    @Throws(GuideFormatException::class)
    fun parseIndex(json: String): Map<String, Entry> {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw GuideFormatException("the index is not a JSON object")
        val schema = (root["schema"] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw GuideFormatException("the index has no schema")
        if (schema > UserGuide.SUPPORTED_SCHEMA) throw UnsupportedSchemaException(schema)
        val packages = root["packages"] as? JsonObject ?: throw GuideFormatException("the index has no packages")
        return packages.mapNotNull { (code, value) ->
            val entry = value as? JsonObject ?: return@mapNotNull null
            val sha = (entry["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase() ?: return@mapNotNull null
            val size = (entry["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            code to Entry(sha, size)
        }.toMap()
    }

    /** Lower-case hex SHA-256 of [bytes]. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Whether [bytes] are the package the index describes: the SHA-256 must match exactly. */
    fun verify(bytes: ByteArray, expectedSha256: String): Boolean =
        sha256(bytes).equals(expectedSha256.trim(), ignoreCase = true)

    /**
     * Whether a zip entry name may be written: `guide.json`, or a flat `images/<name>` — no `..`, no
     * deeper folder, no backslash, no hidden file. Anything else fails the whole package.
     */
    fun isSafeName(name: String): Boolean {
        if (name == "guide.json") return true
        if (!name.startsWith("images/")) return false
        val file = name.removePrefix("images/")
        return file.isNotEmpty() && '/' !in file && '\\' !in file && ".." !in file && !file.startsWith(".") &&
            file.none { it.code < 0x20 }
    }

    /**
     * Unpacks a downloaded package into [directory] (replacing what was there) and returns the guide
     * it holds. The whole package is read and checked — names, guide.json, schema — before anything
     * is written; then it is written into a staging directory beside [directory] and swapped in, so a
     * failure part way leaves the old copy as it was. [sha256], when given, is recorded with it.
     */
    @Throws(IOException::class, GuideFormatException::class)
    fun unpack(data: ByteArray, directory: File, sha256: String? = null): UserGuide {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) {
                    if (name != "images/") throw GuideFormatException("unexpected folder \"$name\"")
                    continue
                }
                if (!isSafeName(name)) throw GuideFormatException("unexpected file \"$name\"")
                if (name in entries) throw GuideFormatException("\"$name\" twice")
                val bytes = readCapped(zip, MAX_PACKAGE_BYTES * 4 - total)
                total += bytes.size
                entries[name] = bytes
            }
        }
        val json = entries["guide.json"] ?: throw GuideFormatException("the package has no guide.json")
        val guide = UserGuide.decode(json.toString(Charsets.UTF_8))

        val parent = directory.absoluteFile.parentFile ?: throw IOException("no parent for $directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("can't create $parent")
        val staging = File(parent, ".${directory.name}-${UUID.randomUUID()}")
        try {
            val images = File(staging, "images")
            if (!images.mkdirs()) throw IOException("can't create $images")
            for ((name, bytes) in entries) File(staging, name).writeBytes(bytes)
            sha256?.let { File(staging, SHA_FILE).writeText(it.lowercase()) }
            swap(staging, directory)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        return guide
    }

    /** The guide unpacked in [directory], if there is a readable one this build can draw. */
    fun load(directory: File): UserGuide? = runCatching {
        UserGuide.decode(File(directory, "guide.json").readText(Charsets.UTF_8))
    }.getOrNull()

    /** The SHA-256 recorded with the guide in [directory], if any. */
    fun recordedSha(directory: File): String? =
        runCatching { File(directory, SHA_FILE).readText().trim().lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** [staging] put in [target]'s place: the old copy moved aside first, then removed. */
    private fun swap(staging: File, target: File) {
        val aside = File(target.absoluteFile.parentFile, ".${target.name}-old-${UUID.randomUUID()}")
        val hadOld = target.exists()
        if (hadOld && !target.renameTo(aside)) throw IOException("can't move the old guide aside")
        if (!staging.renameTo(target)) {
            if (hadOld) aside.renameTo(target)
            throw IOException("can't move the new guide into place")
        }
        if (hadOld) aside.deleteRecursively()
    }

    private fun readCapped(input: ZipInputStream, remaining: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            read += n
            if (read > remaining) throw GuideFormatException("the package unpacks too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
