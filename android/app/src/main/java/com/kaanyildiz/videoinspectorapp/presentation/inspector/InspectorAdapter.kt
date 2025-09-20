// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/inspector/InspectorAdapter.kt
package com.kaanyildiz.videoinspectorapp.presentation.inspector

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.kaanyildiz.videoinspectorapp.R
import com.kaanyildiz.videoinspectorapp.data.remote.model.MediaItemDto

private fun fullUrl(base: String, raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val b = base.trimEnd('/')
    val s = raw.replace('\\', '/')
    return when {
        s.startsWith("http", ignoreCase = true) -> s
        s.startsWith("/") -> "$b$s" // "/files/..." -> "http://IP:3000/files/..."
        s.contains("/uploads/") -> "$b/files/" + s.substringAfter("/uploads/")
        else -> "$b/$s"
    }
}

class InspectorAdapter(
    private val baseUrl: String,
    private val onClick: (MediaItemDto) -> Unit
) : ListAdapter<MediaItemDto, InspectorVH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<MediaItemDto>() {
            override fun areItemsTheSame(old: MediaItemDto, new: MediaItemDto) = old.id == new.id
            override fun areContentsTheSame(old: MediaItemDto, new: MediaItemDto) = old == new
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InspectorVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inspector_media, parent, false)
        return InspectorVH(baseUrl, view, onClick)
    }

    override fun onBindViewHolder(holder: InspectorVH, position: Int) {
        holder.bind(getItem(position))
    }
}

class InspectorVH(
    private val baseUrl: String,
    v: View,
    private val onClick: (MediaItemDto) -> Unit
) : RecyclerView.ViewHolder(v) {

    private val ivThumb: ImageView = v.findViewById(R.id.ivThumb)
    private val tvName: TextView = v.findViewById(R.id.tvName)
    private val tvStatus: TextView = v.findViewById(R.id.tvStatus)

    fun bind(item: MediaItemDto) {
        tvName.text = item.path.substringAfterLast('/')

        tvStatus.text = when (item.status?.lowercase()) {
            "valid" -> "valid"
            "not_valid", "not-valid" -> "not_valid"
            else -> "waiting"
        }

        val thumb = fullUrl(baseUrl, item.thumbnailUrl) ?: fullUrl(baseUrl, item.path)
        if (thumb != null) {
            ivThumb.load(thumb) {
                placeholder(R.drawable.ic_media_placeholder)
                error(R.drawable.ic_media_placeholder)
                crossfade(true)
            }
        } else {
            ivThumb.setImageResource(R.drawable.ic_media_placeholder)
        }

        itemView.setOnClickListener { onClick(item) }
    }
}
