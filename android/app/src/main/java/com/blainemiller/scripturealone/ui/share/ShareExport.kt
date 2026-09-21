package com.blainemiller.scripturealone.ui.share

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * A rendered card handed on — `ShareExport.swift`: a PNG in the app's cache offered to the share
 * sheet through a `FileProvider`, or added to the photo library (`Pictures/Scripture Alone`) through
 * MediaStore, which on Android 10+ needs no storage permission for the app's own additions.
 */
object ShareExport {
    /** The `cache-path` in `res/xml/share_paths.xml`. Emptied before each export: one card at a time. */
    private const val DIRECTORY = "shared_images"

    fun authority(context: Context) = "${context.packageName}.shareimages"

    /** Encodes [bitmap] as a PNG named [filename] in the share cache and returns its content URI. */
    suspend fun write(context: Context, bitmap: Bitmap, filename: String): Uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, DIRECTORY)
        directory.deleteRecursively()
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Can’t prepare the image.")
        val file = File(directory, filename)
        file.outputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("Can’t encode the image.")
        }
        FileProvider.getUriForFile(context, authority(context), file)
    }

    /** The system share sheet for a PNG at [uri], read access granted to whichever app is chosen. */
    fun shareIntent(uri: Uri, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            // The ClipData is what lets the share sheet draw a preview of the image.
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    /** Adds the PNG to the photo library as-is (no re-encoding), as `PhotoSaver.save` does. */
    suspend fun saveToPhotos(context: Context, bitmap: Bitmap, filename: String): Unit = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Scripture Alone")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Scripture Alone can’t add to your photo library.")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("Can’t encode the image.")
            } ?: throw IOException("Scripture Alone can’t add to your photo library.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
