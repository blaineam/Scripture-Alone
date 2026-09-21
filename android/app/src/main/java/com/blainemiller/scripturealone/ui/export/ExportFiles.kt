package com.blainemiller.scripturealone.ui.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A finished export: one file, or a folder of files (Markdown Folder) — `ExportFile.Contents`.
 * [name] is the file's name, or the folder's.
 */
data class ExportedFile(val name: String, val mimeType: String, val contents: Contents) {
    sealed class Contents {
        class File(val data: ByteArray) : Contents()
        /** File names to contents, in order. */
        class Folder(val files: List<Pair<String, ByteArray>>) : Contents()
    }

    val isFolder: Boolean get() = contents is Contents.Folder
}

/**
 * Save to Files for a file of any type: the Storage Access Framework's create-document picker, given
 * the type and the suggested name — `.fileExporter`. The platform contract fixes the type up front;
 * an export's type depends on the format chosen.
 */
class CreateDocumentOfType : androidx.activity.result.contract.ActivityResultContract<Pair<String, String>, Uri?>() {
    /** Input: the MIME type and the suggested file name. */
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.first).putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

/**
 * Hands exports on — `ExportStaging` and the sheet's `ShareLink` / `.fileExporter`. A file is written
 * into the app's cache and offered to the share sheet through the `FileProvider`, or written where the
 * reader chooses through the Storage Access Framework (Save to Files): `CreateDocument` for a file,
 * `OpenDocumentTree` for a folder, into which the folder is created.
 */
object ExportFiles {
    /** The `cache-path` in `res/xml/share_paths.xml`. */
    private const val DIRECTORY = "exports"

    const val PDF = "application/pdf"
    const val MARKDOWN = "text/markdown"
    const val TEXT = "text/plain"
    /** The keepsake's type, as the iOS app declares it (`Info.plist`, docs/heir-mode.md). */
    const val KEEPSAKE = "application/vnd.scripturealone.legacy+zip"

    private fun authority(context: Context) = "${context.packageName}.shareimages"

    /**
     * Writes [file] into a fresh folder of the export cache and returns content URIs for what the share
     * sheet should carry — the file, or each file of a folder. Earlier exports are cleared first.
     */
    suspend fun stage(context: Context, file: ExportedFile): List<Uri> = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, DIRECTORY)
        root.deleteRecursively()
        val folder = File(root, UUID.randomUUID().toString())
        if (!folder.mkdirs()) throw IOException("Can’t prepare the export.")
        val written = when (val contents = file.contents) {
            is ExportedFile.Contents.File -> listOf(File(folder, file.name).apply { writeBytes(contents.data) })
            is ExportedFile.Contents.Folder -> {
                val dir = File(folder, file.name).apply { mkdirs() }
                contents.files.map { (name, data) -> File(dir, name).apply { writeBytes(data) } }
            }
        }
        written.map { FileProvider.getUriForFile(context, authority(context), it) }
    }

    /** The share sheet for staged [uris], read access granted to whichever app is chosen. */
    fun shareIntent(uris: List<Uri>, mimeType: String, title: String): Intent {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }.apply {
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, title)
            clipData = ClipData.newRawUri(title, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    /** Writes a single file to the document the reader created with `CreateDocument`. */
    suspend fun write(context: Context, target: Uri, data: ByteArray): Unit = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(data) }
            ?: throw IOException("Scripture Alone can’t write there.")
    }

    /** Creates the folder inside the tree the reader picked with `OpenDocumentTree`, and its files. */
    suspend fun writeFolder(context: Context, tree: Uri, file: ExportedFile): Unit = withContext(Dispatchers.IO) {
        val contents = file.contents as? ExportedFile.Contents.Folder ?: throw IOException("Not a folder.")
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val folder = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, file.name)
            ?: throw IOException("Scripture Alone can’t make a folder there.")
        for ((name, data) in contents.files) {
            val doc = DocumentsContract.createDocument(resolver, folder, file.mimeType, name)
                ?: throw IOException("Scripture Alone can’t write $name there.")
            resolver.openOutputStream(doc, "wt")?.use { it.write(data) } ?: throw IOException("Scripture Alone can’t write $name there.")
        }
    }
}
