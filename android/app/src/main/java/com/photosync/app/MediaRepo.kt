package com.photosync.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** One photo or video on the device. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateModified: Long,   // seconds (MediaStore convention)
    val isVideo: Boolean
)

object MediaRepo {

    /**
     * Discover every image AND video on the device by querying the two
     * MediaStore collections. Returns newest first.
     */
    fun scanAll(ctx: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>(2048)
        scanCollection(ctx, isVideo = false, out)
        scanCollection(ctx, isVideo = true, out)
        out.sortByDescending { it.dateModified }
        return out
    }

    private fun scanCollection(ctx: Context, isVideo: Boolean, out: MutableList<MediaItem>) {
        val collection = if (isVideo)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        ctx.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cur ->
            val idCol = cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cur.moveToNext()) {
                val id = cur.getLong(idCol)
                out.add(
                    MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = cur.getString(nameCol) ?: "$id",
                        size = cur.getLong(sizeCol),
                        dateModified = cur.getLong(dateCol),
                        isVideo = isVideo
                    )
                )
            }
        }
    }

    /**
     * Save a pulled file into the gallery under Pictures/PhotoSync or
     * Movies/PhotoSync so it shows up like any other photo/video.
     * Returns true on success. `produce` writes the file bytes to the stream.
     */
    fun insertDownloaded(
        ctx: Context, name: String, isVideo: Boolean,
        produce: (java.io.OutputStream) -> Unit
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertScoped(ctx, name, isVideo, produce)
        } else {
            insertLegacy(name, isVideo, produce)
        }
    }

    private fun insertScoped(
        ctx: Context, name: String, isVideo: Boolean,
        produce: (java.io.OutputStream) -> Unit
    ): Boolean {
        val collection = if (isVideo)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = if (isVideo) "${Environment.DIRECTORY_MOVIES}/PhotoSync"
        else "${Environment.DIRECTORY_PICTURES}/PhotoSync"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = ctx.contentResolver.insert(collection, values) ?: return false
        return try {
            ctx.contentResolver.openOutputStream(uri)!!.use { produce(it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            ctx.contentResolver.delete(uri, null, null)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun insertLegacy(
        name: String, isVideo: Boolean, produce: (java.io.OutputStream) -> Unit
    ): Boolean {
        val base = Environment.getExternalStoragePublicDirectory(
            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        )
        val dir = File(base, "PhotoSync").apply { mkdirs() }
        val f = File(dir, name)
        return try {
            f.outputStream().use { produce(it) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
