package com.example.satchel.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.satchel.ui.mimeTypeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Documents' files on the phone. Downloads go to a private cache first (shared
 * with viewer apps through the FileProvider in res/xml/file_paths.xml), and can
 * then be copied to the public Downloads/Satchel folder.
 */
class DocumentFiles(private val context: Context, private val api: ApiClient) {
    private val cache = File(context.cacheDir, "documents")

    /** The uploader's file name is only for display: keep letters, digits, dots, dashes and spaces. */
    fun fileName(doc: Document): String =
        doc.originalFilename.replace(Regex("""[^\w .\-]"""), "_").ifBlank { "document" }

    /** The document's file in the cache, downloading it if it is not there yet. */
    suspend fun fetch(doc: Document): File {
        val target = File(cache, "${doc.id}/${fileName(doc)}")
        if (!(target.exists() && target.length() == doc.sizeBytes)) api.download(doc.id, target)
        return target
    }

    /** A content:// address another app may read, for opening or sharing. */
    fun shareableUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /**
     * Copies the document into Downloads/Satchel, where the Files app and others
     * can see it. Needs no storage permission from Android 10 (the app's minimum).
     * If a file of the same name is already there, Android picks a new name.
     */
    suspend fun saveToDownloads(doc: Document) {
        val file = fetch(doc)
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(file.name))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Satchel")
                put(MediaStore.Downloads.IS_PENDING, 1)   // hidden from other apps until complete
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw ApiException(0, "Could not save to Downloads.")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("No output stream")
                out.use { o -> file.inputStream().use { it.copyTo(o) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                throw ApiException(0, "Could not save “${doc.title}” to Downloads.")
            }
        }
    }

    /** Forget every downloaded file (on logout). Copies in Downloads stay: they belong to the person now. */
    fun clearCache() {
        cache.deleteRecursively()
    }
}
