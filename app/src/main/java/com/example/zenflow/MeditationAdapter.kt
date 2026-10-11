package com.example.zenflow

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MeditationAdapter(
    private val items: List<Meditation>,
    private val onItemClick: (Meditation) -> Unit
) : RecyclerView.Adapter<MeditationAdapter.MeditationViewHolder>() {

    inner class MeditationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvEmoji: TextView = itemView.findViewById(R.id.tvEmoji)
        val emojiBackground: View = itemView.findViewById(R.id.emojiBackground)
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvDescription: TextView = itemView.findViewById(R.id.tvDescription)
        val tvMinutes: TextView = itemView.findViewById(R.id.tvMinutes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MeditationViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_meditation, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: MeditationViewHolder, position: Int) {
        val item = items[position]
        holder.tvEmoji.text = item.emoji
        holder.tvTitle.text = item.title
        holder.tvDescription.text = item.description
        holder.tvMinutes.text = "${item.minutes} min"
        holder.emojiBackground.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(item.colorHex))
        holder.itemView.setOnClickListener { onItemClick(item) }
    }
}
