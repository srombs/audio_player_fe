package com.rombs.newyorkeraudio.playback.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.rombs.newyorkeraudio.NewYorkerAudioApplication
import java.io.FileNotFoundException

/** Public media artwork only. Arbitrary files, remote URLs, and write access are never exposed. */
class ArtworkProvider : ContentProvider() {
    private val cache get() = (requireNotNull(context).applicationContext as NewYorkerAudioApplication).container.artworkCache
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = if (cache.key(uri) != null) "image/png" else null
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Artwork is read-only")
        val file = cache.fileFor(uri) ?: throw FileNotFoundException("Unknown artwork")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val key = cache.key(uri) ?: return null
        val columns = projection?.filter { it in listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE) }
            ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns.toTypedArray()).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) "$key.png" else cache.cachedFile(uri)?.length() })
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only artwork")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only artwork")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only artwork")
}
