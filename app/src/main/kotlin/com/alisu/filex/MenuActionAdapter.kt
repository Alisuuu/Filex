package com.alisu.filex

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class MenuAction(val id: String, val label: String, val iconRes: Int)

class MenuActionAdapter(
    private val actions: List<MenuAction>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<MenuActionAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_menu_action, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = actions[position]
        holder.icon.setImageResource(action.iconRes)
        holder.text.text = action.label
        holder.itemView.setOnClickListener { onClick(action.id) }
    }

    override fun getItemCount(): Int = actions.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.imgActionIcon)
        val text: TextView = view.findViewById(R.id.txtActionLabel)
    }
}
