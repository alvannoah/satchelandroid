package com.example.satchel.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.satchel.ui.mimeTypeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A downloaded document: its details, its file and (if the server made one) its PDF copy. */
data class Download(val document: Document, val file: File, val view: File?, val savedAt: Long) {
    val sizeBytes: Long get() = file.length() + (view?.length() ?: 0)
}

/**
 * Documents' files on the phone, in three places:
 * - a private cache, for opening once (Android may clear it);
 * - Satchel's Downloads, kept until removed and readable offline, one folder per
 *   account, so someone else signing in on the phone does not see them;
 * - the phone's public Downloads/Satchel folder, when the person saves a copy there.
 * Files reach other apps through the FileProvider in res/xml/file_paths.xml.
 */
class DocumentFiles(private val context: Context, private val api: ApiClient) {
    private val cache = File(context.cacheDir, "documents")

    private companion object {
        const val META = "document.json"
        const val VIEW = "view.pdf"   // the server's PDF copy for reading on screen
    }

    /** The signed-in account's id, set by AppContainer; downloads are kept per account. */
    var owner: () -> Int? = { null }

    private val _downloads = MutableStateFlow<List<Download>>(emptyList())

    /** The signed-in account's downloads, newest first. Call [loadDownloads] after signing in. */
    val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()

    private fun downloadsDir(): File? = owner()?.let { File(context.filesDir, "downloads/$it") }

    /** Reads the downloads folder: each document has <id>/document.json and its file. */
    suspend fun loadDownloads() {
        val root = downloadsDir()
        _downloads.value = withContext(Dispatchers.IO) {
            root?.listFiles().orEmpty().mapNotNull { dir ->
                val meta = File(dir, META)
                val doc = runCatching { SatchelJson.decodeFromString<Document>(meta.readText()) }.getOrNull()
                    ?: return@mapNotNull null
                val file = File(dir, fileName(doc)).takeIf { it.exists() } ?: return@mapNotNull null
                Download(doc, file, File(dir, VIEW).takeIf { it.exists() }, meta.lastModified())
            }.sortedByDescending { it.savedAt }
        }
    }

    fun downloaded(id: Int): Download? = _downloads.value.firstOrNull { it.document.id == id }

    /** Keeps the document in Satchel's Downloads, so it can be read without internet. */
    suspend fun download(doc: Document) {
        if (downloaded(doc.id) != null) return
        val source = fetch(doc)
        // The PDF copy too, so offline reading shows the real pages. Optional: without it
        // (not made yet, or an older server) the viewer falls back to the original.
        val view = if (doc.viewable) runCatching { fetchView(doc) }.getOrNull() else null
        val root = downloadsDir() ?: throw ApiException(401, "Sign in to download documents.")
        withContext(Dispatchers.IO) {
            val dir = File(root, "${doc.id}").apply { mkdirs() }
            val target = File(dir, fileName(doc))
            val partial = File(dir, "${target.name}.part")
            try {
                source.copyTo(partial, overwrite = true)
                if (!partial.renameTo(target)) throw IOException("rename failed")
                view?.copyTo(File(dir, VIEW), overwrite = true)
                // Written last: a folder without it is an unfinished download and is ignored.
                File(dir, META).writeText(SatchelJson.encodeToString(Document.serializer(), doc))
            } catch (e: IOException) {
                partial.delete()
                throw ApiException(0, "Not enough space to download “${doc.title}”.")
            }
        }
        loadDownloads()
    }

    suspend fun removeDownloads(ids: Collection<Int>) {
        val root = downloadsDir() ?: return
        withContext(Dispatchers.IO) { ids.forEach { File(root, "$it").deleteRecursively() } }
        loadDownloads()
    }

    /** Forget the list when someone signs out; their files stay for when they return. */
    fun forgetDownloads() {
        _downloads.value = emptyList()
    }

    /** The uploader's file name is only for display: keep letters, digits, dots, dashes and spaces. */
    fun fileName(doc: Document): String =
        doc.originalFilename.replace(Regex("""[^\w .\-]"""), "_").ifBlank { "document" }

    /** The document's file: the downloaded copy if there is one, else the cache, downloading if needed. */
    suspend fun fetch(doc: Document): File {
        downloaded(doc.id)?.file?.let { return it }
        val target = File(cache, "${doc.id}/${fileName(doc)}")
        if (!(target.exists() && target.length() == doc.sizeBytes)) api.download(doc.id, target)
        return target
    }

    /** The document's PDF copy for reading on screen: downloaded, cached, or fetched from the server. */
    suspend fun fetchView(doc: Document): File {
        downloaded(doc.id)?.view?.let { return it }
        val target = File(cache, "${doc.id}/$VIEW")
        if (!target.exists()) api.downloadView(doc.id, target)
        return target
    }

    /** A content:// address another app may read, for opening or sharing. */
    fun shareableUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /**
     * Copies the document into the phone's Downloads/Satchel, where the Files app and others
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
