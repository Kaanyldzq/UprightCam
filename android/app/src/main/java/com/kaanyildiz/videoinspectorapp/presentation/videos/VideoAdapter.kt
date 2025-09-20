// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/videos/VideoAdapter.kt
package com.kaanyildiz.videoinspectorapp.presentation.videos

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
import com.kaanyildiz.videoinspectorapp.data.remote.model.VideoItemDto  // <-- DOĞRU IMPORT

class VideoAdapter(
    private val onClick: (VideoItemDto) -> Unit
) : ListAdapter<VideoItemDto, VideoAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<VideoItemDto>() {
            override fun areItemsTheSame(oldItem: VideoItemDto, newItem: VideoItemDto) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: VideoItemDto, newItem: VideoItemDto) =
                oldItem == newItem
        }
    }

    class VH(itemView: View, private val onClick: (VideoItemDto) -> Unit) :
        RecyclerView.ViewHolder(itemView) {

        private val ivThumb: ImageView = itemView.findViewById(R.id.ivThumb)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)

        fun bind(item: VideoItemDto) {
            val name = item.path.substringAfterLast('/')
            tvTitle.text = name
            tvMeta.text = "${item.status} • ch#${item.channel_id}"

            val thumb = item.thumbnail_url ?: item.path
            ivThumb.load(thumb) {
                placeholder(R.drawable.ic_media_placeholder)
                error(R.drawable.ic_media_placeholder)
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VH(v, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
