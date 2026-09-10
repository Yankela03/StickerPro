package com.stickermaker.whatsapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.stickermaker.whatsapp.StickerPro.StickerPack
import com.stickermaker.whatsapp.StickerPro.StickerPackRepository
import com.stickermaker.whatsapp.databinding.ItemPackBinding
import java.io.File

class PackAdapter(
    private val packs: List<StickerPack>,
    private val onItemClick: (StickerPack) -> Unit
) : RecyclerView.Adapter<PackAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemPackBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(packs[pos])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pack = packs[position]
        holder.binding.tvPackName.text = pack.name
        val visibleStickers = pack.stickers.filterNot { it.imageFileName.contains("_pad") }
        holder.binding.tvStickerCount.text = "${visibleStickers.size} stickers"

        val trayFile = StickerPackRepository.stickerFile(holder.itemView.context, pack.identifier, pack.trayImageFile)
        if (trayFile.exists()) {
            Glide.with(holder.binding.ivTrayIcon)
                .load(trayFile)
                .centerCrop()
                .into(holder.binding.ivTrayIcon)
        }
    }

    override fun getItemCount(): Int = packs.size
}
