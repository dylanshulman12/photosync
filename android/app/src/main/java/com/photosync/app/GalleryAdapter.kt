package com.photosync.app

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.photosync.app.databinding.ItemMediaBinding

/** A grid cell: thumbnail + a colored dot for sync state. */
data class GalleryRow(val item: MediaItem, val synced: Boolean?, val excluded: Boolean = false)

/**
 * [onToggleExclude] is called on long-press with the item and whether it is
 * currently excluded, so the host can flip it.
 */
class GalleryAdapter(
    private val onToggleExclude: (MediaItem, Boolean) -> Unit
) : ListAdapter<GalleryRow, GalleryAdapter.VH>(DIFF) {

    class VH(val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.b.thumb.load(row.item.uri) {
            crossfade(true)
            placeholder(android.R.color.darker_gray)
        }
        holder.b.videoBadge.visibility = if (row.item.isVideo) android.view.View.VISIBLE else android.view.View.GONE
        val dot = holder.b.statusDot
        when (row.synced) {
            true -> dot.setBackgroundResource(R.drawable.dot_synced)
            false -> dot.setBackgroundResource(R.drawable.dot_unsynced)
            null -> dot.setBackgroundResource(R.drawable.dot_unknown)
        }
        // Excluded items are dimmed so they read as "won't sync".
        holder.itemView.alpha = if (row.excluded) 0.3f else 1f

        // Tap: open the photo/video in whatever app the user picks.
        holder.itemView.setOnClickListener { openExternally(holder, row) }
        // Long-press: toggle exclude-from-sync.
        holder.itemView.setOnLongClickListener {
            onToggleExclude(row.item, row.excluded)
            true
        }
    }

    private fun openExternally(holder: VH, row: GalleryRow) {
        val ctx = holder.itemView.context
        val mime = if (row.item.isVideo) "video/*" else "image/*"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(row.item.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ctx.startActivity(Intent.createChooser(view, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "No app can open this", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<GalleryRow>() {
            override fun areItemsTheSame(a: GalleryRow, b: GalleryRow) = a.item.id == b.item.id
            override fun areContentsTheSame(a: GalleryRow, b: GalleryRow) =
                a.synced == b.synced && a.item.id == b.item.id && a.excluded == b.excluded
        }
    }
}
