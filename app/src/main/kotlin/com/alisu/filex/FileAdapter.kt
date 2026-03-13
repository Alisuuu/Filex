package com.alisu.filex

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.alisu.filex.core.FileNode
import com.alisu.filex.core.FileType
import com.alisu.filex.databinding.ItemFileBinding
import com.alisu.filex.util.VectorRenderer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileAdapter(
    private val onFileClick: (FileNode) -> Unit,
    private val onIconClick: (FileNode) -> Unit,
    private val onLongClick: (FileNode) -> Unit
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private var files: List<FileNode> = emptyList()
    private var allFiles: List<FileNode> = emptyList() // BACKUP PARA BUSCA
    private val selectedPaths = mutableSetOf<String>()

    fun submitList(newFiles: List<FileNode>) {
        allFiles = newFiles
        files = newFiles
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        files = if (query.isEmpty()) {
            allFiles
        } else {
            allFiles.filter { it.name.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    fun toggleSelection(path: String) {
        if (selectedPaths.contains(path)) selectedPaths.remove(path)
        else selectedPaths.add(path)
        notifyDataSetChanged()
    }

    fun setSelection(paths: Set<String>) {
        selectedPaths.clear()
        selectedPaths.addAll(paths)
        notifyDataSetChanged()
    }

    fun getCurrentPaths(): List<String> = files.map { it.path }
    fun getSelectedPaths(): Set<String> = selectedPaths.toSet()
    fun getSelectedCount() = selectedPaths.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.bind(file, selectedPaths.contains(file.path))
    }

    override fun getItemCount(): Int = files.size

    inner class ViewHolder(private val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormat = java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault())

        fun bind(file: FileNode, isSelected: Boolean) {
            binding.txtName.text = file.name
            val sizeStr = if (file.isDirectory) binding.root.context.getString(R.string.type_folder) else Formatter.formatShortFileSize(binding.root.context, file.size)
            val dateStr = dateFormat.format(java.util.Date(file.lastModified))
            binding.txtDetails.text = "$sizeStr  •  $dateStr"
            
            binding.imgIcon.setBackgroundColor(Color.TRANSPARENT)
            loadIconAndPreview(file)
            
            binding.imgSelectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.cardView.strokeWidth = if (isSelected) {
                binding.cardView.strokeColor = Color.parseColor("#F35360")
                4
            } else 0

            binding.iconContainer.setOnClickListener { 
                if (selectedPaths.isNotEmpty()) toggleSelection(file.path) else onIconClick(file) 
            }
            binding.textContainer.setOnClickListener { 
                if (selectedPaths.isNotEmpty()) toggleSelection(file.path) else onFileClick(file) 
            }
            
            val longClickListener = View.OnLongClickListener {
                onLongClick(file)
                true
            }
            binding.iconContainer.setOnLongClickListener(longClickListener)
            binding.textContainer.setOnLongClickListener(longClickListener)
            binding.root.setOnLongClickListener(longClickListener)
        }

        private fun loadIconAndPreview(file: FileNode) {
            val context = binding.root.context
            Glide.with(context).clear(binding.imgIcon)

            val name = file.name.lowercase()

            when (file.type) {
                FileType.IMAGE, FileType.VIDEO -> {
                    Glide.with(context)
                        .load(file.path)
                        .centerCrop()
                        .placeholder(if (file.type == FileType.IMAGE) R.drawable.fsn_picture else R.drawable.fsn_video)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(binding.imgIcon)
                }
                FileType.PDF -> {
                    try {
                        val fileDescriptor = ParcelFileDescriptor.open(File(file.path), ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(fileDescriptor)
                        val page = renderer.openPage(0)
                        val bitmap = Bitmap.createBitmap(page.width / 4, page.height / 4, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        binding.imgIcon.setImageBitmap(bitmap)
                        page.close()
                        renderer.close()
                    } catch (e: Exception) {
                        binding.imgIcon.setImageResource(R.drawable.fsn_acrobat)
                    }
                }
                FileType.APK -> {
                    binding.imgIcon.setImageResource(R.drawable.fsn_apk)
                    val path = file.path
                    if (path.endsWith(".apk", true) && context is androidx.lifecycle.LifecycleOwner) {
                        context.lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val packageInfo = context.packageManager.getPackageArchiveInfo(path, 0)
                                packageInfo?.applicationInfo?.let { appInfo ->
                                    appInfo.sourceDir = path
                                    appInfo.publicSourceDir = path
                                    val icon = appInfo.loadIcon(context.packageManager)
                                    withContext(Dispatchers.Main) {
                                        if (binding.txtName.text == file.name) {
                                            binding.imgIcon.setImageDrawable(icon)
                                        }
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    } else if (path.endsWith(".xapk", true)) {
                        binding.imgIcon.setImageResource(R.drawable.fsn_apks)
                    }
                }
                FileType.DIRECTORY -> {
                    if (file.path.contains("/data/app/")) {
                        val packageName = file.name.substringBefore("-")
                        try {
                            val icon = context.packageManager.getApplicationIcon(packageName)
                            binding.imgIcon.setImageDrawable(icon)
                        } catch (e: Exception) {
                            binding.imgIcon.setImageResource(R.drawable.l_folder)
                        }
                    } else {
                        binding.imgIcon.setImageResource(R.drawable.l_folder)
                    }
                }
                FileType.VECTOR, FileType.CODE -> {
                    val isXml = file.name.lowercase().endsWith(".xml")
                    val isSvg = file.name.lowercase().endsWith(".svg")
                    
                    binding.imgIcon.setImageResource(if (isXml) R.drawable.fsn_xml else if (isSvg) R.drawable.fsn_picture else R.drawable.fsn_text)
                    binding.imgIcon.setPadding(0, 0, 0, 0)
                    
                    if (isXml || isSvg) {
                        val realFile = File(file.path)
                        val context = binding.root.context
                        
                        // Criar uma thread/coroutine garantida para o processamento pesado
                        CoroutineScope(Dispatchers.IO).launch {
                            if (VectorRenderer.isAndroidVector(realFile) || isSvg) {
                                val bitmap = VectorRenderer.renderXmlToBitmap(context, realFile, 128)
                                if (bitmap != null) {
                                    withContext(Dispatchers.Main) {
                                        // Verifica se o ViewHolder ainda está mostrando o mesmo arquivo
                                        if (binding.txtName.text == file.name) {
                                            binding.imgIcon.setBackgroundColor(Color.parseColor("#1AFFFFFF"))
                                            binding.imgIcon.setImageBitmap(bitmap)
                                            binding.imgIcon.setPadding(8, 8, 8, 8)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                FileType.ZIP -> binding.imgIcon.setImageResource(R.drawable.fsn_archive_zip)
                FileType.RAR -> binding.imgIcon.setImageResource(R.drawable.fsn_archive_rar)
                FileType.SEVEN_ZIP -> binding.imgIcon.setImageResource(R.drawable.fsn_archive_7z)
                FileType.TAR, FileType.GZIP -> binding.imgIcon.setImageResource(R.drawable.fsn_archive)
                FileType.WORD -> binding.imgIcon.setImageResource(R.drawable.fsn_word)
                FileType.EXCEL -> binding.imgIcon.setImageResource(R.drawable.fsn_excel)
                FileType.POWERPOINT -> binding.imgIcon.setImageResource(R.drawable.fsn_powerpoint)
                FileType.WEB -> binding.imgIcon.setImageResource(R.drawable.fsn_web)
                FileType.LIB -> binding.imgIcon.setImageResource(R.drawable.fsn_lib)
                FileType.AUDIO -> {
                    Glide.with(context)
                        .load(file.path)
                        .centerCrop()
                        .placeholder(R.drawable.fsn_audio)
                        .error(R.drawable.fsn_audio)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(binding.imgIcon)
                }
                FileType.FONT -> binding.imgIcon.setImageResource(R.drawable.fsn_font)
                FileType.TEXT -> binding.imgIcon.setImageResource(R.drawable.fsn_text)
                else -> {
                    val iconRes = when {
                        name.endsWith(".mtz") || name.endsWith(".theme") -> R.drawable.fsn_theme
                        name.endsWith(".epub") || name.endsWith(".mobi") || name.endsWith(".azw3") -> R.drawable.fsn_ebook
                        name.endsWith(".dex") -> R.drawable.fsn_dex
                        name.endsWith(".bak") || name.endsWith(".backup") -> R.drawable.fsn_backup
                        name.endsWith(".iso") || name.endsWith(".img") -> R.drawable.fsn_cd_image
                        name.endsWith(".rom") || name.endsWith(".sav") || name.endsWith(".gba") || name.endsWith(".nes") -> R.drawable.fsn_game_rom
                        name.endsWith(".obb") -> R.drawable.fsn_obb
                        name.endsWith(".apks") -> R.drawable.fsn_apks
                        name.endsWith(".xml") -> R.drawable.fsn_xml
                        name.endsWith(".php") || name.endsWith(".js") || name.endsWith(".py") -> R.drawable.fsn_web
                        else -> R.drawable.fsn_unknown
                    }
                    binding.imgIcon.setImageResource(iconRes)
                }
            }
        }
    }
}
