package com.example.audiovideommaker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object FileUtils {

    /** Returns a human-readable file name for the given URI. */
    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    /** Copies content from src URI to dest URI using streams. Call from a background thread. */
    fun copyUri(context: Context, src: Uri, dest: Uri) {
        context.contentResolver.openInputStream(src)?.use { input ->
            context.contentResolver.openOutputStream(dest)?.use { output ->
                input.copyTo(output)
            }
        }
    }

    /** Deletes a file-scheme URI from the cache directory. */
    fun deleteCacheFile(context: Context, uri: Uri) {
        if (uri.scheme == "file") {
            File(uri.path ?: return).delete()
        }
    }
}
