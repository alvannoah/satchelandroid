package com.example.satchel.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * A PDF's pages as pictures, using Android's built-in PdfRenderer. The renderer
 * handles one page at a time, so rendering is serialised; recently drawn pages
 * are kept in a small cache so swiping back and forth is instant.
 */
class PdfPages(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {   // damaged or password-protected
        descriptor.close()
        throw e
    }
    private val lock = Any()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Each page's width divided by its height, so space is reserved before it is drawn. */
    val aspectRatios: List<Float> = List(pageCount) { i ->
        renderer.openPage(i).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
    }

    // About a sixth of the app's memory, enough for a few pages either side.
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Page [index] drawn [widthPx] wide, on white (PDFs may leave the background transparent). */
    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val key = "$index@$widthPx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (closed) return@withContext null
                renderer.openPage(index).use { page ->
                    val height = (widthPx / aspectRatios[index]).toInt().coerceAtLeast(1)
                    createBitmap(widthPx, height).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }?.also { cache.put(key, it) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            renderer.close()
            descriptor.close()
        }
        cache.evictAll()
    }
}
