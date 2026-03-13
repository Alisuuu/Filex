package com.alisu.filex

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Shortcut(val name: String, val path: String, val iconRes: Int)

class ShortcutAdapter(
    private val shortcuts: List<Shortcut>,
    private val onClick: (Shortcut) -> Unit,
    private val onLongClick: (Shortcut) -> Unit
) : RecyclerView.Adapter<ShortcutAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shortcut, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val shortcut = shortcuts[position]
        holder.name.text = shortcut.name
        holder.icon.setImageResource(shortcut.iconRes)
        holder.itemView.setOnClickListener { onClick(shortcut) }
        holder.itemView.setOnLongClickListener { 
            onLongClick(shortcut)
            true
        }
    }

    override fun getItemCount(): Int = shortcuts.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.imgShortcutIcon)
        val name: TextView = view.findViewById(R.id.txtShortcutName)
    }
}
