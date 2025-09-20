package com.kaanyildiz.videoinspectorapp.presentation.channels

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kaanyildiz.videoinspectorapp.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(oldItem: Channel, newItem: Channel) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Channel, newItem: Channel) = oldItem == newItem
        }
    }

    inner class VH(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            tvName.text = item.name
            tvStatus.text = item.status
        }

        val isPassive = item.status.equals("passive", ignoreCase = true)
        holder.itemView.isEnabled = !isPassive
        holder.itemView.alpha = if (isPassive) 0.5f else 1f

        holder.itemView.setOnClickListener {
            if (!isPassive) onClick(item)
        }
    }
}
