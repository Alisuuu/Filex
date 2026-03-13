package com.alisu.filex

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.alisu.filex.core.FileNode
import com.alisu.filex.core.FileType
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.io.File

class RecentFileAdapter(
    private var files: List<FileNode>,
    private val onClick: (FileNode) -> Unit
) : RecyclerView.Adapter<RecentFileAdapter.ViewHolder>() {

    fun submitList(newFiles: List<FileNode>) {
        files = newFiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.name.text = file.name
        
        val context = holder.itemView.context
        Glide.with(context).clear(holder.icon)

        when (file.type) {
            FileType.IMAGE, FileType.VIDEO, FileType.AUDIO -> {
                Glide.with(context)
                    .load(file.path)
                    .centerCrop()
                    .placeholder(when(file.type) {
                        FileType.IMAGE -> R.drawable.fsn_picture
                        FileType.VIDEO -> R.drawable.fsn_video
                        else -> R.drawable.fsn_audio
                    })
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(holder.icon)
            }
            FileType.APK -> holder.icon.setImageResource(R.drawable.fsn_apk)
            FileType.PDF -> holder.icon.setImageResource(R.drawable.fsn_acrobat)
            FileType.AUDIO -> holder.icon.setImageResource(R.drawable.fsn_audio)
            else -> holder.icon.setImageResource(R.drawable.fsn_unknown)
        }

        holder.itemView.setOnClickListener { onClick(file) }
    }

    override fun getItemCount(): Int = files.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.imgRecentIcon)
        val name: TextView = view.findViewById(R.id.txtRecentName)
    }
}